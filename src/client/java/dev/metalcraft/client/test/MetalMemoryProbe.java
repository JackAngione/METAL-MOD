package dev.metalcraft.client.test;

import com.google.gson.GsonBuilder;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;

/** Explicitly opt-in, bounded quick-play memory probe. Run only against a copied save. */
public final class MetalMemoryProbe {
    private MetalMemoryProbe() { }
    public static void register() {
        int seconds = Integer.getInteger("metalcraft.memoryProbeSeconds", 0);
        if (seconds <= 0 || seconds > 120) throw new IllegalArgumentException("Memory probe must run for 1..120 seconds");
        var samples = new ArrayList<Object>();
        long[] started = {0}, last = {0};
        boolean[] savedPause = {false}, finished = {false};
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            LightStorageRuntimeCheck.run();
            savedPause[0] = client.options.pauseOnLostFocus;
            client.options.pauseOnLostFocus = false;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (finished[0] || client.level == null || client.player == null
                    || client.levelRenderer.sectionRenderDispatcher() == null) return;
            long now = System.nanoTime();
            if (started[0] == 0) {
                started[0] = now;
                MetalFrameMetrics.beginCapture(10);
                System.out.println("Memory probe world loaded; sampling for " + seconds + " seconds");
            }
            if (now-last[0] >= 1_000_000_000L) {
                last[0]=now;
                var sample = new LinkedHashMap<String,Object>();
                sample.put("seconds", (now-started[0])/1e9);
                var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                sample.put("heapUsed",heap.getUsed());sample.put("heapCommitted",heap.getCommitted());sample.put("heapMax",heap.getMax());
                sample.put("visibleSections",client.levelRenderer.visibleSections().size());
                sample.put("renderedSections",client.levelExtractor.countRenderedSections());
                sample.put("uncompiledVisible",client.levelRenderer.visibleSections().stream()
                        .filter(s -> s.getSectionMesh()==CompiledSectionMesh.UNCOMPILED).count());
                sample.put("compileQueue",client.levelRenderer.sectionRenderDispatcher().getCompileQueueSize());
                sample.put("loadedFullChunks", client.level.getChunkSource().getLoadedChunksCount());
                sample.put("horizon", dev.metalcraft.client.horizon.HorizonRenderer.stats());
                sample.put("terrain", terrainCensus(client));
                sample.put("gcMillis",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0,b.getCollectionTime())).sum());
                samples.add(sample);
                System.out.println("Memory probe: " + sample);
            }
            if (now-started[0] < seconds*1_000_000_000L) return;
            finished[0]=true;
            try {
                var report = new LinkedHashMap<String,Object>();
                report.put("flatLightSnapshots",Boolean.getBoolean("metalcraft.flatLightSnapshots"));
                report.put("environment",MetalBenchmarkEnvironment.describe());
                report.put("samples",samples);
                report.put("frames",MetalFrameMetrics.endCapture("saved-world-loading"));
                var file=Path.of("build", "memory-probe-"+(Boolean.getBoolean("metalcraft.flatLightSnapshots")?"flat":"paged")+".json");
                Files.createDirectories(file.getParent());
                Files.writeString(file,new GsonBuilder().setPrettyPrinting().create().toJson(report));
                System.out.println("Memory probe complete: " + file);
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            finally {
                client.options.pauseOnLostFocus=savedPause[0];
                client.disconnectWithSavingScreen();
                client.stop();
            }
        });
    }

    /** Opt-in census of installed meshes, including resident sections outside the visible list. */
    private static Object terrainCensus(net.minecraft.client.Minecraft client) {
        var result = new LinkedHashMap<String,Object>();
        var camera = client.gameRenderer.mainCamera().position();
        int radius = dev.metalcraft.client.MetalCraftConfig.nativeQualityDistance();
        result.put("nativeQualityDistance", radius);
        result.put("nativeTerrainLod", dev.metalcraft.client.MetalCraftConfig.nativeTerrainLod());
        result.put("nativeLodReduction", dev.metalcraft.client.MetalCraftConfig.nativeLodReduction());
        result.put("nativeLodPixels", dev.metalcraft.client.MetalCraftConfig.nativeLodPixels());
        for (boolean visible : new boolean[] {true, false}) {
            Iterable<net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection> sections = visible
                    ? client.levelRenderer.visibleSections()
                    : ((dev.metalcraft.client.mixin.ViewAreaAccessor)client.levelRenderer.viewArea()).metalcraft$sections();
            var counts = new LinkedHashMap<String,Long>();
            for (var section : sections) {
                var mesh = section.getSectionMesh();
                if (mesh == CompiledSectionMesh.UNCOMPILED || !mesh.hasRenderableLayers()) continue;
                long node = section.getSectionNode();
                boolean far = dev.metalcraft.client.chunk.NativeLodSelection.distanceSquared(camera.x,
                        net.minecraft.core.SectionPos.y(node)*16.0+8, camera.z, net.minecraft.core.SectionPos.x(node),
                        net.minecraft.core.SectionPos.y(node), net.minecraft.core.SectionPos.z(node)) > radius*radius*256.0;
                int tier = mesh instanceof dev.metalcraft.client.chunk.NativeLodState state ? state.metalcraft$cellSize() : 1;
                String group = (far ? "far" : "near") + (tier > 1 ? "Shell" : "Native");
                counts.merge(group + "Sections", 1L, Long::sum);
                for (var layer : net.minecraft.client.renderer.chunk.ChunkSectionLayer.values()) {
                    var draw = mesh.getSectionDraw(layer);
                    if (draw != null) counts.merge(group + layer.name() + "Indices", (long)draw.indexCount(), Long::sum);
                }
            }
            result.put(visible ? "visible" : "resident", counts);
        }
        result.put("shellBuilds", dev.metalcraft.client.chunk.NativeTerrainLod.stats());
        return result;
    }
}
