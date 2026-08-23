package dev.metalcraft.client.metal;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Names the tasks that Minecraft's main-thread queue runs, and says how long each kind took.
 *
 * <p>The largest remaining stall in this renderer is not in this renderer: the worst traversal frame
 * of a run spends 170 of its 175 ms inside {@code Minecraft.runAllTasks()}, against 0.015 ms of
 * Metal work. {@link MetalStallProbe} can say that much because {@code CLIENT_TASKS} brackets the
 * whole drain, but a phase that covers everything explains nothing - the queue is a
 * {@code Queue<Runnable>} that any thread may submit to, and the useful question is which submitters
 * dominate while terrain streams.
 *
 * <p>So this counts one level below the phase, keyed by the task's own class. Minecraft submits
 * almost all of them as lambdas, and a lambda's class names the method that created it, which is
 * exactly the attribution wanted. The trailing {@code /0x...} that the JVM appends to a lambda class
 * is dropped when reporting: it identifies the hidden class, varies between runs, and would make two
 * reports of the same call site look like different tasks.
 *
 * <p>Only tasks run on the render thread while a capture is active are counted, and only at the
 * outermost level - a task that blocks on {@code managedBlock} drains the queue again from inside
 * itself, and counting the nested tasks as well as their caller would charge the same nanoseconds to
 * both.
 */
public final class MetalTaskCensus {
	private static final int REPORTED_KINDS = 12;

	/**
	 * Keyed by class identity, so a lookup hashes a pointer and allocates nothing; the class names
	 * are resolved once, at the end of a phase, rather than on every task.
	 */
	private static final Map<Class<?>, long[]> COUNTS = new IdentityHashMap<>();

	private static int depth;
	private static long startedNs;

	private MetalTaskCensus() {
	}

	/** Clears the census and starts a new phase. */
	public static void reset() {
		COUNTS.clear();
		depth = 0;
		startedNs = 0L;
	}

	public static void begin() {
		if (!MetalStallProbe.isEnabled()) {
			return;
		}
		if (depth++ == 0) {
			startedNs = System.nanoTime();
		}
	}

	public static void end(final Class<?> taskClass) {
		if (!MetalStallProbe.isEnabled() || depth == 0) {
			return;
		}
		if (--depth != 0) {
			return;
		}
		long elapsed = System.nanoTime() - startedNs;
		long[] entry = COUNTS.get(taskClass);
		if (entry == null) {
			entry = new long[2];
			COUNTS.put(taskClass, entry);
		}
		entry[0] += elapsed;
		entry[1]++;
	}

	/**
	 * One kind of task, identified by the class of the runnable submitted - or one of the counters
	 * reported alongside them, for which {@code timed} is false and only the count means anything.
	 */
	public record TaskKind(String name, double totalMs, long count, boolean timed) {
		public String describe() {
			return this.timed
				? String.format(Locale.ROOT, "%s=%.3fms/%d", this.name, this.totalMs, this.count)
				: String.format(Locale.ROOT, "%s=%d", this.name, this.count);
		}

		public String toJson() {
			return this.timed
				? String.format(Locale.ROOT, "{\"task\":\"%s\",\"totalMs\":%.3f,\"count\":%d}",
					this.name, this.totalMs, this.count)
				: String.format(Locale.ROOT, "{\"counter\":\"%s\",\"count\":%d}", this.name, this.count);
		}
	}

	/**
	 * @return a total across every kind, followed by the kinds that took the most time this phase,
	 *     longest first, at most {@value #REPORTED_KINDS} of them
	 */
	public static List<TaskKind> take() {
		List<TaskKind> kinds = new ArrayList<>(COUNTS.size());
		long totalNanos = 0L;
		long totalCount = 0L;
		for (Map.Entry<Class<?>, long[]> entry : COUNTS.entrySet()) {
			totalNanos += entry.getValue()[0];
			totalCount += entry.getValue()[1];
			kinds.add(new TaskKind(describeClass(entry.getKey()), entry.getValue()[0] / 1_000_000.0,
				entry.getValue()[1], true));
		}
		kinds.sort((left, right) -> Double.compare(right.totalMs(), left.totalMs()));
		// The total leads, and is reported even when it is zero. A phase that drained an empty queue
		// and a phase whose census never ran are different findings, and a line that simply vanishes
		// when there is nothing to say cannot tell them apart - which is how the first run with this
		// instrument was nearly read as a broken probe rather than as a warm world with no meshing
		// left to do.
		List<TaskKind> reported = new ArrayList<>(1 + Math.min(REPORTED_KINDS, kinds.size()));
		reported.add(new TaskKind("total", totalNanos / 1_000_000.0, totalCount, true));
		reported.addAll(kinds.subList(0, Math.min(REPORTED_KINDS, kinds.size())));
		return List.copyOf(reported);
	}

	private static String describeClass(final Class<?> taskClass) {
		String name = taskClass.getName();
		int hidden = name.indexOf("/0x");
		return hidden < 0 ? name : name.substring(0, hidden);
	}
}
