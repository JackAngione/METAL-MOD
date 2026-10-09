package dev.metalcraft.client.test;

import static dev.metalcraft.client.test.MetalGameTestSupport.command;
import static dev.metalcraft.client.test.MetalGameTestSupport.copySaveFiles;
import static dev.metalcraft.client.test.MetalGameTestSupport.server;

import com.mojang.blaze3d.platform.NativeImage;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.world.WorldLocalLighting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.FlatLevelSource;

/** Short placed-light/fire regression using a copy of an existing NORMAL save. */
final class MetalLocalLightingGameTest {
    static void run(ClientGameTestContext context) {
        String save = copySave();
        String pack = context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        boolean bob = context.computeOnClient(c -> c.options.bobView().get());
        Object debug = context.computeOnClient(c -> ShaderPackRuntime.active().optionValue("debug_view"));
        Object enabled = context.computeOnClient(c -> ShaderPackRuntime.active().optionValue("local_lights"));
        try {
            context.runOnClient(c -> c.createWorldOpenFlows().openWorld(save, () -> { }));
            context.waitFor(c -> c.level != null && c.player != null && c.getSingleplayerServer() != null, 1200);
            server(context, s -> {
                if (s.overworld().getChunkSource().getGenerator() instanceof FlatLevelSource) throw new AssertionError("Local light test requires a standard world");
                s.getGameRules().set(GameRules.ADVANCE_TIME, false, s);
                s.getGameRules().set(GameRules.ADVANCE_WEATHER, false, s);
                s.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, s);
                s.getGameRules().set(GameRules.SPAWN_MOBS, false, s);
                s.getGameRules().set(GameRules.BLOCK_DROPS, false, s);
                s.overworld().getEntities(EntityTypes.ITEM, e -> e.getY() > 180 && e.getY() < 225)
                    .forEach(e -> e.discard());
            });
            server(context, s -> s.overworld().getEntities(EntityTypes.PIG, e -> e.entityTags().contains("metalcraft_light_fixture"))
                .forEach(e -> e.discard()));
            command(context, "gamemode spectator @a");
            command(context, "tp @a 9 210 13 145 35");
            command(context, "time set 18000");
            command(context, "weather clear");
            context.runOnClient(c -> {
                c.options.renderDistance().set(16);
                c.options.simulationDistance().set(16);
                c.options.bobView().set(false);
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID);
                ShaderPackRuntime.active().setOption("local_lights", true);
                ShaderPackRuntime.active().setOption("debug_view", "off");
            });
            context.getInput().lookAt(145, 35);
            context.waitFor(c -> c.level.hasChunk(-2, -2) && c.level.hasChunk(1, -2)
                && c.level.hasChunk(-2, 1) && c.level.hasChunk(1, 1), 600);
            // Existing shader-fixture saves can contain lights underneath the platform.
            // Clear the complete 15-block influence halo before comparing visibility.
            command(context, "fill -20 185 -20 20 200 20 air");
            command(context, "fill -20 201 -20 20 216 20 air");
            command(context, "fill -20 200 -20 20 200 20 white_concrete");
            command(context, "fill 0 201 -1 0 205 1 stone");
            if (Boolean.getBoolean("metalcraft.shadowMotionTest")) {
                ShadowMotionGameTest.run(context);
                return;
            }
            if (Boolean.getBoolean("metalcraft.localLightingBenchmark")) {
                LocalLightingBenchmark.run(context);
                return;
            }
            context.runOnClient(c -> ShaderPackRuntime.active().setOption("debug_view", "local_visibility"));
            Path unshadowed = capture(context, "empty-visibility");
            command(context, "setblock -5 201 0 torch");
            BlockPos torch = new BlockPos(-5, 201, 0);
            await(context, c -> lighting().hasPlacedLight(torch, 14));
            context.runOnClient(c -> ShaderPackRuntime.active().setOption("debug_view", "off"));
            Path torchLit = capture(context, "torch");
            context.runOnClient(c -> ShaderPackRuntime.active().setOption("debug_view", "local_visibility"));
            Path shadow = capture(context, "torch-visibility");
            assertShadowPixels(unshadowed, shadow);
            context.runOnClient(c -> ShaderPackRuntime.active().setOption("debug_view", "off"));

            // Exercise real state-dependent emitters across block families, not a torch whitelist.
            List<BlockState> emitters = List.of(Blocks.SOUL_TORCH.defaultBlockState(), Blocks.LANTERN.defaultBlockState(),
                Blocks.GLOWSTONE.defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState(), Blocks.SHROOMLIGHT.defaultBlockState(),
                Blocks.MAGMA_BLOCK.defaultBlockState(), Blocks.END_ROD.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
                Blocks.FIRE.defaultBlockState(), Blocks.SOUL_FIRE.defaultBlockState(),
                Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true),
                Blocks.COPPER_BULB.weathering().unaffected().defaultBlockState().setValue(BlockStateProperties.LIT, true));
            server(context, s -> {
                for (int i = 0; i < emitters.size(); i++) {
                    BlockPos pos = new BlockPos(-12 + i * 2, 202, -10);
                    s.overworld().setBlock(pos.below(), (i == 9 ? Blocks.SOUL_SOIL : i == 10 ? Blocks.REDSTONE_BLOCK : Blocks.NETHERRACK).defaultBlockState(), 2);
                    s.overworld().setBlock(pos, emitters.get(i), 2);
                }
            });
            await(context, c -> {
                for (int i = 0; i < emitters.size(); i++) {
                    var pos = new BlockPos(-12 + i * 2, 202, -10);
                    int emission = c.level.getBlockState(pos).getLightEmission();
                    if (emission <= 0 || !lighting().hasPlacedLight(pos, emission)) return false;
                }
                return true;
            });
            command(context, "setblock 8 201 -10 netherrack");
            await(context, c -> c.level.getBlockState(new BlockPos(8, 202, -10)).getLightEmission() == 0
                && !lighting().hasPlacedLight(new BlockPos(8, 202, -10), 15));
            command(context, "fill -14 201 -12 14 205 -8 air");
            command(context, "setblock -5 201 0 air");
            await(context, c -> !lighting().hasPlacedLight(torch, 14));

            // Lava can flow outside the source row while its other fixtures are checked.
            command(context, "fill -20 201 -20 20 216 20 air");
            command(context, "fill 0 201 -1 0 205 1 stone");
            // Isolate moving fire light from the lightmap: Minecraft gives this entity no block light.
            server(context, s -> {
                var pig = EntityTypes.PIG.create(s.overworld(), EntitySpawnReason.COMMAND);
                pig.setPos(-5, 201, 0);
                pig.setNoAi(true);
                pig.setInvulnerable(true);
                pig.addTag("metalcraft_light_fixture");
                s.overworld().addFreshEntity(pig);
            });
            await(context, c -> lighting().dynamicLightCount() == 0
                && c.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, torch) == 0);
            // Let the existing chunk-light propagation/remesh finish before the image comparison.
            context.waitTicks(25);
            context.runOnClient(c -> System.out.println("Fire fixture baseline: dynamic=" + lighting().dynamicLightCount()
                + ", blockLight=" + c.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, torch)));
            Path cold = capture(context, "entity-cold");
            server(context, s -> s.overworld().getEntities(EntityTypes.PIG, e -> e.entityTags().contains("metalcraft_light_fixture"))
                .forEach(e -> e.igniteForTicks(2000)));
            await(context, c -> lighting().dynamicLightCount() > 0);
            Path burning = capture(context, "entity-burning");
            assertBrighter(cold, burning);
            command(context, "tp @e[tag=metalcraft_light_fixture] 5 201 0");
            capture(context, "entity-moved");
            server(context, s -> s.overworld().getEntities(EntityTypes.PIG, e -> e.entityTags().contains("metalcraft_light_fixture"))
                .forEach(e -> e.clearFire()));
            await(context, c -> lighting().dynamicLightCount() == 0);
            Path extinguished = capture(context, "entity-extinguished");
            assertBrighter(extinguished, burning);
            context.runOnClient(c -> {
                if (ShaderPackRuntime.active().worldShadows().currentFrame().cascadeCount() == 0)
                    throw new AssertionError("Moonlight shadows are inactive");
                ShaderPackRuntime.active().setOption("local_lights", false);
                if (ShaderPackRuntime.active().worldGeometry().localLighting() != null) throw new AssertionError("Local-light resources survived disable");
                ShaderPackRuntime.active().setOption("local_lights", true);
            });
            capture(context, "restored");
            if (Boolean.getBoolean("metalcraft.verifyWaterBatch")) verifyWaterBatch(context);
            System.out.println("Local lighting live passed: existing NORMAL save copy, Metal 16/16, 12 emitter families, visible torch shadows, burning/moving/extinguished entity, removed source, moon shadows and option reload; torch=" + torchLit);
        } finally {
            context.runOnClient(c -> {
                c.options.bobView().set(bob);
                if (ShaderPackRuntime.BUILTIN_ID.equals(ShaderPackRuntime.active().selectedPackId())) {
                    if (debug != null) ShaderPackRuntime.active().setOption("debug_view", debug);
                    if (enabled != null) ShaderPackRuntime.active().setOption("local_lights", enabled);
                }
                ShaderPackRuntime.active().selectPack(pack);
                if (c.level != null) c.level.disconnect(net.minecraft.network.chat.Component.literal("Lighting test complete"));
                c.disconnect(new TitleScreen(), false);
            });
            context.waitFor(c -> !net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl.isServerRunning && c.level == null, 1200);
            context.waitTicks(2);
            context.setScreen(TitleScreen::new);
        }
    }

    private static WorldLocalLighting lighting() { return ShaderPackRuntime.active().worldGeometry().localLighting(); }

    /** Exercise real sorted water draws with distinct base vertices and arena offsets across sections. */
    private static void verifyWaterBatch(ClientGameTestContext context) {
        var original = dev.metalcraft.client.shader.water.WaterRoutingDebug.mode();
        try {
            command(context, "fill -12 199 -6 12 199 6 stone");
            command(context, "fill -11 200 -5 -3 200 5 water");
            command(context, "fill 3 200 -5 11 200 5 water");
            context.waitTicks(30);
            context.runOnClient(c -> dev.metalcraft.client.shader.water.WaterRoutingDebug.setEnabled(true));
            Path screenshot = capture(context, "water-batch-identity");
            try (var image = NativeImage.read(Files.newInputStream(screenshot))) {
                int left = 0, right = 0;
                for (int y = image.getHeight() / 5; y < image.getHeight() * 4 / 5; y++)
                    for (int x = image.getWidth() / 5; x < image.getWidth() * 4 / 5; x++) {
                        int pixel = image.getPixel(x, y);
                        int r = net.minecraft.util.ARGB.red(pixel), g = net.minecraft.util.ARGB.green(pixel), b = net.minecraft.util.ARGB.blue(pixel);
                        if (r > g + 45 && b > g + 45) {
                            if (x < image.getWidth() / 2) left++; else right++;
                        }
                    }
                if (left < 100 || right < 100) throw new AssertionError("Batched water lost section metadata: " + left + "/" + right);
                System.out.println("Water batch live: distinct section draws retained water metadata, left=" + left + ", right=" + right);
            }
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        finally { context.runOnClient(c -> dev.metalcraft.client.shader.water.WaterRoutingDebug.setMode(original)); }
        capture(context, "water-batch-restored");
    }
    private static void await(ClientGameTestContext context, java.util.function.Predicate<Minecraft> predicate) {
        context.waitFor(c -> {
            if (ShaderPackRuntime.active().lastError().isPresent()) throw new AssertionError(ShaderPackRuntime.active().lastError());
            return lighting() != null && predicate.test(c);
        }, 400);
    }
    private static Path capture(ClientGameTestContext context, String name) {
        context.waitTicks(8);
        context.runOnClient(c -> c.gui.hud.getChat().clearMessages(true));
        return context.takeScreenshot("metalcraft-local-" + name);
    }
    private static String copySave() {
        String sourceName = System.getProperty("metalcraft.localLightingSave", "New World (1)");
        Path source = Path.of("saves", sourceName), target = Path.of("saves", "MetalCraft Local Light Test");
        if (!Files.isRegularFile(target.resolve("level.dat"))) {
            if (!Files.isRegularFile(source.resolve("level.dat"))) throw new AssertionError("Set metalcraft.localLightingSave to an existing standard save");
            try {
                copySaveFiles(source, target);
            } catch (java.io.IOException error) { throw new AssertionError(error); }
        }
        return target.getFileName().toString();
    }
    private static void assertShadowPixels(Path before, Path path) {
        try (var baseline = NativeImage.read(Files.newInputStream(before)); var image = NativeImage.read(Files.newInputStream(path))) {
            int shaded = 0;
            for (int y = image.getHeight() / 4; y < image.getHeight() * 4 / 5; y++)
                for (int x = image.getWidth() / 4; x < image.getWidth() * 4 / 5; x++) {
                    int c = image.getPixel(x, y), r = c & 255, g = c >>> 8 & 255, b = c >>> 16 & 255;
                    int old = baseline.getPixel(x, y);
                    if ((old & 255) > 240 && (old >>> 8 & 255) > 240 && (old >>> 16 & 255) > 240
                        && r < 100 && Math.abs(r - g) < 3 && Math.abs(g - b) < 3) shaded++;
                }
            if (shaded < 50) throw new AssertionError("No local shadow in visibility output: " + shaded);
            System.out.println("Local shadow dark pixels: " + shaded);
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }
    private static void assertBrighter(Path before, Path after) {
        try (var a = NativeImage.read(Files.newInputStream(before)); var b = NativeImage.read(Files.newInputStream(after))) {
            int lit = 0;
            for (int y = a.getHeight() / 4; y < a.getHeight() * 4 / 5; y++)
                for (int x = a.getWidth() / 4; x < a.getWidth() * 4 / 5; x++) {
                    int av = a.getPixel(x, y), bv = b.getPixel(x, y);
                    if (net.minecraft.util.ARGB.red(bv) - net.minecraft.util.ARGB.red(av) > 20
                        && net.minecraft.util.ARGB.green(bv) - net.minecraft.util.ARGB.green(av) > 8) lit++;
                }
            if (lit < 100) throw new AssertionError("Burning entity did not illuminate its surroundings: " + lit);
            System.out.println("Fire illuminated pixels: " + lit);
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }
}
