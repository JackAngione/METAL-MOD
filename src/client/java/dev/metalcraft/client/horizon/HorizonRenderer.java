package dev.metalcraft.client.horizon;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.chunk.NativeLodSelection;
import dev.metalcraft.client.chunk.NativeShellMesher;
import dev.metalcraft.client.lod.LodDrawSource;
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.shader.water.WaterDrawSource;
import dev.metalcraft.client.shader.water.WaterMeshBinding;
import dev.metalcraft.client.shader.water.WaterVertexMetadata;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** Render-owned compact horizon: 64 columns per Metal mesh, independent of native chunk residency. */
public final class HorizonRenderer {
    private static final int GROUP=8, MAX_COLUMNS=262144;
    private static final long MAX_GPU_BYTES=256L<<20;
    private static final ConcurrentHashMap<Long,HorizonColumn> columns=new ConcurrentHashMap<>();
    private static final Map<Long,Group> groups=new HashMap<>();
    private static final List<Retired> retired=new ArrayList<>();
    private static final List<Group> selected=new ArrayList<>();
    private static volatile long epoch;
    private static Object world;
    private static BlockStateModelSet models;
    private static MetalGpuDevice device;
    private static long frame,bytes,uploads,draws;
    private static int lastX=Integer.MIN_VALUE,lastZ=Integer.MIN_VALUE,lastRadius;
    private static int detail=HorizonDetail.DEFAULT;
    public record Stats(int cachedColumns,int groups,int selectedGroups,int frameColumns,long gpuBytes,long uploads,long draws,
                        int nativeDistance,int horizon,HorizonStreamer.Stats streaming) { }
    private static volatile Stats stats=new Stats(0,0,0,0,0,0,0,0,0,new HorizonStreamer.Stats(0,0,0,0,0));
    private record Retired(Mesh mesh,long submission) { }
    private static final class Group {
        final int x,z;
        final HorizonColumn[] columns=new HorizonColumn[64];
        long revision,meshRevision=-1,mask,selectedFrame,lastUse;
        int cell;
        Mesh mesh;
        Group(int x,int z) { this.x=x; this.z=z; }
    }
    private record Layer(ChunkSectionLayer layer,GpuBuffer vertices,GpuBuffer indices,IndexType indexType,int count,
                         WaterMeshBinding water,MeshData.SortState sorting) implements AutoCloseable {
        @Override public void close() { vertices.close(); if(indices!=null) indices.close(); if(water!=null) water.close(); }
    }
    private static final class Mesh implements AutoCloseable {
        final List<Layer> layers; final long bytes;
        double sortX,sortY,sortZ;
        Mesh(List<Layer> layers,long bytes,CameraRenderState camera) { this.layers=layers; this.bytes=bytes; sortX=camera.pos.x;sortY=camera.pos.y;sortZ=camera.pos.z; }
        @Override public void close() { layers.forEach(Layer::close); }
    }
    private HorizonRenderer() { }
    public static Stats stats() { return stats; }
    public static HorizonColumn column(int x,int z) { return columns.get(ChunkPos.pack(x,z)); }
    public static HorizonStreamer.Stats streamingStats() { return HorizonStreamer.stats(); }
    static boolean hasColumn(long requestedEpoch,long key) { return requestedEpoch==epoch && columns.containsKey(key); }

    static void tick(Minecraft client) {
        if(!NativeHorizon.enabled() || client.level==null || client.player==null) { if(world!=null) reset(); return; }
        var nextModels=client.getModelManager().getBlockStateModelSet();
        int requestedDetail=MetalCraftConfig.horizonDetail();
        if(world!=client.level || models!=nextModels || detail!=requestedDetail) {
            reset(); world=client.level; models=nextModels; detail=requestedDetail;
        }
        int x=client.player.chunkPosition().x(),z=client.player.chunkPosition().z(),radius=NativeHorizon.horizon();
        if(x!=lastX || z!=lastZ || radius!=lastRadius) {
            // Only movement/settings changes visit retained columns. Never scan them per rendered frame.
            var entries=columns.entrySet().iterator();
            while(entries.hasNext()) {
                var entry=entries.next(); var column=entry.getValue();
                if(Math.abs((long)column.x()-x)>radius+2 || Math.abs((long)column.z()-z)>radius+2) {
                    entries.remove(); var group=groups.get(groupKey(column.x(),column.z()));
                    if(group!=null) { group.columns[slot(column.x(),column.z())]=null; group.revision++; }
                }
            }
            groups.values().removeIf(group->{
                for(var column:group.columns) if(column!=null) return false;
                retire(group); return true;
            });
            lastX=x; lastZ=z; lastRadius=radius;
        }
        for(int i=0;i<16 && !Boolean.getBoolean("metalcraft.freezeHorizonSampling");i++) {
            var completed=HorizonStreamer.poll(); if(completed==null) break;
            if(completed.epoch()!=epoch) continue;
            var snapshot=completed.snapshot();
            if(columns.size()>=MAX_COLUMNS && !columns.containsKey(ChunkPos.pack(snapshot.x,snapshot.z))) continue;
            if(Math.abs((long)snapshot.x-x)>radius+2 || Math.abs((long)snapshot.z-z)>radius+2) continue;
            var column=snapshot.bake(models,client.getModelManager().getFluidStateModelSet(),client.getBlockColors());
            columns.put(ChunkPos.pack(column.x(),column.z()),column);
            var group=groups.computeIfAbsent(groupKey(column.x(),column.z()),ignored->new Group(Math.floorDiv(column.x(),GROUP),Math.floorDiv(column.z(),GROUP)));
            group.columns[slot(column.x(),column.z())]=column; group.revision++;
        }
        HorizonStreamer.request(new HorizonStreamer.Request(epoch,client.getSingleplayerServer(),client.level.dimension(),x,z,NativeHorizon.innerDistance(),radius,
                HorizonDetail.sampleSize(detail)));
    }
    public static void reset() {
        HorizonStreamer.stop(); epoch++; world=null;models=null; columns.clear(); selected.clear();
        for(var group:groups.values()) retire(group);
        groups.clear();lastX=Integer.MIN_VALUE; lastZ=Integer.MIN_VALUE;
        stats=new Stats(0,0,0,0,bytes,uploads,draws,0,0,HorizonStreamer.stats());
    }
    public static void close(MetalGpuDevice closing) {
        if(device!=closing) return;
        reset(); for(var old:retired) old.mesh.close(); retired.clear();bytes=0;device=null;
    }
    private static void retire(Group group) {
        if(group.mesh!=null) { retired.add(new Retired(group.mesh,group.lastUse)); group.mesh=null; }
    }
    private static long groupKey(int x,int z) { return ChunkPos.pack(Math.floorDiv(x,GROUP),Math.floorDiv(z,GROUP)); }
    private static int slot(int x,int z) { return Math.floorMod(x,GROUP)+Math.floorMod(z,GROUP)*GROUP; }

    public static void prepare(CameraRenderState camera) {
        frame++; selected.clear();
        var current=MetalGpuDevices.current(); if(current==null) return; device=current;
        long completed=current.completedResourceSubmission();
        retired.removeIf(old->{ if(old.submission<=completed) { old.mesh.close(); bytes-=old.mesh.bytes; return true; } return false; });
        if(!NativeHorizon.enabled() || world!=Minecraft.getInstance().level) return;
        int cx=Math.floorDiv((int)Math.floor(camera.pos.x),16),cz=Math.floorDiv((int)Math.floor(camera.pos.z),16);
        int near=NativeHorizon.innerDistance(),far=NativeHorizon.horizon();
        var candidates=new ArrayList<Group>();
        for(var group:groups.values()) {
            if(group.mesh!=null && frame-group.selectedFrame>120) retire(group);
            double gx=group.x*128.0,gz=group.z*128.0;
            double dx=Math.max(0,Math.abs(camera.pos.x-(gx+64))-64),dz=Math.max(0,Math.abs(camera.pos.z-(gz+64))-64);
            if(dx*dx+dz*dz>far*far*256.0 || !camera.cullFrustum.isVisible(new AABB(gx,-2048,gz,gx+128,2048,gz+128))) continue;
            candidates.add(group);
        }
        candidates.sort(Comparator.comparingDouble(g->distance(g,camera)));
        var policy=NativeLodSelection.policy(Minecraft.getInstance().gameRenderer.mainCamera().getFov(),true,
                MetalCraftConfig.nativeLodReduction(),MetalCraftConfig.nativeQualityDistance());
        int budget=4,shown=0;
        for(var group:candidates) {
            long mask=0;
            for(int i=0;i<64;i++) {
                var column=group.columns[i]; if(column==null) continue;
                if(Math.abs((long)column.x()-cx)<=near && Math.abs((long)column.z()-cz)<=near) continue;
                mask|=1L<<i;
            }
            if(mask==0) continue;
            int cell=NativeShellMesher.grid(Math.max(2,policy.selectSquared(distance(group,camera),group.cell==0?2:group.cell)));
            // Bound even the gentlest reduction setting at very long distances.
            double range=distance(group,camera);
            cell=HorizonDetail.cellSize(detail,Math.max(cell,range>768.0*768?16:range>384.0*384?8:4));
            boolean shapeChanged=group.mask!=mask || group.cell!=cell;
            if(group.meshRevision!=group.revision || shapeChanged || group.mesh==null) {
                // Boundary changes cannot draw stale models over the native handoff area.
                if(budget>0) {
                    var mesh=build(group,mask,cell,camera);
                    if(bytes+mesh.bytes<=MAX_GPU_BYTES) {
                        retire(group);group.mesh=mesh; bytes+=mesh.bytes;group.mask=mask;group.cell=cell;group.meshRevision=group.revision;uploads++;
                    } else {
                        mesh.close();
                        // Free off-screen buffers before retrying, respecting in-flight GPU ownership.
                        for(var old:groups.values()) if(old!=group && old.selectedFrame<frame-1) retire(old);
                    }
                    budget--;
                }
                if(shapeChanged && (group.mask!=mask || group.cell!=cell)) continue;
            }
            if(group.mesh==null) continue;
            group.selectedFrame=frame; group.lastUse=current.reserveResourceSubmission(); selected.add(group); shown+=Long.bitCount(group.mask);
            resort(group,camera);
        }
        stats=new Stats(columns.size(),groups.size(),selected.size(),shown,bytes,uploads,draws,
                NativeHorizon.nativeDistance(Minecraft.getInstance().options.renderDistance().get()),far,HorizonStreamer.stats());
    }
    private static double distance(Group g,CameraRenderState c) { double dx=g.x*128.0+64-c.pos.x,dz=g.z*128.0+64-c.pos.z; return dx*dx+dz*dz; }
    public static boolean covers(long section) {
        if(!NativeHorizon.enabled()) return false;
        int x=SectionPos.x(section),z=SectionPos.z(section); var group=groups.get(groupKey(x,z));
        return group!=null && group.selectedFrame==frame && (group.mask&(1L<<slot(x,z)))!=0;
    }
    private static void resort(Group group,CameraRenderState camera) {
        var mesh=group.mesh;double dx=camera.pos.x-mesh.sortX,dy=camera.pos.y-mesh.sortY,dz=camera.pos.z-mesh.sortZ;
        if(dx*dx+dy*dy+dz*dz<1) return;
        for(var layer:mesh.layers) if(layer.sorting!=null) {
            try(var arena=new ByteBufferBuilder(layer.count*(layer.indexType==IndexType.SHORT?2:4))) {
                try(var data=layer.sorting.buildSortedIndexBuffer(arena,VertexSorting.byDistance((float)(camera.pos.x-group.x*128.0),(float)camera.pos.y,(float)(camera.pos.z-group.z*128.0)))) {
                    RenderSystem.getDevice().createCommandEncoder().writeToBuffer(layer.indices.slice(),data.byteBuffer());
                }
            }
        }
        mesh.sortX=camera.pos.x;mesh.sortY=camera.pos.y;mesh.sortZ=camera.pos.z;
    }
    public static ChunkSectionsToRender append(ChunkSectionsToRender original,CameraRenderState camera) {
        if(selected.isEmpty()) return original;
        var infos=new DynamicUniforms.ChunkSectionInfo[selected.size()];
        var view=new Matrix4f(camera.viewRotationMatrix);
        for(int i=0;i<infos.length;i++) { var group=selected.get(i);infos[i]=new DynamicUniforms.ChunkSectionInfo(view,group.x*128,0,group.z*128,1,original.textureView().getWidth(0),original.textureView().getHeight(0)); }
        var uniforms=RenderSystem.getDynamicUniforms().writeChunkSections(infos);
        int max=original.maxIndicesRequired();
        for(int i=0;i<selected.size();i++) {
            var group=selected.get(i);final int slot=i;
            for(var layer:group.mesh.layers) {
                int batch=Boolean.getBoolean("metalcraft.horizonSplitDraws")?192:layer.count;
                for(int first=0;first<layer.count;first+=batch) {
                int count=Math.min(batch,layer.count-first);
                int firstIndex=layer.layer==ChunkSectionLayer.TRANSLUCENT?layer.count-first-count:first;
                var draw=new RenderPass.Draw<GpuBufferSlice[]>(0,layer.vertices,layer.indices,layer.indexType,firstIndex,count,0,
                        (ignored,uploader)->uploader.upload("ChunkSection",uniforms[slot]));
                ((LodDrawSource)(Object)draw).metalcraft$sortDistance(distance(group,camera));
                if(layer.layer==ChunkSectionLayer.SOLID) ((LodDrawSource)(Object)draw).metalcraft$textureMip(group.cell>=4?2:1);
                if(layer.water!=null) ((WaterDrawSource)(Object)draw).metalcraft$waterMesh(layer.water);
                original.drawGroupsPerLayer().get(layer.layer).computeIfAbsent(Integer.MIN_VALUE,ignored->new ArrayList<>()).add(draw);
                max=Math.max(max,layer.count);draws++;
                }
            }
        }
        // Native rendering reverses each translucent list. Combine and sort so far
        // horizon water is composited before near native water, irrespective of map buckets.
        var transparent=original.drawGroupsPerLayer().get(ChunkSectionLayer.TRANSLUCENT);
        var ordered=new ArrayList<RenderPass.Draw<GpuBufferSlice[]>>();
        transparent.values().forEach(ordered::addAll);
        ordered.sort(Comparator.comparingDouble(d->((LodDrawSource)(Object)d).metalcraft$sortDistance()));
        transparent.clear();transparent.put(Integer.MIN_VALUE,ordered);
        return new ChunkSectionsToRender(original.textureView(),original.drawGroupsPerLayer(),max,original.chunkSectionInfos());
    }
    private static Mesh build(Group group,long mask,int cell,CameraRenderState camera) {
        var layers=new ArrayList<Layer>(); long bytes=0;
        try(var solidArena=new ByteBufferBuilder(4096);var waterArena=new ByteBufferBuilder(4096)) {
            var buffers=new BufferBuilder[]{new BufferBuilder(solidArena,PrimitiveTopology.QUADS,ChunkSectionLayer.SOLID.vertexFormat()),
                    new BufferBuilder(waterArena,PrimitiveTopology.QUADS,ChunkSectionLayer.TRANSLUCENT.vertexFormat())};
            int[] vertices={0,0};var water=new WaterVertexMetadata.Builder();float[] shifted=new float[12];
            var lighting=Minecraft.getInstance().level.cardinalLighting();
            for(int i=0;i<64;i++) {
                if((mask&(1L<<i))==0) continue;
                var column=group.columns[i]; int ox=(column.x()-group.x*8)*16,oz=(column.z()-group.z*8)*16;
                column.emit(cell,(positions,material,axis,sign)-> {
                    int layer=material.translucent()?1:0;
                    for(int v=0;v<4;v++) { shifted[v*3]=positions[v*3]+ox;shifted[v*3+1]=positions[v*3+1];shifted[v*3+2]=positions[v*3+2]+oz; }
                    if(layer==1 && material.water()) water.putQuad(vertices[layer],shifted,0,0,0);
                    var direction=axis==0?(sign>0?Direction.EAST:Direction.WEST):axis==1?(sign>0?Direction.UP:Direction.DOWN):(sign>0?Direction.SOUTH:Direction.NORTH);
                    float shade=lighting.byFace(direction);int color=material.color();
                    int shaded=0xff000000|(int)(((color>>16)&255)*shade)<<16|(int)(((color>>8)&255)*shade)<<8|(int)((color&255)*shade);
                    for(int v=0;v<4;v++) buffers[layer].addVertex(shifted[v*3],shifted[v*3+1],shifted[v*3+2]).setColor(shaded).setUv(material.u(),material.v()).setLight(material.light());
                    vertices[layer]+=4;
                });
            }
            for(int i=0;i<2;i++) {
                var mesh=buffers[i].build(); if(mesh==null) continue;
                try(mesh) {
                    var sorting=i==1?mesh.sortQuads(waterArena,VertexSorting.byDistance((float)(camera.pos.x-group.x*128.0),(float)camera.pos.y,(float)(camera.pos.z-group.z*128.0))):null;
                    GpuBuffer vb=null,ib=null;WaterMeshBinding wm=null;
                    try {
                        vb=device.createBuffer(()->"Horizon vertices",GpuBuffer.USAGE_VERTEX,mesh.vertexBuffer());
                        if(mesh.indexBuffer()!=null) ib=device.createBuffer(()->"Horizon sorted indices",GpuBuffer.USAGE_INDEX|GpuBuffer.USAGE_COPY_DST,mesh.indexBuffer());
                        if(i==1 && water.isPopulated()) wm=new WaterMeshBinding(water.build(vertices[i]));
                        bytes+=mesh.vertexBuffer().remaining()+(mesh.indexBuffer()==null?0:mesh.indexBuffer().remaining())+(wm==null?0:wm.vertexCount()*32L);
                        layers.add(new Layer(i==0?ChunkSectionLayer.SOLID:ChunkSectionLayer.TRANSLUCENT,vb,ib,ib==null?null:mesh.drawState().indexType(),mesh.drawState().indexCount(),wm,sorting));
                    } catch(RuntimeException|Error failure) { if(vb!=null)vb.close();if(ib!=null)ib.close();if(wm!=null)wm.close();throw failure; }
                }
            }
            return new Mesh(List.copyOf(layers),bytes,camera);
        } catch(RuntimeException|Error failure) { layers.forEach(Layer::close);throw failure; }
    }
}
