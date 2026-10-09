package dev.metalcraft.client.lod;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * One RGBA texture holding every resident node's 32×32 cell colours, bound as {@code Sampler0}
 * for all distant draws. Slot 0 is solid white for fluids, whose colour is in their vertices.
 *
 * <p>Slot writes are GPU-ordered blits, so a slot released by one node can be handed to another
 * at once: frames already submitted read the old texels before the new ones land.
 */
final class LodAtlas implements AutoCloseable {
    static final int SIZE = 2048;
    static final int SLOT = LodTile.CELLS;
    private static final int PER_ROW = SIZE / SLOT;
    static final int SLOTS = PER_ROW * PER_ROW;
    static final float WHITE_UV = (SLOT / 2 + 0.5F) / SIZE;

    final int size;
    private final int perRow;
    private final int slots;
    private final GpuTexture texture;
    private final GpuTextureView view;
    final LodTextureBinding binding;
    private final IntArrayList free = new IntArrayList(SLOTS);

    LodAtlas(int size) {
        this.size = size;
        this.perRow = size / SLOT;
        this.slots = this.perRow * this.perRow;
        var device = RenderSystem.getDevice();
        this.texture = device.createTexture("Metal Mod distant terrain colours",
            GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, size, size, 1, 1);
        this.view = device.createTextureView(this.texture);
        this.binding = new LodTextureBinding(this.view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        ByteBuffer white = MemoryUtil.memAlloc(SLOT * SLOT * 4);
        try {
            while (white.hasRemaining()) white.put((byte)-1);
            white.flip();
            this.upload(0, white);
        } finally {
            MemoryUtil.memFree(white);
        }
        for (int slot = this.slots - 1; slot > 0; slot--) this.free.add(slot);
    }

    int slotX(int slot) { return slot % this.perRow * SLOT; }

    int slotY(int slot) { return slot / this.perRow * SLOT; }

    float whiteUv() { return (SLOT / 2 + 0.5F) / this.size; }

    static int requiredSize(int detail, int renderDistance, int nativeDistance) {
        double horizon = renderDistance * 16.0;
        double distance = LodSettings.levelDistance(detail);
        long selected = 0;
        for (int level = 0; level <= LodSession.MAX_LEVEL; level++) {
            double width = SLOT * (1 << level);
            double inner = level == 0 ? 0 : distance * (1 << level);
            if (inner >= horizon) continue;
            double outer = level == LodSession.MAX_LEVEL ? horizon : Math.min(horizon, distance * (1 << (level + 1)));
            // A selected square lies outside the inner disk and within one diagonal of the outer disk.
            selected += (long)Math.ceil(Math.PI * (Math.pow(outer + Math.sqrt(2) * width, 2) - inner * inner) / (width * width));
        }
        double nativeWidth = SLOT * (1 << LodSession.SPLIT_LEVEL);
        double nativeRadius = Math.min(horizon, (nativeDistance + 2) * 16.0);
        selected += (long)Math.ceil(Math.PI * Math.pow(nativeRadius + Math.sqrt(2) * nativeWidth, 2) / (nativeWidth * nativeWidth));
        // Retain parent fallbacks and room for concurrent replacements while old nodes are evicted.
        long required = selected * 4 / 3 + 256;
        int size = SIZE;
        while ((long)(size / SLOT) * (size / SLOT) - 1 < required && size < 8192) size *= 2;
        return size;
    }

    /** A free slot, or -1 when every slot is in use. */
    int allocate() { return this.free.isEmpty() ? -1 : this.free.removeInt(this.free.size() - 1); }

    void release(int slot) {
        if (slot > 0) this.free.add(slot);
    }

    int available() { return this.free.size(); }

    void upload(int slot, ByteBuffer rgba) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(this.texture, rgba, 0, 0, slotX(slot), slotY(slot), SLOT, SLOT);
    }

    @Override
    public void close() {
        this.view.close();
        this.texture.close();
    }
}
