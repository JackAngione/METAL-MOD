#!/usr/bin/env python3
"""Compare complete two-pass selector preparation with the pre-change implementation."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[4]
source = 'src/client/java/dev/metalcraft/client/lod/'
baseline = 'f6b5ae5'
old = subprocess.check_output(['git', 'show', f'{baseline}:{source}LodSelector.java'], cwd=root, text=True)
java = '''package dev.metalcraft.client.lod;
import java.util.*;
public class Probe {
    static volatile Object sink;
    record Fixture(int[] candidates, int[] previous, int[] available, int[][] edges) {}
    static Fixture fixture(int count, boolean sparse) {
        var random = new Random(1981);
        int[] candidates = new int[count], previous = new int[count], available = new int[count];
        int[][] edges = new int[count][];
        for (int i = 0; i < count; i++) {
            candidates[i] = sparse ? random.nextInt(6) - 1 : (i % 19 == 0 ? 0 : 4);
            previous[i] = sparse ? random.nextInt(6) - 1 : 4;
            available[i] = sparse ? random.nextInt(16) * 2 + 1 : 31;
            var neighbors = new ArrayList<Integer>();
            for (int step : new int[]{1, 16, 256}) if (i + step < count) neighbors.add(i + step);
            edges[i] = neighbors.stream().mapToInt(Integer::intValue).toArray();
        }
        return new Fixture(candidates, previous, available, edges);
    }
    static void oldWork(Fixture f) {
        sink = OldLodSelector.balance(f.candidates, f.edges);
        sink = OldLodSelector.resolveLoaded(f.candidates, f.previous, f.available, f.edges, true);
    }
    static void newWork(Fixture f) {
        var graph = LodSelector.adjacency(f.edges);
        sink = LodSelector.balance(f.candidates, graph);
        sink = LodSelector.resolveLoaded(f.candidates, f.previous, f.available, graph, true);
    }
    static void measure(String label, Runnable work) {
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId(), bytes = bean.getThreadAllocatedBytes(id), start = System.nanoTime();
        for (int i = 0; i < 500; i++) work.run();
        long elapsed = System.nanoTime() - start;
        System.out.printf(Locale.ROOT, "%s %.3f us/pair %.0f bytes/pair%n", label,
                elapsed / 500000.0, (bean.getThreadAllocatedBytes(id) - bytes) / 500.0);
    }
    public static void main(String[] args) {
        for (int count : new int[]{256, 4096}) for (boolean sparse : new boolean[]{false, true}) {
            var f = fixture(count, sparse);
            var graph = LodSelector.adjacency(f.edges);
            if (!Arrays.equals(OldLodSelector.balance(f.candidates, f.edges), LodSelector.balance(f.candidates, graph)))
                throw new AssertionError("Admission differs");
            for (boolean smoothing : new boolean[]{false, true}) {
                if (!Arrays.equals(OldLodSelector.resolveLoaded(f.candidates, f.previous, f.available, f.edges, smoothing),
                        LodSelector.resolveLoaded(f.candidates, f.previous, f.available, graph, smoothing)))
                    throw new AssertionError("Resolution differs");
            }
            System.out.println("Exact selector output matches: nodes=" + count + " sparse=" + sparse);
            for (int i = 0; i < 500; i++) { oldWork(f); newWork(f); }
            for (int repeat = 0; repeat < 3; repeat++) {
                if (repeat % 2 == 0) { measure("before", () -> oldWork(f)); measure("after", () -> newWork(f)); }
                else { measure("after", () -> newWork(f)); measure("before", () -> oldWork(f)); }
            }
        }
    }
}
'''
with tempfile.TemporaryDirectory(prefix='lod-selection-probe-') as temp:
    directory = Path(temp)
    (directory / 'OldLodSelector.java').write_text(old.replace('LodSelector', 'OldLodSelector'))
    for name in ['LodSelector.java', 'LodSettings.java']:
        (directory / name).write_text((root / source / name).read_text())
    (directory / 'Probe.java').write_text(java)
    subprocess.run(['javac', '-d', temp, *map(str, directory.glob('*.java'))], check=True)
    subprocess.run(['java', '-Xms256m', '-Xmx256m', '-cp', temp, 'dev.metalcraft.client.lod.Probe'], check=True)
