package dev.metalcraft.client.shader.water;

import java.nio.ByteOrder;

/** CPU-only fixture for the water sidecar's sparse indexing and packed GPU ABI. */
public final class WaterVertexMetadataSmoke {
	private WaterVertexMetadataSmoke() {
	}

	public static void main(final String[] arguments) {
		run();
	}

	public static void run() {
		WaterVertexMetadata.Builder builder = new WaterVertexMetadata.Builder();
		builder.putQuad(4, new float[]{
			0.0F, 2.0F, 0.0F,
			0.0F, 2.0F, 1.0F,
			1.0F, 2.0F, 1.0F,
			1.0F, 2.0F, 0.0F
		}, 0.25F, Float.NaN, -0.75F);
		WaterVertexMetadata metadata = builder.build(12);

		if (metadata.vertexCount() != 12 || metadata.materialId(0) != WaterVertexMetadata.MATERIAL_NONE
			|| metadata.materialId(3) != WaterVertexMetadata.MATERIAL_NONE
			|| metadata.materialId(8) != WaterVertexMetadata.MATERIAL_NONE
			|| metadata.materialId(11) != WaterVertexMetadata.MATERIAL_NONE) {
			throw new AssertionError("Sparse non-water gaps were not initialized to zero");
		}
		for (int vertex = 4; vertex < 8; vertex++) {
			if (metadata.materialId(vertex) != WaterVertexMetadata.MATERIAL_WATER) {
				throw new AssertionError("Water material identity was not repeated for its quad");
			}
			assertNear(metadata.normalX(vertex), 0.0F);
			assertNear(metadata.normalY(vertex), 1.0F);
			assertNear(metadata.normalZ(vertex), 0.0F);
			assertNear(metadata.flowX(vertex), 0.25F);
			assertNear(metadata.flowY(vertex), 0.0F);
			assertNear(metadata.flowZ(vertex), -0.75F);
			if (!Float.isFinite(metadata.normalX(vertex)) || !Float.isFinite(metadata.normalY(vertex))
				|| !Float.isFinite(metadata.normalZ(vertex)) || !Float.isFinite(metadata.flowX(vertex))
				|| !Float.isFinite(metadata.flowY(vertex)) || !Float.isFinite(metadata.flowZ(vertex))) {
				throw new AssertionError("Water normal or flow was not finite");
			}
		}

		var bytes = metadata.bytes();
		if (!bytes.isReadOnly() || bytes.order() != ByteOrder.nativeOrder()
			|| bytes.remaining() != metadata.vertexCount() * WaterVertexMetadata.STRIDE_BYTES) {
			throw new AssertionError("Packed water metadata buffer has the wrong contract");
		}
		int waterOffset = 4 * WaterVertexMetadata.STRIDE_BYTES;
		assertNear(bytes.getFloat(waterOffset + 4), 1.0F);
		assertNear(bytes.getFloat(waterOffset + 12), WaterVertexMetadata.MATERIAL_WATER);
		assertNear(bytes.getFloat(waterOffset + 16), 0.25F);
		for (int offset = 0; offset < 4 * WaterVertexMetadata.STRIDE_BYTES; offset += Float.BYTES) {
			assertNear(bytes.getFloat(offset), 0.0F);
		}
		System.out.println("Water metadata: packed ABI, mixed non-water gaps, finite face normals and flow passed");
	}

	private static void assertNear(final float actual, final float expected) {
		if (!Float.isFinite(actual) || Math.abs(actual - expected) > 1.0E-6F) {
			throw new AssertionError("Expected " + expected + ", got " + actual);
		}
	}
}
