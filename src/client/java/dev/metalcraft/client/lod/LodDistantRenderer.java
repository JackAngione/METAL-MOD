package dev.metalcraft.client.lod;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.metalcraft.client.metal.MetalGpuDevice;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/** Distant opaque nodes enter the same Metal terrain/G-buffer pass and full-resolution depth attachment. */
public final class LodDistantRenderer {
    private static volatile @Nullable LodDistantCache cache;
    private static @Nullable Object level;
    private static @Nullable LodAtlas atlas;
    private static @Nullable MetalGpuDevice device;
    private static final LodMeshResidency<Mesh> residency=new LodMeshResidency<>(32L<<20);
    private record Owner(LodDistantNode.Key key,long version) { }
    private static final Map<LodMeshResidency.Key,Owner> owners=new HashMap<>();
    private static List<LodDistantCache.Candidate> selected=List.of();
    private static volatile LodSettings settings=LodSettings.defaults();
    private static long session, generation, draws, triangles, uploads, uploadBytes, failures;
    private static int frameDraws, frameSections;
    private static volatile Stats published=new Stats(null,0,0,0,0,0,0,0,0,0);
    public record Stats(LodDistantCache.Stats cache, long draws, long triangles, long uploads,
                        long uploadBytes, long uploadFailures, long gpuBytes, int residentNodes, int frameDraws, int frameSections) { }
    private record Layer(int layer,int indices) { }
    private record Mesh(List<GpuBuffer> buffers,List<Layer> layers) implements AutoCloseable {
        @Override public void close() { buffers.forEach(GpuBuffer::close); }
    }
    private LodDistantRenderer() { }
    public static int horizon() { return settings.enabled() && settings.diskCache() ? settings.horizonChunks() : 16; }
    public static Stats stats() { return published; }
    public static void clearCache() { var current=cache; if(current!=null) current.clear(); }
    public static void encoded(int indices) { draws++; triangles+=indices/3; }
    public static void worldChanged() {
        var previous=cache; cache=null;
        if(previous!=null) previous.close();
        level=null;
    }

    public static void beginFrame(@Nullable MetalGpuDevice metal,LodSettings next) {
        settings=next;
        if (!LodCapabilities.HORIZON_EXPERIMENTAL) return;
        Minecraft client=Minecraft.getInstance();
        boolean enabled=metal!=null && next.enabled() && next.diskCache() && next.horizonChunks()>16 && client.level!=null;
        if (!enabled || client.level!=level || atlas!=LodAtlas.current() || device!=metal) {
            if(cache!=null) cache.close();
            cache=null; selected=List.of(); owners.clear(); session++;
            residency.invalidate(key -> true);
            level=client.level; atlas=LodAtlas.current(); device=metal;
            if(enabled) {
                var server=client.getSingleplayerServer();
                String world=server!=null ? "local:"+server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize()
                        : client.getCurrentServer()!=null ? "server:"+client.getCurrentServer().ip.toLowerCase(Locale.ROOT) : null;
                if(world!=null) {
                    cache=new LodDistantCache(client.gameDirectory.toPath().resolve("metalcraft-lod"),world,
                            client.level.dimension().identifier().toString(),atlas.fingerprint(),client.getResourceManager());
                    generation=cache.generation();
                    // Already compiled sections need fresh received output after enabling/reopening the cache.
                    client.levelExtractor.allChanged();
                }
            }
        }
        if(metal==null) return;
        residency.beginFrame(metal.completedResourceSubmission());
        long budget=next.meshBudgetBytes(metal.metal().recommendedWorkingSetBytes(),metal.metal().currentAllocatedBytes(),residency.chargedBytes());
        residency.setBudget(Math.max(1,Math.min(32L<<20,budget/2)));
        var current=cache;
        if(current!=null) {
            if(generation!=current.generation()) {
                generation=current.generation(); session++; selected=List.of(); residency.invalidate(key -> true); owners.clear();
            }
            residency.invalidate(key -> {
                var owner=owners.get(key);
                return owner==null || current.version(owner.key())!=owner.version();
            });
            owners.keySet().removeIf(key -> !residency.contains(key));
        }
        published=new Stats(current==null?null:current.stats(),draws,triangles,uploads,uploadBytes,failures,
                residency.chargedBytes(),residency.residentCount(),frameDraws,frameSections);
        frameDraws=0; frameSections=0;
    }
    public static void dirty(int x,int y,int z) {
        var current=cache;
        if(current!=null) current.invalidate(new LodDistantNode.Key(0,x,y,z));
    }
    /** Invoked on compiler workers before MeshData ownership is released. No mutable level escapes. */
    public static void capture(SectionPos section,SectionCompiler.Results results,LodRevisionTracker.@Nullable Ticket ticket) {
        var current=cache;
        if(current==null || ticket==null || !ticket.current()) return;
        try {
            var layers=new ArrayList<LodDistantNode.Layer>(); int bytes=0;
            for(var layer:ChunkSectionLayer.values()) {
                if(layer.translucent()) continue;
                var mesh=results.renderedLayers.get(layer);
                if(mesh==null) continue;
                var state=mesh.drawState();
                if(state.primitiveTopology()!=PrimitiveTopology.QUADS || !state.format().equals(DefaultVertexFormat.BLOCK)
                        || state.format().getVertexSize()!=LodDistantNode.STRIDE || state.vertexCount()%4!=0) return;
                int length=Math.multiplyExact(state.vertexCount(),LodDistantNode.STRIDE);
                bytes+=length;
                if(bytes>LodDistantNode.MAX_BYTES) return;
                var buffer=mesh.vertexBuffer().duplicate();
                byte[] copy=new byte[length]; buffer.get(copy);
                if(ByteOrder.nativeOrder()!=ByteOrder.LITTLE_ENDIAN) throw new IllegalStateException("Unsupported host byte order");
                layers.add(new LodDistantNode.Layer(layer==ChunkSectionLayer.SOLID?0:1,copy));
            }
            current.capture(new LodDistantNode(new LodDistantNode.Key(0,section.x(),section.y(),section.z()),layers,0,1),ticket);
        } catch(IllegalArgumentException unsupported) {
            current.invalidate(new LodDistantNode.Key(0,section.x(),section.y(),section.z()));
        }
    }
    private static LodMeshResidency.Key key(LodDistantCache.Candidate c) {
        var k=c.node().key();
        return new LodMeshResidency.Key(new TerrainSnapshot.Key(session,"distant",k.x(),k.y(),k.z(),c.version(),k.level()),1);
    }
    public static ChunkSectionsToRender append(ChunkSectionsToRender original,CameraRenderState camera) {
        var current=cache;
        if(current==null || device==null || !settings.enabled()) return original;
        int loaded=Minecraft.getInstance().options.getEffectiveRenderDistance();
        current.view(new LodDistantCache.View(camera.pos.x,camera.pos.y,camera.pos.z,loaded,horizon(),(long)settings.diskBudgetMiB()<<20));
        var result=current.takeResult();
        if(result!=null && result.generation()==current.generation()) selected=result.nodes();
        long allowance=switch(settings.backgroundWork()) { case LOW -> 256L<<10; case BALANCED -> 1L<<20; case HIGH -> 2L<<20; };
        int maximum=switch(settings.backgroundWork()) { case LOW -> 2; case BALANCED -> 4; case HIGH -> 8; };
        var visible=new ArrayList<LodDistantCache.Candidate>();
        for(var c:selected) {
            var k=c.node().key();
            if(current.version(k)!=c.version() || !k.outside(camera.pos.x,camera.pos.z,(loaded+1)*16.0)
                    || k.distanceSquared(camera.pos.x,camera.pos.y,camera.pos.z)>=Math.pow(horizon()*16.0,2)) continue;
            if(!camera.cullFrustum.isVisible(new net.minecraft.world.phys.AABB(k.originX(),k.originY(),k.originZ(),
                    k.originX()+k.blocks(),k.originY()+k.blocks(),k.originZ()+k.blocks()))) continue;
            visible.add(c);
        }
        visible.sort(Comparator.comparingDouble(c -> c.node().key().distanceSquared(camera.pos.x,camera.pos.y,camera.pos.z)));
        long frameBytes=0;
        for(var c:visible) {
            var key=key(c); long bytes=c.node().bytes();
            if(!residency.contains(key) && maximum>0 && bytes<=allowance-frameBytes) {
                try {
                    if(residency.upload(key,bytes,() -> upload(c.node()))) {
                        owners.put(key,new Owner(c.node().key(),c.version())); uploads++; uploadBytes+=bytes; frameBytes+=bytes;
                    }
                } catch(RuntimeException failure) { failures++; }
                maximum--;
            }
        }
        var infos=new ArrayList<DynamicUniforms.ChunkSectionInfo>();
        var entries=new ArrayList<Map.Entry<LodDistantCache.Candidate,Mesh>>();
        for(var c:visible) {
            var key=key(c);
            if(!residency.contains(key) || current.version(c.node().key())!=c.version()) continue;
            var mesh=residency.use(key,device.reserveResourceSubmission());
            var k=c.node().key();
            infos.add(new DynamicUniforms.ChunkSectionInfo(new Matrix4f(camera.viewRotationMatrix),Math.toIntExact(k.originX()),
                    Math.toIntExact(k.originY()),Math.toIntExact(k.originZ()),1,original.textureView().getWidth(0),original.textureView().getHeight(0)));
            entries.add(Map.entry(c,mesh));
        }
        if(entries.isEmpty()) return original;
        GpuBufferSlice[] uniforms=RenderSystem.getDynamicUniforms().writeChunkSections(infos.toArray(DynamicUniforms.ChunkSectionInfo[]::new));
        int maxIndices=original.maxIndicesRequired();
        // Append to the existing opaque groups: shared full-resolution depth and the normal pack substitution.
        for(int i=0;i<entries.size();i++) {
            var entry=entries.get(i); var mesh=entry.getValue(); final int slot=i;
            for(int j=0;j<mesh.layers.size();j++) {
                var layer=mesh.layers.get(j);
                var draw=new RenderPass.Draw<GpuBufferSlice[]>(0,mesh.buffers.get(j),null,null,0,layer.indices(),0,
                        (ignored,uploader) -> uploader.upload("ChunkSection",uniforms[slot]));
                ((LodDrawSource)(Object)draw).metalcraft$extended();
                var target=layer.layer()==0?ChunkSectionLayer.SOLID:ChunkSectionLayer.CUTOUT;
                original.drawGroupsPerLayer().get(target).computeIfAbsent(Integer.MIN_VALUE,ignored -> new ArrayList<>()).add(draw);
                maxIndices=Math.max(maxIndices,layer.indices());
                frameDraws++;
            }
            frameSections+=entry.getKey().node().sections();
        }
        return new ChunkSectionsToRender(original.textureView(),original.drawGroupsPerLayer(),maxIndices,original.chunkSectionInfos());
    }
    private static Mesh upload(LodDistantNode node) {
        List<GpuBuffer> buffers=new ArrayList<>();
        try {
            for(var layer:node.layers()) buffers.add(device.createBuffer(() -> "Distant terrain parent",GpuBuffer.USAGE_VERTEX,layer.buffer()));
            // Retain only counts, not a second CPU copy of every uploaded node.
            return new Mesh(List.copyOf(buffers),node.layers().stream().map(layer -> new Layer(layer.layer(),layer.indices())).toList());
        } catch(RuntimeException | Error failure) { buffers.forEach(GpuBuffer::close); throw failure; }
    }
}
