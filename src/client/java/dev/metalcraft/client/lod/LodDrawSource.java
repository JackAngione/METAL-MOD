package dev.metalcraft.client.lod;

import org.jspecify.annotations.Nullable;

/** Per-draw annotations carried on {@code RenderPass.Draw} by a mixin. */
public interface LodDrawSource {
    /** The distant-terrain colour atlas this draw samples, or null for an ordinary section draw. */
    @Nullable LodTextureBinding metalcraft$lodTexture();

    void metalcraft$lodTexture(@Nullable LodTextureBinding binding);

    /** Squared camera distance used to order translucent native and distant draws together. */
    double metalcraft$sortDistance();

    void metalcraft$sortDistance(double distance);
}
