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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * Average top-face colour of every block state, built on the client thread after resources load,
 * plus the block-atlas sprites textured distant cells draw with.
 *
 * <p>Constant tints are folded into the stored colour. Biome tints (grass, foliage, water) keep
 * their resolver and are applied per sample, so worker threads can colour terrain from a biome
 * alone. Textured cells use each state's top and side sprites, tinted the same way, and show
 * {@link #under} below the top block of a wall (dirt under grass). Instances are immutable and
 * safe to read from any thread.
 */
public final class LodBlockColors {
    private static volatile @Nullable LodBlockColors current;
    /** Floats per state in {@link #sprites}: top face u0, v0, u1, v1, then side face u0, v0, u1, v1. */
    static final int SPRITE_FLOATS = 8;
    private static final int WHITE = 0xFFFFFFFF;

    private final int[] argb;
    private final ColorResolver[] resolvers;
    private final byte[] light;
    /** Block-atlas bounds of each state's top and side sprites. */
    final float[] sprites;
    private final int[] topTints;
    private final int[] sideTints;
    private final ColorResolver[] sideResolvers;
    /** Whether a state draws a real upward face, so it can stand for the ground it covers. */
    private final boolean[] topFaces;
    private final int[] under;
    private final int[] deep;

    private LodBlockColors(int[] argb, ColorResolver[] resolvers, byte[] light, float[] sprites, int[] topTints, int[] sideTints,
                           ColorResolver[] sideResolvers, boolean[] topFaces, int[] under, int[] deep) {
        this.argb = argb;
        this.resolvers = resolvers;
        this.light = light;
        this.sprites = sprites;
        this.topTints = topTints;
        this.sideTints = sideTints;
        this.sideResolvers = sideResolvers;
        this.topFaces = topFaces;
        this.under = under;
        this.deep = deep;
    }

    public static @Nullable LodBlockColors current() { return current; }

    /** Every state the same colour, without tints; for headless tests that have no block models. */
    static LodBlockColors uniform(int argb) {
        int count = Block.BLOCK_STATE_REGISTRY.size();
        int[] colors = new int[count];
        java.util.Arrays.fill(colors, argb);
        // Every sprite is the whole texture, so textured meshes can be checked without an atlas.
        float[] sprites = new float[count * SPRITE_FLOATS];
        for (int id = 0; id < count; id++) {
            System.arraycopy(new float[]{0, 0, 1, 1, 0, 0, 1, 1}, 0, sprites, id * SPRITE_FLOATS, SPRITE_FLOATS);
        }
        int[] tints = new int[count];
        java.util.Arrays.fill(tints, WHITE);
        boolean[] topFaces = new boolean[count];
        java.util.Arrays.fill(topFaces, true);
        int[] under = new int[count];
        java.util.Arrays.setAll(under, id -> id);
        return new LodBlockColors(colors, new ColorResolver[count], new byte[count], sprites, tints, tints.clone(),
            new ColorResolver[count], topFaces, under, under.clone());
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
        float[] sprites = new float[count * SPRITE_FLOATS];
        int[] topTints = new int[count], sideTints = new int[count];
        ColorResolver[] sideResolvers = new ColorResolver[count];
        boolean[] topFaces = new boolean[count];
        int[] under = new int[count], deep = new int[count];
        Map<TextureAtlasSprite, Integer> averages = new IdentityHashMap<>();
        List<BlockStateModelPart> parts = new ArrayList<>();
        TintProbe probe = new TintProbe();
        int dirt = Block.getId(Blocks.DIRT.defaultBlockState());
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            int id = Block.getId(state);
            light[id] = (byte)state.getLightEmission();
            under[id] = showsDirtBelow(state) ? dirt : id;
            deep[id] = Block.getId(bedrock(state));
            TextureAtlasSprite sprite, sideSprite;
            BlockTintSource tint, sideTint;
            FluidState fluid = state.getFluidState();
            if (state.getBlock() instanceof LiquidBlock && !fluid.isEmpty()) {
                var model = fluids.get(fluid);
                sprite = sideSprite = model.stillMaterial().sprite();
                tint = sideTint = model.tintSource();
                topFaces[id] = true;
            } else {
                var model = models.get(state);
                parts.clear();
                model.collectParts(RandomSource.create(42L), parts);
                BakedQuad quad = topQuad(parts);
                if (quad != null) {
                    sprite = quad.materialInfo().sprite();
                    tint = tintSource(colors, state, quad);
                    topFaces[id] = quad.direction() == Direction.UP;
                } else {
                    sprite = model.particleMaterial().sprite();
                    tint = null;
                }
                BakedQuad side = sideQuad(parts);
                sideSprite = side == null ? sprite : side.materialInfo().sprite();
                sideTint = side == null ? tint : tintSource(colors, state, side);
            }
            int base = averages.computeIfAbsent(sprite, LodBlockColors::average);
            topTints[id] = sideTints[id] = WHITE;
            if (tint != null) {
                probe.resolver = null;
                int constant = tint.colorInWorld(state, probe, BlockPos.ZERO);
                if (probe.resolver != null) resolvers[id] = probe.resolver;
                else {
                    base = multiply(base, constant);
                    topTints[id] = 0xFF000000 | constant;
                }
            }
            if (sideTint != null) {
                probe.resolver = null;
                int constant = sideTint.colorInWorld(state, probe, BlockPos.ZERO);
                if (probe.resolver != null) sideResolvers[id] = probe.resolver;
                else sideTints[id] = 0xFF000000 | constant;
            }
            argb[id] = base;
            int offset = id * SPRITE_FLOATS;
            sprites[offset] = sprite.getU0();
            sprites[offset + 1] = sprite.getV0();
            sprites[offset + 2] = sprite.getU1();
            sprites[offset + 3] = sprite.getV1();
            sprites[offset + 4] = sideSprite.getU0();
            sprites[offset + 5] = sideSprite.getV0();
            sprites[offset + 6] = sideSprite.getU1();
            sprites[offset + 7] = sideSprite.getV1();
        }
        LodBlockColors built = new LodBlockColors(argb, resolvers, light, sprites, topTints, sideTints, sideResolvers, topFaces, under, deep);
        current = built;
        return built;
    }

    private static @Nullable BlockTintSource tintSource(BlockColors colors, BlockState state, BakedQuad quad) {
        return quad.materialInfo().isTinted() ? colors.getTintSource(state, quad.materialInfo().tintIndex()) : null;
    }

    /** Soil-topped blocks are dirt underneath; everything else is the same block all the way down. */
    private static boolean showsDirtBelow(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM) || state.is(Blocks.DIRT_PATH)
            || state.is(Blocks.FARMLAND);
    }

    /** What lies a few blocks down in natural terrain: rock under soil and sand, otherwise the same block. */
    private static BlockState bedrock(BlockState state) {
        if (showsDirtBelow(state) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)
            || state.is(Blocks.GRAVEL) || state.is(Blocks.SNOW_BLOCK)) return Blocks.STONE.defaultBlockState();
        if (state.is(Blocks.SAND)) return Blocks.SANDSTONE.defaultBlockState();
        if (state.is(Blocks.RED_SAND)) return Blocks.RED_SANDSTONE.defaultBlockState();
        return state;
    }

    /**
     * The first quad facing a side, preferring north. For grass that is the base side texture,
     * whose grass fringe is already coloured, rather than the tinted overlay drawn over it.
     */
    private static @Nullable BakedQuad sideQuad(List<BlockStateModelPart> parts) {
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            for (var part : parts) {
                var quads = part.getQuads(direction);
                if (!quads.isEmpty()) return quads.getFirst();
            }
            for (var part : parts) for (var quad : part.getQuads(null)) if (quad.direction() == direction) return quad;
        }
        return null;
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

    /** Whether distant cells can be textured: every state id fits a {@link LodSurface}. */
    boolean textured() { return this.argb.length <= LodSurface.MAX_STATES; }

    /** Tint multiplied into a state's top-face texture at a position, as opaque ARGB. */
    int topTint(int state, Biome biome, int x, int z) {
        ColorResolver resolver = this.resolvers[state];
        return resolver == null ? this.topTints[state] : 0xFF000000 | resolver.getColor(biome, x, z);
    }

    /** Tint multiplied into a state's side texture at a position, as opaque ARGB. */
    int sideTint(int state, Biome biome, int x, int z) {
        ColorResolver resolver = this.sideResolvers[state];
        return resolver == null ? this.sideTints[state] : 0xFF000000 | resolver.getColor(biome, x, z);
    }

    /** Whether the state draws an upward face; plants and torches do not, so the ground under them shows instead. */
    boolean topFace(BlockState state) {
        int id = Block.getId(state);
        return id >= 0 && id < this.topFaces.length && this.topFaces[id];
    }

    /** The state a wall shows below this one: dirt under grass, podzol and paths, otherwise the same state. */
    int under(int state) { return this.under[state]; }

    /** The state a wall shows more than a few blocks below this one: stone under soil, sandstone under sand. */
    int deep(int state) { return this.deep[state]; }

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
