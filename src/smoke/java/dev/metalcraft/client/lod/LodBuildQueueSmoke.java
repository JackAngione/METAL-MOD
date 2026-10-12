package dev.metalcraft.client.lod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Checks bounded scheduling against the previous complete, stable priority sort. */
public final class LodBuildQueueSmoke {
    private record Request(int order, double priority) { }

    public static void main(String[] args) {
        Random random = new Random(9371);
        LodBuildQueue<Request> queue = new LodBuildQueue<>(32);
        for (int round = 0; round < 1000; round++) {
            int limit = random.nextInt(33), count = random.nextInt(4097);
            queue.reset(limit);
            List<Request> reference = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                // Frequent ties exercise stable traversal order; descending runs replace roots.
                double priority = switch (round % 3) {
                    case 0 -> random.nextInt(20);
                    case 1 -> count - index;
                    default -> index;
                };
                Request request = new Request(index, priority);
                reference.add(request);
                queue.offer(request, priority);
            }
            reference.sort(Comparator.comparingDouble(Request::priority));
            for (int index = 0; index < Math.min(limit, count); index++) {
                if (queue.poll() != reference.get(index)) throw new AssertionError("Priority/order mismatch in round " + round);
            }
            if (queue.poll() != null) throw new AssertionError("Retained more requests than can start");
        }
        // Reset a nonempty queue when workers become saturated, then reuse its full capacity.
        queue.reset(32);
        queue.offer(new Request(0, 0), 0);
        queue.reset(0);
        queue.offer(new Request(1, 0), 0);
        if (queue.poll() != null) throw new AssertionError("Saturated workers should retain no requests");
        queue.reset(32);
        Request request = new Request(2, 1);
        queue.offer(request, 1);
        if (queue.poll() != request || queue.poll() != null) throw new AssertionError("Queue reuse failed");
        if (args.length > 0 && args[0].equals("benchmark")) benchmark();
        System.out.println("LOD build queue smoke passed (1000 stable-sort comparisons)");
    }

    private static void benchmark() {
        List<Request> input = new ArrayList<>(8192);
        Random random = new Random(31);
        for (int index = 0; index < 8192; index++) input.add(new Request(index, random.nextDouble()));
        LodBuildQueue<Request> queue = new LodBuildQueue<>(16);
        int checksum = 0;
        long sortNanos = 0, queueNanos = 0;
        for (int round = 0; round < 600; round++) {
            long start = System.nanoTime();
            List<Request> sorted = new ArrayList<>(input);
            sorted.sort(Comparator.comparingDouble(Request::priority));
            for (int index = 0; index < 16; index++) checksum += sorted.get(index).order;
            long sortedAt = System.nanoTime();
            queue.reset(16);
            for (Request request : input) queue.offer(request, request.priority);
            Request request;
            while ((request = queue.poll()) != null) checksum += request.order;
            long queuedAt = System.nanoTime();
            if (round >= 100) {
                sortNanos += sortedAt - start;
                queueNanos += queuedAt - sortedAt;
            }
        }
        System.out.printf("8192 requests, 16 slots: stable sort %.3f ms/frame; bounded selection %.3f ms/frame; checksum %d%n",
            sortNanos / 500e6, queueNanos / 500e6, checksum);
    }
}
