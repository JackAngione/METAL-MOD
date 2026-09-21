package dev.metalcraft.client.chunk;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * Single-writer lighting index with independently mutable snapshots. A copy shares
 * immutable groups/pages; subsequent writes detach only the changed group and page.
 * DataLayer payload copying remains the native lighting engine's responsibility.
 * Publish snapshots through the engine's existing volatile visibleSectionData field.
 */
public final class LightSectionSnapshots {
    private static final int BITS = 6, WIDTH = 1 << BITS, MASK = WIDTH - 1;
    private final Group[] groups;
    private Object owner = new Object();
    private int size;

    public LightSectionSnapshots() { groups = new Group[WIDTH]; }
    private LightSectionSnapshots(Group[] groups, int size) { this.groups = groups; this.size = size; }

    public LightSectionSnapshots copy() {
        // Neither branch may mutate pages bearing the old token after this fork.
        owner = new Object();
        return new LightSectionSnapshots(groups.clone(), size);
    }

    public DataLayer get(long key) {
        int hash = (int)HashCommon.mix(key);
        Group group = groups[(hash >>> BITS) & MASK];
        if (group == null) return null;
        Page page = group.pages[hash & MASK];
        return page == null ? null : page.values.get(key);
    }

    public DataLayer put(long key, DataLayer value) {
        java.util.Objects.requireNonNull(value);
        DataLayer previous = get(key);
        if (previous == value) return previous;
        int hash = (int)HashCommon.mix(key), groupIndex = (hash >>> BITS) & MASK, pageIndex = hash & MASK;
        Group group = writableGroup(groupIndex);
        Page page = group.pages[pageIndex];
        if (page == null) {
            page = new Page(owner, new Long2ObjectOpenHashMap<>(4));
            group.pages[pageIndex] = page; group.count++;
        } else if (page.owner != owner) {
            group.pages[pageIndex] = page = new Page(owner, page.values.clone());
        }
        page.values.put(key, value);
        if (previous == null) size++;
        return previous;
    }

    public DataLayer remove(long key) {
        DataLayer previous = get(key);
        if (previous == null) return null;
        int hash = (int)HashCommon.mix(key), groupIndex = (hash >>> BITS) & MASK, pageIndex = hash & MASK;
        Group group = writableGroup(groupIndex);
        Page page = group.pages[pageIndex];
        if (page.values.size() == 1) {
            group.pages[pageIndex] = null;
            if (--group.count == 0) groups[groupIndex] = null;
        } else {
            if (page.owner != owner) group.pages[pageIndex] = page = new Page(owner, page.values.clone());
            page.values.remove(key);
        }
        size--;
        return previous;
    }

    private Group writableGroup(int index) {
        Group group = groups[index];
        if (group == null) groups[index] = group = new Group(owner, new Page[WIDTH], 0);
        else if (group.owner != owner) groups[index] = group = new Group(owner, group.pages.clone(), group.count);
        return group;
    }

    public int size() { return size; }
    public int allocatedPages() {
        int result = 0;
        for (var group : groups) if (group != null) result += group.count;
        return result;
    }
    private static final class Group {
        final Object owner;
        final Page[] pages;
        int count;
        Group(Object owner, Page[] pages, int count) { this.owner = owner; this.pages = pages; this.count = count; }
    }
    private record Page(Object owner, Long2ObjectOpenHashMap<DataLayer> values) { }
}
