package dev.metalcraft.client.lod;

import dev.metalcraft.client.mixin.SpriteContentsAccessor;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * Average top-face colour of every block state, built on the client thread after resources load.
 *
 * <p>Constant tints are folded into the stored colour. Biome tints (grass, foliage, water) keep
 * their resolver and are applied per sample, so worker threads can colour terrain from a biome
 * alone. Instances are immutable and safe to read from any thread.
 */
public final class LodBlockColors {
    private static volatile @Nullable LodBlockColors current;

    private final int[] argb;
    private final ColorResolver[] resolvers;
    private final byte[] light;

    private LodBlockColors(int[] argb, ColorResolver[] resolvers, byte[] light) {
        this.argb = argb;
        this.resolvers = resolvers;
        this.light = light;
    }

    public static @Nullable LodBlockColors current() { return current; }

    /** Every state the same colour, without tints; for headless tests that have no block models. */
    static LodBlockColors uniform(int argb) {
        int count = Block.BLOCK_STATE_REGISTRY.size();
        int[] colors = new int[count];
        java.util.Arrays.fill(colors, argb);
        return new LodBlockColors(colors, new ColorResolver[count], new byte[count]);
    }

    public static void invalidate() { current = null; }

    /** Client thread only: requires loaded block models and a block atlas with retained sprite pixels. */
    public static LodBlockColors rebuild(Minecraft client) {
        var models = client.getModelManager().getBlockStateModelSet();
        var fluids = client.getModelManager().getFluidStateModelSet();
        BlockColors colors = client.getBlockColors();
        int count = Block.BLOCK_STATE_REGISTRY.size();
        int[] argb = new int[count];
        ColorResolver[] resolvers = new ColorResolver[count];
        byte[] light = new byte[count];
        Map<TextureAtlasSprite, Integer> averages = new IdentityHashMap<>();
        List<BlockStateModelPart> parts = new ArrayList<>();
        TintProbe probe = new TintProbe();
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            int id = Block.getId(state);
            light[id] = (byte)state.getLightEmission();
            TextureAtlasSprite sprite;
            BlockTintSource tint;
            FluidState fluid = state.getFluidState();
            if (state.getBlock() instanceof LiquidBlock && !fluid.isEmpty()) {
                var model = fluids.get(fluid);
                sprite = model.stillMaterial().sprite();
                tint = model.tintSource();
            } else {
                var model = models.get(state);
                parts.clear();
                model.collectParts(RandomSource.create(42L), parts);
                BakedQuad quad = topQuad(parts);
                if (quad != null) {
                    sprite = quad.materialInfo().sprite();
                    tint = quad.materialInfo().isTinted() ? colors.getTintSource(state, quad.materialInfo().tintIndex()) : null;
                } else {
                    sprite = model.particleMaterial().sprite();
                    tint = null;
                }
            }
            int base = averages.computeIfAbsent(sprite, LodBlockColors::average);
            if (tint != null) {
                probe.resolver = null;
                int constant = tint.colorInWorld(state, probe, BlockPos.ZERO);
                if (probe.resolver != null) resolvers[id] = probe.resolver;
                else base = multiply(base, constant);
            }
            argb[id] = base;
        }
        LodBlockColors built = new LodBlockColors(argb, resolvers, light);
        current = built;
        return built;
    }

    private static @Nullable BakedQuad topQuad(List<BlockStateModelPart> parts) {
        BakedQuad fallback = null;
        for (var part : parts) {
            var up = part.getQuads(Direction.UP);
            if (!up.isEmpty()) return up.getFirst();
            for (var quad : part.getQuads(null)) {
                if (quad.direction() == Direction.UP) return quad;
                if (fallback == null) fallback = quad;
            }
        }
        if (fallback != null) return fallback;
        for (var part : parts) for (Direction direction : Direction.values()) {
            var quads = part.getQuads(direction);
            if (!quads.isEmpty()) return quads.getFirst();
        }
        return null;
    }

    /** Alpha-weighted average of the first animation frame, so cut-out holes do not darken it. */
    private static int average(TextureAtlasSprite sprite) {
        var contents = sprite.contents();
        var image = ((SpriteContentsAccessor)contents).metalcraft$originalImage();
        int width = Math.min(contents.width(), image.getWidth()), height = Math.min(contents.height(), image.getHeight());
        long red = 0, green = 0, blue = 0, alpha = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int pixel = image.getPixel(x, y);
            int a = pixel >>> 24;
            red += (long)(pixel >> 16 & 255) * a;
            green += (long)(pixel >> 8 & 255) * a;
            blue += (long)(pixel & 255) * a;
            alpha += a;
        }
        if (alpha == 0) return 0x00FFFFFF;
        int pixels = Math.max(1, width * height);
        return (int)Math.min(255, alpha / pixels) << 24 | (int)(red / alpha) << 16 | (int)(green / alpha) << 8 | (int)(blue / alpha);
    }

    static int multiply(int argb, int tint) {
        int r = (argb >> 16 & 255) * (tint >> 16 & 255) / 255;
        int g = (argb >> 8 & 255) * (tint >> 8 & 255) / 255;
        int b = (argb & 255) * (tint & 255) / 255;
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** ARGB of the state's top face in the given biome. */
    public int color(BlockState state, Biome biome, int x, int z) {
        int id = Block.getId(state);
        if (id < 0 || id >= this.argb.length) return 0xFF7F7F7F;
        ColorResolver resolver = this.resolvers[id];
        return resolver == null ? this.argb[id] : multiply(this.argb[id], resolver.getColor(biome, x, z));
    }

    public int light(BlockState state) {
        int id = Block.getId(state);
        return id < 0 || id >= this.light.length ? 0 : this.light[id];
    }

    /** Records which biome colour a tint source asks for, instead of resolving it. */
    private static final class TintProbe implements BlockAndTintGetter {
        private @Nullable ColorResolver resolver;

        @Override public CardinalLighting cardinalLighting() { return CardinalLighting.DEFAULT; }
        @Override public int getBlockTint(BlockPos pos, ColorResolver color) { this.resolver = color; return -1; }
        @Override public LevelLightEngine getLightEngine() { return LevelLightEngine.EMPTY; }
        @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) { return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(); }
        @Override public FluidState getFluidState(BlockPos pos) { return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState(); }
        @Override public int getHeight() { return 0; }
        @Override public int getMinY() { return 0; }
    }
}
