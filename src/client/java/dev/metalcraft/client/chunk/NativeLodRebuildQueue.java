package dev.metalcraft.client.chunk;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/** Extraction-thread bookkeeping; the native dispatcher owns and cancels the actual jobs. */
public final class NativeLodRebuildQueue {
    public enum Change { NONE, REFINE, COARSEN }
    private record Pending(Object mesh, int target, long started) { }
    private static final int CAPACITY = 128, COARSENING_LIMIT = 64;
    private static final long RETRY_NANOS = 10_000_000_000L;
    private final Map<Long, Pending> pending = new HashMap<>();

    public boolean isPending(long section) { return pending.containsKey(section); }

    public Change change(long section, Object mesh, int installed, int desired) {
        Pending request = pending.get(section);
        if (request != null && request.mesh == mesh) {
            // A finer request must replace a queued coarse snapshot, even if the
            // currently installed mesh already matches the desired tier.
            return desired < request.target ? Change.REFINE : Change.NONE;
        }
        return desired < installed ? Change.REFINE : desired > installed ? Change.COARSEN : Change.NONE;
    }

    public boolean hasCapacity(long section, Change change) {
        return change != Change.NONE && (pending.containsKey(section)
                || pending.size() < (change == Change.REFINE ? CAPACITY : COARSENING_LIMIT));
    }

    public void requested(long section, Object mesh, int target, long now) {
        pending.put(section, new Pending(mesh, target, now));
    }

    public void prune(long now, BiPredicate<Long, Object> stillInstalled) {
        pending.entrySet().removeIf(entry -> now - entry.getValue().started >= RETRY_NANOS
                || !stillInstalled.test(entry.getKey(), entry.getValue().mesh));
    }

    public void clear() { pending.clear(); }
}
