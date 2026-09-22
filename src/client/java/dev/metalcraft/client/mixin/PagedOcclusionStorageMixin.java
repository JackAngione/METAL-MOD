package dev.metalcraft.client.mixin;

import dev.metalcraft.client.chunk.PagedSectionTable;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Keep the graph's native nodes/algorithm; page only its sparse integer lookup. */
@Mixin(targets = "net.minecraft.client.renderer.SectionOcclusionGraph$SectionToNodeMap")
abstract class PagedOcclusionStorageMixin {
    @Unique private PagedSectionTable<Object> metalcraft$nodes;

    @ModifyVariable(method = "<init>", at = @At("LOAD"), argsOnly = true, ordinal = 0)
    private int metalcraft$deferArray(int size) {
        if (size <= 131072) return size;
        metalcraft$nodes = new PagedSectionTable<>(size);
        return 0;
    }
    @Inject(method = "put", at = @At("HEAD"), cancellable = true)
    private void metalcraft$put(SectionRenderDispatcher.RenderSection section, @Coerce Object node, CallbackInfo ci) {
        if (metalcraft$nodes != null) { metalcraft$nodes.put(section.index,node); ci.cancel(); }
    }
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void metalcraft$get(SectionRenderDispatcher.RenderSection section, CallbackInfoReturnable<Object> cir) {
        if (metalcraft$nodes != null) cir.setReturnValue(metalcraft$nodes.get(section.index));
    }
}
