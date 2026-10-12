package dev.metalcraft.client.test;

import static dev.metalcraft.client.test.MetalGameTestSupport.copySaveFiles;
import static dev.metalcraft.client.test.MetalGameTestSupport.press;
import static dev.metalcraft.client.test.MetalGameTestSupport.widgets;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.MetalCraftRenderResolution;
import dev.metalcraft.client.gui.MetalCraftOptionsScreen;
import dev.metalcraft.client.metal.MetalSurfaceProbe;
import dev.metalcraft.client.shader.ShaderPack;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.world.ShadowFrameReuse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

/** Bounded live smoke route; numerical effect contracts are covered by the native GPU fixtures. */
final class MetalPostEffectsGameTest {
    private static final List<String> EFFECTS = List.of("film_grain", "bloom", "depth_of_field");

    static void run(ClientGameTestContext context) {
        String source = System.getProperty("metalcraft.postEffectsTestWorld");
        check(source != null && !source.isBlank(), "Supply an existing disposable NORMAL save via metalcraft.postEffectsTestWorld");
        check(Path.of(source).getNameCount() == 1 && !source.equals(".") && !source.equals(".."), "World property must be a save name");
        var original = context.computeOnClient(c -> new ClientSettings(ShaderPackRuntime.active().selectedPackId(),
            MetalCraftConfig.halfResolution(), MetalCraftConfig.lodEnabled(), c.options.renderDistance().get(),
            c.options.simulationDistance().get(), c.options.bobView().get(), c.options.cloudStatus().get(),
            c.gui.hud.isHidden(), c.getWindow().getScreenWidth(), c.getWindow().getScreenHeight(), c.getWindow().isFullscreen()));
        Map<String, Object> saved = new LinkedHashMap<>();
        String world = copySave(source);
        try {
            context.runOnClient(c -> {
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "default backend uses Metal");
                MetalCraftConfig.setHalfResolution(false);
                MetalCraftConfig.setLodEnabled(false);
                c.options.renderDistance().set(16);
                c.options.simulationDistance().set(16);
                c.options.cloudStatus().set(CloudStatus.OFF);
                c.options.bobView().set(false);
                if (c.getWindow().isFullscreen()) c.getWindow().toggleFullScreen();
                if (!c.gui.hud.isHidden()) c.gui.hud.toggle();
                var runtime = ShaderPackRuntime.active();
                runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
                for (var option : runtime.options()) saved.put(option.id(), runtime.optionValue(option.id()));
                check(saved.keySet().containsAll(EFFECTS), "all three post-effect controls exist");
                for (var option : runtime.options()) if (EFFECTS.contains(option.id())) {
                    check(option.type() == ShaderPack.OptionType.INT && "tonemap".equals(option.category())
                        && option.apply() == ShaderPack.ApplyMode.UNIFORM, "effect is a live integer uniform: " + option.id());
                    check(option.min().orElseThrow() == 0 && option.max().orElseThrow() == 3
                        && option.step().orElseThrow() == 1 && ((Number)option.defaultValue()).intValue() == 0,
                        "Off/Low/Medium/High contract: " + option.id());
                }
                for (var option : runtime.options()) if ("tonemap".equals(option.category()))
                    runtime.setOption(option.id(), option.defaultValue());
                runtime.setOption("debug_view", "off");
                runtime.setOption("wind_enabled", false);
                runtime.setOption("invert", false);
                c.createWorldOpenFlows().openWorld(world, () -> {});
            });
            context.getInput().resizeWindow(3840, 2160);
            context.waitFor(c -> c.level != null && c.player != null && c.getSingleplayerServer() != null, 1200);
            var setup = context.computeOnClient(c -> c.getSingleplayerServer().submit(() -> {
                var server = c.getSingleplayerServer();
                check(server.overworld().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator,
                    "standard terrain required, not a flat world");
                server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server);
                server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 6000");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode spectator @a");
                var player = server.getPlayerList().getPlayers().getFirst();
                int x = player.blockPosition().getX(), z = player.blockPosition().getZ();
                int y = server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 5;
                // Three small high-frequency panels at distinct depths, with natural terrain still visible.
                panel(server.overworld(), x - 3, x - 2, y - 1, y + 4, z + 4);
                panel(server.overworld(), x - 1, x + 1, y - 1, y + 4, z + 8);
                panel(server.overworld(), x + 6, x + 12, y - 2, y + 8, z + 32);
                // Raise the source above the depth panels and move it to their unobstructed side.
                // The wide black wall supplies a static dark halo receiver, independently of water.
                for (int bx = x + 3; bx <= x + 10; bx++) for (int by = y + 3; by <= y + 10; by++)
                    server.overworld().setBlock(new BlockPos(bx, by, z + 12), Blocks.CONCRETE.black().defaultBlockState(), 3);
                BlockPos bloomSource = new BlockPos(x + 6, y + 6, z + 11);
                server.overworld().setBlock(bloomSource, Blocks.SEA_LANTERN.defaultBlockState(), 3);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "tp @a " + (x + 0.5) + " " + y + " " + (z + 0.5) + " 0 0");
                return bloomSource;
            }));
            context.waitFor(c -> setup.isDone());
            BlockPos bloomSource = setup.join();
            context.runOnClient(c -> c.options.broadcastOptions());
            context.getInput().lookAt(0, 0);
            settle(context);
            context.waitFor(c -> c.gameRenderer.mainRenderTarget().getColorTextureView().getWidth(0) == 3840
                && c.gameRenderer.mainRenderTarget().getColorTextureView().getHeight(0) == 2160, 200);
            Object executor = context.computeOnClient(c -> ShaderPackRuntime.active().executor().orElseThrow());
            verifyMenu(context, executor);
            context.runOnClient(c -> c.gui.setScreen(null));
            sample(context, "post-effects-off-4k");
            context.setScreen(GuiSwatch::new);
            Path baseline = capture(context, "off", executor);
            PixelBounds sourceBounds = projectBlock(context, bloomSource);
            for (String effect : EFFECTS) {
                set(context, effect, 1);
                Path low = capture(context, effect + "-low", executor);
                set(context, effect, 2);
                Path medium = capture(context, effect + "-medium", executor);
                set(context, effect, 3);
                Path high = capture(context, effect + "-high", executor);
                if (effect.equals("bloom")) assertBloomHalo(baseline, low, medium, high, sourceBounds);
                else assertWorldChanged(baseline, high, effect);
                if (effect.equals("film_grain")) {
                    Path animated = capture(context, "film-grain-high-next-frame", executor);
                    assertWorldChanged(high, animated, "film grain animation");
                }
                set(context, effect, 0);
            }
            // Refocusing beyond the narrow center panel hits the checkerboard 32 blocks away.
            context.getInput().lookAt(-16, 0);
            settle(context);
            Path farOff = capture(context, "far-focus-off", executor);
            set(context, "depth_of_field", 3);
            Path farFocus = capture(context, "far-focus-high", executor);
            assertWorldChanged(farOff, farFocus, "DOF after center autofocus changes to far panel");
            set(context, "depth_of_field", 0);
            context.getInput().lookAt(0, 0);
            settle(context);
            for (String effect : EFFECTS) set(context, effect, 2);
            capture(context, "combined-medium", executor);
            context.runOnClient(c -> c.gui.setScreen(null));
            sample(context, "post-effects-combined-medium-4k");
            for (String effect : EFFECTS) set(context, effect, 0);
            context.setScreen(GuiSwatch::new);
            Path restored = capture(context, "returned-off", executor);
            assertReturnedOff(baseline, restored);
            System.out.println("Post-effects integration passed: copied NORMAL terrain, Metal, 3840x2160 actual world/scene, 16/16, "
                + "all UI tiers/reset/back, live uniform updates, animated grain, visible bloom source/dark-annulus tier progression, "
                + "Low/Medium/High calibration captures and near-4/focus-8/far-32 DOF planes, GUI exclusion, all-off return. "
                + "Timing samples are same-scene smoke observations, not a benchmark. Inspect captures for halos and focus-plane quality.");
        } finally {
            context.runOnClient(c -> {
                MetalFrameMetrics.discardCapture();
                c.gui.setScreen(null);
                if (ShaderPackRuntime.BUILTIN_ID.equals(ShaderPackRuntime.active().selectedPackId()))
                    saved.forEach(ShaderPackRuntime.active()::setOption);
                ShaderPackRuntime.active().selectPack(original.pack());
                MetalCraftConfig.setHalfResolution(original.half());
                MetalCraftConfig.setLodEnabled(original.lod());
                c.options.renderDistance().set(original.render());
                c.options.simulationDistance().set(original.simulation());
                c.options.bobView().set(original.bob());
                c.options.cloudStatus().set(original.clouds());
                if (c.gui.hud.isHidden() != original.hudHidden()) c.gui.hud.toggle();
                if (c.level != null) c.level.disconnect(Component.literal("Post-effects test complete"));
                c.disconnect(new TitleScreen(), false);
            });
            context.waitFor(c -> !net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl.isServerRunning && c.level == null, 1200);
            context.getInput().resizeWindow(original.width(), original.height());
            context.runOnClient(c -> {
                if (c.getWindow().isFullscreen() != original.fullscreen()) c.getWindow().toggleFullScreen();
                MetalCraftRenderResolution.apply(c);
            });
            context.setScreen(TitleScreen::new);
        }
    }

    private static void panel(net.minecraft.server.level.ServerLevel level, int minX, int maxX, int minY, int maxY, int z) {
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++)
            level.setBlock(new BlockPos(x, y, z), Blocks.CONCRETE.pick((x + y) % 2 == 0 ? DyeColor.WHITE : DyeColor.BLACK).defaultBlockState(), 3);
    }

    /** Local chunk coverage plus a quiet publication interval prevents capture during scene growth. */
    private static void settle(ClientGameTestContext context) {
        long revision = -1;
        int quiet = 0;
        for (int tick = 0; tick < 900 && quiet < 30; tick++) {
            context.runOnClient(c -> c.getSingleplayerServer().execute(() -> c.getSingleplayerServer().getPlayerList()
                .getPlayers().forEach(p -> p.connection.chunkSender.onChunkBatchReceivedByClient(PlayerChunkSender.MAX_CHUNKS_PER_TICK))));
            context.waitTick();
            long now = ShadowFrameReuse.meshRevision();
            boolean ready = context.computeOnClient(c -> {
                int cx = c.player.blockPosition().getX() >> 4, cz = c.player.blockPosition().getZ() >> 4;
                for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++)
                    if (dx * dx + dz * dz <= 64 && !c.level.hasChunk(cx + dx, cz + dz)) return false;
                return c.levelRenderer.sectionRenderDispatcher().isQueueEmpty()
                    && !c.level.getChunkSource().getLightEngine().hasLightWork()
                    && c.levelRenderer.visibleSections().stream().filter(s -> s.getSectionMesh()
                        .getSectionDraw(net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID) != null).count() >= 32;
            });
            quiet = ready && now == revision ? quiet + 1 : 0;
            revision = now;
        }
        check(quiet >= 30, "camera region and mesh publication must settle before captures");
        System.out.println("Post-effects readiness: local 8-chunk radius received, light/compile queues idle, mesh revision quiet for 30 ticks");
    }

    private static void verifyMenu(ClientGameTestContext context, Object executor) {
        context.setScreen(() -> new MetalCraftOptionsScreen(null));
        press(context, Component.translatable("metalcraft.options.shaders.title").getString());
        press(context, "Color Grading");
        context.runOnClient(c -> {
            check("Color Grading".equals(c.gui.screen().getTitle().getString()), "Color Grading submenu opened");
            for (String effect : EFFECTS) {
                String prefix = Component.translatable("metalcraft.grading." + effect, "").getString();
                var slider = (AbstractSliderButton)widgets(c.gui.screen()).stream()
                    .filter(w -> w instanceof AbstractSliderButton && w.getMessage().getString().startsWith(prefix))
                    .findFirst().orElseThrow(() -> new AssertionError("Effect slider exists: " + effect));
                for (int tier = 0; tier <= 3; tier++) {
                    slider.onClick(new MouseButtonEvent(slider.getX() + 4 + (slider.getWidth() - 8) * tier / 3.0,
                        slider.getY() + 10, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)), false);
                    check(((Number)ShaderPackRuntime.active().optionValue(effect)).intValue() == tier, "slider applies tier " + tier + ": " + effect);
                    check(slider.getMessage().getString().contains(Component.translatable("metalcraft.grading.strength." + tier).getString()),
                        "tier label updates: " + effect);
                    check(ShaderPackRuntime.active().executor().orElseThrow() == executor, "slider preserves executor");
                }
            }
        });
        context.takeScreenshot("metalcraft-post-effects-menu-high");
        press(context, Component.translatable("metalcraft.grading.reset").getString());
        context.runOnClient(c -> {
            for (var option : ShaderPackRuntime.active().options()) if ("tonemap".equals(option.category())) {
                Object value = ShaderPackRuntime.active().optionValue(option.id()), def = option.defaultValue();
                check(value instanceof Number n && def instanceof Number d ? n.doubleValue() == d.doubleValue() : value.equals(def),
                    "reset restores grading/effect: " + option.id());
            }
            check(ShaderPackRuntime.active().executor().orElseThrow() == executor, "reset preserves executor");
        });
        press(context, Component.translatable("gui.back").getString());
        context.runOnClient(c -> check(c.gui.screen().getTitle().getString()
            .equals(Component.translatable("metalcraft.options.shaders.title").getString()), "Back returns to Shader Packs"));
    }

    private static void set(ClientGameTestContext context, String id, int tier) {
        context.runOnClient(c -> ShaderPackRuntime.active().setOption(id, tier));
    }
    private static Path capture(ClientGameTestContext context, String name, Object executor) {
        context.waitTicks(4);
        context.runOnClient(c -> {
            var runtime = ShaderPackRuntime.active();
            check(runtime.isActive() && runtime.lastError().isEmpty(), "healthy shader runtime: " + runtime.lastError());
            check(runtime.executor().orElseThrow() == executor, "uniform update preserves executor: " + name);
            dimensions(name);
            c.gui.hud.getChat().clearMessages(true);
        });
        return context.takeScreenshot("metalcraft-post-effects-" + name);
    }
    private static void dimensions(String name) {
        var c = net.minecraft.client.Minecraft.getInstance();
        var target = c.gameRenderer.mainRenderTarget().getColorTextureView();
        var runtime = ShaderPackRuntime.active();
        int[] drawable = MetalSurfaceProbe.drawableSize();
        check(target.getWidth(0) == 3840 && target.getHeight(0) == 2160, "actual world target is 4K");
        check(runtime.frameWidth() == 3840 && runtime.frameHeight() == 2160, "actual shader scene is 4K");
        check(!MetalCraftConfig.halfResolution() && c.options.renderDistance().get() == 16
            && c.options.simulationDistance().get() == 16, "full-resolution 16/16 shader testing");
        check(drawable[0] > 0 && drawable[1] > 0, "drawable dimensions available");
        System.out.println("Post-effects " + name + ": worldTarget=3840x2160, shaderScene=" + runtime.frameWidth() + "x" + runtime.frameHeight()
            + ", presentationFramebuffer=" + c.getWindow().getWidth() + "x" + c.getWindow().getHeight()
            + ", windowPoints=" + c.getWindow().getScreenWidth() + "x" + c.getWindow().getScreenHeight()
            + ", drawable=" + drawable[0] + "x" + drawable[1]);
    }
    private static void sample(ClientGameTestContext context, String name) {
        context.runOnClient(c -> { dimensions(name); MetalFrameMetrics.beginCapture(8); });
        context.waitTicks(30);
        var phase = context.computeOnClient(c -> MetalFrameMetrics.endCapture(name));
        check(phase.frames() > 0, "live timing sample exists: " + name);
        System.out.println("Post-effects timing smoke: " + phase.toLogLine() + " gpuMedianMs=" + phase.gpuFrame().p50Ms());
    }
    /** Uses the real raster projection; the source must be visible before its surrounding glow is measured. */
    private static PixelBounds projectBlock(ClientGameTestContext context, BlockPos block) {
        return context.computeOnClient(c -> {
            var camera = c.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            var matrix = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix)
                .translate((float)-camera.pos.x, (float)-camera.pos.y, (float)-camera.pos.z);
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (int dz = 0; dz <= 1; dz++) for (int dy = 0; dy <= 1; dy++) for (int dx = 0; dx <= 1; dx++) {
                var clip = matrix.transform(new Vector4f(block.getX() + dx, block.getY() + dy, block.getZ() + dz, 1));
                check(clip.w > 0, "bloom fixture is in front of the camera");
                float px = (clip.x / clip.w * 0.5F + 0.5F) * 3840 - 0.5F;
                float py = (0.5F - clip.y / clip.w * 0.5F) * 2160 - 0.5F;
                minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                minY = Math.min(minY, py); maxY = Math.max(maxY, py);
            }
            var result = new PixelBounds((int)Math.floor(minX), (int)Math.floor(minY),
                (int)Math.ceil(maxX), (int)Math.ceil(maxY));
            check(result.left() >= 106 && result.top() >= 106 && result.right() < 3840 - 106
                && result.bottom() < 2160 - 106, "source and complete halo neighborhood are on screen: " + result);
            System.out.println("Post-effects bloom source world=" + block + ", projectedPixelBounds=" + result);
            return result;
        });
    }

    /** Static black-backed annulus excludes the emitter itself and all moving terrain/water pixels. */
    private static void assertBloomHalo(Path off, Path low, Path medium, Path high, PixelBounds bounds) {
        // Retain the GUI-boundary check, without using the whole-frame delta as bloom evidence.
        compare(off, high, false);
        try (var oi = Files.newInputStream(off); var li = Files.newInputStream(low);
             var mi = Files.newInputStream(medium); var hi = Files.newInputStream(high);
             var o = NativeImage.read(oi); var l = NativeImage.read(li);
             var m = NativeImage.read(mi); var h = NativeImage.read(hi)) {
            int visibleBright = 0;
            double brightest = 0;
            for (int y = bounds.top(); y <= bounds.bottom(); y += 2)
                for (int x = bounds.left(); x <= bounds.right(); x += 2) {
                    double brightness = luminance(o.getPixel(x, y));
                    brightest = Math.max(brightest, brightness);
                    if (brightness >= 95) visibleBright++;
                }
            // Standard shades this conventional source around 130 encoded, so
            // verify contrast against its black backing rather than HDR-white.
            check(visibleBright >= 100 && brightest >= 110,
                "conventional sea lantern must be visibly bright in its projected bounds; brightPixels=" + visibleBright
                    + ", maxLuminance=" + brightest);
            double[] energy = new double[3], nearGain = new double[3];
            int[] changed = new int[3];
            int samples = 0, nearSamples = 0;
            NativeImage[] tiers = { l, m, h };
            for (int y = bounds.top() - 104; y <= bounds.bottom() + 104; y += 2)
                for (int x = bounds.left() - 104; x <= bounds.right() + 104; x += 2) {
                    int dx = Math.max(bounds.left() - x, x - bounds.right());
                    int dy = Math.max(bounds.top() - y, y - bounds.bottom());
                    int distance = Math.max(dx, dy);
                    if (distance < 2) continue;
                    double base = luminance(o.getPixel(x, y));
                    // This mask admits only the static black wall outside the source silhouette.
                    if (base >= 95) continue;
                    samples++;
                    boolean near = distance <= 20;
                    if (near) nearSamples++;
                    for (int tier = 0; tier < tiers.length; tier++) {
                        double gain = luminance(tiers[tier].getPixel(x, y)) - base;
                        energy[tier] += gain;
                        if (near) nearGain[tier] += gain;
                        if (gain >= 1) changed[tier]++;
                    }
                }
            check(samples >= 2000 && nearSamples >= 500, "visible dark halo receiver has sufficient pixels");
            for (int tier = 0; tier < tiers.length; tier++) {
                nearGain[tier] /= nearSamples;
                System.out.println("Post-effects isolated bloom halo tier=" + (tier + 1) + ", sourceBrightPixels=" + visibleBright
                    + ", darkAnnulusSamples=" + samples + ", integratedLuminanceGain=" + energy[tier]
                    + ", nearAnnulusMeanGain=" + nearGain[tier] + ", pixelsWithGainAtLeastOne=" + changed[tier]);
                check(nearGain[tier] >= 1 && changed[tier] >= 200,
                    "bloom tier " + (tier + 1) + " makes a visible halo around the conventional source");
            }
            // Wider kernels redistribute energy, so monotonicity is assessed over the whole halo.
            check(energy[1] > energy[0] * 1.1 && energy[2] > energy[1] * 1.1,
                "bloom Low/Medium/High increase integrated halo strength");
        } catch (IOException error) { throw new AssertionError(error); }
    }
    private static double luminance(int pixel) {
        return 0.2126 * net.minecraft.util.ARGB.red(pixel) + 0.7152 * net.minecraft.util.ARGB.green(pixel)
            + 0.0722 * net.minecraft.util.ARGB.blue(pixel);
    }
    private record PixelBounds(int left, int top, int right, int bottom) { }

    private static void assertWorldChanged(Path before, Path after, String effect) {
        double difference = compare(before, after, false);
        check(difference > 0.015, "world pixels change for " + effect + ": meanChannelDelta=" + difference);
        System.out.println("Post-effects pixel smoke: " + effect + " meanChannelDelta=" + difference);
    }
    private static void assertReturnedOff(Path before, Path after) {
        double difference = compare(before, after, true);
        check(difference < 0.5, "all-off returns the static foreground panel: meanChannelDelta=" + difference);
        System.out.println("Post-effects all-off return meanChannelDelta=" + difference);
    }
    private static double compare(Path before, Path after, boolean centerPanel) {
        try (var ai = Files.newInputStream(before); var bi = Files.newInputStream(after);
             var a = NativeImage.read(ai); var b = NativeImage.read(bi)) {
            check(a.getWidth() == 3840 && a.getHeight() == 2160 && a.getWidth() == b.getWidth()
                && a.getHeight() == b.getHeight(), "capture dimensions match actual world target");
            int marker = a.getPixel(a.getWidth() * 8 / 100, a.getHeight() * 8 / 100);
            check(Math.abs(net.minecraft.util.ARGB.red(marker) - 127) <= 1
                && Math.abs(net.minecraft.util.ARGB.green(marker) - 63) <= 1
                && Math.abs(net.minecraft.util.ARGB.blue(marker) - 191) <= 1, "opaque GUI exclusion marker is present");
            for (int y = a.getHeight() * 7 / 100; y < a.getHeight() / 10; y += 4)
                for (int x = a.getWidth() * 7 / 100; x < a.getWidth() / 10; x += 4)
                    check(a.getPixel(x, y) == b.getPixel(x, y), "GUI marker excluded from post effects");
            double total = 0;
            int samples = 0;
            int left = centerPanel ? 46 : 20, right = centerPanel ? 54 : 80;
            int top = centerPanel ? 40 : 20, bottom = centerPanel ? 60 : 80;
            for (int y = a.getHeight() * top / 100; y < a.getHeight() * bottom / 100; y += 4)
                for (int x = a.getWidth() * left / 100; x < a.getWidth() * right / 100; x += 4) {
                    int ap = a.getPixel(x, y), bp = b.getPixel(x, y);
                    total += Math.abs(net.minecraft.util.ARGB.red(ap) - net.minecraft.util.ARGB.red(bp))
                        + Math.abs(net.minecraft.util.ARGB.green(ap) - net.minecraft.util.ARGB.green(bp))
                        + Math.abs(net.minecraft.util.ARGB.blue(ap) - net.minecraft.util.ARGB.blue(bp));
                    samples++;
                }
            return total / (samples * 3.0);
        } catch (IOException error) { throw new AssertionError(error); }
    }
    private static String copySave(String sourceName) {
        Path saves = Path.of("saves").toAbsolutePath().normalize();
        Path source = saves.resolve(sourceName).normalize();
        check(source.getParent().equals(saves) && Files.isRegularFile(source.resolve("level.dat")), "existing standard save is available: " + source);
        Path target = saves.resolve("MetalCraft Post Effects " + UUID.randomUUID().toString().substring(0, 8));
        try {
            copySaveFiles(source, target);
        } catch (IOException error) { throw new AssertionError("Could not copy disposable test world", error); }
        System.out.println("Post-effects disposable save copy: " + target + "; source retained: " + source);
        return target.getFileName().toString();
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private record ClientSettings(String pack, boolean half, boolean lod, int render, int simulation, boolean bob,
        CloudStatus clouds, boolean hudHidden, int width, int height, boolean fullscreen) { }
    private static final class GuiSwatch extends Screen {
        GuiSwatch() { super(Component.literal("Post-effects GUI exclusion probe")); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(width / 20, height / 20, width / 8, height / 8, 0xFF7F3FBF);
        }
    }
}
