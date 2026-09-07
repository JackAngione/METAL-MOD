package dev.metalcraft.client.shader.water;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Immutable per-vertex water data parallel to an unchanged terrain vertex buffer. */
public final class WaterVertexMetadata {
	public static final int FLOATS_PER_VERTEX = 8;
	public static final int STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;
	public static final int MATERIAL_NONE = 0;
	public static final int MATERIAL_WATER = 1;

	private final int vertexCount;
	private final float[] values;

	WaterVertexMetadata(final int vertexCount, final float[] values) {
		if (vertexCount < 0 || values.length != Math.multiplyExact(vertexCount, FLOATS_PER_VERTEX)) {
			throw new IllegalArgumentException("Water metadata size does not match vertex count");
		}
		this.vertexCount = vertexCount;
		this.values = values.clone();
	}

	public int vertexCount() {
		return this.vertexCount;
	}

	public int materialId(final int vertex) {
		return Math.round(this.value(vertex, 3));
	}

	public float normalX(final int vertex) {
		return this.value(vertex, 0);
	}

	public float normalY(final int vertex) {
		return this.value(vertex, 1);
	}

	public float normalZ(final int vertex) {
		return this.value(vertex, 2);
	}

	public float flowX(final int vertex) {
		return this.value(vertex, 4);
	}

	public float flowY(final int vertex) {
		return this.value(vertex, 5);
	}

	public float flowZ(final int vertex) {
		return this.value(vertex, 6);
	}

	/** Returns a new read-only native-order buffer suitable for one GPU upload. */
	public ByteBuffer bytes() {
		ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(this.vertexCount, STRIDE_BYTES))
			.order(ByteOrder.nativeOrder());
		bytes.asFloatBuffer().put(this.values);
		return bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
	}

	private float value(final int vertex, final int component) {
		if (vertex < 0 || vertex >= this.vertexCount) {
			throw new IndexOutOfBoundsException(vertex);
		}
		return this.values[vertex * FLOATS_PER_VERTEX + component];
	}

	public static final class Builder {
		private float[] values = new float[0];
		private int highestVertex;
		private boolean populated;

		public void putQuad(final int firstVertex, final float[] positions, final float flowX,
			final float flowY, final float flowZ) {
			if (firstVertex < 0 || positions.length != 12) {
				throw new IllegalArgumentException("Invalid water quad");
			}
			int requiredVertices = Math.addExact(firstVertex, 4);
			this.ensureCapacity(requiredVertices);
			float ax = positions[3] - positions[0];
			float ay = positions[4] - positions[1];
			float az = positions[5] - positions[2];
			float bx = positions[6] - positions[0];
			float by = positions[7] - positions[1];
			float bz = positions[8] - positions[2];
			float nx = ay * bz - az * by;
			float ny = az * bx - ax * bz;
			float nz = ax * by - ay * bx;
			float lengthSquared = nx * nx + ny * ny + nz * nz;
			if (Float.isFinite(lengthSquared) && lengthSquared > 1.0E-12F) {
				float inverseLength = (float)(1.0 / Math.sqrt(lengthSquared));
				nx *= inverseLength;
				ny *= inverseLength;
				nz *= inverseLength;
			} else {
				nx = 0.0F;
				ny = 1.0F;
				nz = 0.0F;
			}
			float safeFlowX = finiteOrZero(flowX);
			float safeFlowY = finiteOrZero(flowY);
			float safeFlowZ = finiteOrZero(flowZ);
			for (int vertex = firstVertex; vertex < requiredVertices; vertex++) {
				int offset = vertex * FLOATS_PER_VERTEX;
				if (this.values[offset + 3] != MATERIAL_NONE) {
					throw new IllegalStateException("Water metadata overlaps vertex " + vertex);
				}
				this.values[offset] = nx;
				this.values[offset + 1] = ny;
				this.values[offset + 2] = nz;
				this.values[offset + 3] = MATERIAL_WATER;
				this.values[offset + 4] = safeFlowX;
				this.values[offset + 5] = safeFlowY;
				this.values[offset + 6] = safeFlowZ;
			}
			this.highestVertex = Math.max(this.highestVertex, requiredVertices);
			this.populated = true;
		}

		public boolean isPopulated() {
			return this.populated;
		}

		public WaterVertexMetadata build(final int vertexCount) {
			if (vertexCount < this.highestVertex) {
				throw new IllegalStateException("Water metadata exceeds final vertex count");
			}
			return new WaterVertexMetadata(vertexCount,
				Arrays.copyOf(this.values, Math.multiplyExact(vertexCount, FLOATS_PER_VERTEX)));
		}

		private void ensureCapacity(final int vertices) {
			int required = Math.multiplyExact(vertices, FLOATS_PER_VERTEX);
			if (required > this.values.length) {
				int grown = Math.max(required, Math.max(32, this.values.length * 2));
				this.values = Arrays.copyOf(this.values, grown);
			}
		}

		private static float finiteOrZero(final float value) {
			return Float.isFinite(value) ? value : 0.0F;
		}
	}
}
