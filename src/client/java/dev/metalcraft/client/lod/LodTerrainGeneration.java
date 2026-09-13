package dev.metalcraft.client.lod;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Integrated-server-only generation. One nine-chunk neighborhood and one mesh job at a time. */
public final class LodTerrainGeneration {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{
        var thread=new Thread(r,"MetalCraft terrain generation"); thread.setDaemon(true); return thread;
    });
    // Loading only: never simulation, forced chunks, persisted tickets or dimension keepalive.
    static final TicketType TICKET=new TicketType(0,TicketType.FLAG_LOADING);
    private record Request(LodDistantCache cache,long generation,MinecraftServer server,ResourceKey<Level> dimension,
                           int x,int z,int loaded,int horizon,int diskBudgetMiB,LodSettings.Work work,
                           BlockStateModelSet models,BlockColors colors,CardinalLighting lighting) { }
    private static volatile Request requested;
    private static Session session; // server thread only
    private static CompletableFuture<?> draining=CompletableFuture.completedFuture(null);
    private static MinecraftServer drainingServer;
    public record Stats(boolean active,long columns,long sections,long failures,int tickets,int x,int z) { }
    private static volatile Stats published=new Stats(false,0,0,0,0,0,0);
    public static Stats stats() { return published; }
    private LodTerrainGeneration() { }

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(LodTerrainGeneration::request);
        ServerTickEvents.END_SERVER_TICK.register(LodTerrainGeneration::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{
            if(session!=null && session.request.server==server) stop();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{
            if(drainingServer==server) { drainingServer=null; draining=CompletableFuture.completedFuture(null); }
            var request=requested;
            if(request!=null && request.server==server) requested=null;
        });
    }

    private static void request(Minecraft client) {
        var cache=LodDistantRenderer.currentCache();
        var settings=LodDistantRenderer.frameSettings();
        var server=client.getSingleplayerServer();
        if(cache==null || server==null || client.level==null || client.player==null
                || !settings.enabled() || !settings.generateTerrain() || !settings.diskCache()
                || settings.horizonChunks()<=16 || !cache.stats().ready() || cache.stats().failures()!=0) {
            reset(); return;
        }
        requested=new Request(cache,cache.generation(),server,client.level.dimension(),
                Math.floorDiv(client.player.getBlockX(),16),Math.floorDiv(client.player.getBlockZ(),16),
                client.options.getEffectiveRenderDistance(),settings.horizonChunks(),settings.diskBudgetMiB(),settings.backgroundWork(),
                client.getModelManager().getBlockStateModelSet(),client.getBlockColors(),client.level.cardinalLighting());
    }

    /** Also revokes a request immediately during render-side world/cache teardown. */
    static void reset() {
        var previous=requested; requested=null;
        if(previous!=null && !previous.server.isStopped()) previous.server.execute(()-> {
            // Server tick events can be suspended by the single-player pause menu.
            // Main-thread tasks still drain there, so disabling releases tickets too.
            if(requested==null && session!=null && session.request.server==previous.server) stop();
        });
    }

    private static final class Session {
        Request request;
        LodGenerationCursor cursor;
        Job job;
        boolean exhausted;
        long columns,sections,failures,nextStart;
        Session(Request request) { this.request=request; resetCursor(); }
        void resetCursor() { cursor=new LodGenerationCursor(request.x,request.z,request.loaded,request.horizon); exhausted=false; }
    }
    private static final class Job {
        final Request request;
        final ServerLevel level;
        final int x,z;
        final List<ChunkPos> tickets=new ArrayList<>();
        final LodRevisionTracker revisions=new LodRevisionTracker(64);
        volatile boolean cancelled;
        CompletableFuture<LevelChunk[]> chunks;
        CompletableFuture<Boolean> mesh;
        int nextSection;
        int repairs;
        long retryAfter;
        Job(Request request,ServerLevel level,int x,int z) {
            this.request=request; this.level=level; this.x=x; this.z=z; nextSection=level.getMinSectionY();
        }
        void release(boolean cancel) {
            cancelled=true;
            if(cancel) {
                revisions.resources();
                // Moving/teleporting repeatedly must not queue a new neighborhood
                // behind each cancelled request. Let the one old request drain first.
                draining=CompletableFuture.allOf(chunks==null?CompletableFuture.completedFuture(null):chunks.handle((v,e)->null),
                        mesh==null?CompletableFuture.completedFuture(null):mesh.handle((v,e)->null));
                drainingServer=request.server;
            }
            for(var pos:tickets) level.getChunkSource().removeTicketWithRadius(TICKET,pos,0);
            tickets.clear();
        }
    }

    private static void stop() {
        if(session==null) return;
        if(session.job!=null) session.job.release(true);
        published=new Stats(false,session.columns,session.sections,session.failures,0,0,0);
        session=null;
    }
    private static boolean sameWorld(Request a,Request b) {
        return a.cache==b.cache && a.generation==b.generation && a.server==b.server
                && a.dimension.equals(b.dimension) && a.models==b.models;
    }
    private static void tick(MinecraftServer server) {
        Request next=requested;
        if(next==null || next.server!=server || next.generation!=next.cache.generation()) { stop(); return; }
        if(session==null || !sameWorld(session.request,next)) { stop(); session=new Session(next); }
        var s=session;
        if(s.request.x!=next.x || s.request.z!=next.z || s.request.loaded!=next.loaded
                || s.request.horizon!=next.horizon || s.request.diskBudgetMiB!=next.diskBudgetMiB) {
            if(s.job!=null) { s.job.release(true); s.job=null; }
            s.request=next; s.resetCursor();
        } else s.request=next;
        try {
            if(s.job!=null) advance(s);
            if(s.job==null && !s.exhausted && (drainingServer!=server || draining.isDone()) && System.nanoTime()>=s.nextStart && !server.isPaused()
                    && server.getAverageTickTimeNanos()<45_000_000L && next.cache.stats().queuedNodes()<64) {
                var level=server.getLevel(next.dimension);
                if(level==null) { stop(); return; }
                for(int scanned=0;scanned<64;scanned++) {
                    var column=s.cursor.next();
                    // Do not rebuild evicted columns forever when the horizon exceeds
                    // the disk budget. Movement/settings/cache changes start a new sweep.
                    if(column==null) { s.exhausted=true; break; }
                    if(next.cache.columnStored(column.x(),column.z(),level.getMinSectionY(),level.getMaxSectionY())) continue;
                    if(!level.getWorldBorder().isWithinBounds(new ChunkPos(column.x(),column.z()))) continue;
                    start(s,level,column);
                    break;
                }
            }
        } catch(RuntimeException error) {
            s.failures++;
            if(s.job!=null) { s.job.release(true); s.job=null; }
            s.nextStart=System.nanoTime()+5_000_000_000L;
            com.mojang.logging.LogUtils.getLogger().warn("Distant terrain generation skipped a chunk: {}",error.toString());
        }
        var job=s.job;
        published=new Stats(true,s.columns,s.sections,s.failures,job==null?0:job.tickets.size(),job==null?0:job.x,job==null?0:job.z);
    }

    private static void start(Session s,ServerLevel level,LodGenerationCursor.Column column) {
        var job=new Job(s.request,level,column.x(),column.z()); s.job=job;
        for(int z=-1;z<=1;z++) for(int x=-1;x<=1;x++) {
            var pos=new ChunkPos(job.x+x,job.z+z);
            level.getChunkSource().addTicketWithRadius(TICKET,pos,0); job.tickets.add(pos);
        }
        // The public method managed-blocks on the server thread. Calling it on our
        // worker schedules the request there but returns its future without waiting.
        var positions=List.copyOf(job.tickets);
        job.chunks=CompletableFuture.supplyAsync(()-> {
            var futures=new ArrayList<CompletableFuture<ChunkResult<ChunkAccess>>>();
            if(job.cancelled) return futures;
            for(var pos:positions) {
                futures.add(level.getChunkSource().getChunkFuture(pos.x(),pos.z(),ChunkStatus.FULL,true));
            }
            return futures;
        },WORKER).thenCompose(futures->CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored->futures.stream().map(f-> {
                    var chunk=f.getNow(null).orElse(null);
                    if(!(chunk instanceof LevelChunk full)) throw new IllegalStateException("Distant generation chunk unavailable");
                    return full;
                }).toArray(LevelChunk[]::new)));
        s.nextStart=System.nanoTime()+switch(s.request.work) { case LOW -> 2_000_000_000L; case BALANCED -> 1_000_000_000L; case HIGH -> 250_000_000L; };
    }

    private static void advance(Session s) {
        var job=s.job;
        if(job.mesh!=null) {
            if(!job.mesh.isDone()) return;
            if(job.mesh.join()) s.sections++;
            job.mesh=null;
        }
        if(!job.chunks.isDone()) return;
        var chunks=job.chunks.join(); // completed only; never wait for generation or a worker
        if(job.nextSection>=job.level.getMaxSectionY()) {
            if(job.request.cache.columnStored(job.x,job.z,job.level.getMinSectionY(),job.level.getMaxSectionY())) {
                job.release(false); s.columns++; s.job=null; return;
            }
            // Received light/neighbor invalidations may race a snapshot. Revisit
            // missing sections instead of calling a partially written column complete.
            if(System.nanoTime()<job.retryAfter) return;
            if(++job.repairs>4) throw new IllegalStateException("Generated column did not persist within repair budget");
            job.retryAfter=System.nanoTime()+1_000_000_000L;
            job.nextSection=job.level.getMinSectionY();
        }
        if(job.request.cache.stats().queuedNodes()>128 || job.request.cache.stats().queuedBytes()>LodDistantCache.QUEUE_BYTES/2) return;
        if(job.request.server.isPaused() || job.request.server.getAverageTickTimeNanos()>45_000_000L) return;
        int interval=switch(s.request.work) { case LOW -> 4; case BALANCED -> 2; case HIGH -> 1; };
        if(job.request.server.getTickCount()%interval!=0) return;
        var key=new LodDistantNode.Key(0,job.x,job.nextSection++,job.z);
        if(job.request.cache.storedCurrent(key)) return;
        long version=job.request.cache.version(key);
        var ticket=job.revisions.capture(key.x(),key.y(),key.z());
        var chunk=chunks[4];
        boolean empty=chunk.getSection(chunk.getSectionIndexFromSectionY(key.y())).hasOnlyAir();
        var snapshot=empty?null:new LodGeneratedSnapshot(job.level,chunks,key,job.request.lighting);
        job.mesh=CompletableFuture.supplyAsync(()-> {
            if(job.cancelled || job.request.cache.generation()!=job.request.generation) return false;
            var node=snapshot==null?new LodDistantNode(key,List.of(),0,1)
                    :LodGeneratedMesher.build(snapshot,job.request.models,job.request.colors);
            return !job.cancelled && job.request.cache.captureGenerated(node,ticket,version,job.request.generation);
        },WORKER);
    }
}
