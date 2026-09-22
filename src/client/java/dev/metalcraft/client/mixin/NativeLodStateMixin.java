package dev.metalcraft.client.mixin;

import dev.metalcraft.client.chunk.NativeLodState;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin({RenderSectionRegion.class, SectionCompiler.Results.class, CompiledSectionMesh.class})
abstract class NativeLodStateMixin implements NativeLodState {
    @Unique private int metalcraft$cellSize = 1;
    @Override public int metalcraft$cellSize() { return this.metalcraft$cellSize; }
    @Override public void metalcraft$cellSize(int size) { this.metalcraft$cellSize = size; }
}
