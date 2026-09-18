package dev.metalcraft.client.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.chunk.NativeChunkDistance;
import dev.metalcraft.client.lod.LodCapabilities;
import dev.metalcraft.client.lod.LodCompilerCapture;
import dev.metalcraft.client.lod.LodTerrainGeneration;
import dev.metalcraft.client.mixin.ChunkMapDistanceAccessor;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.level.ChunkPos;

/** Short full-detail test above the vanilla limit; no LOD cache or custom chunk injection. */
final class MetalNativeChunkDistanceGameTest {
    private static final int DISTANCE = 36;

    static void run(ClientGameTestContext context) {
        int savedDistance = context.computeOnClient(c -> c.options.renderDistance().get());
        int savedSimulation = context.computeOnClient(c -> c.options.simulationDistance().get());
        var savedLod = MetalCraftConfig.lod();
        boolean savedFog = MetalCraftConfig.clearDistanceFog();
        var report = new LinkedHashMap<String, Object>();
        long started = System.nanoTime();
        try {
            context.runOnClient(c -> {
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "Metal backend");
                // Check the real option codec/validation without allocating an enormous ViewArea.
                for (int distance : new int[]{33, 36, 64, 127, 128, 255, 256}) {
                    c.options.renderDistance().set(distance);
                    check(c.options.renderDistance().get() == distance, "option accepts " + distance);
                    var original = c.options.buildPlayerInformation();
                    check(original.viewDistance() == distance, "settings record preserves " + distance);
                    var buffer = new FriendlyByteBuf(Unpooled.buffer());
                    try {
                        original.write(buffer);
                        var decoded = new ClientInformation(buffer);
                        check(decoded.viewDistance() == 32, "remote wire distance does not overflow " + distance);
                        check(decoded.modelCustomisation() == original.modelCustomisation()
                                && decoded.particleStatus() == original.particleStatus() && !buffer.isReadable(),
                                "packet fields and framing preserved");
                    } finally { buffer.release(); }
                }
                new PriorityProbe().verify();
                c.options.renderDistance().set(DISTANCE);
                c.options.simulationDistance().set(16);
                MetalCraftConfig.setClearDistanceFog(false);
                // A saved opt-in from an older build must not activate the old pipeline.
                MetalCraftConfig.setLod(savedLod.withEnabled(true).withHorizon(256, true, 2048));
                check(!LodCapabilities.current(true).effective(MetalCraftConfig.lod()).enabled(), "legacy preferences inactive");
            });
            var builder = context.worldBuilder().adjustSettings(settings -> {
                var normal = settings.getSettings().worldgenLoadContext()
                        .lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                        .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
                settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
                settings.setSeed("metalcraft");
                settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                settings.setAllowCommands(true);
            });
            try (var world = builder.create()) {
                var center = context.computeOnClient(c -> c.player.chunkPosition());
                int cameraX = center.x() * 16 + 8, cameraZ = center.z() * 16 + 8;
                var target = new ChunkPos(center.x() + 35, center.z());
                var marker = new BlockPos(target.x() * 16 + 4, 170, target.z() * 16 + 4);
                world.getServer().runCommand("gamemode spectator @a");
                world.getServer().runCommand("tp @a " + cameraX + " 176 " + cameraZ + " -90 0");
                world.getServer().runCommand("time set noon");
                world.getServer().runCommand("weather clear");
                context.getInput().lookAt(-90, 0);
                context.waitTicks(2);
                world.getServer().runOnServer(server -> {
                    var chunks = server.overworld().getChunkSource();
                    var player = server.getPlayerList().getPlayers().getFirst();
                    check(((ChunkMapDistanceAccessor)chunks.chunkMap).metalcraft$serverViewDistance() == DISTANCE,
                            "ChunkMap passes the vanilla cap");
                    check(player.getChunkTrackingView() instanceof net.minecraft.server.level.ChunkTrackingView.Positioned view
                                    && view.viewDistance() == DISTANCE && view.contains(target),
                            "native player tracking includes distant column: requested=" + player.requestedViewDistance()
                                    + " view=" + player.getChunkTrackingView());
                    check(!chunks.isPositionTicking(target.pack()), "distant terrain does not simulate");
                });
                context.waitFor(c -> c.options.getEffectiveRenderDistance() == DISTANCE
                        && c.level.getChunkSource().hasChunk(target.x(), target.z()), 1800);
                // Placed only after ordinary generation/delivery; exposes the distant section to the camera.
                world.getServer().runCommand("fill " + marker.getX() + " 170 " + marker.getZ() + " "
                        + (marker.getX() + 7) + " 181 " + (marker.getZ() + 7) + " minecraft:diamond_block");
                context.waitFor(c -> rendered(c, marker), 1200);
                context.runOnClient(c -> {
                    check(c.levelRenderer.viewArea().getViewDistance() == DISTANCE, "native renderer distance");
                    check(!LodCompilerCapture.capturing() && !LodTerrainGeneration.stats().active(), "no legacy LOD work");
                    check(c.options.simulationDistance().get() == 16, "simulation unchanged");
                    c.gui.hud.getChat().clearMessages(true);
                });
                report.put("backend", "Metal"); report.put("world", "NORMAL / metalcraft");
                report.put("renderDistance", DISTANCE); report.put("simulationDistance", 16);
                report.put("targetChunk", target.toString()); report.put("targetOffsetChunks", 35);
                report.put("nativeMeshUploadedAndVisible", true); report.put("targetSimulated", false);
                context.takeScreenshot("metalcraft-native-distance-36");
                context.runOnClient(c -> {
                    MetalCraftConfig.setClearDistanceFog(true);
                    MetalCraftConfig.reload();
                    check(MetalCraftConfig.clearDistanceFog(), "clear distance fog persists");
                });
                context.waitTicks(3);
                context.takeScreenshot("metalcraft-native-distance-36-clear-fog");
                context.runOnClient(c -> MetalCraftConfig.setClearDistanceFog(false));
                context.waitTicks(3);
                context.takeScreenshot("metalcraft-native-distance-36-restored-fog");
                report.put("distanceFogToggle", "off -> on (disk reload) -> off; paired frames captured");

                // Both settings remain above the old limit. Shrink must unload and growth must reload.
                context.runOnClient(c -> { c.options.renderDistance().set(33); c.options.broadcastOptions(); });
                context.waitFor(c -> c.options.getEffectiveRenderDistance() == 33
                        && !c.level.getChunkSource().hasChunk(target.x(), target.z()), 600);
                context.runOnClient(c -> { c.options.renderDistance().set(DISTANCE); c.options.broadcastOptions(); });
                context.waitFor(c -> c.level.getChunkSource().hasChunk(target.x(), target.z()) && rendered(c, marker), 1200);
                report.put("shrinkAndRegrow", "36 -> 33 -> 36: unloaded and rendered again");
            }
            report.put("worldClosed", true);
            report.put("elapsedSeconds", (System.nanoTime() - started) / 1e9);
            System.out.println("Native chunk distance passed: NORMAL world, Metal, 36/16, column 35 received/uploaded/visible, shrink/reload/close, byte compatibility through 256");
        } finally {
            context.runOnClient(c -> {
                c.options.renderDistance().set(savedDistance); c.options.simulationDistance().set(savedSimulation);
                MetalCraftConfig.setLod(savedLod);
                MetalCraftConfig.setClearDistanceFog(savedFog);
            });
            try {
                Path output = Path.of("build/native-chunk-distance.json");
                Files.createDirectories(output.getParent());
                Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            } catch (java.io.IOException error) { throw new AssertionError(error); }
        }
    }

    private static boolean rendered(Minecraft client, BlockPos marker) {
        if (client.levelRenderer.viewArea() == null) return false;
        var section = client.levelRenderer.viewArea().getRenderSectionAt(marker);
        if (section == null || !client.levelRenderer.visibleSections().contains(section)) return false;
        var mesh = section.getSectionMesh();
        return mesh.getSectionDraw(ChunkSectionLayer.SOLID) != null
                && client.levelRenderer.sectionRenderDispatcher().getRenderSectionSlice(mesh, ChunkSectionLayer.SOLID) != null;
    }

    private static final class PriorityProbe extends ChunkTaskPriorityQueue {
        PriorityProbe() { super("native-distance-test"); }
        void verify() {
            long far = ChunkPos.pack(256, 0), near = ChunkPos.pack(1, 0);
            submit(() -> { }, far, 256); submit(() -> { }, near, 1);
            check(pop().chunkPos() == near, "near chunks keep priority");
            resortChunkTasks(256, ChunkPos.unpack(far), NativeChunkDistance.NO_LEVEL);
            check(pop().chunkPos() == far && !hasWork(), "extended queue and removal sentinel fit");
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
