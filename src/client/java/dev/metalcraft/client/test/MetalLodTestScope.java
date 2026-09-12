package dev.metalcraft.client.test;

import com.google.gson.GsonBuilder;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.lod.LodCapabilities;
import dev.metalcraft.client.lod.LodLoadedRenderer;
import dev.metalcraft.client.lod.LodSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Opt-in LOD ownership for the existing composition/lifecycle scenarios, with preference restoration. */
final class MetalLodTestScope implements AutoCloseable {
    private final ClientGameTestContext context;
    private final LodSettings saved;
    static final int HORIZON = Integer.getInteger("metalcraft.testLodHorizon",16);
    private final boolean enabled = Boolean.getBoolean("metalcraft.testLod") || HORIZON>16;
    private final long initialDraws = LodLoadedRenderer.stats().draws();
    private final long initialDistantDraws = dev.metalcraft.client.lod.LodDistantRenderer.stats().draws();

    MetalLodTestScope(ClientGameTestContext context) {
        this.context = context;
        saved = MetalCraftConfig.lod();
        if (enabled) {
            if (!LodCapabilities.EXPERIMENTAL) throw new IllegalArgumentException("LOD validation requires -PmetalLodExperimental=true");
            if (HORIZON>16 && !LodCapabilities.HORIZON_EXPERIMENTAL)
                throw new IllegalArgumentException("Extended composition validation requires -PmetalLodHorizonExperimental=true");
            context.runOnClient(client -> MetalCraftConfig.setLod(LodSettings.defaults().withGeometry(2, 2)
                    .withEnabled(true).withHorizon(HORIZON,true,512)));
        }
    }

    @Override public void close() {
        if (!enabled) return;
        try {
            context.waitFor(client -> LodLoadedRenderer.stats().chargedBytes() == 0, 2400);
            var stats = LodLoadedRenderer.stats();
            Path path = Path.of("build", "lod-compatibility.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(stats));
            if (stats.draws() <= initialDraws || stats.uploadFailures() != 0)
                throw new AssertionError("Compatibility scenario did not validate successful live LOD ownership: " + stats);
            System.out.println("LOD compatibility ownership passed: " + stats);
            if (HORIZON>16) {
                var distant=dev.metalcraft.client.lod.LodDistantRenderer.stats();
                Files.writeString(Path.of("build","lod-horizon-compatibility.json"),new GsonBuilder().setPrettyPrinting().create().toJson(distant));
                if (distant.draws()<=initialDistantDraws || distant.uploadFailures()!=0 || distant.gpuBytes()!=0)
                    throw new AssertionError("Extended composition did not validate distant ownership and release: "+distant);
                System.out.println("Distant composition ownership passed: "+distant);
            }
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        finally { context.runOnClient(client -> MetalCraftConfig.setLod(saved)); }
    }

    static void prepareHorizon(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world) {
        if (HORIZON<=16) return;
        context.runOnClient(c -> dev.metalcraft.client.shader.ShaderPackRuntime.active()
                .selectPack(dev.metalcraft.client.shader.ShaderPackRuntime.BUILTIN_ID));
        world.getServer().runCommand("tp @a 0 210 -640 180 15");
        context.getInput().lookAt(180,15);
        MetalLodHorizonGameTest.await(context,"water terrain exploration",() -> {
            var cache=dev.metalcraft.client.lod.LodDistantRenderer.stats().cache();
            return cache!=null && cache.saved()>300 && cache.indexedNodes()>150 && cache.queuedNodes()==0;
        });
        context.waitTicks(100);
        context.takeScreenshot("metalcraft-water-horizon-explored");
        System.out.println("Distant water exploration: "+dev.metalcraft.client.lod.LodDistantRenderer.stats());
    }
}
