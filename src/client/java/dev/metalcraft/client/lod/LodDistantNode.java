package dev.metalcraft.client.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Immutable exact opaque surfaces. Parents batch received children without filling unknown space. */
public final class LodDistantNode {
    public static final int STRIDE = 28;
    public static final int MAX_BYTES = 4 << 20;
    public static final int MAX_LEVEL = 8;
    public record Key(int level, int x, int y, int z) {
        public Key {
            if (level < 0 || level > MAX_LEVEL || Math.abs((long)x) > 2_000_000
                    || Math.abs((long)y) > 2_000_000 || Math.abs((long)z) > 2_000_000)
                throw new IllegalArgumentException("Invalid distant node key");
        }
        public int blocks() { return 16 << level; }
        public long originX() { return (long)x * blocks(); }
        public long originY() { return (long)y * blocks(); }
        public long originZ() { return (long)z * blocks(); }
        public Key parent() { return new Key(level + 1, Math.floorDiv(x, 2), Math.floorDiv(y, 2), Math.floorDiv(z, 2)); }
        public Key child(int i) { return new Key(level - 1, x*2 + (i&1), y*2 + ((i>>1)&1), z*2 + ((i>>2)&1)); }
        public boolean contains(Key other) {
            if (level < other.level) return false;
            int divisor = 1 << (level - other.level);
            return Math.floorDiv(other.x, divisor) == x && Math.floorDiv(other.y, divisor) == y && Math.floorDiv(other.z, divisor) == z;
        }
        public boolean outside(double cx, double cz, double radius) {
            return originX() >= cx + radius || originX() + blocks() <= cx - radius
                    || originZ() >= cz + radius || originZ() + blocks() <= cz - radius;
        }
        public boolean outsideLoaded(double cx,double cz,int chunks) {
            // Minecraft owns a chunk-aligned (2r+1) square. A camera-centered safety
            // ring would leave artificial missing strips at the loaded/cache seam.
            double centerX=(Math.floor(cx/16)+.5)*16, centerZ=(Math.floor(cz/16)+.5)*16;
            return outside(centerX,centerZ,(chunks+.5)*16);
        }
        public double distanceSquared(double cx, double cy, double cz) {
            double dx = Math.max(Math.max(originX()-cx, cx-originX()-blocks()), 0);
            double dy = Math.max(Math.max(originY()-cy, cy-originY()-blocks()), 0);
            double dz = Math.max(Math.max(originZ()-cz, cz-originZ()-blocks()), 0);
            return dx*dx + dy*dy + dz*dz;
        }
    }
    public record Layer(int layer, byte[] vertices) {
        public Layer {
            if (layer < 0 || layer > 1 || vertices.length % (STRIDE*4) != 0 || vertices.length > MAX_BYTES)
                throw new IllegalArgumentException("Invalid opaque layer");
            vertices = vertices.clone();
        }
        @Override public byte[] vertices() { return vertices.clone(); }
        public ByteBuffer buffer() { return ByteBuffer.wrap(vertices).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }
        public int bytes() { return vertices.length; }
        public int indices() { return vertices.length / (STRIDE*4) * 6; }
    }
    private final Key key;
    private final List<Layer> layers;
    private final int children, sections;
    private final boolean complete;
    private final float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};

    public LodDistantNode(Key key, List<Layer> layers, int children, int sections) {
        this(key,layers,children,sections,true);
    }
    LodDistantNode(Key key,List<Layer> layers,int children,int sections,boolean complete) {
        this.key = java.util.Objects.requireNonNull(key);
        this.complete=complete;
        this.layers = List.copyOf(layers);
        this.children = children;
        this.sections = sections;
        if ((!complete && (key.level==0 || !layers.isEmpty())) || layers.size() > 2 || bytes() > MAX_BYTES || children < 0 || children > 255 || sections < 0
                || sections > 1 << 24 || (key.level == 0 && (children != 0 || sections != 1)))
            throw new IllegalArgumentException("Invalid distant node");
        int seen = 0;
        for (Layer layer : layers) {
            if ((seen & (1 << layer.layer)) != 0) throw new IllegalArgumentException("Duplicate layer");
            seen |= 1 << layer.layer;
            ByteBuffer b = layer.buffer();
            for (int i = 0; i < b.limit(); i += STRIDE) {
                for (int axis = 0; axis < 3; axis++) {
                    float p = b.getFloat(i + axis*4);
                    if (!Float.isFinite(p) || p < -16 || p > key.blocks() + 16)
                        throw new IllegalArgumentException("Invalid position");
                    bounds[axis]=Math.min(bounds[axis],p);
                    bounds[axis+3]=Math.max(bounds[axis+3],p);
                }
                for (int axis = 0; axis < 2; axis++) {
                    float uv = b.getFloat(i + 16 + axis*4);
                    if (!Float.isFinite(uv) || uv < 0 || uv > 1) throw new IllegalArgumentException("Invalid UV");
                }
            }
        }
    }
    public Key key() { return key; }
    public List<Layer> layers() { return layers; }
    public boolean complete() { return complete; }
    public int children() { return children; }
    public int sections() { return sections; }
    public int bytes() { return layers.stream().mapToInt(Layer::bytes).sum(); }
    public boolean drawable() { return bytes() > 0; }
    /** Actual received surface bounds, avoiding huge empty bounds in partially explored parents. */
    public double distanceSquared(double x, double y, double z) {
        double dx=Math.max(Math.max(key.originX()+bounds[0]-x,x-key.originX()-bounds[3]),0);
        double dy=Math.max(Math.max(key.originY()+bounds[1]-y,y-key.originY()-bounds[4]),0);
        double dz=Math.max(Math.max(key.originZ()+bounds[2]-z,z-key.originZ()-bounds[5]),0);
        return dx*dx+dy*dy+dz*dz;
    }

    /** Oversize parents retain a child manifest, so selection refines instead of truncating surfaces. */
    public static LodDistantNode parent(Key key, List<LodDistantNode> children) {
        if (key.level == 0) throw new IllegalArgumentException("A leaf has no children");
        int mask = 0, sections = 0, bytes = 0;
        boolean complete = true;
        for (var child : children) {
            if (!child.key.parent().equals(key)) throw new IllegalArgumentException("Unrelated child");
            int index = Math.floorMod(child.key.x,2) | Math.floorMod(child.key.y,2)<<1 | Math.floorMod(child.key.z,2)<<2;
            if ((mask & 1<<index) != 0) throw new IllegalArgumentException("Duplicate child");
            mask |= 1<<index;
            sections += child.sections;
            bytes += child.bytes();
            complete &= child.complete;
        }
        List<Layer> layers = new ArrayList<>();
        if (complete && bytes <= MAX_BYTES) for (int layer = 0; layer < 2; layer++) {
            final int selected = layer;
            int length = children.stream().flatMap(c -> c.layers.stream()).filter(l -> l.layer == selected).mapToInt(Layer::bytes).sum();
            if (length == 0) continue;
            ByteBuffer output = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
            for (var child : children) for (var source : child.layers) if (source.layer == layer) {
                int start = output.position();
                output.put(source.buffer());
                for (int offset = start; offset < output.position(); offset += STRIDE) {
                    output.putFloat(offset, output.getFloat(offset) + child.key.originX() - key.originX());
                    output.putFloat(offset+4, output.getFloat(offset+4) + child.key.originY() - key.originY());
                    output.putFloat(offset+8, output.getFloat(offset+8) + child.key.originZ() - key.originZ());
                }
            }
            layers.add(new Layer(layer, output.array()));
        }
        return new LodDistantNode(key, layers, mask, sections,complete && bytes<=MAX_BYTES);
    }
}
