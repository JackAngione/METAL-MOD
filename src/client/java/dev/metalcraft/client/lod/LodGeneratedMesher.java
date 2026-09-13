package dev.metalcraft.client.lod;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.util.ArrayList;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;

/** Uses the current resource pack's actual opaque models, UVs, tint and copied lighting. */
final class LodGeneratedMesher {
    static LodDistantNode build(LodGeneratedSnapshot snapshot,BlockStateModelSet models,BlockColors colors) {
        try(var solid=new ByteBufferBuilder(1<<16); var cutout=new ByteBufferBuilder(1<<16)) {
            var builders=new BufferBuilder[]{new BufferBuilder(solid,PrimitiveTopology.QUADS,DefaultVertexFormat.BLOCK),
                    new BufferBuilder(cutout,PrimitiveTopology.QUADS,DefaultVertexFormat.BLOCK)};
            int[] quads={0};
            BlockQuadOutput output=(x,y,z,quad,instance)-> {
                var layer=quad.materialInfo().layer();
                if(layer.translucent()) return;
                if(++quads[0]>LodDistantNode.MAX_BYTES/(4*LodDistantNode.STRIDE))
                    throw new IllegalArgumentException("Generated section exceeds mesh budget");
                builders[layer==ChunkSectionLayer.SOLID?0:1].putBlockBakedQuad(x,y,z,quad,instance);
            };
            var renderer=new ModelBlockRenderer(true,true,colors);
            var pos=new BlockPos.MutableBlockPos();
            BlockModelLighter.enableCaching();
            try {
                for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                    pos.set(snapshot.key.x()*16+x,snapshot.key.y()*16+y,snapshot.key.z()*16+z);
                    var state=snapshot.getBlockState(pos);
                    if(state.getRenderShape()!=RenderShape.MODEL) continue;
                    renderer.tesselateBlock(output,x,y,z,snapshot,pos,state,models.get(state),state.getSeed(pos));
                }
                var layers=new ArrayList<LodDistantNode.Layer>();
                for(int i=0;i<2;i++) {
                    var mesh=builders[i].build();
                    if(mesh==null) continue;
                    try(mesh) {
                        byte[] bytes=new byte[mesh.drawState().vertexCount()*LodDistantNode.STRIDE];
                        mesh.vertexBuffer().duplicate().get(bytes);
                        layers.add(new LodDistantNode.Layer(i,bytes));
                    }
                }
                return new LodDistantNode(snapshot.key,layers,0,1);
            } finally { BlockModelLighter.clearCache(); }
        }
    }
}
