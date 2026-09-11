package dev.metalcraft.client.test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.lod.LodCapabilities;
import dev.metalcraft.client.lod.LodCompilerCapture;
import dev.metalcraft.client.lod.LodLoadedRenderer;
import dev.metalcraft.client.lod.LodSettings;
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.mixin.GpuDeviceAccessor;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;

/** Explicit benchmark controls and provenance. Restores persisted renderer preferences after a run. */
final class MetalBenchmarkEnvironment implements AutoCloseable {
    private final ClientGameTestContext context;
    private final boolean half = MetalCraftConfig.halfResolution();
    private final boolean unlocked = MetalCraftConfig.unlockedFrameRate();
    private final LodSettings lod = MetalCraftConfig.lod();
    private final String pack;

    MetalBenchmarkEnvironment(ClientGameTestContext context) {
        this.context = context;
        String requestedPack = System.getProperty("metalcraft.benchmarkPack");
        if (requestedPack != null && !requestedPack.equals("none") && !requestedPack.equals("standard")) {
            throw new IllegalArgumentException("Benchmark pack must be none or standard");
        }
        boolean requestedHalf = booleanProperty("metalcraft.benchmarkHalfResolution", half);
        boolean requestedUnlocked = booleanProperty("metalcraft.benchmarkUnlocked", unlocked);
        boolean requestedLod = booleanProperty("metalcraft.benchmarkLod", false);
        if (requestedLod && !LodCapabilities.EXPERIMENTAL) throw new IllegalArgumentException("LOD benchmark requires -PmetalLodExperimental=true");
        pack = context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        try {
            context.runOnClient(c -> {
                if (requestedPack != null) ShaderPackRuntime.active().selectPack(requestedPack.equals("none") ? ShaderPackRuntime.NONE_ID : ShaderPackRuntime.BUILTIN_ID);
                MetalCraftConfig.setHalfResolution(requestedHalf);
                MetalCraftConfig.setUnlockedFrameRate(requestedUnlocked);
                MetalCraftConfig.setLod(LodSettings.defaults().withEnabled(requestedLod));
                c.invalidateSurfaceConfiguration();
            });
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private static boolean booleanProperty(String name, boolean fallback) {
        String value = System.getProperty(name);
        if (value == null) return fallback;
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(name + " must be true or false");
        return Boolean.parseBoolean(value);
    }

    /** Main-thread point-in-time samples, not process or GPU peaks. */
    static JsonObject sample() {
        JsonObject value = new JsonObject();
        Runtime runtime = Runtime.getRuntime();
        value.addProperty("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
        value.addProperty("heapCommittedBytes", runtime.totalMemory());
        value.add("lodRenderer", new Gson().toJsonTree(LodLoadedRenderer.stats()));
        value.add("lodCapture", new Gson().toJsonTree(LodCompilerCapture.stats()));
        var backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).metalcraft$backend();
        if (backend instanceof MetalGpuDevice metal) {
            value.addProperty("metalAllocatedBytes", metal.metal().currentAllocatedBytes());
            value.addProperty("metalWorkingSetBytes", metal.metal().recommendedWorkingSetBytes());
        }
        return value;
    }

    static JsonObject describe() {
        JsonObject value = sample();
        var client = Minecraft.getInstance();
        value.addProperty("device", RenderSystem.getDevice().getDeviceInfo().name());
        value.addProperty("osVersion", System.getProperty("os.version"));
        value.addProperty("javaVersion", System.getProperty("java.version"));
        value.addProperty("pack", ShaderPackRuntime.active().selectedPackId());
        value.addProperty("halfResolution", MetalCraftConfig.halfResolution());
        value.addProperty("unlockedFrameRate", MetalCraftConfig.unlockedFrameRate());
        value.addProperty("vsync", client.options.enableVsync().get());
        value.addProperty("frameLimit", client.options.framerateLimit().get());
        value.addProperty("fov", client.options.fov().get());
        value.addProperty("passMerging", Boolean.parseBoolean(System.getProperty("metalcraft.passMerging", "true")));
        value.addProperty("commandBatching", Boolean.parseBoolean(System.getProperty("metalcraft.commandBatching", "true")));
        value.add("lodRequested", new Gson().toJsonTree(MetalCraftConfig.lod()));
        value.addProperty("lodGeometryAvailable", LodCapabilities.EXPERIMENTAL);
        value.addProperty("lodCounterMeaning", "Cumulative encoded main-world terrain, distant subset, replacements and shadows are separate; subtract phase endpoints. Memory samples are not peaks.");
        value.addProperty("memorySampling", "Point-in-time after each phase; not a peak or resident-set measurement");
        return value;
    }

    static JsonObject camera() {
        var player = Minecraft.getInstance().player;
        JsonObject value = new JsonObject();
        value.addProperty("x", player.getX());
        value.addProperty("y", player.getY());
        value.addProperty("z", player.getZ());
        value.addProperty("yaw", player.getYRot());
        value.addProperty("pitch", player.getXRot());
        return value;
    }

    @Override public void close() {
        context.runOnClient(c -> {
            MetalCraftConfig.setHalfResolution(half);
            MetalCraftConfig.setUnlockedFrameRate(unlocked);
            MetalCraftConfig.setLod(lod);
            if (!pack.equals(ShaderPackRuntime.active().selectedPackId())) ShaderPackRuntime.active().selectPack(pack);
            c.invalidateSurfaceConfiguration();
        });
    }
}
