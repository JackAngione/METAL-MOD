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
import org.lwjgl.glfw.GLFW;
import dev.metalcraft.client.metal.MetalSurfaceProbe;

/** Explicit benchmark controls and provenance. Restores persisted renderer preferences after a run. */
final class MetalBenchmarkEnvironment implements AutoCloseable {
    private final ClientGameTestContext context;
    private final boolean half = MetalCraftConfig.halfResolution();
    private final boolean unlocked = MetalCraftConfig.unlockedFrameRate();
    private final LodSettings lod = MetalCraftConfig.lod();
    private final String pack;
    private final boolean fullscreen;

    MetalBenchmarkEnvironment(ClientGameTestContext context) {
        this.context = context;
        fullscreen = context.computeOnClient(c -> c.getWindow().isFullscreen());
        String requestedPack = System.getProperty("metalcraft.benchmarkPack");
        if (requestedPack != null && !requestedPack.equals("none") && !requestedPack.equals("standard")) {
            throw new IllegalArgumentException("Benchmark pack must be none or standard");
        }
        boolean requestedHalf = booleanProperty("metalcraft.benchmarkHalfResolution", half);
        boolean requestedUnlocked = booleanProperty("metalcraft.benchmarkUnlocked", unlocked);
        boolean requestedLod = booleanProperty("metalcraft.benchmarkLod", false);
        boolean requestedFullscreen = booleanProperty("metalcraft.benchmarkFullscreen", false);
        if (requestedLod && !LodCapabilities.GEOMETRY_AVAILABLE) throw new IllegalArgumentException("LOD geometry is unavailable");
        pack = context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        try {
            context.runOnClient(c -> {
                if (c.getWindow().isFullscreen() != requestedFullscreen) c.getWindow().toggleFullScreen();
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
        long[] process = dev.metalcraft.client.metal.MetalGpuFrameCapture.processMemoryAndThermalState();
        value.addProperty("processResidentBytes", process[0]);
        value.addProperty("processPeakResidentBytes", process[1]);
        value.addProperty("processPhysicalFootprintBytes", process[2]);
        value.addProperty("processPeakPhysicalFootprintBytes", process[3]);
        value.addProperty("thermalState", process[4]);
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
        JsonObject shaderOptions = new JsonObject();
        for (var option : ShaderPackRuntime.active().options()) {
            shaderOptions.add(option.id(), new Gson().toJsonTree(ShaderPackRuntime.active().optionValue(option.id())));
        }
        value.add("shaderOptions", shaderOptions);
        value.addProperty("halfResolution", MetalCraftConfig.halfResolution());
        value.addProperty("lodTerrainCensus", LodLoadedRenderer.TERRAIN_CENSUS);
        value.addProperty("unlockedFrameRate", MetalCraftConfig.unlockedFrameRate());
        value.addProperty("vsync", client.options.enableVsync().get());
        value.addProperty("frameLimit", client.options.framerateLimit().get());
        value.addProperty("fov", client.options.fov().get());
        value.addProperty("fullscreen", client.getWindow().isFullscreen());
        value.addProperty("sceneWidth", client.getWindow().getWidth());
        value.addProperty("sceneHeight", client.getWindow().getHeight());
        value.addProperty("renderDistance", client.options.renderDistance().get());
        value.addProperty("simulationDistance", client.options.simulationDistance().get());
        value.addProperty("passMerging", Boolean.parseBoolean(System.getProperty("metalcraft.passMerging", "true")));
        value.addProperty("commandBatching", Boolean.parseBoolean(System.getProperty("metalcraft.commandBatching", "true")));
        value.add("lodRequested", new Gson().toJsonTree(MetalCraftConfig.lod()));
        value.addProperty("lodGeometryAvailable", LodCapabilities.GEOMETRY_AVAILABLE);
        value.addProperty("lodPrepareTimingScope", "Loaded and distant frame maintenance, selection, uniforms and uploads; excludes workers and GPU execution");
        value.addProperty("lodCounterMeaning", "Cumulative encoded main-world terrain, distant subset, replacements and shadows are separate; subtract phase endpoints. Memory samples are not peaks.");
        value.addProperty("memorySampling", "Heap/Metal snapshots after each phase; OS process-lifetime resident/physical-footprint peaks include startup; LOD CPU charge peaks cover all worker admissions");
        value.addProperty("thermalStateMeaning", "0 nominal, 1 fair, 2 serious, 3 critical; -1 unavailable");
        value.addProperty("buildLatencyMeaning", "Cumulative admitted worker capture/validation/simplification attempts, nanoseconds total and maximum; includes rejected attempts, excludes queue wait and GPU upload");
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

    static void focus(ClientGameTestContext context) {
        context.runOnClient(c -> GLFW.glfwFocusWindow(c.getWindow().handle()));
        context.waitFor(c -> MetalSurfaceProbe.presentationState(c.getWindow().handle()) == 15
                && GLFW.glfwGetWindowAttrib(c.getWindow().handle(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE, 200);
    }

    static final class PresentationInterrupted extends AssertionError {
        PresentationInterrupted(String message) { super(message); }
    }

    /** One phase, checked on every game tick. A lost/occluded/resized window rejects the capture. */
    static final class Presentation {
        private final long window = Minecraft.getInstance().getWindow().handle();
        private final boolean fullscreen = GLFW.glfwGetWindowMonitor(window) != 0L;
        private final int[] drawable = MetalSurfaceProbe.drawableSize();
        private int checks;

        Presentation() { check(); }

        void check() {
            int state = MetalSurfaceProbe.presentationState(window);
            if ((GLFW.glfwGetWindowMonitor(window) != 0L) != fullscreen
                    || !java.util.Arrays.equals(drawable, MetalSurfaceProbe.drawableSize()))
                throw new AssertionError("Benchmark monitor attachment or drawable changed");
            if (state != 15 || GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) != GLFW.GLFW_TRUE
                    || GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) != GLFW.GLFW_FALSE) {
                throw new PresentationInterrupted("Benchmark presentation changed: AppKit state=" + state
                        + ", focused=" + GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED));
            }
            checks++;
        }

        JsonObject describe() {
            check();
            JsonObject value = new JsonObject();
            value.addProperty("passed", true);
            value.addProperty("checks", checks);
            value.addProperty("fullscreen", fullscreen);
            value.addProperty("drawableWidth", drawable[0]);
            value.addProperty("drawableHeight", drawable[1]);
            value.addProperty("scope", "Every game tick: AppKit active/visible/not-minimized/not-occluded, GLFW focused, stable mode and drawable; not a GPU pacing guarantee");
            return value;
        }
    }

    @Override public void close() {
        context.runOnClient(c -> {
            if (c.getWindow().isFullscreen() != fullscreen) c.getWindow().toggleFullScreen();
            MetalCraftConfig.setHalfResolution(half);
            MetalCraftConfig.setUnlockedFrameRate(unlocked);
            MetalCraftConfig.setLod(lod);
            if (!pack.equals(ShaderPackRuntime.active().selectedPackId())) ShaderPackRuntime.active().selectPack(pack);
            c.invalidateSurfaceConfiguration();
        });
    }
}
