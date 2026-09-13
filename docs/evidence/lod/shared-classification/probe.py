#!/usr/bin/env python3
"""Short isolated CPU comparison with the pre-change mesher; no game/GPU claims."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[4]
source = 'src/client/java/dev/metalcraft/client/lod/LodBakedMesh.java'
baseline = '03b6be033ef6900dfa1317664c9439b666357e71'
old = subprocess.check_output(['git', 'show', f'{baseline}:{source}'], cwd=root, text=True)
factory = '''
    static TYPE fixtureNAME(int planes, boolean shaded) {
        var sprite = new TYPE.Sprite("stone", 0, 0, .5f, .5f);
        var quads = new ArrayList<TYPE.Quad>();
        for (int y = 0; y < planes; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int color = shaded && (x + z) % 5 == 0 ? 0xff112233 : -1;
            quads.add(new TYPE.Quad(sprite, List.of(
                new TYPE.Vertex(x, y, z, 0, 0, color, 240),
                new TYPE.Vertex(x, y, z + 1, .5f, 0, -1, 240),
                new TYPE.Vertex(x + 1, y, z + 1, .5f, .5f, -1, 240),
                new TYPE.Vertex(x + 1, y, z, 0, .5f, -1, 240))));
        }
        return new TYPE(quads);
    }
'''
java = '''package dev.metalcraft.client.lod;
import java.util.*;
public class Probe {
    static volatile Object sink;
    static List<OldLodBakedMesh.Simplified> oldBuild(OldLodBakedMesh mesh) {
        var fourth = mesh.simplify(4);
        return List.of(mesh.simplify(1), mesh.simplify(2), mesh.simplify(3), fourth);
    }
    static List<LodBakedMesh.Simplified> newBuild(LodBakedMesh mesh) {
        var classified = mesh.classify();
        var fourth = classified.simplify(4);
        return List.of(classified.simplify(1), classified.simplify(2), classified.simplify(3), fourth);
    }
    static void measure(String label, Runnable work, int iterations) {
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long bytes = bean.getThreadAllocatedBytes(id), start = System.nanoTime();
        for (int i = 0; i < iterations; i++) work.run();
        long nanos = System.nanoTime() - start;
        System.out.printf(Locale.ROOT, "%s %.3f us/build %.0f bytes/build%n", label,
                nanos / (iterations * 1000.0), (bean.getThreadAllocatedBytes(id) - bytes) / (double) iterations);
    }
    public static void main(String[] args) {
        for (int planes : new int[] {1, 16}) for (boolean shaded : new boolean[] {false, true}) {
            var oldMesh = fixtureOld(planes, shaded);
            var newMesh = fixtureNew(planes, shaded);
            if (!oldBuild(oldMesh).toString().equals(newBuild(newMesh).toString()))
                throw new AssertionError("Tier output differs from baseline");
            System.out.println("Exact four-tier baseline output matches: quads=" + planes * 256 + " shaded=" + shaded);
            Runnable oldWork = () -> sink = oldBuild(oldMesh), newWork = () -> sink = newBuild(newMesh);
            for (int i = 0; i < 100; i++) { oldWork.run(); newWork.run(); }
            for (int repeat = 0; repeat < 3; repeat++) {
                if (repeat % 2 == 0) { measure("before", oldWork, 100); measure("after", newWork, 100); }
                else { measure("after", newWork, 100); measure("before", oldWork, 100); }
            }
        }
    }
'''
java += factory.replace('TYPE', 'OldLodBakedMesh').replace('NAME', 'Old')
java += factory.replace('TYPE', 'LodBakedMesh').replace('NAME', 'New') + '}\n'
with tempfile.TemporaryDirectory(prefix='lod-tier-probe-') as temp:
    directory = Path(temp)
    (directory / 'OldLodBakedMesh.java').write_text(old.replace('LodBakedMesh', 'OldLodBakedMesh'))
    (directory / 'LodBakedMesh.java').write_text((root / source).read_text())
    (directory / 'Probe.java').write_text(java)
    subprocess.run(['javac', '-d', temp, *map(str, directory.glob('*.java'))], check=True)
    subprocess.run(['java', '-Xms256m', '-Xmx256m', '-cp', temp, 'dev.metalcraft.client.lod.Probe'], check=True)
