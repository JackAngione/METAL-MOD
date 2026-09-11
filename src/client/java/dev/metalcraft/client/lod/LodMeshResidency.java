package dev.metalcraft.client.lod;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Render-owner cache. Retired meshes stay charged until a nonblocking GPU completion poll advances. */
public final class LodMeshResidency<R extends AutoCloseable> implements AutoCloseable {
    public record Key(TerrainSnapshot.Key terrain, int tier) {
        public Key {
            Objects.requireNonNull(terrain);
            if(tier<1||tier>4) throw new IllegalArgumentException("LOD tier must be 1–4");
        }
    }
    private final class Entry {
        final R resource;
        final long bytes;
        long lastUse;
        Entry(R resource,long bytes) { this.resource=resource; this.bytes=bytes; }
    }
    private final LinkedHashMap<Key,Entry> resident = new LinkedHashMap<>(16,.75f,true);
    private final ArrayDeque<Entry> retired = new ArrayDeque<>();
    private long budget;
    private long charged;
    private long completed;
    private boolean closed;

    public LodMeshResidency(long budget) {
        if(budget<1) throw new IllegalArgumentException("Positive mesh budget required");
        this.budget=budget;
    }

    /** Completion comes from an event/fence poll; this method never waits for the GPU. */
    public void beginFrame(long completedSubmission) {
        requireOpen();
        if(completedSubmission<completed) throw new IllegalArgumentException("Completion timeline moved backwards");
        completed=completedSubmission;
        var iterator=retired.iterator();
        while(iterator.hasNext()) {
            Entry entry=iterator.next();
            if(entry.lastUse<=completed) {
                dispose(entry);
                iterator.remove();
            }
        }
    }

    public void setBudget(long bytes) {
        requireOpen();
        if(bytes<1) throw new IllegalArgumentException("Positive mesh budget required");
        budget=bytes;
        makeRoom(0);
    }

    /** Calls the allocator only after room is available. Existing owners survive duplicate installs. */
    public boolean upload(Key key,long bytes,Supplier<R> allocator) {
        requireOpen();
        Objects.requireNonNull(key);
        Objects.requireNonNull(allocator);
        if(bytes<1) throw new IllegalArgumentException("Positive allocation size required");
        if(resident.containsKey(key)) return true;
        if(bytes>budget || !makeRoom(bytes)) return false;
        R resource=Objects.requireNonNull(allocator.get());
        resident.put(key,new Entry(resource,bytes));
        charged=Math.addExact(charged,bytes);
        return true;
    }

    /** The submission ID must be reserved before encoding any draw that borrows this mesh. */
    public R use(Key key,long submission) {
        requireOpen();
        if(submission<=completed) throw new IllegalArgumentException("Draw submission is already completed");
        Entry entry=resident.get(key);
        if(entry==null) return null;
        entry.lastUse=Math.max(entry.lastUse,submission);
        return entry.resource;
    }

    /** Edits, unloads and world changes remove draw ownership immediately, not memory accounting. */
    public void invalidate(Predicate<Key> invalid) {
        requireOpen();
        var iterator=resident.entrySet().iterator();
        while(iterator.hasNext()) {
            Map.Entry<Key,Entry> entry=iterator.next();
            if(invalid.test(entry.getKey())) {
                retire(entry.getValue());
                iterator.remove();
            }
        }
    }

    private boolean makeRoom(long bytes) {
        var iterator=resident.entrySet().iterator();
        while(charged>budget-bytes && iterator.hasNext()) {
            Entry entry=iterator.next().getValue();
            retire(entry);
            iterator.remove();
        }
        return charged<=budget-bytes;
    }

    private void retire(Entry entry) {
        if(entry.lastUse<=completed) dispose(entry);
        else retired.add(entry);
    }

    private void dispose(Entry entry) {
        try { entry.resource.close(); }
        catch(Exception error) { throw new IllegalStateException("Could not retire LOD mesh",error); }
        charged-=entry.bytes;
    }

    public long chargedBytes() { return charged; }
    public boolean contains(Key key) { requireOpen(); return resident.containsKey(key); }
    public int residentCount() { return resident.size(); }
    public int retiredCount() { return retired.size(); }
    private void requireOpen() { if(closed) throw new IllegalStateException("LOD residency is closed"); }

    /** Shutdown only. For a world change, invalidate all keys and continue polling the timeline. */
    @Override public void close() {
        if(closed) return;
        var residents=resident.values().iterator();
        while(residents.hasNext()) {
            dispose(residents.next());
            residents.remove();
        }
        var retirements=retired.iterator();
        while(retirements.hasNext()) {
            dispose(retirements.next());
            retirements.remove();
        }
        closed=true;
    }
}
