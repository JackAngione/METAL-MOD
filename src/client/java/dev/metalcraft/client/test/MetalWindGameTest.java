package dev.metalcraft.client.test;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.metal.MetalSurfaceProbe;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.wind.WindMeshSource;
import dev.metalcraft.client.shader.wind.WindVertexMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Brief animation/toggle/reload check in an existing standard test save, at Metal 16/16. */
final class MetalWindGameTest {
    static void run(ClientGameTestContext context) {
        String save = System.getProperty("metalcraft.windTestWorld", "MetalCraft Local Light Test");
        String pack = context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        boolean hud = context.computeOnClient(c -> c.gui.hud.isHidden());
        boolean bob = context.computeOnClient(c -> c.options.bobView().get());
        CloudStatus clouds = context.computeOnClient(c -> c.options.cloudStatus().get());
        context.runOnClient(c -> ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID));
        Object enabled = context.computeOnClient(c -> ShaderPackRuntime.active().optionValue("wind_enabled"));
        Object strength = context.computeOnClient(c -> ShaderPackRuntime.active().optionValue("wind_strength"));
        Object debug = context.computeOnClient(c -> ShaderPackRuntime.active().optionValue("debug_view"));
        boolean cutoutLeaves = context.computeOnClient(c -> c.options.cutoutLeaves().get());
        try {
            context.runOnClient(c -> {
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "Default selected Metal");
                check(c.getLevelSource().levelExists(save), "existing test save available");
                MetalCraftConfig.setLodEnabled(false);
                MetalCraftConfig.setHalfResolution(false);
                c.options.renderDistance().set(16); c.options.simulationDistance().set(16);
                c.options.cloudStatus().set(CloudStatus.OFF); c.options.bobView().set(false); if (!c.gui.hud.isHidden()) c.gui.hud.toggle();
                ShaderPackRuntime.active().setOption("wind_enabled", true);
                ShaderPackRuntime.active().setOption("wind_strength", 1.0);
                c.createWorldOpenFlows().openWorld(save, () -> { });
            });
            context.getInput().resizeWindow(3840, 2160);
            context.waitFor(c -> c.level != null && c.player != null && c.getSingleplayerServer() != null, 1200);
            server(context, s -> {
                check(s.overworld().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator, "standard world required");
                s.getGameRules().set(GameRules.ADVANCE_TIME, false, s);
                s.getGameRules().set(GameRules.ADVANCE_WEATHER, false, s);
                s.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, s);
                s.getGameRules().set(GameRules.SPAWN_MOBS, false, s);
                s.getGameRules().set(GameRules.BLOCK_DROPS, false, s);
            });
            command(context, "gamemode spectator @a");
            command(context, "tp @a 8 204 12 147 15");
            command(context, "time set 3000"); command(context, "weather clear");
            command(context, "fill -10 201 -8 10 212 8 air");
            command(context, "fill -10 200 -8 10 200 8 grass_block");
            command(context, "fill -4 201 0 -4 204 0 oak_log");
            command(context, "fill -6 204 -2 -2 205 2 oak_leaves[persistent=true]");
            command(context, "fill -5 206 -1 -3 206 1 oak_leaves[persistent=true]");
            for (int x=0;x<=4;x+=2) for (int z=-2;z<=4;z+=2) {
                command(context, "setblock "+x+" 201 "+z+" tall_grass[half=lower]");
                command(context, "setblock "+x+" 202 "+z+" tall_grass[half=upper]");
            }
            command(context, "fill -2 201 4 0 201 4 short_grass");
            command(context, "fill 5 201 -2 5 202 2 oak_fence");
            context.getInput().lookAt(147,15);
            context.waitFor(c -> c.levelRenderer.visibleSections().stream().anyMatch(section ->
                section.getSectionMesh() instanceof WindMeshSource source && source.metalcraft$windMesh(ChunkSectionLayer.CUTOUT) != null), 600);
            context.runOnClient(c -> {
                check(WindVertexMetadata.kind(Blocks.OAK_LEAVES.defaultBlockState()) == WindVertexMetadata.LEAVES, "leaf tag recognized");
                check(WindVertexMetadata.kind(Blocks.OAK_FENCE.defaultBlockState()) == WindVertexMetadata.NONE, "fences excluded");
                check(WindVertexMetadata.kind(Blocks.TALL_GRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER)) == WindVertexMetadata.UPPER_GRASS, "upper half recognized");
            });
            context.waitTicks(20);
            Path onA = capture(context,"on-a");
            context.runOnClient(c -> MetalFrameMetrics.beginCapture(4));
            context.waitTicks(20);
            var timing = context.computeOnClient(c -> MetalFrameMetrics.endCapture("wind-4k"));
            System.out.println("Wind 4K GPU frame timing smoke: medianMs=" + timing.gpuFrame().p50Ms());
            Path onB = capture(context,"on-b");
            int moving = differences(onA,onB);
            check(moving>100,"visible foliage animation: "+moving);
            context.runOnClient(c -> ShaderPackRuntime.active().setOption("wind_enabled",false));
            context.waitTicks(8);
            Path offA = capture(context,"off-a"); context.waitTicks(20); Path offB = capture(context,"off-b");
            int still = differences(offA,offB);
            check(still < moving/3,"disabled foliage stays still: moving="+moving+", off="+still);
            command(context, "time set 12400");
            context.runOnClient(c -> {
                c.options.cutoutLeaves().set(false);
                ShaderPackRuntime.active().setOption("debug_view", "visibility");
                ShaderPackRuntime.active().setOption("wind_enabled", true);
            });
            context.waitTicks(20);
            checkSmoothLeafFace(capture(context,"shadow-on"));
            context.getInput().lookAt(148, 15);
            context.waitTicks(3);
            checkSmoothLeafFace(capture(context,"shadow-on-rotated"));
            context.runOnClient(c -> ShaderPackRuntime.active().setOption("wind_enabled",false));
            context.waitTicks(5);
            capture(context,"shadow-off-rotated");
            context.runOnClient(c -> {
                ShaderPackRuntime.active().setOption("debug_view", debug);
                c.options.cutoutLeaves().set(cutoutLeaves);
            });
            context.runOnClient(c -> ShaderPackRuntime.active().setOption("wind_enabled",true));
            var reload = context.computeOnClient(c -> c.reloadResourcePacks());
            context.waitFor(c -> reload.isDone() && c.gui.overlay() == null,1200); reload.join();
            context.waitTicks(10); capture(context,"reloaded");
            System.out.println("Foliage wind live passed: existing NORMAL world, Metal 4K 16/16, leaves and tall/short grass, static fences, animation/toggle, grazing-light receiver stability and reload; movingPixels="+moving+", offPixels="+still);
        } finally {
            context.runOnClient(c -> {
                if (c.gui.hud.isHidden()!=hud) c.gui.hud.toggle(); c.options.bobView().set(bob); c.options.cloudStatus().set(clouds);
                c.options.cutoutLeaves().set(cutoutLeaves);
                if (ShaderPackRuntime.BUILTIN_ID.equals(ShaderPackRuntime.active().selectedPackId())) {
                    ShaderPackRuntime.active().setOption("wind_enabled",enabled);
                    ShaderPackRuntime.active().setOption("wind_strength",strength);
                    ShaderPackRuntime.active().setOption("debug_view",debug);
                }
                ShaderPackRuntime.active().selectPack(pack);
                if(c.level!=null)c.level.disconnect(net.minecraft.network.chat.Component.literal("Wind test complete"));
                c.disconnect(new TitleScreen(),false);
            });
            context.waitFor(c -> !net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl.isServerRunning && c.level==null,1200);
            context.setScreen(TitleScreen::new);
        }
    }
    private static Path capture(ClientGameTestContext context,String name) {
        context.runOnClient(c -> {
            check(ShaderPackRuntime.active().isActive(), "Standard active: "+ShaderPackRuntime.active().lastError());
            var target = c.gameRenderer.mainRenderTarget().getColorTextureView();
            check(target.getWidth(0) == 3840 && target.getHeight(0) == 2160, "wind test requires a 4K render target");
            int[] drawable = MetalSurfaceProbe.drawableSize();
            System.out.println("Wind capture " + name + ": worldTarget=" + target.getWidth(0) + "x" + target.getHeight(0)
                + ", drawable=" + drawable[0] + "x" + drawable[1]);
            c.gui.hud.getChat().clearMessages(true);
        });
        return context.takeScreenshot("metalcraft-wind-"+name);
    }
    private static int differences(Path first,Path second) {
        try(var a=NativeImage.read(Files.newInputStream(first));var b=NativeImage.read(Files.newInputStream(second))) {
            int count=0;
            for(int y=a.getHeight()/4;y<a.getHeight()*4/5;y++) for(int x=a.getWidth()/5;x<a.getWidth()*4/5;x++) {
                int p=a.getPixel(x,y), q=b.getPixel(x,y);
                if(Math.abs((p&255)-(q&255))+Math.abs((p>>>8&255)-(q>>>8&255))+Math.abs((p>>>16&255)-(q>>>16&255))>24)count++;
            }
            return count;
        }catch(java.io.IOException e){throw new AssertionError(e);}
    }
    private static void checkSmoothLeafFace(Path path) {
        // Interior of the opaque canopy's visible face in the fixed 4K fixture.
        // This contains no texture, silhouette, grass or real cast-shadow edge in
        // visibility mode. The old depth-derived wind plane speckled >50% of it.
        try (var image = NativeImage.read(Files.newInputStream(path))) {
            int noisy=0, samples=0;
            for (int y=(int)(image.getHeight()*.345);y<(int)(image.getHeight()*.37)-1;y++) {
                for (int x=(int)(image.getWidth()*.36);x<(int)(image.getWidth()*.4)-1;x++) {
                    int value=net.minecraft.util.ARGB.red(image.getPixel(x,y));
                    if (Math.abs(value-net.minecraft.util.ARGB.red(image.getPixel(x+1,y)))>24
                        || Math.abs(value-net.minecraft.util.ARGB.red(image.getPixel(x,y+1)))>24) noisy++;
                    samples++;
                }
            }
            System.out.println("Wind receiver speckling: " + noisy + "/" + samples);
            check(samples>8000 && noisy<samples/100, "wind-deformed leaf face must not self-shadow with pixel noise");
        } catch(java.io.IOException e) { throw new AssertionError(e); }
    }
    private static void command(ClientGameTestContext context,String text) {
        server(context,s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),text));
    }
    private static void server(ClientGameTestContext context,Consumer<MinecraftServer> action) {
        CompletableFuture<?> done=context.computeOnClient(c -> {var s=c.getSingleplayerServer();return s.submit(() -> action.accept(s));});
        context.waitFor(c -> done.isDone());done.join();
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
