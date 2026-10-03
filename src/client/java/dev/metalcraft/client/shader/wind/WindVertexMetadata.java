package dev.metalcraft.client.shader.wind;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Four bytes per original terrain vertex: -1 for leaves, grass height above its root, or zero. */
public final class WindVertexMetadata {
    public static final int STRIDE_BYTES = Float.BYTES;
    public static final int NONE = 0, LEAVES = 1, GRASS = 2, UPPER_GRASS = 3;
    private final float[] values;

    private WindVertexMetadata(float[] values) { this.values = values; }
    public int vertexCount() { return this.values.length; }
    public float value(int vertex) { return this.values[vertex]; }
    public ByteBuffer bytes() {
        ByteBuffer bytes = ByteBuffer.allocate(this.values.length * STRIDE_BYTES).order(ByteOrder.nativeOrder());
        bytes.asFloatBuffer().put(this.values);
        return bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    public static int kind(BlockState state) {
        if (state.is(BlockTags.LEAVES)) return LEAVES;
        if (state.is(Blocks.TALL_GRASS) || state.is(Blocks.LARGE_FERN)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? UPPER_GRASS : GRASS;
        }
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.FERN) ? GRASS : NONE;
    }

    public static final class Builder {
        private float[] values = new float[0];
        private int highestVertex;

        public void put(int vertex, int kind, float modelHeight) {
            if (vertex < 0 || kind < LEAVES || kind > UPPER_GRASS || !Float.isFinite(modelHeight)) {
                throw new IllegalArgumentException("Invalid wind vertex");
            }
            int required = Math.addExact(vertex, 1);
            if (required > this.values.length) this.values = Arrays.copyOf(this.values,
                Math.max(required, Math.max(32, this.values.length * 2)));
            this.values[vertex] = kind == LEAVES ? -1 : Math.clamp(modelHeight + (kind == UPPER_GRASS ? 1 : 0), 0, 2);
            this.highestVertex = Math.max(this.highestVertex, required);
        }

        public WindVertexMetadata build(int vertexCount) {
            if (vertexCount < this.highestVertex) throw new IllegalStateException("Wind metadata exceeds terrain mesh");
            return new WindVertexMetadata(Arrays.copyOf(this.values, vertexCount));
        }
    }
}
