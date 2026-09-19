package dev.metalcraft.client.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.chunk.NativeLodState;
import dev.metalcraft.client.chunk.NativeTerrainLod;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.core.BlockPos;

/** Same-camera 0/5/0 visual comparison on actual one-block steps in a NORMAL generated world. */
final class MetalGeometricLodGameTest {
    static void run(ClientGameTestContext context) {
        int savedDistance = context.computeOnClient(c -> c.options.renderDistance().get());
        int savedSimulation = context.computeOnClient(c -> c.options.simulationDistance().get());
        int savedFov = context.computeOnClient(c -> c.options.fov().get());
        int savedReduction = MetalCraftConfig.nativeLodReduction();
        int savedNativeDistance = MetalCraftConfig.nativeQualityDistance();
        boolean savedEnabled = MetalCraftConfig.nativeTerrainLod(), savedFog = MetalCraftConfig.clearDistanceFog();
        var savedBuilder = context.computeOnClient(c -> c.options.prioritizeChunkUpdates().get());
        String savedPack = context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        var report = new LinkedHashMap<String, Object>();
        long started = System.nanoTime();
        try {
            context.runOnClient(c -> {
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "Metal selected");
                c.options.renderDistance().set(16); c.options.simulationDistance().set(16);
                c.options.prioritizeChunkUpdates().set(PrioritizeChunkUpdates.NONE); c.options.fov().set(70);
                MetalCraftConfig.setNativeQualityDistance(4);
                MetalCraftConfig.setNativeTerrainLod(true); MetalCraftConfig.setNativeLodReduction(0);
                MetalCraftConfig.setClearDistanceFog(true); ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID);
            });
            context.getInput().resizeWindow(1280, 720);
            try (var world = context.worldBuilder().adjustSettings(settings -> {
                var normal = settings.getSettings().worldgenLoadContext().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                        .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
                settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
                settings.setSeed("metalcraft"); settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE); settings.setAllowCommands(true);
            }).create()) {
                var center = context.computeOnClient(c -> c.player.chunkPosition());
                int targetX = (center.x() + 8) * 16, targetZ = center.z() * 16;
                int cameraX = center.x() * 16 + 8, cameraZ = targetZ + 64;
                BlockPos marker = new BlockPos(targetX + 8, 150, targetZ + 8);
                world.getServer().runCommand("gamemode spectator @a");
                world.getServer().runCommand("tp @a " + cameraX + " 185 " + cameraZ + " -110 10");
                world.getServer().runCommand("time set noon"); world.getServer().runCommand("weather clear");
                context.getInput().lookAt(-110, 10);
                context.waitFor(c -> c.level.getChunkSource().hasChunk(center.x() + 9, center.z() + 1), 900);
                for (int x = 0; x < 32; x++) world.getServer().runCommand("fill " + (targetX + x) + " 145 " + (targetZ + 1)
                        + " " + (targetX + x) + " " + (145 + x) + " " + (targetZ + 30) + " minecraft:stone");
                context.waitFor(c -> uploaded(c, marker, 1), 600);
                context.waitTicks(40);
                context.runOnClient(c -> c.gui.hud.getChat().clearMessages(true));
                int nativeIndices = context.computeOnClient(c -> indices(c, marker));
                var before = NativeTerrainLod.stats();
                context.takeScreenshot("geometric-lod-0-native");
                context.runOnClient(c -> { MetalCraftConfig.setNativeLodReduction(5); MetalCraftConfig.reload(); });
                check(MetalCraftConfig.nativeLodReduction() == 5, "extreme preference persisted");
                context.waitTicks(5);
                int selectedCell = context.computeOnClient(c -> NativeTerrainLod.snapshotCellSize(net.minecraft.core.SectionPos.of(marker).asLong()));
                context.runOnClient(c -> System.out.println("Geometric LOD selection: requested=" + selectedCell
                        + " installed=" + ((NativeLodState)mesh(c, marker)).metalcraft$cellSize()
                        + " camera=" + c.gameRenderer.mainCamera().position() + " fov=" + c.gameRenderer.mainCamera().getFov()
                        + " stats=" + NativeTerrainLod.stats()));
                check(selectedCell > 1, "fixture selects a coarser geometric tier");
                context.waitFor(c -> uploaded(c, marker, selectedCell), 600);
                context.waitTicks(10);
                int coarseIndices = context.computeOnClient(c -> indices(c, marker));
                check(coarseIndices < nativeIndices, "stepped fixture geometry reduced");
                check(NativeTerrainLod.stats().geometricBuilds() > before.geometricBuilds(), "actual clustering was uploaded");
                context.takeScreenshot("geometric-lod-5-extreme");
                // Keep LOD enabled: moving into the native radius must replace the
                // already uploaded coarse mesh without a reload or block edit.
                long approachStarted = System.nanoTime();
                world.getServer().runCommand("tp @a " + (targetX - 24) + " 158 " + (targetZ + 8) + " -90 0");
                context.getInput().lookAt(-90, 0);
                context.waitFor(c -> uploaded(c, marker, 1) && indices(c, marker) == nativeIndices, 100);
                report.put("approachNativeRestoredSeconds", (System.nanoTime() - approachStarted) / 1e9);
                context.waitTicks(20);
                check(context.computeOnClient(c -> uploaded(c, marker, 1) && indices(c, marker) == nativeIndices),
                        "late coarse builds cannot leave nearby fixture degraded");
                context.takeScreenshot("geometric-lod-approach-native");
                world.getServer().runCommand("tp @a " + cameraX + " 185 " + cameraZ + " -110 10");
                context.getInput().lookAt(-110, 10);
                context.waitFor(c -> uploaded(c, marker, selectedCell), 100);
                report.put("retreatCoarseRestored", true);
                context.runOnClient(c -> MetalCraftConfig.setNativeQualityDistance(16));
                context.waitFor(c -> uploaded(c, marker, 1) && indices(c, marker) == nativeIndices, 100);
                report.put("expandedRadiusRestoresNative", true);
                context.runOnClient(c -> MetalCraftConfig.setNativeQualityDistance(4));
                context.waitFor(c -> uploaded(c, marker, selectedCell), 100);
                context.runOnClient(c -> MetalCraftConfig.setNativeLodReduction(0));
                context.waitFor(c -> uploaded(c, marker, 1) && indices(c, marker) == nativeIndices, 600);
                context.waitTicks(5);
                context.takeScreenshot("geometric-lod-0-restored");
                report.put("world", "NORMAL / metalcraft"); report.put("backend", "Metal");
                report.put("renderDistance", 16); report.put("simulationDistance", 16); report.put("chunkBuilder", "Threaded");
                report.put("sameCameraSequence", "0 -> 5 -> 0");
                report.put("selectedCell", selectedCell);
                report.put("nativeIndices", nativeIndices); report.put("coarseIndices", coarseIndices);
                report.put("nativeRestored", true); report.put("stats", NativeTerrainLod.stats());
            }
            report.put("elapsedSeconds", (System.nanoTime() - started) / 1e9); report.put("worldClosed", true);
            System.out.println("Geometric LOD route passed; inspect same-camera images: " + report);
        } finally {
            context.runOnClient(c -> {
                c.options.renderDistance().set(savedDistance); c.options.simulationDistance().set(savedSimulation);
                c.options.fov().set(savedFov); c.options.prioritizeChunkUpdates().set(savedBuilder);
                MetalCraftConfig.setNativeTerrainLod(savedEnabled); MetalCraftConfig.setNativeLodReduction(savedReduction);
                MetalCraftConfig.setNativeQualityDistance(savedNativeDistance);
                MetalCraftConfig.setClearDistanceFog(savedFog); ShaderPackRuntime.active().selectPack(savedPack);
            });
            try {
                Path path = Path.of("build/geometric-lod.json"); Files.createDirectories(path.getParent());
                Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            } catch (java.io.IOException error) { throw new AssertionError(error); }
        }
    }
    private static SectionMesh mesh(Minecraft c, BlockPos pos) {
        var area = c.levelRenderer.viewArea(); var section = area == null ? null : area.getRenderSectionAt(pos);
        return section == null ? null : section.getSectionMesh();
    }
    private static int indices(Minecraft c, BlockPos pos) { return mesh(c, pos).getSectionDraw(ChunkSectionLayer.SOLID).indexCount(); }
    private static boolean uploaded(Minecraft c, BlockPos pos, int cell) {
        SectionMesh mesh = mesh(c, pos);
        return mesh instanceof NativeLodState state && state.metalcraft$cellSize() == cell
                && mesh.getSectionDraw(ChunkSectionLayer.SOLID) != null
                && c.levelRenderer.visibleSections().contains(c.levelRenderer.viewArea().getRenderSectionAt(pos))
                && c.levelRenderer.sectionRenderDispatcher().getRenderSectionSlice(mesh, ChunkSectionLayer.SOLID) != null;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
