package dev.metalcraft.client.horizon;

import dev.metalcraft.client.MetalCraftConfig;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/** Shared mode policy; world data remains native only in a small handoff area. */
public final class NativeHorizon {
    private static volatile boolean metal;
    private NativeHorizon() { }
    public static void initialize() {
        HorizonStreamer.initialize();
        ClientLifecycleEvents.CLIENT_STARTED.register(c->metal="Metal".equals(com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName()));
        ClientTickEvents.END_CLIENT_TICK.register(HorizonRenderer::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c->HorizonRenderer.reset());
    }
    public static boolean enabled() {
        var client=Minecraft.getInstance();
        return metal && !Boolean.getBoolean("metalcraft.disableHorizon") && client.getSingleplayerServer()!=null
                && MetalCraftConfig.nativeTerrainLod() && MetalCraftConfig.nativeLodReduction()>0
                && client.options.renderDistance().get()>MetalCraftConfig.nativeQualityDistance()+3;
    }
    public static int nativeDistance(int requested) {
        return enabled()?Math.min(requested,Math.max(2,MetalCraftConfig.nativeQualityDistance()+3)):requested;
    }
    public static int horizon() { return enabled()?Minecraft.getInstance().options.renderDistance().get():0; }
    public static int innerDistance() { return Math.max(2,MetalCraftConfig.nativeQualityDistance()+1); }
    public static void invalidate(net.minecraft.server.level.ServerLevel level,long key) { HorizonStreamer.invalidate(level,key); }
    static boolean hasColumn(long epoch,long key) { return HorizonRenderer.hasColumn(epoch,key); }
}
