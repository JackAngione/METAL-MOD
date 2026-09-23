package dev.metalcraft.client.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.horizon.HorizonRenderer;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/** Short fresh NORMAL-world generation measurement, including paused cancellation and recovery. */
final class MetalHorizonGenerationGameTest {
    static void run(ClientGameTestContext context) {
        int distance=context.computeOnClient(c->c.options.renderDistance().get());
        int simulation=context.computeOnClient(c->c.options.simulationDistance().get());
        int quality=MetalCraftConfig.nativeQualityDistance(),reduction=MetalCraftConfig.nativeLodReduction();
        int detail=MetalCraftConfig.horizonDetail(); boolean enabled=MetalCraftConfig.nativeTerrainLod();
        String pack=context.computeOnClient(c->ShaderPackRuntime.active().selectedPackId());
        var report=new LinkedHashMap<String,Object>();
        try {
            context.runOnClient(c->{
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()),"Metal backend");
                c.options.renderDistance().set(128); c.options.simulationDistance().set(16);
                MetalCraftConfig.setNativeTerrainLod(true); MetalCraftConfig.setNativeLodReduction(2);
                MetalCraftConfig.setNativeQualityDistance(20); MetalCraftConfig.setHorizonDetail(1);
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID);
            });
            try(var world=context.worldBuilder().adjustSettings(settings->{
                var normal=settings.getSettings().worldgenLoadContext().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                        .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
                settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal)); settings.setSeed("metalcraft");
                settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE); settings.setAllowCommands(true);
            }).create()) {
                world.getServer().runCommand("gamemode spectator @a");
                var center=context.computeOnClient(c->c.player.chunkPosition());
                world.getServer().runCommand("tp @a "+(center.x()*16+8)+" 210 "+(center.z()*16+8)+" 0 15");
                context.waitFor(c->HorizonRenderer.streamingStats().columns()>0,600);
                var before=HorizonRenderer.streamingStats();
                int firstTick=world.getServer().computeOnServer(s->s.getTickCount());
                long start=System.nanoTime();
                int[] peaks={0,0,0};
                context.waitFor(c->{
                    var stats=HorizonRenderer.streamingStats();
                    check(stats.failures()==before.failures(),"generation failures: "+stats);
                    check(stats.tickets()<=64 && stats.pendingSnapshots()<=64,"bounded ownership: "+stats);
                    peaks[0]=Math.max(peaks[0],stats.tickets()); peaks[1]=Math.max(peaks[1],stats.pendingSnapshots());
                    peaks[2]=Math.max(peaks[2],stats.serverLoadedChunks());
                    return c.getSingleplayerServer().getTickCount()-firstTick>=200;
                },1200);
                double seconds=(System.nanoTime()-start)/1e9;
                var after=HorizonRenderer.streamingStats();
                check(after.columns()>before.columns(),"generation makes progress");
                report.put("before",before); report.put("after",after);
                report.put("seconds",seconds); report.put("columnsPerSecond",(after.columns()-before.columns())/seconds);
                report.put("renderer",HorizonRenderer.stats());
                report.put("averageServerTickMs",world.getServer().computeOnServer(s->s.getAverageTickTimeNanos()/1e6));
                report.put("peakTickets",peaks[0]); report.put("peakSnapshots",peaks[1]); report.put("peakServerLoadedChunks",peaks[2]);
                var detached=context.computeOnClient(c->{
                    for(int z=center.z()-40;z<=center.z()+40;z++) for(int x=center.x()-40;x<=center.x()+40;x++) {
                        var column=HorizonRenderer.column(x,z);
                        if(column!=null && Math.max(Math.abs(x-center.x()),Math.abs(z-center.z()))>16
                                && !c.level.getChunkSource().hasChunk(x,z)) return new net.minecraft.world.level.ChunkPos(x,z);
                    }
                    throw new AssertionError("generated model beyond native delivery is present without a client chunk");
                });
                report.put("detachedColumn",detached.toString());
                world.getServer().runOnServer(server->{
                    var level=server.overworld();
                    check(!level.getChunkSource().isPositionTicking(detached.pack()),"distant terrain is not simulated");
                    level.getChunk(detached.x(),detached.z()).setBlockState(
                            new net.minecraft.core.BlockPos(detached.getMinBlockX(),300,detached.getMinBlockZ()),
                            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3);
                });
                context.waitFor(c->{
                    var column=HorizonRenderer.column(detached.x(),detached.z());
                    if(column==null) return false;
                    for(var cell:column.cells()) if(cell.solid()!=null && cell.solid().high()==301) return true;
                    return false;
                },200);
                report.put("detachedTerrainAndEditRefresh",true);
                context.runOnClient(c->{
                    check(c.options.renderDistance().get()==128 && c.options.simulationDistance().get()==16,"128/16 settings");
                    check(HorizonRenderer.stats().frameColumns()>0,"generated models draw");
                    c.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
                });
                context.waitFor(c->c.getSingleplayerServer().isPaused(),200);
                // Keep native delivery bounded while turning off the detached horizon.
                context.runOnClient(c->{c.options.renderDistance().set(23); MetalCraftConfig.setNativeTerrainLod(false);});
                context.waitFor(c->HorizonRenderer.streamingStats().tickets()==0 && HorizonRenderer.stats().cachedColumns()==0,200);
                context.runOnClient(c->{c.gui.setScreen(null); MetalCraftConfig.setNativeTerrainLod(true); c.options.renderDistance().set(128);});
                context.waitFor(c->HorizonRenderer.stats().frameColumns()>0,400);
                report.put("pausedCancellationAndResume",true);
            }
            context.waitFor(c->HorizonRenderer.streamingStats().tickets()==0 && HorizonRenderer.stats().cachedColumns()==0,200);
            report.put("passed",true); report.put("world","NORMAL / metalcraft / 128 render / 16 simulation / Metal");
            System.out.println("Horizon generation passed: "+report);
        } finally {
            context.runOnClient(c->{
                c.options.renderDistance().set(distance); c.options.simulationDistance().set(simulation);
                MetalCraftConfig.setNativeQualityDistance(quality); MetalCraftConfig.setNativeLodReduction(reduction);
                MetalCraftConfig.setHorizonDetail(detail); MetalCraftConfig.setNativeTerrainLod(enabled);
                ShaderPackRuntime.active().selectPack(pack);
            });
            try { Files.writeString(Path.of("build/horizon-generation.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
