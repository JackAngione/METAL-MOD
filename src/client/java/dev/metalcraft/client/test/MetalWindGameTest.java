package dev.metalcraft.client.test;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
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
        try {
            context.runOnClient(c -> {
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "Default selected Metal");
                check(c.getLevelSource().levelExists(save), "existing test save available");
                MetalCraftConfig.setLodEnabled(false);
                c.options.renderDistance().set(16); c.options.simulationDistance().set(16);
                c.options.cloudStatus().set(CloudStatus.OFF); c.options.bobView().set(false); if (!c.gui.hud.isHidden()) c.gui.hud.toggle();
                ShaderPackRuntime.active().setOption("wind_enabled", true);
                ShaderPackRuntime.active().setOption("wind_strength", 1.0);
                c.createWorldOpenFlows().openWorld(save, () -> { });
            });
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
            Path onA = capture(context,"on-a"); context.waitTicks(20); Path onB = capture(context,"on-b");
            int moving = differences(onA,onB);
            check(moving>100,"visible foliage animation: "+moving);
            context.runOnClient(c -> ShaderPackRuntime.active().setOption("wind_enabled",false));
            context.waitTicks(8);
            Path offA = capture(context,"off-a"); context.waitTicks(20); Path offB = capture(context,"off-b");
            int still = differences(offA,offB);
            check(still < moving/3,"disabled foliage stays still: moving="+moving+", off="+still);
            context.runOnClient(c -> ShaderPackRuntime.active().setOption("wind_enabled",true));
            var reload = context.computeOnClient(c -> c.reloadResourcePacks());
            context.waitFor(c -> reload.isDone() && c.gui.overlay() == null,1200); reload.join();
            context.waitTicks(10); capture(context,"reloaded");
            System.out.println("Foliage wind live passed: existing NORMAL world, Metal 16/16, leaves and tall/short grass, static fences, animation/toggle and reload; movingPixels="+moving+", offPixels="+still);
        } finally {
            context.runOnClient(c -> {
                if (c.gui.hud.isHidden()!=hud) c.gui.hud.toggle(); c.options.bobView().set(bob); c.options.cloudStatus().set(clouds);
                if (ShaderPackRuntime.BUILTIN_ID.equals(ShaderPackRuntime.active().selectedPackId())) {
                    ShaderPackRuntime.active().setOption("wind_enabled",enabled);
                    ShaderPackRuntime.active().setOption("wind_strength",strength);
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
    private static void command(ClientGameTestContext context,String text) {
        server(context,s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),text));
    }
    private static void server(ClientGameTestContext context,Consumer<MinecraftServer> action) {
        CompletableFuture<?> done=context.computeOnClient(c -> {var s=c.getSingleplayerServer();return s.submit(() -> action.accept(s));});
        context.waitFor(c -> done.isDone());done.join();
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
