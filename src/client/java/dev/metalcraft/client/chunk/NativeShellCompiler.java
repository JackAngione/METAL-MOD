package dev.metalcraft.client.chunk;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.metalcraft.client.shader.water.SectionCompilerResultsWater;
import dev.metalcraft.client.shader.water.WaterVertexMetadata;
import dev.metalcraft.client.shader.world.WaterIdentityDebug;
import java.util.ArrayList;
import java.util.EnumMap;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/** Direct model/fluid envelopes. Never constructs native block or fluid meshes first. */
public final class NativeShellCompiler {
    private NativeShellCompiler() { }

    public static SectionCompiler.Results compile(SectionPos section, BlockAndTintGetter region,
            SectionBufferBuilderPack builders, BlockStateModelSet models, FluidStateModelSet fluids,
            BlockColors colors, VertexSorting sorting, int tier) {
        long started = System.nanoTime();
        var results = new SectionCompiler.Results();
        var layers = new EnumMap<ChunkSectionLayer, Layer>(ChunkSectionLayer.class);
        var origin = section.origin();
        var pos = new BlockPos.MutableBlockPos();
        var visibility = new VisGraph();
        var fluidKinds = new ArrayList<FluidState>();
        int blocks = 0, fluidBlocks = 0;
        // Preserve native opaque visibility without native tessellation, model lookups,
        // per-block water sidecars or block-entity extraction.
        for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
            var state = region.getBlockState(pos);
            if (state.isSolidRender()) visibility.setOpaque(pos);
            if (state.getRenderShape() == RenderShape.MODEL) blocks++;
            var fluid = state.getFluidState();
            if (fluid.isEmpty()) continue;
            fluidBlocks++;
            boolean known = false;
            for (var kind : fluidKinds) if (fluid.getType().isSame(kind.getType())) { known = true; break; }
            if (!known) fluidKinds.add(fluid);
        }
        try {
            int solidFaces = blocks == 0 ? 0 : NativeShellMesher.build((x, y, z) -> {
                pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                return region.getBlockState(pos).getRenderShape() == RenderShape.MODEL;
            }, tier, (axis, sign, plane, u0, v0, u1, v1, sx, sy, sz) -> {
                pos.set(origin.getX() + sx, origin.getY() + sy, origin.getZ() + sz);
                var state = region.getBlockState(pos);
                var tintSource = colors.getTintSource(state, 0);
                int tint = tintSource == null ? -1 : tintSource.colorAsTerrainParticle(state, region, pos);
                var direction = direction(axis, sign);
                int color = shade(tint, region.cardinalLighting().byFace(direction));
                int light = light(region, pos, direction);
                float[] vertices = new float[12];
                for (int i = 0; i < 4; i++) {
                    int corner = sign > 0 ? i : 3 - i;
                    float u = corner == 1 || corner == 2 ? u1 : u0;
                    float v = corner >= 2 ? v1 : v0;
                    vertices[i * 3] = axis == 0 ? plane : axis == 1 ? v : u;
                    vertices[i * 3 + 1] = axis == 0 ? u : axis == 1 ? plane : v;
                    vertices[i * 3 + 2] = axis == 0 ? v : axis == 1 ? u : plane;
                }
                layer(layers, builders, ChunkSectionLayer.SOLID).face(vertices,
                        models.getParticleMaterial(state).sprite(), color, light, null);
            });
            int fluidFaces = 0;
            for (var kind : fluidKinds) {
                boolean water = kind.is(FluidTags.WATER);
                fluidFaces += NativeFluidShellMesher.build((x, y, z) -> {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    var state = region.getBlockState(pos);
                    // Full opaque neighbors also hide a section boundary; they are
                    // not part of the fluid's envelope inside the section.
                    return state.getFluidState().getType().isSame(kind.getType())
                            || (x < 0 || y < 0 || z < 0 || x >= 16 || y >= 16 || z >= 16) && state.isSolidRender();
                }, (x, y, z) -> {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    var state = region.getBlockState(pos);
                    return state.isSolidRender() ? 1 : state.getFluidState().getHeight(region, pos);
                }, tier, water, (vertices, axis, sign, sx, sy, sz) -> {
                    pos.set(origin.getX() + sx, origin.getY() + sy, origin.getZ() + sz);
                    var state = region.getBlockState(pos);
                    var fluid = state.getFluidState();
                    var model = fluids.get(fluid);
                    var flow = fluid.getFlow(region, pos);
                    int tint = model.tintSource() == null ? -1 : model.tintSource().colorInWorld(state, region, pos);
                    var direction = direction(axis, sign);
                    int color = shade(tint, region.cardinalLighting().byFace(direction));
                    if (water && WaterIdentityDebug.enabled()) color = (color & 0xff000000) | 0xff00ff;
                    int light = light(region, pos, direction);
                    var material = axis == 1 && flow.x == 0 && flow.z == 0 ? model.stillMaterial() : model.flowingMaterial();
                    layer(layers, builders, model.layer()).face(vertices, material.sprite(), color, light, water ? flow : null);
                });
            }
            var metadata = new EnumMap<ChunkSectionLayer, WaterVertexMetadata>(ChunkSectionLayer.class);
            for (var entry : layers.entrySet()) {
                var layer = entry.getKey(); var output = entry.getValue();
                var mesh = output.buffer.build();
                if (mesh == null) continue;
                results.renderedLayers.put(layer, mesh);
                if (layer == ChunkSectionLayer.TRANSLUCENT)
                    results.transparencyState = mesh.sortQuads(builders.buffer(layer), sorting);
                if (output.water.isPopulated()) metadata.put(layer, output.water.build(output.vertices));
            }
            results.visibilitySet = visibility.resolve();
            ((NativeLodState)(Object)results).metalcraft$cellSize(tier);
            ((SectionCompilerResultsWater)(Object)results).metalcraft$setWaterVertexMetadata(metadata);
            NativeTerrainLod.recordShell(blocks, solidFaces, fluidBlocks, fluidFaces, System.nanoTime() - started);
            return results;
        } catch (RuntimeException | Error error) {
            results.release();
            throw error;
        }
    }

    private static Layer layer(EnumMap<ChunkSectionLayer, Layer> layers, SectionBufferBuilderPack builders, ChunkSectionLayer layer) {
        return layers.computeIfAbsent(layer, key -> new Layer(new BufferBuilder(builders.buffer(key), PrimitiveTopology.QUADS, key.vertexFormat())));
    }
    private static Direction direction(int axis, int sign) {
        return axis == 0 ? (sign > 0 ? Direction.EAST : Direction.WEST)
                : axis == 1 ? (sign > 0 ? Direction.UP : Direction.DOWN) : (sign > 0 ? Direction.SOUTH : Direction.NORTH);
    }
    private static int shade(int tint, float shade) {
        return 0xff000000 | (int)(((tint >> 16) & 255) * shade) << 16
                | (int)(((tint >> 8) & 255) * shade) << 8 | (int)((tint & 255) * shade);
    }
    private static int light(BlockAndTintGetter region, BlockPos.MutableBlockPos pos, Direction direction) {
        int block = region.getBrightness(LightLayer.BLOCK, pos), sky = region.getBrightness(LightLayer.SKY, pos);
        pos.move(direction);
        return Math.max(block, region.getBrightness(LightLayer.BLOCK, pos)) << 4
                | Math.max(sky, region.getBrightness(LightLayer.SKY, pos)) << 20;
    }
    private static final class Layer {
        final BufferBuilder buffer;
        final WaterVertexMetadata.Builder water = new WaterVertexMetadata.Builder();
        int vertices;
        Layer(BufferBuilder buffer) { this.buffer = buffer; }
        void face(float[] positions, TextureAtlasSprite sprite, int color, int light, Vec3 flow) {
            if (flow != null) water.putQuad(vertices, positions, (float)flow.x, (float)flow.y, (float)flow.z);
            for (int i = 0; i < 4; i++) buffer.addVertex(positions[i*3], positions[i*3+1], positions[i*3+2])
                    .setColor(color).setUv((sprite.getU0()+sprite.getU1())*.5f, (sprite.getV0()+sprite.getV1())*.5f).setLight(light);
            vertices += 4;
        }
    }
}
