package dev.metalcraft.client.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.horizon.HorizonRenderer;
import dev.metalcraft.client.lod.LodGenerationCursor;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/** Same-world legacy/fixed mesh comparison at maximum detail, with coverage and stability gates. */
final class MetalHorizonDetailGameTest {
    static void run(ClientGameTestContext context) {
        int distance=context.computeOnClient(c->c.options.renderDistance().get());
        int simulation=context.computeOnClient(c->c.options.simulationDistance().get());
        int quality=MetalCraftConfig.nativeQualityDistance(),reduction=MetalCraftConfig.nativeLodReduction(),detail=MetalCraftConfig.horizonDetail();
        boolean enabled=MetalCraftConfig.nativeTerrainLod();
        String pack=context.computeOnClient(c->ShaderPackRuntime.active().selectedPackId());
        var report=new LinkedHashMap<String,Object>();
        try {
            context.runOnClient(c->{
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()),"Metal backend");
                c.options.renderDistance().set(64);c.options.simulationDistance().set(16);
                MetalCraftConfig.setNativeTerrainLod(true);MetalCraftConfig.setNativeLodReduction(2);
                MetalCraftConfig.setNativeQualityDistance(20);MetalCraftConfig.setHorizonDetail(5);
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID);
            });
            context.getInput().resizeWindow(1920,1080);
            try(var world=context.worldBuilder().adjustSettings(settings->{
                var normal=settings.getSettings().worldgenLoadContext().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                        .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
                settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));settings.setSeed("metalcraft");
                settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);settings.setAllowCommands(true);
            }).create()) {
                world.getServer().runCommand("gamemode spectator @a");
                world.getServer().runCommand("tp @a 8.5 210 8.5 135 18");
                world.getServer().runCommand("time set noon");world.getServer().runCommand("weather clear");
                context.getInput().lookAt(135,18);
                var cursor=new LodGenerationCursor(0,0,19,64);int total=0;while(cursor.next()!=null) total++;
                final int expected=total;int[] progress={-1};
                context.waitFor(c->{
                    int ready=HorizonRenderer.stats().cachedColumns();
                    if(ready/2000!=progress[0]) { progress[0]=ready/2000;System.out.println("Detail coverage: "+ready+" / "+expected+" "+HorizonRenderer.diagnostics()); }
                    check(HorizonRenderer.streamingStats().failures()==0,"generation succeeds");
                    return ready>=expected && HorizonRenderer.streamingStats().tickets()==0;
                },12000);
                System.setProperty("metalcraft.freezeHorizonSampling","true");
                context.runOnClient(c->{
                    if(c.gui.hud.isHidden()) c.gui.hud.toggle();c.gui.hud.getChat().clearMessages(true);
                    c.debugEntries.setOverlayVisible(true);
                    c.debugEntries.setStatus(net.minecraft.client.gui.components.debug.DebugScreenEntries.FPS,
                            net.minecraft.client.gui.components.debug.DebugScreenEntryStatus.IN_OVERLAY);
                });
                System.setProperty("metalcraft.horizonLegacyMesh","true");context.waitTicks(80);
                report.put("legacy",measure(context,"legacy-expanded-mesh"));
                context.takeScreenshot("horizon-detail5-before");
                System.clearProperty("metalcraft.horizonLegacyMesh");
                context.waitFor(c->{
                    var d=HorizonRenderer.diagnostics();var s=HorizonRenderer.stats();
                    return s.frameColumns()>1000 && s.frameColumns()==d.wantedColumns() && d.coarseGroups()==0;
                },600);
                context.waitTicks(80);
                long builds=HorizonRenderer.diagnostics().meshBuilds(),uploads=HorizonRenderer.stats().uploads();
                report.put("fixed",measure(context,"merged-exact-mesh"));
                check(HorizonRenderer.diagnostics().meshBuilds()==builds && HorizonRenderer.stats().uploads()==uploads,
                        "stationary completed view never rebuilds/uploads unchanged meshes");
                check(HorizonRenderer.stats().gpuBytes()<=HorizonRenderer.diagnostics().gpuBudget(),"GPU ownership stays bounded");
                context.takeScreenshot("horizon-detail5-fixed-f3");
                // A camera turn must recover full detail with no stale masks or admission churn.
                world.getServer().runCommand("tp @a 8.5 210 8.5 -45 18");context.getInput().lookAt(-45,18);
                context.waitFor(c->{var d=HorizonRenderer.diagnostics();return HorizonRenderer.stats().frameColumns()==d.wantedColumns() && d.coarseGroups()==0;},600);
                context.waitTicks(40);context.takeScreenshot("horizon-detail5-fixed-reverse");
                report.put("reverse",HorizonRenderer.stats());report.put("reverseDiagnostics",HorizonRenderer.diagnostics());
                report.put("fullDetailCoverageAndStableBuffers",true);
                context.runOnClient(c->{c.options.renderDistance().set(23);MetalCraftConfig.setNativeTerrainLod(false);});
                context.waitFor(c->HorizonRenderer.stats().cachedColumns()==0 && HorizonRenderer.streamingStats().tickets()==0,100);
            }
            report.put("passed",true);System.out.println("Horizon maximum-detail comparison passed: "+report);
        } finally {
            System.clearProperty("metalcraft.horizonLegacyMesh");System.clearProperty("metalcraft.freezeHorizonSampling");
            context.runOnClient(c->{
                c.debugEntries.setOverlayVisible(false);c.options.renderDistance().set(distance);c.options.simulationDistance().set(simulation);
                MetalCraftConfig.setNativeQualityDistance(quality);MetalCraftConfig.setNativeLodReduction(reduction);
                MetalCraftConfig.setHorizonDetail(detail);MetalCraftConfig.setNativeTerrainLod(enabled);ShaderPackRuntime.active().selectPack(pack);
            });
            report.put("lastRenderer",HorizonRenderer.stats());report.put("lastDiagnostics",HorizonRenderer.diagnostics());
            try { Files.writeString(Path.of("build/horizon-detail.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
            catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }
    }
    private static Object measure(ClientGameTestContext context,String name) {
        var values=new LinkedHashMap<String,Object>();
        long allocated=context.computeOnClient(c->allocated());long start=System.nanoTime();
        var diagnostics=HorizonRenderer.diagnostics();
        context.runOnClient(c->MetalFrameMetrics.beginCapture(20));context.waitTicks(100);
        values.put("frames",context.computeOnClient(c->MetalFrameMetrics.endCapture(name)));
        values.put("allocatedMiBPerSecond",(context.computeOnClient(c->allocated())-allocated)/1048576.0/((System.nanoTime()-start)/1e9));
        values.put("meshBuilds",HorizonRenderer.diagnostics().meshBuilds()-diagnostics.meshBuilds());
        values.put("meshBuildMs",(HorizonRenderer.diagnostics().meshBuildNanos()-diagnostics.meshBuildNanos())/1e6);
        values.put("renderer",HorizonRenderer.stats());values.put("diagnostics",HorizonRenderer.diagnostics());
        System.out.println("Horizon phase "+name+": "+values);return values;
    }
    private static long allocated() {
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        return bean instanceof com.sun.management.ThreadMXBean memory?memory.getThreadAllocatedBytes(Thread.currentThread().threadId()):0;
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
