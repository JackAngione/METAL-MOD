package dev.metalcraft.client.lod;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/** Keeps only the best requests that can start this frame, without allocating per request. */
final class LodBuildQueue<T> {
    private final Object[] values;
    private final double[] priorities;
    private final long[] orders;
    private int limit, size;
    private long order;

    LodBuildQueue(int capacity) {
        this.values = new Object[capacity];
        this.priorities = new double[capacity];
        this.orders = new long[capacity];
    }

    void reset(int limit) {
        if (limit < 0 || limit > this.values.length) throw new IllegalArgumentException("Invalid build limit " + limit);
        Arrays.fill(this.values, 0, this.size, null);
        this.limit = limit;
        this.size = 0;
        this.order = 0;
    }

    void offer(T value, double priority) {
        long sequence = this.order++;
        if (this.limit == 0) return;
        if (this.size < this.limit) {
            int index = this.size++;
            this.put(index, value, priority, sequence);
            this.up(index);
        } else if (compare(priority, sequence, this.priorities[0], this.orders[0]) < 0) {
            this.put(0, value, priority, sequence);
            this.down(0);
        }
    }

    /** Best request first, with visitation order breaking equal priorities as a stable sort did. */
    @SuppressWarnings("unchecked")
    @Nullable T poll() {
        if (this.size == 0) return null;
        int best = 0;
        for (int index = 1; index < this.size; index++) {
            if (this.compare(index, best) < 0) best = index;
        }
        T result = (T)this.values[best];
        int last = --this.size;
        if (best < last) {
            this.put(best, this.values[last], this.priorities[last], this.orders[last]);
            if (best > 0 && this.compare(best, (best - 1) / 2) > 0) this.up(best);
            else this.down(best);
        }
        this.values[last] = null;
        return result;
    }

    private void up(int index) {
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (this.compare(index, parent) <= 0) break;
            this.swap(index, parent);
            index = parent;
        }
    }

    private void down(int index) {
        while (2 * index + 1 < this.size) {
            int child = 2 * index + 1;
            if (child + 1 < this.size && this.compare(child + 1, child) > 0) child++;
            if (this.compare(index, child) >= 0) break;
            this.swap(index, child);
            index = child;
        }
    }

    private int compare(int left, int right) {
        return compare(this.priorities[left], this.orders[left], this.priorities[right], this.orders[right]);
    }

    private static int compare(double leftPriority, long leftOrder, double rightPriority, long rightOrder) {
        int priority = Double.compare(leftPriority, rightPriority);
        return priority != 0 ? priority : Long.compare(leftOrder, rightOrder);
    }

    private void put(int index, Object value, double priority, long order) {
        this.values[index] = value;
        this.priorities[index] = priority;
        this.orders[index] = order;
    }

    private void swap(int left, int right) {
        Object value = this.values[left];
        double priority = this.priorities[left];
        long order = this.orders[left];
        this.put(left, this.values[right], this.priorities[right], this.orders[right]);
        this.put(right, value, priority, order);
    }
}
