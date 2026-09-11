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
    private final boolean enabled = Boolean.getBoolean("metalcraft.testLod");
    private final long initialDraws = LodLoadedRenderer.stats().draws();

    MetalLodTestScope(ClientGameTestContext context) {
        this.context = context;
        saved = MetalCraftConfig.lod();
        if (enabled) {
            if (!LodCapabilities.EXPERIMENTAL) throw new IllegalArgumentException("LOD validation requires -PmetalLodExperimental=true");
            context.runOnClient(client -> MetalCraftConfig.setLod(LodSettings.defaults().withGeometry(2, 2).withEnabled(true)));
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
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        finally { context.runOnClient(client -> MetalCraftConfig.setLod(saved)); }
    }
}
