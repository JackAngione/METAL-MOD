package dev.metalcraft.client.chunk;

import java.util.function.Consumer;

/** Sparse array with native integer indices and no per-entry wrappers. Externally synchronized. */
public final class PagedSectionTable<T> {
    private static final int SHIFT = 8, PAGE_SIZE = 1 << SHIFT, MASK = PAGE_SIZE - 1;
    private final int size;
    private final Object[][] pages;
    private int allocatedPages, entries;

    public PagedSectionTable(int size) {
        if (size < 0) throw new IllegalArgumentException("Negative table size");
        this.size = size;
        this.pages = new Object[(int)(((long)size + MASK) >>> SHIFT)][];
    }
    @SuppressWarnings("unchecked")
    public T get(int index) {
        if (index < 0 || index >= size) return null;
        Object[] page = pages[index >>> SHIFT];
        return page == null ? null : (T)page[index & MASK];
    }
    public void put(int index, T value) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        Object[] page = pages[index >>> SHIFT];
        if (page == null) {
            if (value == null) return;
            pages[index >>> SHIFT] = page = new Object[Math.min(PAGE_SIZE, size - ((index >>> SHIFT) << SHIFT))];
            allocatedPages++;
        }
        Object previous = page[index & MASK];
        if (previous == null && value != null) entries++;
        else if (previous != null && value == null) entries--;
        page[index & MASK] = value;
    }
    @SuppressWarnings("unchecked")
    public void forEach(Consumer<? super T> action) {
        for (Object[] page : pages) if (page != null)
            for (Object value : page) if (value != null) action.accept((T)value);
    }
    public int size() { return size; }
    public int entries() { return entries; }
    public int allocatedPages() { return allocatedPages; }
}
