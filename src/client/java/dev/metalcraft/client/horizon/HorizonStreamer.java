package dev.metalcraft.client.horizon;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import dev.metalcraft.client.lod.LodGenerationCursor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Bounded server-owned sampling. Full chunks are borrowed temporarily, never sent to the client. */
public final class HorizonStreamer {
    record Request(long epoch,MinecraftServer server,ResourceKey<Level> dimension,int x,int z,int near,int far,int sampleSize) { }
    record Completed(long epoch,HorizonSnapshot snapshot) { }
    public record Stats(int tickets,int pendingSnapshots,long columns,long failures,int serverLoadedChunks) { }
    private static final TicketType TICKET=new TicketType(0,TicketType.FLAG_LOADING);
    private static final ExecutorService REQUESTS=Executors.newSingleThreadExecutor(r->{
        var t=new Thread(r,"MetalCraft horizon requests"); t.setDaemon(true); return t;
    });
    private static volatile Request request;
    private static final ConcurrentLinkedQueue<Completed> completed=new ConcurrentLinkedQueue<>();
    private static final HashMap<Long,Job> jobs=new HashMap<>(); // server thread only
    private static final Set<Long> dirty=ConcurrentHashMap.newKeySet();
    private static volatile Request previous;
    private static LodGenerationCursor cursor;
    private static boolean exhausted;
    private static long columns,failures;
    private static volatile Stats stats=new Stats(0,0,0,0,0);
    private static final class Job {
        volatile boolean released;
        final Request request;
        final ServerLevel level;
        final ChunkPos pos;
        final CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>> future;
        Job(Request request,ServerLevel level,ChunkPos pos) {
            this.request=request; this.level=level; this.pos=pos;
            level.getChunkSource().addTicketWithRadius(TICKET,pos,0);
            // Native getChunkFuture schedules on the server when invoked off-thread;
            // invoking it on that server thread would managed-block the tick.
            future=CompletableFuture.supplyAsync(()->released?CompletableFuture.<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>>completedFuture(null):level.getChunkSource().getChunkFuture(pos.x(),pos.z(),ChunkStatus.FULL,true),REQUESTS)
                    .thenCompose(f->f);
        }
        void release() { released=true; level.getChunkSource().removeTicketWithRadius(TICKET,pos,0); }
    }
    static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(HorizonStreamer::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{
            if(previous!=null && previous.server==server) { clearJobs(); previous=null; cursor=null; }
            if(request!=null && request.server==server) request=null;
            completed.clear(); publish();
        });
    }
    static void request(Request next) { request=next; }
    static Completed poll() { return completed.poll(); }
    static Stats stats() { return stats; }
    static void stop() {
        Request old=request; request=null; completed.clear(); dirty.clear();
        // Run on the server executor even if the integrated server is paused.
        if(old!=null) old.server.execute(()->{
            if(previous!=null && sameWorld(previous,old) && !sameWorld(request,old)) {
                clearJobs(); previous=null; cursor=null; publish();
            }
        });
    }
    private static void clearJobs() { for(var job:jobs.values()) job.release(); jobs.clear(); dirty.clear(); }
    static void invalidate(ServerLevel level,long key) {
        var active=request;
        if(active!=null && active.server==level.getServer() && active.dimension.equals(level.dimension())
                && NativeHorizon.hasColumn(active.epoch,key)) dirty.add(key);
    }
    private static boolean sameWorld(Request a,Request b) { return a!=null && b!=null && a.epoch==b.epoch && a.server==b.server && a.dimension.equals(b.dimension); }
    private static void tick(MinecraftServer server) {
        Request next=request;
        if(next==null || next.server!=server) {
            clearJobs();
            previous=null; cursor=null; publish(); return;
        }
        if(!sameWorld(previous,next)) clearJobs();
        if(Boolean.getBoolean("metalcraft.freezeHorizonSampling")) { clearJobs(); publish(); return; }
        if(!sameWorld(previous,next) || previous.x!=next.x || previous.z!=next.z || previous.near!=next.near || previous.far!=next.far) {
            cursor=new LodGenerationCursor(next.x,next.z,Math.max(0,next.near-2),next.far); exhausted=false; previous=next;
        }
        long deadline=System.nanoTime()+2_000_000L;
        var iterator=jobs.entrySet().iterator();
        while(iterator.hasNext() && completed.size()<64 && System.nanoTime()<deadline) {
            var job=iterator.next().getValue(); if(!job.future.isDone()) continue;
            try {
                var result=job.future.join();
                var chunk=result==null?null:result.orElse(null);
                if(sameWorld(job.request,next) && chunk instanceof LevelChunk full) {
                    completed.add(new Completed(next.epoch,HorizonSnapshot.capture(job.level,full,job.request.sampleSize))); columns++;
                }
            } catch(RuntimeException error) {
                failures++; com.mojang.logging.LogUtils.getLogger().warn("Horizon sample failed at {}: {}",job.pos,error.toString());
            } finally { job.release(); iterator.remove(); }
        }
        if(server.isPaused() || completed.size()>=48) { publish(); return; }
        ServerLevel level=server.getLevel(next.dimension); if(level==null) { publish(); return; }
        // Coalesce edits per column; retain the old model until its replacement is ready.
        // Leave half the slots for discovery so animated terrain cannot starve the horizon.
        int refreshes=0;
        for(var edits=dirty.iterator();edits.hasNext() && jobs.size()<16 && refreshes<8;) {
            long key=edits.next(); if(jobs.containsKey(key)) continue;
            edits.remove(); int x=ChunkPos.getX(key),z=ChunkPos.getZ(key);
            if(Math.abs((long)x-next.x)>next.far+2 || Math.abs((long)z-next.z)>next.far+2) continue;
            jobs.put(key,new Job(next,level,new ChunkPos(x,z))); refreshes++;
        }
        // Sampling and generation stay bounded independently of the requested horizon.
        for(int scanned=0;!exhausted && jobs.size()<16 && scanned<256;scanned++) {
            var column=cursor.next(); if(column==null) { exhausted=true; break; }
            long key=ChunkPos.pack(column.x(),column.z());
            if(jobs.containsKey(key) || NativeHorizon.hasColumn(next.epoch,key)) continue;
            var pos=new ChunkPos(column.x(),column.z());
            if(!level.getWorldBorder().isWithinBounds(pos)) continue;
            jobs.put(key,new Job(next,level,pos));
        }
        publish();
    }
    private static void publish() { var active=previous; var level=active==null?null:active.server.getLevel(active.dimension);
        stats=new Stats(jobs.size(),completed.size(),columns,failures,level==null?0:level.getChunkSource().getLoadedChunksCount()); }
}
