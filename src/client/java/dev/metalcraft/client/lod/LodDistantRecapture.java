package dev.metalcraft.client.lod;

import java.util.Iterator;
import java.util.List;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import net.minecraft.core.SectionPos;

/** Owning-thread input extraction for dirty cached sections outside the current view.
 * Uses Minecraft's existing snapshot/worker/upload path, and never loads additional chunks. */
public final class LodDistantRecapture {
    private static LodDistantCache owner;
    private static long generation;
    private static Iterator<LodDistantNode.Key> cursor;
    private LodDistantRecapture() { }
    public static void reset() { owner=null; cursor=null; generation=0; }

    public static void extract(ClientLevel level,SectionUpdateTracker tracker,LevelRenderState frame) {
        var cache=LodDistantRenderer.currentCache();
        if (cache==null) { reset(); return; }
        if (cache!=owner || generation!=cache.generation()) {
            owner=cache; generation=cache.generation(); cursor=cache.knownIterator();
        }
        var stats=cache.stats();
        if (!stats.ready() || stats.failures()!=0 || stats.queuedNodes()>128
                || stats.queuedBytes()>LodDistantCache.QUEUE_BYTES/2) return;
        int allowance=switch(LodDistantRenderer.frameSettings().backgroundWork()) { case LOW -> 1; case BALANCED -> 2; case HIGH -> 4; };
        RenderRegionCache snapshots=null;
        for (int scanned=0,requested=0;scanned<128 && requested<allowance;scanned++) {
            if (!cursor.hasNext()) { cursor=cache.knownIterator(); break; }
            var key=cursor.next();
            if (!cache.needsRecapture(key)) continue;
            long section=SectionPos.asLong(key.x(),key.y(),key.z());
            var dirty=tracker.getDirtyState(section);
            if (dirty==null || dirty.getSectionNode()!=section || !dirty.isDirty() || !tracker.hasAllNeighbors(level,section)) continue;
            if (snapshots==null) snapshots=new RenderRegionCache();
            var region=snapshots.createRegion(level,section);
            // Extra hidden work must remain asynchronous, regardless of the dirty cause.
            frame.sectionUpdateRenderStates.add(new SectionUpdateRenderState(section,false,region));
            if (region==null) {
                // Vanilla skips compilation for an empty section; persist that valid empty result.
                var ticket=LodCompilerCapture.REVISIONS.capture(key.x(),key.y(),key.z());
                cache.capture(new LodDistantNode(key,List.of(),0,1),ticket);
            }
            dirty.setNotDirty(); cache.recaptureRequested(); requested++;
        }
    }
}
