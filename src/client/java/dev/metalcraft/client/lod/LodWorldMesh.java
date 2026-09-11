package dev.metalcraft.client.lod;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalDevice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** BLOCK vertices plus a 48-byte texture-repeat sidecar, uploaded only after residency admission. */
public final class LodWorldMesh implements AutoCloseable {
    public static final int VERTEX_BYTES = 28;
    public static final int METADATA_BYTES = 48;
    private final MetalBuffer vertices, metadata;
    private final int indexCount, originalIndexCount;

    private LodWorldMesh(MetalBuffer vertices, MetalBuffer metadata, int indexCount, int originalIndexCount) {
        this.vertices = vertices;
        this.metadata = metadata;
        this.indexCount = indexCount;
        this.originalIndexCount = originalIndexCount;
    }

    public MetalBuffer vertices() { return vertices; }
    public MetalBuffer metadata() { return metadata; }
    public int indexCount() { return indexCount; }
    public int originalIndexCount() { return originalIndexCount; }
    public static long bytes(LodBakedMesh.Simplified mesh) { return (long)mesh.quads() * 4 * (VERTEX_BYTES + METADATA_BYTES); }

    public static LodWorldMesh upload(MetalDevice device, LodBakedMesh.Simplified mesh) {
        if (!mesh.supported() || mesh.quads() < 1 || mesh.quads() >= mesh.originalQuads())
            throw new IllegalArgumentException("LOD replacement must reduce supported geometry");
        MetalBuffer vertices = device.createBuffer((long)mesh.quads() * 4 * VERTEX_BYTES, MetalBuffer.StorageMode.SHARED);
        MetalBuffer metadata = null;
        try {
            metadata = device.createBuffer((long)mesh.quads() * 4 * METADATA_BYTES, MetalBuffer.StorageMode.SHARED);
            try (var v = vertices.map(); var m = metadata.map()) { write(mesh, v.bytes(), m.bytes()); }
            return new LodWorldMesh(vertices, metadata, mesh.quads() * 6, mesh.originalQuads() * 6);
        } catch (RuntimeException | Error failure) {
            vertices.close();
            if (metadata != null) metadata.close();
            throw failure;
        }
    }

    /** Retains source winding, UV orientation, packed tint/AO/light and the original diagonal. */
    public static void write(LodBakedMesh.Simplified mesh, ByteBuffer vertices, ByteBuffer metadata) {
        vertices.order(ByteOrder.nativeOrder());
        metadata.order(ByteOrder.nativeOrder());
        for (var face : mesh.rectangles()) {
            if (face.width() == 1 && face.height() == 1) { original(face.source(), vertices, metadata); continue; }
            int ua = (face.axis() + 1) % 3, va = (face.axis() + 2) % 3;
            var source = face.source().vertices();
            float minU = Float.POSITIVE_INFINITY, minV = minU;
            for (var vertex : source) {
                minU = Math.min(minU, vertex.coordinate(ua));
                minV = Math.min(minV, vertex.coordinate(va));
            }
            LodBakedMesh.Vertex[] corners = new LodBakedMesh.Vertex[4];
            for (var vertex : source) corners[(int)(vertex.coordinate(ua) - minU) + 2 * (int)(vertex.coordinate(va) - minV)] = vertex;
            for (var vertex : source) {
                float u = (vertex.coordinate(ua) - minU) * face.width();
                float v = (vertex.coordinate(va) - minV) * face.height();
                float surfaceU = face.u() + u, surfaceV = face.v() + v;
                vertices.putFloat(face.axis() == 0 ? face.plane() : face.axis() == 1 ? surfaceV : surfaceU)
                        .putFloat(face.axis() == 1 ? face.plane() : face.axis() == 2 ? surfaceV : surfaceU)
                        .putFloat(face.axis() == 2 ? face.plane() : face.axis() == 0 ? surfaceV : surfaceU);
                vertices.putInt(vertex.color()).putFloat(u).putFloat(v).putInt(vertex.light());
                metadata.putFloat(corners[0].u()).putFloat(corners[0].v())
                        .putFloat(corners[1].u() - corners[0].u()).putFloat(corners[1].v() - corners[0].v());
                metadata.putFloat(corners[2].u() - corners[0].u()).putFloat(corners[2].v() - corners[0].v())
                        .putFloat(1).putFloat(0);
                var sprite = face.source().sprite();
                metadata.putFloat(sprite.u0()).putFloat(sprite.v0()).putFloat(sprite.u1()).putFloat(sprite.v1());
            }
        }
        for (var quad : mesh.unmerged()) original(quad, vertices, metadata);
    }

    private static void original(LodBakedMesh.Quad quad, ByteBuffer vertices, ByteBuffer metadata) {
        for (var vertex : quad.vertices()) {
            vertices.putFloat(vertex.x()).putFloat(vertex.y()).putFloat(vertex.z()).putInt(vertex.color())
                    .putFloat(vertex.u()).putFloat(vertex.v()).putInt(vertex.light());
            for (int i = 0; i < METADATA_BYTES / 4; i++) metadata.putFloat(0);
        }
    }

    @Override public void close() { vertices.close(); metadata.close(); }
}
