package dev.metalcraft.client.mixin;

import dev.metalcraft.client.chunk.LightSectionSnapshots;
import dev.metalcraft.client.chunk.LightSnapshotAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve native cache, payload copy-on-write, light propagation and publication. */
@Mixin(DataLayerStorageMap.class)
abstract class LightStorageMixin implements LightSnapshotAccess {
    @Unique private LightSectionSnapshots metalcraft$snapshots;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void metalcraft$initialize(Long2ObjectOpenHashMap<DataLayer> map, CallbackInfo ci) {
        if (this instanceof LightSnapshotAccess.Enabled && map.isEmpty()
                && !Boolean.getBoolean("metalcraft.flatLightSnapshots")) metalcraft$snapshots = new LightSectionSnapshots();
    }
    @Override public LightSectionSnapshots metalcraft$lightSnapshots() { return metalcraft$snapshots; }
    @Override public void metalcraft$lightSnapshots(LightSectionSnapshots snapshots) { metalcraft$snapshots = snapshots; }

    @Redirect(method = {"getLayer", "copyDataLayer"}, at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;get(J)Ljava/lang/Object;"))
    private Object metalcraft$get(Long2ObjectOpenHashMap<DataLayer> map, long key) {
        return metalcraft$snapshots == null ? map.get(key) : metalcraft$snapshots.get(key);
    }
    @Redirect(method = {"setLayer", "copyDataLayer"}, at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;put(JLjava/lang/Object;)Ljava/lang/Object;"))
    private Object metalcraft$put(Long2ObjectOpenHashMap<DataLayer> map, long key, Object value) {
        return metalcraft$snapshots == null ? map.put(key, (DataLayer)value) : metalcraft$snapshots.put(key, (DataLayer)value);
    }
    @Redirect(method = "hasLayer", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;containsKey(J)Z"))
    private boolean metalcraft$contains(Long2ObjectOpenHashMap<DataLayer> map, long key) {
        return metalcraft$snapshots == null ? map.containsKey(key) : metalcraft$snapshots.get(key) != null;
    }
    @Redirect(method = "removeLayer", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;remove(J)Ljava/lang/Object;"))
    private Object metalcraft$remove(Long2ObjectOpenHashMap<DataLayer> map, long key) {
        return metalcraft$snapshots == null ? map.remove(key) : metalcraft$snapshots.remove(key);
    }
}
