package dev.metalcraft.client.lod;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Single-worker disk owner. No caller on the render thread may invoke filesystem methods. */
public final class LodDistantStore implements AutoCloseable {
    private static final int MAGIC = 0x4d434c37, VERSION = 2;
    private static final int MAX_FILES = 16_384;
    private static final int MAX_FILE_BYTES = LodDistantNode.MAX_BYTES + 4096;
    private record Entry(long bytes, long accessed) { }
    private final Path root, directory;
    private final java.nio.channels.FileChannel lockChannel;
    private final java.nio.channels.FileLock lock;
    private final Map<Path, Entry> inventory = new HashMap<>();
    private final Set<LodDistantNode.Key> index = new HashSet<>();
    private long budget, diskBytes, corrupt, evicted;
    private long peakDiskBytes, updateBatches, updateNanos, maxUpdateNanos;
    public record Diagnostics(long peakDiskPayloadBytes, long updateBatches, long updateNanos, long maxUpdateNanos) { }
    public Diagnostics diagnostics() { return new Diagnostics(peakDiskBytes, updateBatches, updateNanos, maxUpdateNanos); }

    public LodDistantStore(Path root, String world, String dimension, String materials, long budget) throws IOException {
        if (budget < MAX_FILE_BYTES) throw new IllegalArgumentException("Cache budget too small");
        this.budget = budget;
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        lockChannel = java.nio.channels.FileChannel.open(this.root.resolve("cache.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        java.nio.channels.FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (IOException | java.nio.channels.OverlappingFileLockException error) {
            lockChannel.close();
            throw new IOException("Distant cache is owned by another client", error);
        }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("Distant cache is owned by another client");
        }
        lock = acquired;
        directory = this.root.resolve(digest(world)).resolve(digest(dimension + "\n" + materials + "\n" + VERSION));
        try {
            Files.createDirectories(directory);
            // One inventory and one budget across every world, dimension and material generation.
            // Do not follow symlinks. Only owned cache files at the exact namespace depth are touched.
            try (var worlds=Files.newDirectoryStream(this.root)) {
                for (Path worldDirectory:worlds) {
                    if (!Files.isDirectory(worldDirectory,LinkOption.NOFOLLOW_LINKS)) continue;
                    try (var namespaces=Files.newDirectoryStream(worldDirectory)) {
                        for (Path namespace:namespaces) {
                            if (!Files.isDirectory(namespace,LinkOption.NOFOLLOW_LINKS)) continue;
                            scanNamespace(namespace);
                        }
                    }
                }
            }
            trim();
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }
    private void scanNamespace(Path namespace) throws IOException {
        // DirectoryStream tolerates ancestor removals without pre-statting deleted paths.
        try (var files=Files.newDirectoryStream(namespace)) {
            for (Path file:files) {
                if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) continue;
                String filename=file.getFileName().toString();
                if (filename.endsWith(".tmp")) { Files.deleteIfExists(file); continue; }
                if (!filename.endsWith(".lod")) continue;
                try {
                    var key=parse(filename);
                    long size=Files.size(file);
                    if (size>MAX_FILE_BYTES || size<36) {
                        invalidate(namespace,key); corrupt++; continue;
                    }
                    if (inventory.size()>=MAX_FILES) {
                        invalidate(namespace,key); evicted++; continue;
                    }
                    inventory.put(file,new Entry(size,Files.getLastModifiedTime(file).toMillis()));
                    if (namespace.equals(directory)) index.add(key);
                    diskBytes+=size;
                } catch(IllegalArgumentException invalid) { Files.deleteIfExists(file); }
            }
        }
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
    public Set<LodDistantNode.Key> keys() { return Set.copyOf(index); }
    public long diskBytes() { return diskBytes; }
    public long corruptEntries() { return corrupt; }
    public long evictions() { return evicted; }
    public Path directory() { return directory; }

    /** Revoke ancestor representations before changing a leaf. Readers must not mix revisions. */
    public void invalidate(LodDistantNode.Key leaf) throws IOException {
        invalidate(directory, leaf);
    }
    private void invalidate(Path namespace, LodDistantNode.Key leaf) throws IOException {
        var ancestors = new ArrayList<LodDistantNode.Key>();
        for (var k = leaf; k.level() < LodDistantNode.MAX_LEVEL;) { k = k.parent(); ancestors.add(k); }
        // Revoke highest ancestors first so interruption cannot resurrect a stale parent.
        for (var k : ancestors.reversed()) remove(namespace, k);
        remove(namespace, leaf);
    }
    public void putLeaf(LodDistantNode leaf) throws IOException { putLeaves(List.of(leaf)); }

    /** Rebuild each touched ancestor once per bounded batch, not once per arriving leaf. */
    public void putLeaves(List<LodDistantNode> leaves) throws IOException { updateLeaves(leaves,Set.of()); }

    public void updateLeaves(List<LodDistantNode> leaves,Set<LodDistantNode.Key> removed) throws IOException {
        long started = System.nanoTime();
        Set<LodDistantNode.Key> changed=new HashSet<>(removed), parents=new HashSet<>();
        for (var leaf:leaves) changed.add(leaf.key());
        if (leaves.size()>16 || changed.size()>16) throw new IllegalArgumentException("Distant update batch exceeds bound");
        for (var key:changed) {
            if (key.level()!=0) throw new IllegalArgumentException("Expected leaf");
            invalidate(key); parents.add(key.parent());
        }
        for (var leaf:leaves) write(leaf);
        for (int level=1; level<=LodDistantNode.MAX_LEVEL; level++) {
            Set<LodDistantNode.Key> next = new HashSet<>();
            for (var key : parents) {
                List<LodDistantNode> children = new ArrayList<>();
                for (int child=0; child<8; child++) {
                    var node=read(key.child(child));
                    if (node!=null) children.add(node);
                }
                if (!children.isEmpty()) write(LodDistantNode.parent(key,children));
                if (level<LodDistantNode.MAX_LEVEL) next.add(key.parent());
            }
            parents=next;
        }
        trim();
        long elapsed = System.nanoTime() - started;
        updateBatches++; updateNanos += elapsed; maxUpdateNanos = Math.max(maxUpdateNanos, elapsed);
    }
    public LodDistantNode read(LodDistantNode.Key key) throws IOException {
        if (!index.contains(key)) return null;
        try {
            Path file=directory.resolve(name(key));
            long size=Files.size(file);
            if (size>MAX_FILE_BYTES) throw new IOException("Oversized cache entry");
            var node=decode(key,Files.readAllBytes(file));
            inventory.put(file,new Entry(size,System.currentTimeMillis()));
            return node;
        } catch (IOException | IllegalArgumentException damaged) {
            corrupt++;
            invalidate(key);
            return null;
        }
    }
    /** The same bounded codec owns both queued captures and persisted nodes. No filesystem access. */
    static LodDistantNode decode(LodDistantNode.Key key, byte[] encoded) throws IOException {
        if (encoded.length>MAX_FILE_BYTES) throw new IOException("Oversized cache entry");
        var in=new DataInputStream(new ByteArrayInputStream(encoded));
        if (in.readInt()!=MAGIC || in.readInt()!=VERSION) throw new IOException("Cache version mismatch");
        var stored=new LodDistantNode.Key(in.readInt(),in.readInt(),in.readInt(),in.readInt());
        if (!stored.equals(key)) throw new IOException("Cache identity mismatch");
        int length=in.readInt(); long checksum=in.readLong();
        if (length<16 || length>MAX_FILE_BYTES) throw new IOException("Invalid expanded size");
        byte[] payload;
        try(var zipped=new InflaterInputStream(in)) {
            payload=zipped.readNBytes(length+1);
            if (payload.length!=length) throw new IOException("Truncated/oversized payload");
        }
        CRC32 crc=new CRC32(); crc.update(payload);
        if (crc.getValue()!=checksum) throw new IOException("Cache checksum mismatch");
        var data=new DataInputStream(new ByteArrayInputStream(payload));
        int children=data.readInt(), sections=data.readInt(), complete=data.readInt(), count=data.readInt();
        if (count<0 || count>2 || complete<0 || complete>1) throw new IOException("Invalid layer/manifest count");
        List<LodDistantNode.Layer> layers=new ArrayList<>();
        for(int i=0;i<count;i++) {
            int layer=data.readInt(), bytes=data.readInt();
            if (bytes<0 || bytes>data.available()) throw new IOException("Invalid layer size");
            layers.add(new LodDistantNode.Layer(layer,data.readNBytes(bytes)));
        }
        if (data.available()!=0) throw new IOException("Trailing node data");
        return new LodDistantNode(key,layers,children,sections,complete==1);
    }
    static byte[] encode(LodDistantNode node) throws IOException {
        var payload=new ByteArrayOutputStream(node.bytes()+64);
        try(var out=new DataOutputStream(payload)) {
            out.writeInt(node.children()); out.writeInt(node.sections()); out.writeInt(node.complete()?1:0); out.writeInt(node.layers().size());
            for(var layer:node.layers()) { out.writeInt(layer.layer()); out.writeInt(layer.bytes()); out.write(layer.vertices()); }
        }
        byte[] bytes=payload.toByteArray();
        CRC32 crc=new CRC32(); crc.update(bytes);
        var encoded=new ByteArrayOutputStream();
        var out=new DataOutputStream(encoded);
        out.writeInt(MAGIC); out.writeInt(VERSION);
        out.writeInt(node.key().level()); out.writeInt(node.key().x()); out.writeInt(node.key().y()); out.writeInt(node.key().z());
        out.writeInt(bytes.length); out.writeLong(crc.getValue());
        var compressor=new Deflater(Deflater.BEST_SPEED);
        try {
            var deflated=new DeflaterOutputStream(encoded,compressor);
            deflated.write(bytes); deflated.finish();
        } finally { compressor.end(); }
        if (encoded.size()>MAX_FILE_BYTES) throw new IOException("Compressed node exceeds bound");
        return encoded.toByteArray();
    }
    private void write(LodDistantNode node) throws IOException {
        byte[] bytes=encode(node);
        Path target=directory.resolve(name(node.key())), temp=directory.resolve(name(node.key())+".tmp");
        try {
            try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                var buffer=java.nio.ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // The complete temporary file coexists with the current inventory until rename.
            // Counts owned file payloads, excluding filesystem metadata/allocation rounding.
            peakDiskBytes = Math.max(peakDiskBytes, diskBytes + bytes.length);
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            Entry old=inventory.put(target,new Entry(bytes.length,System.currentTimeMillis()));
            index.add(node.key());
            diskBytes+=bytes.length-(old==null?0:old.bytes);
        } finally { Files.deleteIfExists(temp); }
    }
    private void remove(Path namespace, LodDistantNode.Key key) throws IOException {
        Path file = namespace.resolve(name(key));
        Files.deleteIfExists(file);
        Entry old = inventory.remove(file);
        if (namespace.equals(directory)) index.remove(key);
        if (old != null) diskBytes -= old.bytes;
    }
    public void setBudget(long bytes) throws IOException {
        if (bytes < MAX_FILE_BYTES) throw new IllegalArgumentException("Cache budget too small");
        budget = bytes; trim();
    }
    private void trim() throws IOException {
        peakDiskBytes = Math.max(peakDiskBytes, diskBytes);
        while (diskBytes > budget || inventory.size() > MAX_FILES) {
            Path oldest = inventory.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().accessed))
                    .orElseThrow().getKey();
            invalidate(oldest.getParent(), parse(oldest.getFileName().toString()));
            evicted++;
        }
    }
    /** Clear all namespaces under the same exclusive lock as reads and writes. */
    public void clear() throws IOException {
        for (Path file : List.copyOf(inventory.keySet())) Files.deleteIfExists(file);
        inventory.clear(); index.clear(); diskBytes = 0;
    }
    public void clearNamespace() throws IOException {
        for (var key : List.copyOf(index)) invalidate(key);
    }
    @Override public void close() throws IOException {
        try { if (lock.isValid()) lock.release(); }
        finally { lockChannel.close(); }
    }
}
