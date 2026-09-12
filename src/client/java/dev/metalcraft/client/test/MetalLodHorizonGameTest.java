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
        var marker=new dev.metalcraft.client.lod.LodDistantNode.Key(0,-96,14,-11);
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
                await(context, "explore", () -> LodDistantRenderer.stats().cache()!=null
                        && LodDistantRenderer.stats().cache().saved()>150 && LodDistantRenderer.stats().cache().indexedNodes()>150
                        && LodDistantRenderer.stats().cache().queuedNodes()==0);
                context.waitTicks(120);
                report.put("explored",LodDistantRenderer.stats());
                if(LodDistantRenderer.stats().cache().dropped()!=0)
                    throw new AssertionError("Prepared horizon scene lost captures to backpressure");
                System.out.println("LOD horizon explored: "+LodDistantRenderer.stats());
                context.takeScreenshot("metalcraft-lod-horizon-explored");
                for(int horizon:new int[]{32,64,128,256}) {
                    int distance=horizon*12;
                    context.runOnClient(c -> MetalCraftConfig.setLod(MetalCraftConfig.lod().withHorizon(horizon,true,512)));
                    world.getServer().runCommand("tp @a -1535.5 236 "+(-127.5+distance)+" 180 3");
                    context.getInput().lookAt(180,3);
                    context.waitTicks(120);
                    long before=LodDistantRenderer.stats().draws();
                    await(context, "horizon "+horizon, () -> LodDistantRenderer.stats().draws()>before+10
                            && LodDistantRenderer.stats().frameFarthestBlocks()>horizon*10);
                    context.waitTicks(40);
                    context.runOnClient(c -> c.gui.hud.getChat().clearMessages(true));
                    context.waitTicks(2);
                    var stats=LodDistantRenderer.stats();
                    if(stats.uploadFailures()!=0 || stats.gpuBytes()>LodDistantRenderer.MAX_GPU_BYTES || stats.frameDraws()>256
                            || stats.frameFarthestBlocks()>=horizon*16
                            || stats.cache().queuedBytes()>(16L<<20) || stats.cache().diskBytes()>(512L<<20))
                        throw new AssertionError("Distant budget/Metal failure: "+stats);
                    context.runOnClient(c -> {
                        if(c.options.renderDistance().get()!=16 || c.options.simulationDistance().get()!=16)
                            throw new AssertionError("Horizon changed Minecraft distances");
                    });
                    context.takeScreenshot("metalcraft-lod-horizon-"+horizon);
                    report.put("horizon"+horizon,stats);
                    System.out.println("LOD horizon "+horizon+": "+stats);
                    report.put("camera"+horizon,context.computeOnClient(c -> MetalBenchmarkEnvironment.camera()));
                }
                context.runOnClient(c -> ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID));
                context.waitTicks(40);
                long before=LodDistantRenderer.stats().draws();
                context.waitFor(c -> LodDistantRenderer.stats().draws()>before+10,2400);
                context.takeScreenshot("metalcraft-lod-horizon-none");
                report.put("none",LodDistantRenderer.stats());
                context.runOnClient(c -> ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID));
                world.getServer().runCommand("tp @a -1535.5 236 -127.5 180 3");
                context.getInput().lookAt(180,3);
                context.waitTicks(160);
                long oldRevision=LodDistantRenderer.entry(marker).receivedRevision();
                world.getServer().runCommand("fill -1552 224 -177 -1520 248 -176 minecraft:gold_block");
                await(context,"edited leaf persistence",() -> {
                    var entry=LodDistantRenderer.entry(marker);
                    return entry.receivedRevision()>oldRevision && entry.persistedRevision()==entry.receivedRevision() && entry.stored();
                });
                report.put("editedLeaf",LodDistantRenderer.entry(marker));
                await(context,"revisited terrain repair",() -> LodDistantRenderer.recaptures()>0
                        && LodDistantRenderer.stats().cache().queuedNodes()==0);
                context.waitTicks(100);
                report.put("revisitRecaptures",LodDistantRenderer.recaptures());
                context.runOnClient(c -> MetalCraftConfig.setLod(MetalCraftConfig.lod().withHorizon(32,true,512)));
                world.getServer().runCommand("tp @a -1535.5 236 256.5 180 3");
                context.getInput().lookAt(180,3);
                context.waitTicks(120);
                await(context,"edited distant view",() -> LodDistantRenderer.stats().frameFarthestBlocks()>320);
                context.runOnClient(c -> c.gui.hud.getChat().clearMessages(true));
                context.takeScreenshot("metalcraft-lod-horizon-edited");
                var reload=context.computeOnClient(c -> c.reloadResourcePacks());
                context.waitFor(c -> reload.isDone(),2400);
                if(reload.isCompletedExceptionally()) throw new AssertionError("Distant resource reload failed");
                await(context,"resource reload",() -> LodDistantRenderer.stats().frameFarthestBlocks()>320);
                context.waitTicks(60);
                report.put("reloaded",LodDistantRenderer.stats());
                context.takeScreenshot("metalcraft-lod-horizon-reloaded");
                world.getServer().runCommand("execute as @a in minecraft:the_nether run tp @s 0 80 0 180 3");
                context.waitFor(c -> c.level.dimension()==net.minecraft.world.level.Level.NETHER,2400);
                context.waitTicks(80);
                if(LodDistantRenderer.stats().frameDraws()!=0) throw new AssertionError("Overworld cached terrain leaked into Nether");
                report.put("nether",LodDistantRenderer.stats());
                world.getServer().runCommand("execute as @a in minecraft:overworld run tp @s -1535.5 236 256.5 180 3");
                context.waitFor(c -> c.level.dimension()==net.minecraft.world.level.Level.OVERWORLD,2400);
                context.getInput().lookAt(180,3);
                await(context,"dimension return",() -> LodDistantRenderer.stats().frameFarthestBlocks()>320);
                context.waitTicks(60);
                report.put("dimensionReturn",LodDistantRenderer.stats());
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
                await(context,"persisted world reopen",() -> LodDistantRenderer.stats().draws()>before+10);
                report.put("reopened",LodDistantRenderer.stats());
                context.takeScreenshot("metalcraft-lod-horizon-reopened");
                context.runOnClient(c -> LodDistantRenderer.clearCache());
                await(context,"clear cache",() -> LodDistantRenderer.stats().cache()!=null
                        && !LodDistantRenderer.entry(marker).stored() && LodDistantRenderer.stats().gpuBytes()==0);
                report.put("clearedActive",LodDistantRenderer.stats());
                context.waitTicks(20);
                if(LodDistantRenderer.stats().frameDraws()!=0) throw new AssertionError("Cleared distant nodes returned without recapture");
                context.runOnClient(c -> MetalCraftConfig.setLod(MetalCraftConfig.lod().withEnabled(false)));
                context.waitFor(c -> LodDistantRenderer.stats().cache()==null && LodDistantRenderer.stats().gpuBytes()==0,2400);
                Path cacheRoot=context.computeOnClient(c -> c.gameDirectory.toPath().resolve("build/lod-test-cache"));
                var clearing=dev.metalcraft.client.lod.LodDistantCache.clearRoot(cacheRoot,512L<<20);
                context.waitFor(c -> clearing.isDone(),2400);
                if(clearing.isCompletedExceptionally()) throw new AssertionError("Inactive global cache clear failed");
                try(var files=Files.walk(cacheRoot)) {
                    if(files.anyMatch(p->p.toString().endsWith(".lod"))) throw new AssertionError("Inactive clear left disk entries");
                } catch(java.io.IOException error) { throw new AssertionError(error); }
                report.put("clearedInactive",LodDistantRenderer.stats());
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
    static void await(ClientGameTestContext context,String stage,java.util.function.BooleanSupplier ready) {
        long[] logged={System.nanoTime()};
        context.waitFor(c -> {
            if (System.nanoTime()-logged[0]>10_000_000_000L) {
                System.out.println("LOD horizon awaiting "+stage+": "+LodDistantRenderer.stats());
                logged[0]=System.nanoTime();
            }
            return ready.getAsBoolean();
        },2400);
    }
}
