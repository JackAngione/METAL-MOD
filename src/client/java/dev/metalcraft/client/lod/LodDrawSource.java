package dev.metalcraft.client.lod;

import org.jspecify.annotations.Nullable;

/** Optional replacement; the original draw and its buffers remain available until submission. */
public interface LodDrawSource {
    LodLoadedRenderer.@Nullable Draw metalcraft$lodDraw();
    void metalcraft$lodDraw(LodLoadedRenderer.@Nullable Draw draw);
    void metalcraft$terrain(boolean distant);
    boolean metalcraft$isTerrain();
    boolean metalcraft$isDistant();
    void metalcraft$extended();
    boolean metalcraft$isExtended();
    void metalcraft$textureMip(int mip);
    int metalcraft$textureMip();
}
