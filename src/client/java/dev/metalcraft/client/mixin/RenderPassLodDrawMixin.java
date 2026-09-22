package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.systems.RenderPass;
import dev.metalcraft.client.lod.LodDrawSource;
import dev.metalcraft.client.lod.LodLoadedRenderer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderPass.Draw.class)
abstract class RenderPassLodDrawMixin implements LodDrawSource {
    @Unique private LodLoadedRenderer.@Nullable Draw metalcraft$lod;
    @Unique private boolean metalcraft$terrain, metalcraft$distant;
    @Unique private boolean metalcraft$extended;
    @Unique private int metalcraft$textureMip;
    @Unique private double metalcraft$sortDistance;
    @Override public void metalcraft$sortDistance(double distance) { metalcraft$sortDistance = distance; }
    @Override public double metalcraft$sortDistance() { return metalcraft$sortDistance; }
    @Override public void metalcraft$textureMip(int mip) { metalcraft$textureMip = mip; }
    @Override public int metalcraft$textureMip() { return metalcraft$textureMip; }
    @Override public void metalcraft$extended() { metalcraft$extended = true; }
    @Override public boolean metalcraft$isExtended() { return metalcraft$extended; }
    @Override public LodLoadedRenderer.@Nullable Draw metalcraft$lodDraw() { return metalcraft$lod; }
    @Override public void metalcraft$lodDraw(LodLoadedRenderer.@Nullable Draw draw) { metalcraft$lod = draw; }
    @Override public void metalcraft$terrain(boolean distant) { metalcraft$terrain = true; metalcraft$distant = distant; }
    @Override public boolean metalcraft$isTerrain() { return metalcraft$terrain; }
    @Override public boolean metalcraft$isDistant() { return metalcraft$distant; }
}
