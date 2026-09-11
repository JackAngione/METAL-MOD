package dev.metalcraft.client.test;

import com.google.gson.Gson;
import dev.metalcraft.client.lod.LodCompilerCapture;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/** Verifies mapped capture hooks using emitted standard-world meshes without replacing their draws. */
final class MetalLodCompilerGameTest {
    static void run(ClientGameTestContext context) {
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
            context.waitFor(client -> client.level != null && client.player != null);
            world.getServer().runCommand("gamemode spectator @a");
            world.getServer().runCommand("tp @a -1535.5 173 -127.5 22.5 30");
            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("weather clear");
            context.waitFor(client -> LodCompilerCapture.stats().sections() >= 100, 24000);
            context.waitTicks(200);
            var before = LodCompilerCapture.stats();
            if (before.supportedSections() == 0 || before.originalQuads() <= before.simplifiedQuads())
                throw new AssertionError("No supported emitted terrain simplification: " + before);
            if (before.transferredCandidates() == 0 || before.retainedCandidates() == 0 || before.retainedBytes() > (64L << 20))
                throw new AssertionError("No bounded compiler-to-mesh ownership transfer: " + before);
            long editedSection = net.minecraft.core.SectionPos.asLong(-97, 10, -8);
            LodCompilerCapture.watch(editedSection);
            world.getServer().runCommand("setblock -1540 175 -128 minecraft:stone");
            context.waitFor(client -> LodCompilerCapture.watchedCompiles() > 0, 2400);
            var edited = LodCompilerCapture.watchedTicket();
            if (edited == null) throw new AssertionError("Compiler missed extraction identity");
            world.getServer().runCommand("setblock -1540 175 -128 minecraft:deepslate");
            context.waitFor(client -> !edited.current(), 2400);
            context.waitFor(client -> LodCompilerCapture.watchedTicket() != edited
                    && LodCompilerCapture.watchedTicket() != null && LodCompilerCapture.watchedTicket().current(), 2400);
            context.takeScreenshot("metalcraft-lod-compiler-standard-world");
            context.waitTicks(100);
            var preReload = LodCompilerCapture.watchedTicket();
            if (preReload == null || !preReload.current()) throw new AssertionError("No current source identity before reload");
            LodCompilerCapture.watch(editedSection);
            var reload = context.computeOnClient(client -> client.reloadResourcePacks());
            context.waitFor(client -> reload.isDone(), 2400);
            if (reload.isCompletedExceptionally()) throw new AssertionError("LOD capture resource reload failed");
            if (preReload.current()) throw new AssertionError("Resource reload did not revoke captured identity");
            context.waitFor(client -> LodCompilerCapture.watchedCompiles() > 0, 2400);
            var postReload = LodCompilerCapture.watchedTicket();
            if (postReload == null || postReload.key().resources() <= preReload.key().resources())
                throw new AssertionError("Recompiled material generation did not advance");
            world.getServer().runCommand("tp @a 4096 173 4096 22.5 30");
            context.waitFor(client -> !postReload.current(), 2400);
            System.out.println("LOD emitted terrain capture (compiles, not unique sections): " + LodCompilerCapture.stats());
        }
        System.out.println("LOD capture immediately after world close: " + LodCompilerCapture.stats());
        context.waitFor(client -> LodCompilerCapture.stats().reservedBytes() == 0
                && LodCompilerCapture.stats().retainedBytes() == 0 && LodCompilerCapture.stats().retainedCandidates() == 0, 600);
        if (LodCompilerCapture.stats().trackedSections() != 0) throw new AssertionError("World close retained section identities");
        try {
            Path report = Path.of("build", "lod-compiler-capture.json");
            Files.createDirectories(report.getParent());
            Files.writeString(report, new Gson().toJson(LodCompilerCapture.stats()));
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }
}
