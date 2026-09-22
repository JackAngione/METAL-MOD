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

/** Short native pipeline check, with a measurable opaque fixture inside a NORMAL generated world. */
final class MetalNativeTerrainLodGameTest {
    static void run(ClientGameTestContext context) {
        int savedDistance = context.computeOnClient(c -> c.options.renderDistance().get());
        int savedSimulation = context.computeOnClient(c -> c.options.simulationDistance().get());
        int savedReduction = MetalCraftConfig.nativeLodReduction();
        int savedNativeDistance = MetalCraftConfig.nativeQualityDistance();
        int savedFov = context.computeOnClient(c -> c.options.fov().get());
        var savedBuilder = context.computeOnClient(c -> c.options.prioritizeChunkUpdates().get());
        boolean savedEnabled = MetalCraftConfig.nativeTerrainLod(), savedFog = MetalCraftConfig.clearDistanceFog();
        String savedPack = context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        var report = new LinkedHashMap<String, Object>();
        long started = System.nanoTime();
        try {
            context.runOnClient(c -> {
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "Metal backend");
                c.options.renderDistance().set(16); c.options.simulationDistance().set(16);
                c.options.prioritizeChunkUpdates().set(PrioritizeChunkUpdates.NONE); // Native label: Threaded.
                c.options.fov().set(70);
                MetalCraftConfig.setNativeQualityDistance(4);
                MetalCraftConfig.setNativeTerrainLod(true); MetalCraftConfig.setNativeLodReduction(3);
                MetalCraftConfig.setClearDistanceFog(true);
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID);
            });
            try (var world = context.worldBuilder().adjustSettings(settings -> {
                var normal = settings.getSettings().worldgenLoadContext().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                        .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
                settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
                settings.setSeed("metalcraft"); settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                settings.setAllowCommands(true);
            }).create()) {
                var center = context.computeOnClient(c -> c.player.chunkPosition());
                int cameraX = center.x() * 16 + 8, cameraZ = center.z() * 16 + 8;
                int targetX = (center.x() + 15) * 16, targetZ = center.z() * 16;
                BlockPos marker = new BlockPos(targetX + 8, 170, targetZ + 8);
                world.getServer().runCommand("gamemode spectator @a");
                world.getServer().runCommand("tp @a " + cameraX + " 180 " + cameraZ + " -90 2");
                world.getServer().runCommand("time set noon"); world.getServer().runCommand("weather clear");
                context.getInput().lookAt(-90, 2);
                context.waitFor(c -> c.level.getChunkSource().hasChunk(center.x() + 15, center.z()), 900);
                world.getServer().runCommand("fill " + targetX + " 170 " + targetZ + " " + (targetX + 15) + " 170 " + (targetZ + 15) + " minecraft:stone");
                context.waitFor(c -> uploaded(c, marker, 4), 600);
                int distantIndices = context.computeOnClient(c -> mesh(c, marker).getSectionDraw(ChunkSectionLayer.SOLID).indexCount());
                report.put("distantCellSize", 4); report.put("distantIndices", distantIndices);
                context.runOnClient(c -> c.gui.hud.getChat().clearMessages(true));
                context.takeScreenshot("metalcraft-native-lod-far");
                var initialMesh = context.computeOnClient(c -> mesh(c, marker));
                world.getServer().runCommand("setblock " + (targetX + 8) + " 170 " + (targetZ + 8) + " minecraft:air");
                context.waitFor(c -> mesh(c, marker) != initialMesh && uploaded(c, marker, 4), 600);
                report.put("distantEditRebuilt", true);
                world.getServer().runCommand("setblock " + (targetX + 8) + " 170 " + (targetZ + 8) + " minecraft:stone");
                context.waitFor(c -> uploaded(c, marker, 4)
                        && mesh(c, marker).getSectionDraw(ChunkSectionLayer.SOLID).indexCount() == distantIndices, 600);
                context.runOnClient(c -> { MetalCraftConfig.setNativeTerrainLod(false); MetalCraftConfig.reload(); });
                check(!MetalCraftConfig.nativeTerrainLod(), "disabled preference persists");
                context.waitFor(c -> uploaded(c, marker, 1), 600);
                int nativeIndices = context.computeOnClient(c -> mesh(c, marker).getSectionDraw(ChunkSectionLayer.SOLID).indexCount());
                check(distantIndices < nativeIndices, "real uploaded native mesh has fewer indices with LOD");
                report.put("nativeIndices", nativeIndices);
                report.put("fixtureTriangleReductionPercent", 100.0 * (nativeIndices - distantIndices) / nativeIndices);
                context.takeScreenshot("metalcraft-native-lod-off");
                context.runOnClient(c -> MetalCraftConfig.setNativeTerrainLod(true));
                context.waitFor(c -> uploaded(c, marker, 4), 600);
                context.runOnClient(c -> c.options.fov().set(30));
                context.waitFor(c -> uploaded(c, marker, 2), 600);
                report.put("zoomRestoresFinerDetail", "4 -> 2 at FOV 30");
                context.runOnClient(c -> c.options.fov().set(70));
                context.waitFor(c -> uploaded(c, marker, 4), 600);
                world.getServer().runCommand("tp @a " + (targetX - 24) + " 180 " + cameraZ + " -90 12");
                context.getInput().lookAt(-90, 12);
                context.waitFor(c -> uploaded(c, marker, 1), 600);
                check(context.computeOnClient(c -> mesh(c, marker).getSectionDraw(ChunkSectionLayer.SOLID).indexCount()) == nativeIndices,
                        "approaching restores exact native index count");
                report.put("approachRestoresFullDetail", true);
                context.runOnClient(c -> c.gui.hud.getChat().clearMessages(true));
                context.takeScreenshot("metalcraft-native-lod-near");
                world.getServer().runCommand("tp @a " + cameraX + " 180 " + cameraZ + " -90 2");
                context.getInput().lookAt(-90, 2);
                context.waitFor(c -> uploaded(c, marker, 4), 600);
                context.runOnClient(c -> ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID));
                context.waitFor(c -> uploaded(c, marker, 4), 600);
                // Pack composition and its pipelines settle asynchronously after selection.
                context.waitTicks(100);
                context.runOnClient(c -> c.gui.hud.getChat().clearMessages(true));
                context.takeScreenshot("metalcraft-native-lod-standard");
                context.runOnClient(c -> MetalCraftConfig.setNativeTerrainLod(false));
                context.waitFor(c -> uploaded(c, marker, 1), 600);
                context.waitTicks(20);
                context.takeScreenshot("metalcraft-native-lod-standard-off");
                report.put("packs", "None and Standard native uploads; inspect paired screenshots for visual acceptance");
                report.put("compilerStats", NativeTerrainLod.stats());
                report.put("world", "NORMAL / metalcraft"); report.put("backend", "Metal");
                report.put("renderDistance", 16); report.put("simulationDistance", 16); report.put("chunkBuilder", "Threaded");
            }
            report.put("worldClosed", true); report.put("elapsedSeconds", (System.nanoTime() - started) / 1e9);
            System.out.println("Native terrain LOD live check passed: " + report);
        } finally {
            context.runOnClient(c -> {
                c.options.renderDistance().set(savedDistance); c.options.simulationDistance().set(savedSimulation);
                c.options.fov().set(savedFov); c.options.prioritizeChunkUpdates().set(savedBuilder);
                MetalCraftConfig.setNativeTerrainLod(savedEnabled); MetalCraftConfig.setNativeLodReduction(savedReduction);
                MetalCraftConfig.setNativeQualityDistance(savedNativeDistance);
                MetalCraftConfig.setClearDistanceFog(savedFog);
                ShaderPackRuntime.active().selectPack(savedPack);
            });
            try {
                Path path = Path.of("build/native-terrain-lod.json"); Files.createDirectories(path.getParent());
                Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            } catch (java.io.IOException error) { throw new AssertionError(error); }
        }
    }

    private static SectionMesh mesh(Minecraft client, BlockPos marker) {
        var area = client.levelRenderer.viewArea();
        var section = area == null ? null : area.getRenderSectionAt(marker);
        return section == null ? null : section.getSectionMesh();
    }
    private static boolean uploaded(Minecraft client, BlockPos marker, int size) {
        SectionMesh mesh = mesh(client, marker);
        if (!(mesh instanceof NativeLodState state) || state.metalcraft$cellSize() != size
                || mesh.getSectionDraw(ChunkSectionLayer.SOLID) == null) return false;
        var section = client.levelRenderer.viewArea().getRenderSectionAt(marker);
        return client.levelRenderer.visibleSections().contains(section)
                && client.levelRenderer.sectionRenderDispatcher().getRenderSectionSlice(mesh, ChunkSectionLayer.SOLID) != null;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
