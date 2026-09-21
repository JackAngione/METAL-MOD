package dev.metalcraft.client.test;

import dev.metalcraft.client.chunk.LightSnapshotAccess;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;

/** Exercises transformed native storage and its bridge copy methods, not a mock owner. */
final class LightStorageRuntimeCheck {
    static void run() {
        try {
            for (String kind : new String[]{"Block", "Sky"}) {
                var type = Class.forName("net.minecraft.world.level.lighting." + kind + "LightSectionStorage$" + kind + "DataLayerStorageMap");
                var ctor = kind.equals("Block") ? type.getDeclaredConstructor(Long2ObjectOpenHashMap.class)
                        : type.getDeclaredConstructor(Long2ObjectOpenHashMap.class, Long2IntOpenHashMap.class, int.class);
                ctor.setAccessible(true);
                var map = (DataLayerStorageMap<?>)(kind.equals("Block") ? ctor.newInstance(new Long2ObjectOpenHashMap<>())
                        : ctor.newInstance(new Long2ObjectOpenHashMap<>(), new Long2IntOpenHashMap(), -4));
                check((((LightSnapshotAccess)map).metalcraft$lightSnapshots() != null)
                        != Boolean.getBoolean("metalcraft.flatLightSnapshots"), "configured backing " + kind);
                long key = SectionPos.asLong(-128,-4,128);
                map.setLayer(key, new DataLayer(5));
                check(map.hasLayer(key) && map.getLayer(key).get(3,4,5)==5,"native insertion/cache");
                var snapshot = map.copy(); snapshot.disableCache();
                map.copyDataLayer(key).set(3,4,5,14);
                check(snapshot.getLayer(key).get(3,4,5)==5 && map.getLayer(key).get(3,4,5)==14,"native payload isolation");
                var branch = snapshot.copy();
                snapshot.removeLayer(key); snapshot.clearCache();
                check(!snapshot.hasLayer(key) && snapshot.getLayer(key)==null,"native removal");
                check(branch.getLayer(key).get(3,4,5)==5 && map.hasLayer(key),"two independently mutable branches");
                map.removeLayer(key); map.clearCache();
                var pages = ((LightSnapshotAccess)map).metalcraft$lightSnapshots();
                check(pages==null || (pages.size()==0 && pages.allocatedPages()==0),"native unload releases pages");
            }
            System.out.println("Native block/sky lighting snapshot, cache, edit and unload checks passed");
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
