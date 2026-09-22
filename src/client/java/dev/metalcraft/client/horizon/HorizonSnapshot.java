package dev.metalcraft.client.horizon;

import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Server-thread sample, detached before any renderer/resource work. */
final class HorizonSnapshot {
    private record Surface(float low,float high,Sample sample) { }
    final int x,z;
    private final Surface[] solid=new Surface[16],fluid=new Surface[16];
    private HorizonSnapshot(int x,int z) { this.x=x; this.z=z; }

    static HorizonSnapshot capture(ServerLevel level,LevelChunk chunk) {
        var result=new HorizonSnapshot(chunk.getPos().x(),chunk.getPos().z());
        var pos=new BlockPos.MutableBlockPos();
        for(int tz=0;tz<4;tz++) for(int tx=0;tx<4;tx++) {
            int sx=tx*4,sz=tz*4,highest=Integer.MIN_VALUE;
            // Keep the highest feature in each tile instead of occasionally missing
            // a tree/cliff by sampling only its center. The envelope below is vague.
            for(int dz=0;dz<4;dz++) for(int dx=0;dx<4;dx++) {
                int h=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,tx*4+dx,tz*4+dz);
                if(h>highest) { highest=h; sx=tx*4+dx; sz=tz*4+dz; }
            }
            int wx=result.x*16+sx,wz=result.z*16+sz;
            float solidLow=Float.POSITIVE_INFINITY,solidHigh=Float.NEGATIVE_INFINITY;
            float fluidLow=Float.POSITIVE_INFINITY,fluidHigh=Float.NEGATIVE_INFINITY;
            Sample solidSample=null,fluidSample=null;
            FluidState kind=null;
            for(int y=Math.min(level.getMaxY()-1,highest+1);y>=level.getMinY();y--) {
                pos.set(wx,y,wz); var state=chunk.getBlockState(pos); var fs=state.getFluidState();
                if(state.getRenderShape()==RenderShape.MODEL && (fs.isEmpty() || state.isSolidRender())) {
                    solidLow=y;
                    if(solidSample==null) { solidHigh=y+1; solidSample=sample(level,pos,state); }
                }
                if(fs.isEmpty() || kind!=null && !fs.getType().isSame(kind.getType())) continue;
                if(kind==null) kind=fs;
                fluidLow=y;
                if(fluidSample==null) {
                    boolean stacked=chunk.getFluidState(pos.above()).getType().isSame(fs.getType());
                    fluidHigh=y+(stacked?1:fs.getOwnHeight()); fluidSample=sample(level,pos,state);
                }
            }
            int i=tx+tz*4;
            if(solidSample!=null) result.solid[i]=new Surface(solidLow,solidHigh,solidSample);
            if(fluidSample!=null) result.fluid[i]=new Surface(fluidLow,fluidHigh,fluidSample);
        }
        return result;
    }
    private static Sample sample(ServerLevel level,BlockPos pos,BlockState state) {
        return new Sample(pos.immutable(),state,level.getBiome(pos).value(),
                Math.max(level.getBrightness(LightLayer.BLOCK,pos),level.getBrightness(LightLayer.BLOCK,pos.above()))
                        |Math.max(level.getBrightness(LightLayer.SKY,pos),level.getBrightness(LightLayer.SKY,pos.above()))<<4,
                level.getMinY(),level.getHeight());
    }
    HorizonColumn bake(BlockStateModelSet models,FluidStateModelSet fluids,BlockColors colors) {
        var cells=new HorizonColumn.Cell[16];
        for(int i=0;i<16;i++) cells[i]=new HorizonColumn.Cell(bake(solid[i],false,models,fluids,colors),
                bake(fluid[i],true,models,fluids,colors));
        return new HorizonColumn(x,z,cells);
    }
    private static HorizonColumn.Surface bake(Surface surface,boolean fluid,BlockStateModelSet models,FluidStateModelSet fluids,BlockColors colors) {
        if(surface==null) return null;
        var sample=surface.sample; var state=sample.state;
        var fm=fluid?fluids.get(state.getFluidState()):null;
        var sprite=fluid?fm.stillMaterial().sprite():models.getParticleMaterial(state).sprite();
        var tint=fluid?fm.tintSource():colors.getTintSource(state,0);
        int color=tint==null?-1:fluid?tint.colorInWorld(state,sample,sample.pos):tint.colorAsTerrainParticle(state,sample,sample.pos);
        int light=(sample.light&15)<<4|((sample.light>>4)&15)<<20;
        var material=new HorizonColumn.Material((sprite.getU0()+sprite.getU1())*.5f,(sprite.getV0()+sprite.getV1())*.5f,
                color|0xff000000,light,fluid&&state.getFluidState().is(FluidTags.WATER),fluid&&fm.layer().translucent());
        return new HorizonColumn.Surface(surface.low,surface.high,material);
    }
    private record Sample(BlockPos pos,BlockState state,Biome biome,int light,int minY,int height) implements BlockAndTintGetter {
        @Override public BlockState getBlockState(BlockPos ignored) { return state; }
        @Override public FluidState getFluidState(BlockPos ignored) { return state.getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos ignored) { return null; }
        @Override public CardinalLighting cardinalLighting() { return CardinalLighting.DEFAULT; }
        @Override public int getBlockTint(BlockPos position,ColorResolver resolver) { return resolver.getColor(biome,position.getX(),position.getZ()); }
        @Override public int getBrightness(LightLayer layer,BlockPos ignored) { return layer==LightLayer.SKY?(light>>4)&15:light&15; }
        @Override public int getRawBrightness(BlockPos ignored,int darkness) { return Math.max(light&15,((light>>4)&15)-darkness); }
        @Override public LevelLightEngine getLightEngine() { throw new UnsupportedOperationException("Detached horizon light"); }
        @Override public int getMinY() { return minY; }
        @Override public int getHeight() { return height; }
    }
}
