package dev.metalcraft.client.lod;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Server-thread copy; no mutable server chunk, light engine or client level escapes. */
final class LodGeneratedSnapshot implements BlockAndTintGetter {
    private static final int SIZE=18, COUNT=SIZE*SIZE*SIZE;
    final LodDistantNode.Key key;
    private final BlockState[] blocks=new BlockState[COUNT];
    private final Biome[] biomes=new Biome[COUNT];
    private final byte[] light=new byte[COUNT];
    private final CardinalLighting lighting;
    private final int minY,height;

    LodGeneratedSnapshot(ServerLevel level, LevelChunk[] chunks, LodDistantNode.Key key, CardinalLighting lighting) {
        this.key=key; this.lighting=lighting; minY=level.getMinY(); height=level.getHeight();
        var position=new BlockPos.MutableBlockPos();
        int i=0;
        for(int z=-1;z<=16;z++) for(int y=-1;y<=16;y++) for(int x=-1;x<=16;x++,i++) {
            position.set(key.x()*16+x,key.y()*16+y,key.z()*16+z);
            var chunk=chunks[(Math.floorDiv(x,16)+1)+(Math.floorDiv(z,16)+1)*3];
            blocks[i]=chunk.getBlockState(position);
            biomes[i]=level.getBiome(position).value();
            light[i]=(byte)(level.getBrightness(LightLayer.BLOCK,position)
                    | level.getBrightness(LightLayer.SKY,position)<<4);
        }
    }

    private int index(BlockPos pos) {
        int x=pos.getX()-key.x()*16+1,y=pos.getY()-key.y()*16+1,z=pos.getZ()-key.z()*16+1;
        return x<0 || y<0 || z<0 || x>=SIZE || y>=SIZE || z>=SIZE ? -1 : x+SIZE*(y+SIZE*z);
    }
    @Override public BlockState getBlockState(BlockPos pos) {
        int i=index(pos); return i<0?Blocks.AIR.defaultBlockState():blocks[i];
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public CardinalLighting cardinalLighting() { return lighting; }
    @Override public int getBlockTint(BlockPos pos,ColorResolver resolver) {
        int i=index(pos); if(i<0) i=COUNT/2;
        return resolver.getColor(biomes[i],pos.getX(),pos.getZ());
    }
    @Override public int getBrightness(LightLayer layer,BlockPos pos) {
        int i=index(pos); if(i<0) return 0;
        return layer==LightLayer.SKY ? (light[i]>>>4)&15 : light[i]&15;
    }
    @Override public int getRawBrightness(BlockPos pos,int darkness) {
        return Math.max(getBrightness(LightLayer.BLOCK,pos),getBrightness(LightLayer.SKY,pos)-darkness);
    }
    @Override public LevelLightEngine getLightEngine() {
        throw new UnsupportedOperationException("Generated terrain uses copied light values");
    }
    @Override public int getMinY() { return minY; }
    @Override public int getHeight() { return height; }
}
