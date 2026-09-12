package dev.metalcraft.client.lod;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Single-worker disk owner. No caller on the render thread may invoke filesystem methods. */
public final class LodDistantStore {
    private static final int MAGIC = 0x4d434c37, VERSION = 1;
    private static final int MAX_FILES = 16_384;
    private static final int MAX_FILE_BYTES = LodDistantNode.MAX_BYTES + 4096;
    private record Entry(long bytes, long accessed) { }
    private final Path directory;
    private final Map<LodDistantNode.Key, Entry> index = new HashMap<>();
    private long budget, diskBytes, corrupt, evicted;

    public LodDistantStore(Path root, String world, String dimension, String materials, long budget) throws IOException {
        if (budget < MAX_FILE_BYTES) throw new IllegalArgumentException("Cache budget too small");
        this.budget = budget;
        directory = root.resolve(digest(world)).resolve(digest(dimension + "\n" + materials + "\n" + VERSION));
        Files.createDirectories(directory);
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = file.getFileName().toString();
                if (name.endsWith(".tmp")) { Files.deleteIfExists(file); continue; }
                if (!name.endsWith(".lod")) continue;
                try {
                    var key = parse(name);
                    long size = Files.size(file);
                    if (size > MAX_FILE_BYTES || size < 32 || index.size() >= MAX_FILES) {
                        Files.deleteIfExists(file); continue;
                    }
                    index.put(key, new Entry(size, Files.getLastModifiedTime(file).toMillis()));
                    diskBytes += size;
                } catch (IllegalArgumentException invalid) { Files.deleteIfExists(file); }
            }
        }
        trim();
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String name(LodDistantNode.Key k) { return k.level()+"_"+k.x()+"_"+k.y()+"_"+k.z()+".lod"; }
    private static LodDistantNode.Key parse(String name) {
        String[] p = name.substring(0, name.length()-4).split("_");
        if (p.length != 4) throw new IllegalArgumentException("Bad node name");
        return new LodDistantNode.Key(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]));
    }
    public Set<LodDistantNode.Key> keys() { return Set.copyOf(index.keySet()); }
    public long diskBytes() { return diskBytes; }
    public long corruptEntries() { return corrupt; }
    public long evictions() { return evicted; }
    public Path directory() { return directory; }

    /** Remove ancestors before changing a leaf. A crash can lose cached coverage, never resurrect an old parent. */
    public void invalidate(LodDistantNode.Key leaf) throws IOException {
        remove(leaf);
        for (var k = leaf; k.level() < LodDistantNode.MAX_LEVEL;) { k = k.parent(); remove(k); }
    }
    public void putLeaf(LodDistantNode leaf) throws IOException {
        if (leaf.key().level() != 0) throw new IllegalArgumentException("Expected leaf");
        invalidate(leaf.key());
        write(leaf);
        for (var key = leaf.key(); key.level() < LodDistantNode.MAX_LEVEL;) {
            key = key.parent();
            List<LodDistantNode> children = new ArrayList<>();
            for (int child = 0; child < 8; child++) {
                var node = read(key.child(child));
                if (node != null) children.add(node);
            }
            if (children.isEmpty()) break;
            write(LodDistantNode.parent(key, children));
        }
        trim();
    }
    public LodDistantNode read(LodDistantNode.Key key) throws IOException {
        if (!index.containsKey(key)) return null;
        try {
            Path file = directory.resolve(name(key));
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) throw new IOException("Oversized cache entry");
            try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Cache version mismatch");
                var stored = new LodDistantNode.Key(in.readInt(),in.readInt(),in.readInt(),in.readInt());
                if (!stored.equals(key)) throw new IOException("Cache identity mismatch");
                int length = in.readInt();
                long checksum = in.readLong();
                if (length < 12 || length > MAX_FILE_BYTES) throw new IOException("Invalid expanded size");
                byte[] payload;
                try (var zipped = new InflaterInputStream(in)) {
                    payload = zipped.readNBytes(length + 1);
                    if (payload.length != length) throw new IOException("Truncated/oversized payload");
                }
                CRC32 crc = new CRC32(); crc.update(payload);
                if (crc.getValue() != checksum) throw new IOException("Cache checksum mismatch");
                var data = new DataInputStream(new ByteArrayInputStream(payload));
                int children = data.readInt(), sections = data.readInt(), count = data.readInt();
                if (count < 0 || count > 3) throw new IOException("Invalid layer count");
                List<LodDistantNode.Layer> layers = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    int layer = data.readInt(), bytes = data.readInt();
                    if (bytes < 0 || bytes > data.available()) throw new IOException("Invalid layer size");
                    layers.add(new LodDistantNode.Layer(layer, data.readNBytes(bytes)));
                }
                if (data.available() != 0) throw new IOException("Trailing node data");
                var node = new LodDistantNode(key,layers,children,sections);
                index.put(key,new Entry(size,System.currentTimeMillis()));
                return node;
            }
        } catch (IOException | IllegalArgumentException damaged) {
            corrupt++;
            invalidate(key);
            return null;
        }
    }
    private void write(LodDistantNode node) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(node.bytes()+64);
        try (var out = new DataOutputStream(payload)) {
            out.writeInt(node.children()); out.writeInt(node.sections()); out.writeInt(node.layers().size());
            for (var layer : node.layers()) { out.writeInt(layer.layer()); out.writeInt(layer.bytes()); out.write(layer.vertices()); }
        }
        byte[] bytes = payload.toByteArray();
        CRC32 crc = new CRC32(); crc.update(bytes);
        Path target = directory.resolve(name(node.key())), temp = directory.resolve(name(node.key())+".tmp");
        try {
            try (var channel = java.nio.channels.FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                var stream = java.nio.channels.Channels.newOutputStream(channel);
                var out = new DataOutputStream(stream);
                out.writeInt(MAGIC); out.writeInt(VERSION);
                out.writeInt(node.key().level()); out.writeInt(node.key().x()); out.writeInt(node.key().y()); out.writeInt(node.key().z());
                out.writeInt(bytes.length); out.writeLong(crc.getValue());
                DeflaterOutputStream deflated = new DeflaterOutputStream(stream);
                deflated.write(bytes); deflated.finish();
                channel.force(true);
            }
            long size = Files.size(temp);
            if (size > MAX_FILE_BYTES) throw new IOException("Compressed node exceeds bound");
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            Entry old = index.put(node.key(), new Entry(size,System.currentTimeMillis()));
            diskBytes += size - (old == null ? 0 : old.bytes);
        } finally { Files.deleteIfExists(temp); }
    }
    private void remove(LodDistantNode.Key key) throws IOException {
        Files.deleteIfExists(directory.resolve(name(key)));
        Entry old = index.remove(key);
        if (old != null) diskBytes -= old.bytes;
    }
    public void setBudget(long bytes) throws IOException {
        if (bytes < MAX_FILE_BYTES) throw new IllegalArgumentException("Cache budget too small");
        budget = bytes; trim();
    }
    private void trim() throws IOException {
        while (diskBytes > budget || index.size() > MAX_FILES) {
            var oldest = index.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().accessed)).orElseThrow().getKey();
            invalidate(oldest); evicted++;
        }
    }
    public void clear() throws IOException { for (var key : List.copyOf(index.keySet())) remove(key); }
}
