package dev.metalcraft.client.test;

import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.lod.LodDistantRenderer;
import dev.metalcraft.client.lod.LodSettings;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/** Explores received NORMAL terrain, then observes it beyond the ordinary 16-chunk client horizon. */
final class MetalLodHorizonGameTest {
    static void run(ClientGameTestContext context) {
        var saved=MetalCraftConfig.lod();
        String pack=context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        var report=new LinkedHashMap<String,Object>();
        var builder=context.worldBuilder().adjustSettings(settings -> {
            var normal=settings.getSettings().worldgenLoadContext().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                    .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
            settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
            settings.setSeed("metalcraft");
            settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            settings.setAllowCommands(true);
        });
        try {
            context.runOnClient(c -> {
                MetalCraftConfig.setLod(LodSettings.defaults().withEnabled(true).withHorizon(256,true,512));
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID);
            });
            net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave save;
            try(var world=builder.create()) {
                save=world.getWorldSave();
                world.getServer().runCommand("gamemode spectator @a");
                world.getServer().runCommand("tp @a -1535.5 236 -127.5 22.5 35");
                world.getServer().runCommand("time set noon");
                world.getServer().runCommand("weather clear");
                context.getInput().lookAt(22.5F,35);
                context.waitFor(c -> LodDistantRenderer.stats().cache()!=null && LodDistantRenderer.stats().cache().saved()>150,6000);
                context.waitTicks(120);
                report.put("explored",LodDistantRenderer.stats());
                context.takeScreenshot("metalcraft-lod-horizon-explored");
                for(int horizon:new int[]{32,64,128,256}) {
                    int distance=horizon*12;
                    context.runOnClient(c -> MetalCraftConfig.setLod(MetalCraftConfig.lod().withHorizon(horizon,true,512)));
                    world.getServer().runCommand("tp @a -1535.5 236 "+(-127.5+distance)+" 180 3");
                    context.getInput().lookAt(180,3);
                    context.waitTicks(120);
                    long before=LodDistantRenderer.stats().draws();
                    context.waitFor(c -> LodDistantRenderer.stats().draws()>before+10,6000);
                    context.waitTicks(40);
                    var stats=LodDistantRenderer.stats();
                    if(stats.uploadFailures()!=0 || stats.gpuBytes()>(32L<<20) || stats.frameDraws()>256
                            || stats.cache().queuedBytes()>(16L<<20) || stats.cache().diskBytes()>(512L<<20))
                        throw new AssertionError("Distant budget/Metal failure: "+stats);
                    context.runOnClient(c -> {
                        if(c.options.renderDistance().get()!=16 || c.options.simulationDistance().get()!=16)
                            throw new AssertionError("Horizon changed Minecraft distances");
                    });
                    context.takeScreenshot("metalcraft-lod-horizon-"+horizon);
                    report.put("horizon"+horizon,stats);
                    report.put("camera"+horizon,context.computeOnClient(c -> MetalBenchmarkEnvironment.camera()));
                }
                context.runOnClient(c -> ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID));
                context.waitTicks(40);
                long before=LodDistantRenderer.stats().draws();
                context.waitFor(c -> LodDistantRenderer.stats().draws()>before+10,2400);
                context.takeScreenshot("metalcraft-lod-horizon-none");
                report.put("none",LodDistantRenderer.stats());
                context.runOnClient(c -> MetalCraftConfig.setLod(MetalCraftConfig.lod().withEnabled(false)));
                context.waitTicks(40);
                long disabled=LodDistantRenderer.stats().draws();
                context.waitTicks(20);
                if(LodDistantRenderer.stats().draws()!=disabled || LodDistantRenderer.stats().gpuBytes()!=0)
                    throw new AssertionError("Disabled distant terrain retained ownership");
                context.takeScreenshot("metalcraft-lod-horizon-off");
                context.runOnClient(c -> MetalCraftConfig.setLod(MetalCraftConfig.lod().withEnabled(true)));
            }
            context.waitFor(c -> LodDistantRenderer.stats().gpuBytes()==0,2400);
            report.put("closed",LodDistantRenderer.stats());
            try(var reopened=save.open()) {
                context.waitFor(c -> c.level!=null && c.player!=null,2400);
                context.getInput().lookAt(180,3);
                long before=LodDistantRenderer.stats().draws();
                context.waitFor(c -> LodDistantRenderer.stats().draws()>before+10,6000);
                report.put("reopened",LodDistantRenderer.stats());
                context.takeScreenshot("metalcraft-lod-horizon-reopened");
            }
            context.waitFor(c -> LodDistantRenderer.stats().gpuBytes()==0,2400);
            report.put("finalClose",LodDistantRenderer.stats());
        } finally {
            try {
                Path path=Path.of("build/lod-horizon.json"); Files.createDirectories(path.getParent());
                Files.writeString(path,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
            } catch(java.io.IOException error) { throw new AssertionError(error); }
            context.runOnClient(c -> { MetalCraftConfig.setLod(saved); ShaderPackRuntime.active().selectPack(pack); });
        }
        System.out.println("LOD horizon live passed: "+report);
    }
}
