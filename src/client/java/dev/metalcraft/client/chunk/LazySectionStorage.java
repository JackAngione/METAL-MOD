package dev.metalcraft.client.chunk;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/** High-distance storage: retain native slot identities, materialize only accessed entries. */
public final class LazySectionStorage<T extends RotatingSectionStorage.Value> extends RotatingSectionStorage<T> {
    private final int radius, minY, maxY, height, width;
    private final ValueCreator<T> factory;
    private final PagedSectionTable<T> values;
    private SectionPos center = SectionPos.of(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
    private boolean positioned;
    private long repositionVisits;

    public LazySectionStorage(int radius, int minY, int maxY, ValueCreator<T> factory) {
        // The superclass owns no useful entries. Override every public storage operation.
        super(0, 0, 0, (index, node) -> null);
        if (radius < 0 || maxY < minY) throw new IllegalArgumentException("Invalid section volume");
        this.radius = radius; this.minY = minY; this.maxY = maxY;
        this.height = Math.addExact(Math.subtractExact(maxY,minY),1);
        this.width = Math.addExact(Math.multiplyExact(radius,2),1);
        this.values = new PagedSectionTable<>(Math.multiplyExact(Math.multiplyExact(width,width),height));
        this.factory = java.util.Objects.requireNonNull(factory);
    }
    @Override public int radius() { return radius; }
    @Override public int minY() { return minY; }
    @Override public int maxY() { return maxY; }
    @Override public int height() { return height; }
    @Override public int size() { return values.size(); }
    @Override public synchronized SectionPos centerSectionPos() { return center; }
    @Override public T getValueAt(BlockPos pos) { return getValue(SectionPos.asLong(pos)); }
    @Override public T getValue(long node) { return getValue(SectionPos.x(node),SectionPos.y(node),SectionPos.z(node)); }
    @Override public synchronized T getValue(int x, int y, int z) {
        if (!positioned || y < minY || y > maxY || Math.abs((long)x-center.x()) > radius
                || Math.abs((long)z-center.z()) > radius) return null;
        int index = (Math.floorMod(z,width)*height + y-minY)*width + Math.floorMod(x,width);
        T value = values.get(index);
        if (value == null) {
            long node = SectionPos.asLong(x,y,z);
            value = java.util.Objects.requireNonNull(factory.createValue(index,node));
            // Dirty-state construction leaves its node unset in the runtime jar.
            // Native eager storage positions it during the first camera move.
            if (value.getSectionNode() != node) value.setSectionNode(node);
            values.put(index,value);
        }
        return value;
    }
    @Override public synchronized boolean repositionCenter(SectionPos next) {
        if (positioned && next.equals(center)) return false;
        int lowX = next.x()-radius, lowZ = next.z()-radius;
        int highX = next.x()+radius, highZ = next.z()+radius;
        values.forEach(value -> {
            repositionVisits++;
            long previous = value.getSectionNode();
            int x = SectionPos.x(previous), z = SectionPos.z(previous);
            if (x >= lowX && x <= highX && z >= lowZ && z <= highZ) return;
            int movedX = lowX + Math.floorMod(x-lowX,width);
            int movedZ = lowZ + Math.floorMod(z-lowZ,width);
            if (x != movedX || z != movedZ) {
                // Native recycling cancels tasks/releases meshes and resets dirty state.
                // Never replace an existing slot object: visibility nodes keep references.
                value.setSectionNode(SectionPos.asLong(movedX,SectionPos.y(previous),movedZ));
            }
        });
        center = next; positioned = true;
        return true;
    }
    /** Iteration is for reset/cleanup. An absent entry has no mesh or tasks to release. */
    @Override public synchronized Iterator<T> iterator() { return snapshot().iterator(); }
    @Override public synchronized Spliterator<T> spliterator() { return snapshot().spliterator(); }
    @Override public synchronized void forEach(Consumer<? super T> action) { values.forEach(action); }
    private ArrayList<T> snapshot() {
        var result = new ArrayList<T>(values.entries()); values.forEach(result::add); return result;
    }
    public synchronized int residentEntries() { return values.entries(); }
    public synchronized int allocatedPages() { return values.allocatedPages(); }
    public synchronized long repositionVisits() { return repositionVisits; }
}
