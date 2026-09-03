package dev.metalcraft.client.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.metalcraft.api.MetalCraftLightRegistry;
import dev.metalcraft.api.MetalCraftLocalLight;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Metal world lighting: collects emitters, keeps a voxel occupancy volume of the real world,
 * and publishes GPU-ready lights, tiles, and occlusion data for the deferred resolve.
 */
public final class MetalWorldLighting implements AutoCloseable {
	public static final int MAX_LIGHTS = 256;
	public static final int TILE_SIZE = 16;
	public static final int MAX_LIGHTS_PER_TILE = 64;
	public static final int MAX_SHADOW_LIGHTS = 4;
	public static final int OCCUPANCY_SIZE = 64;
	static final int GPU_LIGHT_BYTES = 48;
	private static final int GPU_HEADER_BYTES = 128;
	private static final int GPU_BUFFER_BYTES = GPU_HEADER_BYTES + MAX_LIGHTS * GPU_LIGHT_BYTES;
	private static final int OCCUPANCY_WORDS = OCCUPANCY_SIZE * OCCUPANCY_SIZE * OCCUPANCY_SIZE / 32;
	private static final int OCCUPANCY_BYTES = OCCUPANCY_WORDS * Integer.BYTES;
	private static final int TILE_STRIDE = MAX_LIGHTS_PER_TILE + 1;
	private static final Identifier STATIC_PROVIDER = Identifier.parse("metalcraft:vanilla_static");
	private static final Identifier DYNAMIC_PROVIDER = Identifier.parse("metalcraft:vanilla_dynamic");
	private static final Comparator<RankedLight> STRONGEST_FIRST = Comparator
		.comparingDouble(RankedLight::impact).reversed()
		.thenComparing(light -> light.id().providerId().toString())
		.thenComparingLong(light -> light.id().stableId());

	private final MetalCraftLightRegistry registry;
	private final Map<Long, List<WorldLight>> staticChunks = new HashMap<>();
	private final Map<Long, long[]> occupancySections = new HashMap<>();
	private final Map<LightId, Integer> shadowSlots = new LinkedHashMap<>();
	private final Matrix4f worldFromView = new Matrix4f();
	private @Nullable ClientLevel cachedLevel;
	private @Nullable MetalGpuDevice gpuDevice;
	private @Nullable GpuBufferSlice lightBuffer;
	private @Nullable GpuBufferSlice tileBuffer;
	private @Nullable GpuBufferSlice occupancyBuffer;
	private int cameraBlockX;
	private int cameraBlockY;
	private int cameraBlockZ;
	private float cameraFracX;
	private float cameraFracY;
	private float cameraFracZ;
	private int occupancyOriginX;
	private int occupancyOriginY;
	private int occupancyOriginZ;
	private int occupancySolid;
	private volatile Snapshot current = Snapshot.EMPTY;

	public MetalWorldLighting(final MetalCraftLightRegistry registry) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.worldFromView.identity();
		this.resetOccupancyWindow();
	}

	/** Rebuilds one chunk atomically when it arrives from the client chunk cache. */
	public synchronized void chunkLoaded(final ClientLevel level, final LevelChunk chunk) {
		this.selectLevel(level);
		List<WorldLight> found = new ArrayList<>();
		chunk.findBlockLightSources((position, state) -> {
			WorldLight light = staticEmitter(position, state);
			if (light != null) found.add(light);
		});
		this.staticChunks.put(chunk.getPos().pack(), List.copyOf(found));
		this.rebuildChunkOccupancy(chunk);
	}

	/** Drops all emitters and occupancy owned by an unloading chunk. */
	public synchronized void chunkUnloaded(final ClientLevel level, final LevelChunk chunk) {
		this.selectLevel(level);
		this.staticChunks.remove(chunk.getPos().pack());
		this.dropChunkOccupancy(chunk.getPos(), chunk.getMinSectionY(), chunk.getMaxSectionY());
	}

	/** Incrementally replaces the emitter and occupancy bit at one changed block position. */
	public synchronized void blockChanged(final ClientLevel level, final BlockPos position, final BlockState state) {
		this.selectLevel(level);
		this.replaceBlockEmitter(position, state);
		this.setOccupied(position, occludesLight(state));
	}

	synchronized void blockChangedForTesting(final BlockPos position, final BlockState state) {
		this.replaceBlockEmitter(position, state);
		this.setOccupied(position, occludesLight(state));
	}

	synchronized void blockChangedForTesting(final BlockPos position, final boolean emitting) {
		long chunkKey = ChunkPos.pack(position.getX() >> 4, position.getZ() >> 4);
		List<WorldLight> previous = this.staticChunks.getOrDefault(chunkKey, List.of());
		List<WorldLight> replacement = new ArrayList<>(previous.size() + 1);
		for (WorldLight light : previous) if (light.id().stableId() != position.asLong()) replacement.add(light);
		if (emitting) {
			replacement.add(new WorldLight(
				new LightId(STATIC_PROVIDER, position.asLong()), Vec3.atCenterOf(position),
				1.0F, 0.86F, 0.68F, 3.0F, 18.5F, true
			));
		}
		if (replacement.isEmpty()) this.staticChunks.remove(chunkKey);
		else this.staticChunks.put(chunkKey, List.copyOf(replacement));
	}

	synchronized void chunkUnloadedForTesting(final ChunkPos position) {
		this.staticChunks.remove(position.pack());
		this.dropChunkOccupancy(position, -4, 20);
	}

	synchronized int staticEmitterCountForTesting() {
		return this.staticChunks.values().stream().mapToInt(List::size).sum();
	}

	/** Marks a world block as a solid occluder for headless occupancy tests. */
	synchronized void occupyForTesting(final BlockPos position, final boolean solid) {
		this.setOccupied(position, solid);
	}

	synchronized void clearOccupancyForTesting() {
		this.occupancySections.clear();
	}

	synchronized int occupancySolidCountForTesting() {
		int count = 0;
		for (long[] section : this.occupancySections.values()) {
			for (long word : section) count += Long.bitCount(word);
		}
		return count;
	}

	private void replaceBlockEmitter(final BlockPos position, final BlockState state) {
		long chunkKey = ChunkPos.pack(position.getX() >> 4, position.getZ() >> 4);
		List<WorldLight> previous = this.staticChunks.getOrDefault(chunkKey, List.of());
		List<WorldLight> replacement = new ArrayList<>(previous.size() + 1);
		long stableId = position.asLong();
		for (WorldLight light : previous) {
			if (light.id().stableId() != stableId) replacement.add(light);
		}
		WorldLight changed = staticEmitter(position, state);
		if (changed != null) replacement.add(changed);
		if (replacement.isEmpty()) this.staticChunks.remove(chunkKey);
		else this.staticChunks.put(chunkKey, List.copyOf(replacement));
	}

	/** Collects registered lights only; retained for API tests and headless callers. */
	public Snapshot publish(final CameraRenderState camera) {
		return this.publish(camera, null, 1920, 1080, MAX_SHADOW_LIGHTS);
	}

	/** Collects, culls, ranks, tiles, shadow-selects, uploads, and atomically publishes this frame. */
	public synchronized Snapshot publish(
		final CameraRenderState camera,
		final @Nullable ClientLevel level,
		final int screenWidth,
		final int screenHeight,
		final int shadowLimit
	) {
		Objects.requireNonNull(camera, "camera");
		if (screenWidth <= 0 || screenHeight <= 0) {
			throw new IllegalArgumentException("Local-light screen dimensions must be positive");
		}
		if (shadowLimit < 0 || shadowLimit > MAX_SHADOW_LIGHTS) {
			throw new IllegalArgumentException("Local shadow limit must be between zero and four");
		}
		if (level != null) this.selectLevel(level);
		this.captureCamera(camera);
		Matrix4f viewProjection = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix);
		FrustumIntersection frustum = new FrustumIntersection(viewProjection, true);
		List<RankedLight> ranked = new ArrayList<>();

		if (level != null) {
			for (List<WorldLight> chunk : this.staticChunks.values()) {
				for (WorldLight light : chunk) this.rank(light, camera, frustum, ranked);
			}
			this.collectDynamic(level, camera, frustum, ranked);
		}
		for (MetalCraftLightRegistry.RegisteredLight registered : this.registry.collect()) {
			MetalCraftLocalLight light = registered.light();
			this.rank(new WorldLight(
				new LightId(registered.providerId(), light.stableId()), light.position(),
				light.red(), light.green(), light.blue(), light.intensity(), light.radius(),
				light.shadowEligible()
			), camera, frustum, ranked);
		}

		ranked.sort(STRONGEST_FIRST);
		int retained = Math.min(MAX_LIGHTS, ranked.size());
		List<FrameLight> lights = new ArrayList<>(retained);
		for (int index = 0; index < retained; index++) {
			RankedLight light = ranked.get(index);
			lights.add(new FrameLight(
				light.id(), light.cameraX(), light.cameraY(), light.cameraZ(),
				light.viewX(), light.viewY(), light.viewZ(),
				light.red(), light.green(), light.blue(), light.intensity(), light.radius(),
				light.shadowEligible(), -1
			));
		}
		lights = this.assignShadowSlots(lights, shadowLimit);
		TileGrid tiles = buildTiles(lights, camera.projectionMatrix, screenWidth, screenHeight);
		Snapshot published = new Snapshot(lights, ranked.size() - retained, tiles);
		this.current = published;
		this.upload(published);
		return published;
	}

	public Snapshot snapshot() {
		return this.current;
	}

	synchronized Snapshot publishForTesting(
		final List<FrameLight> frameLights,
		final Matrix4f projection,
		final int width,
		final int height,
		final int shadowLimit
	) {
		this.worldFromView.identity();
		this.cameraBlockX = 0;
		this.cameraBlockY = 0;
		this.cameraBlockZ = 0;
		this.cameraFracX = 0.0F;
		this.cameraFracY = 0.0F;
		this.cameraFracZ = 0.0F;
		this.resetOccupancyWindow();
		List<FrameLight> assigned = this.assignShadowSlots(List.copyOf(frameLights), shadowLimit);
		Snapshot published = new Snapshot(assigned, 0, buildTiles(assigned, projection, width, height));
		this.current = published;
		this.upload(published);
		return published;
	}

	/** Attaches the frame-owned first-party buffers. External packs never bind these slots. */
	public synchronized void attach(final MetalGpuDevice device) {
		Objects.requireNonNull(device, "device");
		if (this.gpuDevice != null) return;
		this.gpuDevice = device;
		this.upload(this.current);
	}

	MetalBuffer lightBuffer() {
		return metal(Objects.requireNonNull(this.lightBuffer, "local-light GPU buffer"));
	}

	MetalBuffer tileBuffer() {
		return metal(Objects.requireNonNull(this.tileBuffer, "local-light tile GPU buffer"));
	}

	MetalBuffer occupancyBuffer() {
		return metal(Objects.requireNonNull(this.occupancyBuffer, "world occupancy GPU buffer"));
	}

	long lightBufferOffset() {
		return Objects.requireNonNull(this.lightBuffer, "local-light GPU buffer").offset();
	}

	long tileBufferOffset() {
		return Objects.requireNonNull(this.tileBuffer, "local-light tile GPU buffer").offset();
	}

	long occupancyBufferOffset() {
		return Objects.requireNonNull(this.occupancyBuffer, "world occupancy GPU buffer").offset();
	}

	private void rank(
		final WorldLight light,
		final CameraRenderState camera,
		final FrustumIntersection frustum,
		final List<RankedLight> ranked
	) {
		float relativeX = (float)(light.position().x - camera.pos.x);
		float relativeY = (float)(light.position().y - camera.pos.y);
		float relativeZ = (float)(light.position().z - camera.pos.z);
		if (!frustum.testSphere(relativeX, relativeY, relativeZ, light.radius())) return;
		Vector3f view = camera.viewRotationMatrix.transformPosition(
			new Vector3f(relativeX, relativeY, relativeZ), new Vector3f()
		);
		double distanceSquared = (double)relativeX * relativeX
			+ (double)relativeY * relativeY + (double)relativeZ * relativeZ;
		double luminance = 0.2126 * light.red() + 0.7152 * light.green() + 0.0722 * light.blue();
		double impact = luminance * light.intensity() * light.radius() * light.radius()
			/ Math.max(distanceSquared, 1.0);
		ranked.add(new RankedLight(
			light.id(), relativeX, relativeY, relativeZ, view.x, view.y, view.z,
			light.red(), light.green(), light.blue(), light.intensity(), light.radius(),
			light.shadowEligible(), impact
		));
	}

	private void collectDynamic(
		final ClientLevel level,
		final CameraRenderState camera,
		final FrustumIntersection frustum,
		final List<RankedLight> ranked
	) {
		for (Entity entity : level.entitiesForRendering()) {
			int ordinal = 0;
			if (entity.isOnFire()) {
				this.rank(dynamicEmitter(entity, ordinal++, 1.0F, 0.28F, 0.06F, 2.2F, 9.0F), camera, frustum, ranked);
			}
			String entityName = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
			if (entityName.contains("lightning_bolt")) {
				this.rank(dynamicEmitter(entity, ordinal++, 0.62F, 0.76F, 1.0F, 8.0F, 32.0F), camera, frustum, ranked);
			} else if (entityName.contains("blaze") || entityName.contains("magma_cube")) {
				this.rank(dynamicEmitter(entity, ordinal++, 1.0F, 0.32F, 0.05F, 1.7F, 10.0F), camera, frustum, ranked);
			} else if (entityName.contains("glow_squid")) {
				this.rank(dynamicEmitter(entity, ordinal++, 0.18F, 0.72F, 0.78F, 1.0F, 7.0F), camera, frustum, ranked);
			}
			if (entity instanceof ItemEntity itemEntity) {
				ordinal = this.addItemEmitter(entity, itemEntity.getItem(), ordinal, camera, frustum, ranked);
			}
			if (entity instanceof LivingEntity living) {
				ordinal = this.addItemEmitter(entity, living.getMainHandItem(), ordinal, camera, frustum, ranked);
				this.addItemEmitter(entity, living.getOffhandItem(), ordinal, camera, frustum, ranked);
			} else if (entity instanceof Display.ItemDisplay display) {
				this.addItemEmitter(entity, display.itemRenderState().itemStack(), ordinal, camera, frustum, ranked);
			} else if (entity instanceof Display.BlockDisplay display) {
				BlockState state = display.blockRenderState().blockState();
				if (state.getLightEmission() > 0) {
					Color color = colorFor(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
					this.rank(dynamicEmitter(
						entity, ordinal, color.red(), color.green(), color.blue(),
						intensityFor(state.getLightEmission()), radiusFor(state.getLightEmission())
					), camera, frustum, ranked);
				}
			}
		}
	}

	private int addItemEmitter(
		final Entity owner,
		final ItemStack stack,
		final int ordinal,
		final CameraRenderState camera,
		final FrustumIntersection frustum,
		final List<RankedLight> ranked
	) {
		if (stack.isEmpty()) return ordinal;
		Block block = Block.byItem(stack.getItem());
		int emission = block == Blocks.AIR ? itemEmission(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath())
			: block.defaultBlockState().getLightEmission();
		if (emission <= 0) return ordinal;
		String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		Color color = colorFor(name);
		this.rank(dynamicEmitter(
			owner, ordinal, color.red(), color.green(), color.blue(), intensityFor(emission), radiusFor(emission)
		), camera, frustum, ranked);
		return ordinal + 1;
	}

	private static WorldLight dynamicEmitter(
		final Entity entity,
		final int ordinal,
		final float red,
		final float green,
		final float blue,
		final float intensity,
		final float radius
	) {
		Vec3 position = entity.getBoundingBox().getCenter();
		long stableId = ((long)entity.getId() << 8) | (ordinal & 0xFFL);
		return new WorldLight(
			new LightId(DYNAMIC_PROVIDER, stableId), position, red, green, blue,
			intensity, radius, true
		);
	}

	private List<FrameLight> assignShadowSlots(final List<FrameLight> lights, final int shadowLimit) {
		Map<LightId, FrameLight> eligible = new LinkedHashMap<>();
		for (FrameLight light : lights) if (light.shadowEligible()) eligible.put(light.id(), light);
		Map<LightId, Integer> next = new LinkedHashMap<>();
		boolean[] occupied = new boolean[shadowLimit];
		for (Map.Entry<LightId, Integer> prior : this.shadowSlots.entrySet()) {
			if (eligible.containsKey(prior.getKey()) && prior.getValue() < shadowLimit) {
				next.put(prior.getKey(), prior.getValue());
				occupied[prior.getValue()] = true;
			}
		}
		for (FrameLight candidate : lights) {
			if (!candidate.shadowEligible() || next.containsKey(candidate.id())) continue;
			int free = -1;
			for (int slot = 0; slot < shadowLimit; slot++) if (!occupied[slot]) { free = slot; break; }
			if (free < 0) break;
			next.put(candidate.id(), free);
			occupied[free] = true;
		}
		for (FrameLight candidate : lights) {
			if (!candidate.shadowEligible() || next.containsKey(candidate.id()) || next.isEmpty()) continue;
			LightId weakestId = null;
			double weakestImpact = Double.POSITIVE_INFINITY;
			for (LightId selectedId : next.keySet()) {
				FrameLight selected = eligible.get(selectedId);
				double impact = frameImpact(selected);
				if (impact < weakestImpact) { weakestImpact = impact; weakestId = selectedId; }
			}
			if (frameImpact(candidate) > weakestImpact * 1.25) {
				int slot = next.remove(weakestId);
				next.put(candidate.id(), slot);
			}
		}
		this.shadowSlots.clear();
		this.shadowSlots.putAll(next);
		List<FrameLight> assigned = new ArrayList<>(lights.size());
		for (FrameLight light : lights) assigned.add(light.withShadowSlot(next.getOrDefault(light.id(), -1)));
		return List.copyOf(assigned);
	}

	private static double frameImpact(final FrameLight light) {
		double luminance = 0.2126 * light.red() + 0.7152 * light.green() + 0.0722 * light.blue();
		double distanceSquared = (double)light.viewX() * light.viewX()
			+ (double)light.viewY() * light.viewY() + (double)light.viewZ() * light.viewZ();
		return luminance * light.intensity() * light.radius() * light.radius()
			/ Math.max(distanceSquared, 1.0);
	}

	private static TileGrid buildTiles(
		final List<FrameLight> lights,
		final Matrix4f projection,
		final int width,
		final int height
	) {
		int tilesX = (width + TILE_SIZE - 1) / TILE_SIZE;
		int tilesY = (height + TILE_SIZE - 1) / TILE_SIZE;
		int[] entries = new int[Math.multiplyExact(Math.multiplyExact(tilesX, tilesY), TILE_STRIDE)];
		int overflow = 0;
		for (int lightIndex = 0; lightIndex < lights.size(); lightIndex++) {
			FrameLight light = lights.get(lightIndex);
			float depth = -light.viewZ();
			int minTileX;
			int maxTileX;
			int minTileY;
			int maxTileY;
			if (depth <= light.radius() + 0.05F) {
				minTileX = 0; maxTileX = tilesX - 1; minTileY = 0; maxTileY = tilesY - 1;
			} else {
				Vector4f clip = projection.transform(new Vector4f(light.viewX(), light.viewY(), light.viewZ(), 1.0F));
				float centerX = clip.x / clip.w;
				float centerY = clip.y / clip.w;
				float nearestDepth = Math.max(0.05F, depth - light.radius());
				float radiusX = Math.abs(projection.m00()) * light.radius() / nearestDepth;
				float radiusY = Math.abs(projection.m11()) * light.radius() / nearestDepth;
				minTileX = clampTile((int)Math.floor(((centerX - radiusX) * 0.5F + 0.5F) * width / TILE_SIZE), tilesX);
				maxTileX = clampTile((int)Math.floor(((centerX + radiusX) * 0.5F + 0.5F) * width / TILE_SIZE), tilesX);
				minTileY = clampTile((int)Math.floor(((-centerY - radiusY) * 0.5F + 0.5F) * height / TILE_SIZE), tilesY);
				maxTileY = clampTile((int)Math.floor(((-centerY + radiusY) * 0.5F + 0.5F) * height / TILE_SIZE), tilesY);
				if (minTileX > maxTileX) { int swap = minTileX; minTileX = maxTileX; maxTileX = swap; }
				if (minTileY > maxTileY) { int swap = minTileY; minTileY = maxTileY; maxTileY = swap; }
			}
			for (int tileY = minTileY; tileY <= maxTileY; tileY++) {
				for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
					int base = (tileY * tilesX + tileX) * TILE_STRIDE;
					int count = entries[base];
					if (count < MAX_LIGHTS_PER_TILE) entries[base + 1 + count] = lightIndex;
					else overflow++;
					if (count < MAX_LIGHTS_PER_TILE) entries[base] = count + 1;
				}
			}
		}
		return new TileGrid(width, height, tilesX, tilesY, entries, overflow);
	}

	private static int clampTile(final int value, final int tileCount) {
		return Math.max(0, Math.min(tileCount - 1, value));
	}

	private void captureCamera(final CameraRenderState camera) {
		this.cameraBlockX = (int)Math.floor(camera.pos.x);
		this.cameraBlockY = (int)Math.floor(camera.pos.y);
		this.cameraBlockZ = (int)Math.floor(camera.pos.z);
		this.cameraFracX = (float)(camera.pos.x - this.cameraBlockX);
		this.cameraFracY = (float)(camera.pos.y - this.cameraBlockY);
		this.cameraFracZ = (float)(camera.pos.z - this.cameraBlockZ);
		this.occupancyOriginX = this.cameraBlockX - OCCUPANCY_SIZE / 2;
		this.occupancyOriginY = this.cameraBlockY - OCCUPANCY_SIZE / 2;
		this.occupancyOriginZ = this.cameraBlockZ - OCCUPANCY_SIZE / 2;
		this.worldFromView.set(camera.viewRotationMatrix).invert();
		if (!Float.isFinite(this.worldFromView.determinant())) {
			this.worldFromView.identity();
		}
	}

	private void resetOccupancyWindow() {
		this.occupancyOriginX = -OCCUPANCY_SIZE / 2;
		this.occupancyOriginY = -OCCUPANCY_SIZE / 2;
		this.occupancyOriginZ = -OCCUPANCY_SIZE / 2;
	}

	private void upload(final Snapshot snapshot) {
		MetalGpuDevice device = this.gpuDevice;
		if (device == null) return;
		int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
		int[] occupancy = this.packOccupancy();
		this.occupancySolid = 0;
		for (int word : occupancy) {
			if (word != 0) {
				this.occupancySolid = 1;
				break;
			}
		}
		try (GpuBufferSlice.MappedView mapping = device.transientMemory().allocateGpuMapped(
			GPU_BUFFER_BYTES, alignment, GpuBuffer.USAGE_UNIFORM
		)) {
			this.lightBuffer = mapping.slice();
			var bytes = mapping.data().order(ByteOrder.nativeOrder());
			bytes.putInt(snapshot.lights().size());
			bytes.putInt(snapshot.tiles().tilesX());
			bytes.putInt(snapshot.tiles().tilesY());
			bytes.putInt(TILE_STRIDE);
			bytes.putInt(this.occupancyOriginX);
			bytes.putInt(this.occupancyOriginY);
			bytes.putInt(this.occupancyOriginZ);
			bytes.putInt(OCCUPANCY_SIZE);
			bytes.putInt(this.cameraBlockX);
			bytes.putInt(this.cameraBlockY);
			bytes.putInt(this.cameraBlockZ);
			bytes.putInt(this.occupancySolid);
			bytes.putFloat(this.cameraFracX);
			bytes.putFloat(this.cameraFracY);
			bytes.putFloat(this.cameraFracZ);
			bytes.putFloat(0.0F);
			this.worldFromView.get(64, bytes);
			bytes.position(GPU_HEADER_BYTES);
			for (FrameLight light : snapshot.lights()) {
				bytes.putFloat(light.viewX()).putFloat(light.viewY()).putFloat(light.viewZ()).putFloat(light.radius());
				bytes.putFloat(light.red()).putFloat(light.green()).putFloat(light.blue()).putFloat(light.intensity());
				bytes.putInt(light.shadowSlot()).putInt(0).putInt(0).putInt(0);
			}
		}
		try (GpuBufferSlice.MappedView mapping = device.transientMemory().allocateGpuMapped(
			snapshot.tiles().gpuBytes(), alignment, GpuBuffer.USAGE_UNIFORM
		)) {
			this.tileBuffer = mapping.slice();
			var bytes = mapping.data().order(ByteOrder.nativeOrder());
			for (int entry : snapshot.tiles().entries) bytes.putInt(entry);
		}
		try (GpuBufferSlice.MappedView mapping = device.transientMemory().allocateGpuMapped(
			OCCUPANCY_BYTES, alignment, GpuBuffer.USAGE_UNIFORM
		)) {
			this.occupancyBuffer = mapping.slice();
			var bytes = mapping.data().order(ByteOrder.nativeOrder());
			for (int word : occupancy) bytes.putInt(word);
		}
	}

	private int[] packOccupancy() {
		int[] packed = new int[OCCUPANCY_WORDS];
		int minX = this.occupancyOriginX;
		int minY = this.occupancyOriginY;
		int minZ = this.occupancyOriginZ;
		int maxX = minX + OCCUPANCY_SIZE - 1;
		int maxY = minY + OCCUPANCY_SIZE - 1;
		int maxZ = minZ + OCCUPANCY_SIZE - 1;
		for (int sectionX = minX >> 4; sectionX <= maxX >> 4; sectionX++) {
			for (int sectionY = minY >> 4; sectionY <= maxY >> 4; sectionY++) {
				for (int sectionZ = minZ >> 4; sectionZ <= maxZ >> 4; sectionZ++) {
					long[] section = this.occupancySections.get(SectionPos.asLong(sectionX, sectionY, sectionZ));
					if (section == null) continue;
					this.copySection(section, sectionX, sectionY, sectionZ, packed);
				}
			}
		}
		return packed;
	}

	private void copySection(
		final long[] section,
		final int sectionX,
		final int sectionY,
		final int sectionZ,
		final int[] packed
	) {
		int baseX = sectionX << 4;
		int baseY = sectionY << 4;
		int baseZ = sectionZ << 4;
		for (int bit = 0; bit < 4096; bit++) {
			if ((section[bit >> 6] & 1L << (bit & 63)) == 0L) continue;
			int worldX = baseX + (bit & 15);
			int worldZ = baseZ + (bit >> 4 & 15);
			int worldY = baseY + (bit >> 8 & 15);
			int localX = worldX - this.occupancyOriginX;
			int localY = worldY - this.occupancyOriginY;
			int localZ = worldZ - this.occupancyOriginZ;
			if (localX < 0 || localX >= OCCUPANCY_SIZE || localY < 0 || localY >= OCCUPANCY_SIZE
				|| localZ < 0 || localZ >= OCCUPANCY_SIZE) {
				continue;
			}
			int index = (localY * OCCUPANCY_SIZE + localZ) * OCCUPANCY_SIZE + localX;
			packed[index >> 5] |= 1 << (index & 31);
		}
	}

	private void rebuildChunkOccupancy(final LevelChunk chunk) {
		this.dropChunkOccupancy(chunk.getPos(), chunk.getMinSectionY(), chunk.getMaxSectionY());
		chunk.findBlocks(MetalWorldLighting::occludesLight, (position, state) -> this.setOccupied(position, true));
	}

	private void dropChunkOccupancy(final ChunkPos position, final int minSectionY, final int maxSectionY) {
		for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
			this.occupancySections.remove(SectionPos.asLong(position.x(), sectionY, position.z()));
		}
	}

	private void setOccupied(final BlockPos position, final boolean solid) {
		long key = SectionPos.asLong(position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4);
		int local = (position.getX() & 15) | (position.getZ() & 15) << 4 | (position.getY() & 15) << 8;
		if (solid) {
			long[] bits = this.occupancySections.get(key);
			if (bits == null) {
				bits = new long[64];
				this.occupancySections.put(key, bits);
			}
			bits[local >> 6] |= 1L << (local & 63);
			return;
		}
		long[] bits = this.occupancySections.get(key);
		if (bits == null) return;
		bits[local >> 6] &= ~(1L << (local & 63));
		for (long word : bits) {
			if (word != 0L) return;
		}
		this.occupancySections.remove(key);
	}

	private static boolean occludesLight(final BlockState state) {
		return state.canOcclude() && state.isSolidRender();
	}

	private static MetalBuffer metal(final GpuBufferSlice slice) {
		return ((MetalGpuBuffer)slice.buffer()).metal();
	}

	private void selectLevel(final ClientLevel level) {
		if (this.cachedLevel == level) return;
		this.cachedLevel = level;
		this.staticChunks.clear();
		this.occupancySections.clear();
		this.shadowSlots.clear();
	}

	private static @Nullable WorldLight staticEmitter(final BlockPos position, final BlockState state) {
		int emission = state.getLightEmission();
		if (emission <= 0) return null;
		String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		Color color = colorFor(name);
		return new WorldLight(
			new LightId(STATIC_PROVIDER, position.asLong()), Vec3.atCenterOf(position),
			color.red(), color.green(), color.blue(), intensityFor(emission), radiusFor(emission),
			true
		);
	}

	private static float intensityFor(final int emission) {
		return 0.55F + 3.2F * emission / 15.0F;
	}

	private static float radiusFor(final int emission) {
		return 3.0F + emission * 1.25F;
	}

	private static int itemEmission(final String name) {
		if (name.contains("glowstone_dust") || name.contains("blaze_rod") || name.contains("blaze_powder")
			|| name.contains("magma_cream") || name.contains("fire_charge") || name.contains("glow_berries")
			|| name.contains("glow_ink_sac")) return 8;
		return 0;
	}

	/** Curated vanilla spectral families; unknown emitting blocks deliberately use warm white. */
	private static Color colorFor(final String name) {
		if (name.contains("soul_")) return new Color(0.18F, 0.64F, 1.0F);
		if (name.contains("redstone")) return new Color(1.0F, 0.08F, 0.025F);
		if (name.contains("verdant_froglight")) return new Color(0.68F, 1.0F, 0.55F);
		if (name.contains("pearlescent_froglight")) return new Color(1.0F, 0.55F, 0.92F);
		if (name.contains("ochre_froglight")) return new Color(1.0F, 0.82F, 0.42F);
		if (name.contains("sea_lantern") || name.contains("end_rod") || name.contains("beacon")) {
			return new Color(0.72F, 0.94F, 1.0F);
		}
		if (name.contains("copper")) return new Color(0.48F, 1.0F, 0.70F);
		if (name.contains("lava") || name.contains("magma")) return new Color(1.0F, 0.20F, 0.025F);
		if (name.contains("fire") || name.contains("torch") || name.contains("campfire")) {
			return new Color(1.0F, 0.36F, 0.075F);
		}
		if (name.contains("glowstone") || name.contains("shroomlight") || name.contains("lantern")
			|| name.contains("candle") || name.contains("jack_o_lantern")) {
			return new Color(1.0F, 0.68F, 0.30F);
		}
		return new Color(1.0F, 0.86F, 0.68F);
	}

	@Override
	public synchronized void close() {
		this.gpuDevice = null;
		this.lightBuffer = null;
		this.tileBuffer = null;
		this.occupancyBuffer = null;
		this.staticChunks.clear();
		this.occupancySections.clear();
		this.shadowSlots.clear();
		this.cachedLevel = null;
		this.current = Snapshot.EMPTY;
	}

	public record LightId(Identifier providerId, long stableId) {
		public LightId { Objects.requireNonNull(providerId, "providerId"); }
	}

	public record FrameLight(
		LightId id,
		float cameraX,
		float cameraY,
		float cameraZ,
		float viewX,
		float viewY,
		float viewZ,
		float red,
		float green,
		float blue,
		float intensity,
		float radius,
		boolean shadowEligible,
		int shadowSlot
	) {
		public FrameLight { Objects.requireNonNull(id, "id"); }

		FrameLight withShadowSlot(final int slot) {
			return new FrameLight(
				this.id, this.cameraX, this.cameraY, this.cameraZ, this.viewX, this.viewY, this.viewZ,
				this.red, this.green, this.blue, this.intensity, this.radius,
				this.shadowEligible, slot
			);
		}
	}

	public record Snapshot(List<FrameLight> lights, int overflowCount, TileGrid tiles) {
		private static final Snapshot EMPTY = new Snapshot(List.of(), 0, TileGrid.empty());

		public Snapshot {
			lights = List.copyOf(lights);
			Objects.requireNonNull(tiles, "tiles");
			if (overflowCount < 0) throw new IllegalArgumentException("overflowCount must not be negative");
		}
	}

	/** Immutable fixed-stride tile lists: count followed by up to 64 strongest light indices. */
	public static final class TileGrid {
		private final int width;
		private final int height;
		private final int tilesX;
		private final int tilesY;
		private final int[] entries;
		private final int overflowCount;

		private TileGrid(
			final int width, final int height, final int tilesX, final int tilesY,
			final int[] entries, final int overflowCount
		) {
			this.width = width; this.height = height; this.tilesX = tilesX; this.tilesY = tilesY;
			this.entries = entries.clone(); this.overflowCount = overflowCount;
		}

		private static TileGrid empty() { return new TileGrid(1, 1, 1, 1, new int[TILE_STRIDE], 0); }
		public int width() { return this.width; }
		public int height() { return this.height; }
		public int tilesX() { return this.tilesX; }
		public int tilesY() { return this.tilesY; }
		public int overflowCount() { return this.overflowCount; }
		public int count(final int tileX, final int tileY) { return this.entries[this.base(tileX, tileY)]; }
		public int lightIndex(final int tileX, final int tileY, final int index) {
			int count = this.count(tileX, tileY);
			if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
			return this.entries[this.base(tileX, tileY) + 1 + index];
		}
		private int base(final int tileX, final int tileY) {
			if (tileX < 0 || tileX >= this.tilesX || tileY < 0 || tileY >= this.tilesY) {
				throw new IndexOutOfBoundsException("tile " + tileX + "," + tileY);
			}
			return (tileY * this.tilesX + tileX) * TILE_STRIDE;
		}
		private int gpuBytes() { return Math.multiplyExact(this.entries.length, Integer.BYTES); }
	}

	private record WorldLight(
		LightId id, Vec3 position, float red, float green, float blue,
		float intensity, float radius, boolean shadowEligible
	) { }

	private record RankedLight(
		LightId id,
		float cameraX, float cameraY, float cameraZ,
		float viewX, float viewY, float viewZ,
		float red, float green, float blue,
		float intensity, float radius,
		boolean shadowEligible,
		double impact
	) { }

	private record Color(float red, float green, float blue) { }
}
