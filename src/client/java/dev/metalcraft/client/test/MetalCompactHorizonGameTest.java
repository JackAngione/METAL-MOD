package dev.metalcraft.client.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.chunk.NativeLodState;
import dev.metalcraft.client.horizon.HorizonRenderer;
import dev.metalcraft.client.horizon.NativeHorizon;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/** Bounded NORMAL-world ownership/edit/water/lifecycle route, plus identical-mesh submission ABBA. */
final class MetalCompactHorizonGameTest {
    static void run(ClientGameTestContext context) {
        int distance=context.computeOnClient(c->c.options.renderDistance().get());
        int simulation=context.computeOnClient(c->c.options.simulationDistance().get());
        int quality=MetalCraftConfig.nativeQualityDistance(),reduction=MetalCraftConfig.nativeLodReduction();
        boolean enabled=MetalCraftConfig.nativeTerrainLod(),fog=MetalCraftConfig.clearDistanceFog();
        boolean vsync=context.computeOnClient(c->c.options.enableVsync().get());
        String pack=context.computeOnClient(c->ShaderPackRuntime.active().selectedPackId());
        var report=new LinkedHashMap<String,Object>(); long start=System.nanoTime();
        try {
            context.runOnClient(c->{
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()),"Metal backend");
                c.options.renderDistance().set(128);c.options.simulationDistance().set(16);c.options.enableVsync().set(false);
                MetalCraftConfig.setNativeTerrainLod(true);MetalCraftConfig.setNativeLodReduction(5);MetalCraftConfig.setNativeQualityDistance(4);
                MetalCraftConfig.setClearDistanceFog(true);ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID);
            });
            context.getInput().resizeWindow(1280,720);
            try(var world=context.worldBuilder().adjustSettings(settings->{
                var normal=settings.getSettings().worldgenLoadContext().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                        .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
                settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));settings.setSeed("metalcraft");
                settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);settings.setAllowCommands(true);
            }).create()) {
                var center=context.computeOnClient(c->c.player.chunkPosition());
                int x=(center.x()+12)*16,z=center.z()*16,cx=center.x()*16+8,cz=z+8;
                int columnX=center.x()+12,columnZ=center.z();
                var marker=new BlockPos(x+8,176,z+8);
                world.getServer().runCommand("gamemode spectator @a");
                world.getServer().runCommand("tp @a "+cx+" 210 "+cz+" -90 10");context.getInput().lookAt(-90,10);
                world.getServer().runCommand("time set noon");world.getServer().runCommand("weather clear");
                world.getServer().runCommand("forceload add "+x+" "+z+" "+(x+31)+" "+(z+31));
                context.waitFor(c->HorizonRenderer.column(columnX,columnZ)!=null,600);
                world.getServer().runCommand("fill "+x+" 172 "+z+" "+(x+31)+" 176 "+(z+31)+" minecraft:stone");
                world.getServer().runCommand("fill "+(x+1)+" 176 "+(z+1)+" "+(x+30)+" 176 "+(z+30)+" minecraft:water");
                world.getServer().runCommand("fill "+(x+16)+" 100 "+(z+16)+" "+(x+31)+" 176 "+(z+31)+" minecraft:water");
                context.waitFor(c->height(columnX,columnZ,false)>=176 && height(columnX,columnZ,true)>176
                        && height(columnX+1,columnZ+1,true)>176,300);
                world.getServer().runCommand("forceload remove all");
                context.waitFor(c->HorizonRenderer.covers(SectionPos.of(marker).asLong()),300);
                check(context.computeOnClient(c->!c.level.getChunkSource().hasChunk(columnX,columnZ)),"distant shell survives without a full client chunk");
                check(context.computeOnClient(c->c.options.getEffectiveRenderDistance())==7,"native view bounded at quality plus handoff");
                check(context.computeOnClient(c->c.level.getChunkSource().getLoadedChunksCount())<500,"full client chunk count bounded independently of horizon");
                context.runOnClient(c->c.gui.hud.getChat().clearMessages(true));context.waitTicks(5);
                context.takeScreenshot("compact-horizon-none");
                report.put("unloadedDistantColumnRendered",true); report.put("initial",HorizonRenderer.stats());
                world.getServer().runOnServer(server->server.overworld().getChunk(columnX,columnZ)
                        .setBlockState(new BlockPos(x+8,195,z+8),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3));
                context.waitFor(c->height(columnX,columnZ,false)==196,200);
                report.put("serverEditRefreshesDetachedModel",true);
                context.runOnClient(c->ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID));
                context.waitTicks(10);context.takeScreenshot("compact-horizon-standard");
                world.getServer().runCommand("tp @a "+cx+" 210 "+(cz+64)+" -103 15");context.getInput().lookAt(-103,15);
                context.waitFor(c->HorizonRenderer.covers(SectionPos.of(marker).asLong()),100);
                context.waitTicks(8);context.takeScreenshot("compact-horizon-waterfall");
                world.getServer().runCommand("tp @a "+(x+8)+" 175.1 "+(z+8)+" -90 0");context.getInput().lookAt(-90,0);
                context.waitFor(c->{
                    var area=c.levelRenderer.viewArea(); var section=area==null?null:area.getRenderSectionAt(marker);
                    return c.level.getChunkSource().hasChunk(columnX,columnZ) && section!=null
                        && section.getSectionMesh() instanceof NativeLodState state && state.metalcraft$cellSize()==1
                        && !HorizonRenderer.covers(SectionPos.of(marker).asLong());
                },300);
                context.waitTicks(5);context.takeScreenshot("compact-horizon-underwater-native");
                report.put("approachRestoresNativeAndReleasesProxyOwnership",true);
                world.getServer().runCommand("tp @a "+cx+" 210 "+cz+" -90 10");context.getInput().lookAt(-90,10);
                context.waitFor(c->HorizonRenderer.covers(SectionPos.of(marker).asLong()) && !c.level.getChunkSource().hasChunk(columnX,columnZ),300);
                context.runOnClient(c->ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID));
                // Freeze discovery, never alter or replace the already visible geometry between phases.
                System.setProperty("metalcraft.freezeHorizonSampling","true");context.waitTicks(30);
                var phases=new ArrayList<Object>();
                int columns=HorizonRenderer.stats().frameColumns(); long uploads=HorizonRenderer.stats().uploads();
                for(boolean split:new boolean[]{true,false,false,true}) {
                    System.setProperty("metalcraft.horizonSplitDraws",Boolean.toString(split));
                    context.runOnClient(c->MetalFrameMetrics.beginCapture(10));context.waitTicks(40);
                    var frames=context.computeOnClient(c->MetalFrameMetrics.endCapture(split?"split-identical-mesh":"grouped-identical-mesh"));
                    check(HorizonRenderer.stats().frameColumns()==columns && HorizonRenderer.stats().uploads()==uploads,"same model coverage and GPU buffers across timing phases");
                    check(frames.frames()>20,"populated timing capture");phases.add(frames);
                }
                report.put("sameMeshABBA",phases);report.put("fixedModelColumns",columns);
                System.clearProperty("metalcraft.horizonSplitDraws");System.clearProperty("metalcraft.freezeHorizonSampling");
                context.getInput().resizeWindow(1100,700);context.waitTicks(3);
                // Turning the horizon off clears all model ownership. Keep native load small during the check.
                context.runOnClient(c->{c.options.renderDistance().set(7);MetalCraftConfig.setNativeTerrainLod(false);});
                context.waitFor(c->HorizonRenderer.stats().cachedColumns()==0 && HorizonRenderer.streamingStats().tickets()==0,100);
                context.runOnClient(c->{MetalCraftConfig.setNativeTerrainLod(true);c.options.renderDistance().set(128);});
                context.waitFor(c->NativeHorizon.enabled() && HorizonRenderer.stats().frameColumns()>0,300);
                report.put("disableReenableAndResize",true);report.put("final",HorizonRenderer.stats());
            }
            context.waitFor(c->HorizonRenderer.streamingStats().tickets()==0 && HorizonRenderer.stats().cachedColumns()==0,100);
            report.put("worldCloseReleasesOwnership",true);report.put("world","NORMAL / metalcraft");
            report.put("renderDistance",128);report.put("simulationDistance",16);report.put("backend","Metal");
            report.put("elapsedSeconds",(System.nanoTime()-start)/1e9);report.put("passed",true);
            System.out.println("Compact horizon route passed in "+report.get("elapsedSeconds")+" seconds; evidence: build/compact-horizon.json");
        } finally {
            System.clearProperty("metalcraft.horizonSplitDraws");System.clearProperty("metalcraft.freezeHorizonSampling");
            context.runOnClient(c->{
                c.options.renderDistance().set(distance);c.options.simulationDistance().set(simulation);c.options.enableVsync().set(vsync);
                MetalCraftConfig.setNativeQualityDistance(quality);MetalCraftConfig.setNativeLodReduction(reduction);
                MetalCraftConfig.setNativeTerrainLod(enabled);MetalCraftConfig.setClearDistanceFog(fog);ShaderPackRuntime.active().selectPack(pack);
            });
            try { Files.writeString(Path.of("build/compact-horizon.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }
    }
    private static float height(int x,int z,boolean water) {
        var column=HorizonRenderer.column(x,z);if(column==null)return Float.NEGATIVE_INFINITY;
        float high=Float.NEGATIVE_INFINITY;
        for(var cell:column.cells()) {var surface=water?cell.fluid():cell.solid();if(surface!=null)high=Math.max(high,surface.high());}
        return high;
    }
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
}
