package dev.metalcraft.client.chunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Exact surface-coverage oracle; not a second copy of the greedy merge algorithm. */
public final class NativeTerrainLodSmoke {
    private static final NativeSurfaceMesher.Layout LAYOUT = new NativeSurfaceMesher.Layout(28, 0, 12, 16, 24, -1);
    private static final int QUAD_BYTES = 112;

    public static void main(String[] args) {
        NativeShellSmoke.run();
        dev.metalcraft.client.horizon.HorizonColumnSmoke.run();
        selection();
        compiledSelection();
        eligibilityParity();
        reductionSettings();
        nativeDistanceSettings();
        rebuildTransitions();
        NativeGeometryLodSmoke.run();
        ByteBuffer plane = fixture(1, 1, 8, false, false, 1);
        int previous = 256;
        for (int cell : new int[]{2, 4, 8, 16}) {
            var result = NativeSurfaceMesher.reduce(plane, LAYOUT, cell);
            check(result != null && result.quads() < previous, "progressively fewer quads at " + cell);
            verify(plane, result);
            previous = result.quads();
            System.out.println("Native surface LOD " + cell + ": 256 -> " + result.quads() + " quads (" + (100.0 * (256 - result.quads()) / 256) + "% fewer)");
        }
        check(NativeSurfaceMesher.reduce(plane, LAYOUT, 1) == null, "near meshes stay native");
        check(NativeSurfaceMesher.reduce(plane, LAYOUT, 3) == null, "invalid tiers stay native");
        // All axes and windings; holes, terraces, material borders and randomized AO/lighting.
        for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) for (int seed = 0; seed < 30; seed++) {
            ByteBuffer source = fixture(axis, sign, seed % 17, true, true, seed);
            byte[] original = bytes(source);
            for (int cell : new int[]{2, 4, 8, 16}) {
                var result = NativeSurfaceMesher.reduce(source, LAYOUT, cell);
                if (result != null) verify(source, result);
                check(Arrays.equals(original, bytes(source)), "source immutable during reduction");
                check(source.position() == 0, "source position untouched");
            }
        }
        ByteBuffer checker = ByteBuffer.allocate(256 * QUAD_BYTES).order(ByteOrder.nativeOrder());
        for (int v = 0; v < 16; v++) for (int u = 0; u < 16; u++) quad(checker, 1, 1, 8, u, v, (u + v) % 2, 0xffe0e0e0);
        checker.flip();
        check(NativeSurfaceMesher.reduce(checker, LAYOUT, 8) == null, "checker material boundaries preserved");
        ByteBuffer duplicate = ByteBuffer.allocate(plane.remaining() * 2).order(ByteOrder.nativeOrder());
        duplicate.put(plane.duplicate()).put(plane.duplicate()).flip();
        check(NativeSurfaceMesher.reduce(duplicate, LAYOUT, 8) == null, "overlays not merged or removed");
        ByteBuffer custom = ByteBuffer.allocate(plane.remaining() + QUAD_BYTES).order(ByteOrder.nativeOrder());
        custom.put(plane.duplicate());
        int customOffset = custom.position();
        quad(custom, 1, 1, 8, 5, 5, 0, 0xffabcdef);
        custom.putFloat(customOffset + 4, 8.5f); // Non-cubic/slanted quad.
        custom.flip();
        byte[] customBytes = Arrays.copyOfRange(bytes(custom), customOffset, customOffset + QUAD_BYTES);
        var mixed = NativeSurfaceMesher.reduce(custom, LAYOUT, 8);
        check(mixed != null && containsQuad(mixed.vertices(), customBytes), "custom geometry retained byte-for-byte beside simplified terrain");
        ByteBuffer translucent = ByteBuffer.wrap(bytes(plane)).order(ByteOrder.nativeOrder());
        for (int offset = 0; offset < translucent.limit(); offset += 28) translucent.putInt(offset + 12, 0x80ffffff);
        check(NativeSurfaceMesher.reduce(translucent, LAYOUT, 8) == null, "non-opaque alpha excluded");
        ByteBuffer oversized = ByteBuffer.allocate((NativeSurfaceMesher.MAX_QUADS + 1) * QUAD_BYTES);
        check(NativeSurfaceMesher.reduce(oversized, LAYOUT, 8) == null, "worker memory cap");
        System.out.println("Native terrain LOD smoke passed: distance/zoom/hysteresis, 720 randomized tier cases, exact oriented coverage/materials/edges, fallback and bounds");
    }

    private static void selection() {
        check(NativeLodSelection.select(64, 150, 8, true) == 1, "full-detail radius always wins");
        check(NativeLodSelection.select(120, 70, 1, true) == 2, "middle tier");
        check(NativeLodSelection.select(240, 70, 1, true) == 4, "far tier");
        check(NativeLodSelection.select(500, 70, 1, true) == 8, "horizon tier");
        check(NativeLodSelection.select(500, 10, 8, true) == 2, "zoom refines shell without restoring distant block models");
        check(NativeLodSelection.select(500, 70, 8, false) == 1, "disabled restores native mesh");
        check(NativeLodSelection.select(146, 70, 1, true) == 2 && NativeLodSelection.select(146, 70, 4, true) == 4, "hysteresis");
        check(NativeLodSelection.select(110, 70, 4, true) == 2, "refinement threshold");
        check(NativeLodSelection.select(Double.NaN, 70, 8, true) == 1, "invalid camera fallback");
        check(NativeLodSelection.distance(-16, -16, -16, -1, -1, -1) == 0, "negative section bounds");
        check(NativeLodSelection.distance(0, 0, 0, 1, 0, 0) == 16, "nearest bounds distance");
    }

    private static void eligibilityParity() {
        try {
            var classify=NativeSurfaceMesher.class.getDeclaredMethod("classify",ByteBuffer.class,int.class,NativeSurfaceMesher.Layout.class);
            classify.setAccessible(true);
            Random random=new Random(1909);
            // Compare the new allocation-free predicate with the unchanged surface classifier,
            // including malformed positions, UVs, alpha, winding and optional packed normals.
            for(int axis=0;axis<3;axis++) for(int sign:new int[]{-1,1}) for(int i=0;i<2000;i++) {
                var data=ByteBuffer.allocate(128).order(ByteOrder.nativeOrder());
                quad(data,axis,sign,8,4,5,0,0xffa0b0c0);
                if(i%2==0) {
                    int vertex=random.nextInt(4), component=random.nextInt(7);
                    int offset=vertex*28+component*4;
                    if(component==3 || component==6) data.putInt(offset,random.nextInt());
                    else data.putFloat(offset,i%13==0?Float.NaN:i%17==0?Float.POSITIVE_INFINITY:random.nextFloat()*20-2);
                }
                var layout=LAYOUT;
                if(i%3==0) {
                    // Repack each 28-byte vertex into a 32-byte layout with a normal.
                    var withNormals=ByteBuffer.allocate(128).order(ByteOrder.nativeOrder());
                    for(int v=0;v<4;v++) {
                        withNormals.put(data.slice(v*28,28));
                        withNormals.putInt((sign*127&255)<<(axis*8));
                    }
                    if(i%7==0) withNormals.put(28,(byte)42);
                    data=withNormals;
                    layout=new NativeSurfaceMesher.Layout(32,0,12,16,24,28);
                }
                boolean old=classify.invoke(null,data,0,layout)!=null;
                check(NativeSurfaceMesher.unitFace(data,0,layout)==old,"allocation-free eligibility matches full classification");
            }
            System.out.println("Native face eligibility passed: 12,000 original-classifier comparisons including custom/malformed inputs");
        } catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void compiledSelection() {
        Random random=new Random(74093);
        for(int radius=1;radius<=256;radius++) for(int reduction=0;reduction<=5;reduction++) {
            double fov=1+random.nextDouble()*178;
            var policy=NativeLodSelection.policy(fov,true,reduction,radius);
            for(int i=0;i<64;i++) {
                double distance=i==0?radius*16.0:random.nextDouble()*8192;
                int previous=1<<random.nextInt(5);
                check(policy.selectSquared(distance*distance,previous)==NativeLodSelection.select(distance,fov,previous,true,reduction,radius),
                        "compiled squared-distance selector matches scalar FOV/hysteresis contract");
            }
            check(policy.selectSquared(Double.NaN,16)==1,"invalid squared distance stays native");
        }
        for(double fov:new double[]{0,180,Double.NaN,Double.POSITIVE_INFINITY})
            check(NativeLodSelection.policy(fov,true,5,4).selectSquared(1e8,16)==1,"invalid FOV remains native");
        check(NativeLodSelection.policy(70,false,5,4).selectSquared(1e8,16)==1,"disabled compiled policy");
        System.out.println("Compiled native selection passed: 98,304 scalar comparisons, all radii/strengths, zoom/hysteresis and invalid inputs");
    }

    private static void reductionSettings() {
        for (int previous : new int[]{1, 2, 4, 8, 16}) for (int distance = 0; distance <= 4096; distance += 8) {
            int last = 1;
            for (int level = 0; level <= 5; level++) {
                int selected = NativeLodSelection.select(distance, 70, previous, true, level);
                check(selected >= last, "increasing reduction never increases detail");
                check(level != 0 || selected == 1, "zero restores native detail at every distance");
                check(distance > 64 || selected == 1, "all settings preserve nearby detail");
                check(NativeLodSelection.select(distance, 70, previous, false, level) == 1, "master switch overrides reduction");
                last = selected;
            }
            check(NativeLodSelection.select(distance, 70, previous, true, 3)
                    == NativeLodSelection.select(distance, 70, previous, true), "default retains previous behavior");
        }
        check(NativeLodSelection.select(500, 70, 1, true, 5) == 16, "extreme admits largest patches");
        check(NativeLodSelection.select(500, 70, 1, true, 4) == 8, "strong differs from extreme");
        check(NativeLodSelection.select(4096, 70, 16, true, 1) == 2, "subtle caps cell size");
        check(NativeLodSelection.select(4096, 70, 16, true, 2) == 4, "mild caps cell size");
        check(NativeLodSelection.select(500, 70, 16, true, -1) == 1, "negative clamped to native");
        check(NativeLodSelection.select(500, 70, 1, true, 99) == 16, "oversized clamped to extreme");
        for (String json : new String[]{"null", "true", "{}", "[]", "\"5\"", "2.5", "1e999"})
            check(dev.metalcraft.client.NativeLodSettingsCodec.readReduction(com.google.gson.JsonParser.parseString(json)) == 3, "malformed preference defaults: " + json);
        check(dev.metalcraft.client.NativeLodSettingsCodec.readReduction(null) == 3, "missing preference migrates to balanced");
        check(dev.metalcraft.client.NativeLodSettingsCodec.readReduction(com.google.gson.JsonParser.parseString("-100")) == 0, "saved negative clamped");
        check(dev.metalcraft.client.NativeLodSettingsCodec.readReduction(com.google.gson.JsonParser.parseString("1e100")) == 5, "saved large value clamped without integer overflow");
        for (int level = 0; level <= 5; level++) {
            String json = new com.google.gson.Gson().toJson(level);
            check(dev.metalcraft.client.NativeLodSettingsCodec.readReduction(com.google.gson.JsonParser.parseString(json)) == level, "saved numeric levels round trip");
        }
    }

    private static void nativeDistanceSettings() {
        // Sweep every slider value, including the two endpoints, and every installed tier.
        for (int radius = 1; radius <= 256; radius++) {
            double boundary = radius * 16.0;
            for (int previous : new int[]{1, 2, 4, 8, 16}) for (int level = 1; level <= 5; level++) {
                check(NativeLodSelection.select(boundary, 150, previous, true, level, radius) == 1,
                        "native radius overrides strength, FOV and old mesh tier");
                check(NativeLodSelection.select(boundary + .01, 70, previous, true, level, radius) >= 2,
                        "normal-FOV LOD begins immediately beyond chosen radius");
                check(NativeLodSelection.select(boundary + .01, 10, previous, true, level, radius) >= 2,
                        "every enabled section beyond the radius uses a shell, including zoom");
                check(NativeLodSelection.select(boundary + 1000, 70, previous, true, 0, radius) == 1,
                        "zero reduction overrides radius");
                check(NativeLodSelection.select(boundary + 1000, 70, previous, false, level, radius) == 1,
                        "master toggle overrides radius");
                int before = NativeLodSelection.select(512, 70, previous, true, level, radius);
                int after = NativeLodSelection.select(512, 70, previous, true, level, radius + 1);
                check(after <= before, "expanding radius can only restore detail");
            }
            String json = new com.google.gson.Gson().toJson(radius);
            check(dev.metalcraft.client.NativeLodSettingsCodec.readNativeDistance(com.google.gson.JsonParser.parseString(json)) == radius,
                    "saved radius round trips");
        }
        check(NativeLodSelection.select(128, 70, 4, true, 5, 8) == 1, "expansion restores an existing coarse mesh");
        check(NativeLodSelection.select(128, 70, 1, true, 5, 1) > 1, "contraction coarsens an existing native mesh");
        for (String json : new String[]{"null", "true", "{}", "[]", "\"8\"", "2.5", "1e999"})
            check(dev.metalcraft.client.NativeLodSettingsCodec.readNativeDistance(com.google.gson.JsonParser.parseString(json)) == 4,
                    "malformed radius defaults: " + json);
        check(dev.metalcraft.client.NativeLodSettingsCodec.readNativeDistance(null) == 4, "old config defaults to four chunks");
        check(dev.metalcraft.client.NativeLodSettingsCodec.readNativeDistance(com.google.gson.JsonParser.parseString("-100")) == 1, "saved negative radius clamps");
        check(dev.metalcraft.client.NativeLodSettingsCodec.readNativeDistance(com.google.gson.JsonParser.parseString("1e100")) == 256, "saved large radius clamps without overflow");
        check(NativeLodSelection.clampNativeDistance(Integer.MIN_VALUE) == 1
                && NativeLodSelection.clampNativeDistance(Integer.MAX_VALUE) == 256, "setter range bounds");
        System.out.println("Native quality distance passed: all 256 radii, onset, restoration, zoom, overrides and saved values");
    }

    private static void rebuildTransitions() {
        var queue = new NativeLodRebuildQueue();
        Object mesh = new Object();
        var refine = NativeLodRebuildQueue.Change.REFINE;
        var coarsen = NativeLodRebuildQueue.Change.COARSEN;
        var none = NativeLodRebuildQueue.Change.NONE;
        queue.requested(1, mesh, 8, 0);
        check(queue.change(1, mesh, 4, 1) == refine, "approaching preempts queued coarsening without timeout");
        check(queue.change(1, mesh, 1, 1) == refine, "cancel coarse snapshot even when installed mesh is still native");
        queue.requested(1, mesh, 1, 1);
        check(queue.change(1, mesh, 4, 1) == none, "equivalent in-flight refinement is not repeatedly cancelled");
        check(queue.change(1, mesh, 4, 8) == none, "moving away allows in-flight refinement to finish");
        queue.clear();
        for (int i = 0; i < 64; i++) {
            check(queue.hasCapacity(i, coarsen), "coarsening slots available");
            queue.requested(i, mesh, 8, 0);
        }
        check(!queue.hasCapacity(100, coarsen), "distant work cannot consume refinement reserve");
        check(queue.hasCapacity(100, refine), "approach admitted under saturated distant backlog");
        for (int i = 64; i < 128; i++) queue.requested(i, mesh, 2, 0);
        check(!queue.hasCapacity(128, refine), "total jobs remain bounded");
        check(queue.hasCapacity(127, refine) && queue.change(127, mesh, 8, 1) == refine,
                "finer target supersedes existing request even at capacity");
        queue.prune(1, (node, installed) -> node != 0);
        check(queue.hasCapacity(128, refine), "completed or unloaded mesh frees slot immediately, even off screen");
        Object replacement = new Object();
        check(queue.change(1, replacement, 8, 1) == refine, "late coarse upload is rechecked against current camera");
        queue.prune(10_000_000_000L, (node, installed) -> true);
        check(queue.hasCapacity(1000, coarsen), "lost jobs eventually retry");
        queue.clear();
        check(queue.change(1, mesh, 1, 1) == none, "world reset clears requests");
        System.out.println("Native LOD transitions passed: saturated queues, preemption, deduplication, completion, late uploads and reset");
    }

    private static ByteBuffer fixture(int axis, int sign, int plane, boolean holes, boolean materials, int seed) {
        ByteBuffer data = ByteBuffer.allocate(256 * QUAD_BYTES).order(ByteOrder.nativeOrder());
        Random random = new Random(seed);
        for (int v = 0; v < 16; v++) for (int u = 0; u < 16; u++) {
            if (holes && random.nextInt(7) == 0) continue;
            int color = 0xff000000 | random.nextInt(0xffffff);
            quad(data, axis, sign, plane, u, v, materials && u >= 8 ? 1 : 0, color);
        }
        return data.flip();
    }

    private static void quad(ByteBuffer target, int axis, int sign, int plane, int u, int v, int material, int color) {
        int[] order = sign > 0 ? new int[]{0, 1, 3, 2} : new int[]{0, 2, 3, 1};
        for (int corner : order) {
            float[] p = new float[3];
            p[axis] = plane; p[(axis + 1) % 3] = u + (corner & 1); p[(axis + 2) % 3] = v + (corner >> 1);
            target.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]).putInt(color);
            target.putFloat(material * .25f + (corner & 1) * .125f).putFloat((corner >> 1) * .125f);
            target.putInt(0x00f00070 + ((u + v) % 8) * 16);
        }
    }

    private static void verify(ByteBuffer source, NativeSurfaceMesher.Reduced result) {
        ByteBuffer output = ByteBuffer.wrap(result.vertices()).order(ByteOrder.nativeOrder());
        check(coverage(source).equals(coverage(output)), "surface coverage, winding and material identical");
        check(boundary(source).equals(boundary(output)), "all section edge quads unchanged");
        check(result.vertices().length == result.quads() * QUAD_BYTES && result.quads() < result.originalQuads(), "upload payload and index count reduced");
    }

    private static Map<String, Integer> coverage(ByteBuffer data) {
        Map<String, Integer> coverage = new HashMap<>();
        for (int offset = 0; offset < data.limit(); offset += QUAD_BYTES) {
            float[] min = {100, 100, 100}, max = {-100, -100, -100};
            float minU = 2, maxU = -1, minV = 2, maxV = -1;
            for (int i = 0; i < 4; i++) {
                for (int a = 0; a < 3; a++) {
                    float value = data.getFloat(offset + i * 28 + a * 4);
                    min[a] = Math.min(min[a], value); max[a] = Math.max(max[a], value);
                }
                minU = Math.min(minU, data.getFloat(offset + i * 28 + 16)); maxU = Math.max(maxU, data.getFloat(offset + i * 28 + 16));
                minV = Math.min(minV, data.getFloat(offset + i * 28 + 20)); maxV = Math.max(maxV, data.getFloat(offset + i * 28 + 20));
            }
            int axis = min[0] == max[0] ? 0 : min[1] == max[1] ? 1 : 2;
            int ua = (axis + 1) % 3, va = (axis + 2) % 3;
            double cross = (data.getFloat(offset + 28 + ua * 4) - data.getFloat(offset + ua * 4))
                    * (data.getFloat(offset + 56 + va * 4) - data.getFloat(offset + va * 4))
                    - (data.getFloat(offset + 28 + va * 4) - data.getFloat(offset + va * 4))
                    * (data.getFloat(offset + 56 + ua * 4) - data.getFloat(offset + ua * 4));
            for (int v = (int)min[va]; v < max[va]; v++) for (int u = (int)min[ua]; u < max[ua]; u++) {
                String key = axis + ":" + Math.signum(cross) + ":" + min[axis] + ":" + u + ":" + v + ":" + minU + ":" + minV + ":" + maxU + ":" + maxV;
                coverage.merge(key, 1, Integer::sum);
            }
        }
        return coverage;
    }

    private static Map<String, Integer> boundary(ByteBuffer data) {
        Map<String, Integer> result = new HashMap<>();
        for (int offset = 0; offset < data.limit(); offset += QUAD_BYTES) {
            boolean edge = false;
            for (int axis = 0; axis < 3; axis++) {
                float first = data.getFloat(offset + axis * 4);
                boolean same = true;
                for (int i = 1; i < 4; i++) same &= first == data.getFloat(offset + i * 28 + axis * 4);
                if (same) continue;
                for (int i = 0; i < 4; i++) {
                    float value = data.getFloat(offset + i * 28 + axis * 4);
                    edge |= value == 0 || value == 16;
                }
            }
            if (edge) result.merge(Arrays.toString(bytes(data.slice(offset, QUAD_BYTES))), 1, Integer::sum);
        }
        return result;
    }

    private static boolean containsQuad(byte[] data, byte[] quad) {
        for (int offset = 0; offset < data.length; offset += QUAD_BYTES)
            if (Arrays.equals(quad, Arrays.copyOfRange(data, offset, offset + QUAD_BYTES))) return true;
        return false;
    }
    private static byte[] bytes(ByteBuffer buffer) { byte[] bytes = new byte[buffer.remaining()]; buffer.duplicate().get(bytes); return bytes; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
