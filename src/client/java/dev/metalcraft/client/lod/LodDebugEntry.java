package dev.metalcraft.client.lod;

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/** Debug overlay line ("distant_terrain" in the debug options screen). */
final class LodDebugEntry implements DebugScreenEntry {
    @Override
    public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
        if (!LodSystem.active()) return;
        LodStats stats = LodSystem.stats();
        displayer.addLine(String.format("Distant terrain: %d nodes, %d draws, %d resident, %.0f MiB, %d pending, %d building, %d real chunks",
            stats.drawnNodes, stats.draws, stats.residentNodes, stats.gpuBytes / 1048576.0, stats.pending, stats.inFlight, stats.capturedChunks));
    }

    @Override
    public boolean isAllowed(boolean reducedDebugInfo) {
        return true;
    }
}
