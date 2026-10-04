package dev.metalcraft.client.mixin;

import dev.metalcraft.client.shader.world.ShadowFrameReuse;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Publication and recycling both invalidate depth reuse, including off-camera casters. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionShadowRevisionMixin {
    @Inject(method = "setSectionMesh", at = {@At("HEAD"), @At("RETURN")})
    private void metalcraft$published(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> callback) {
        ShadowFrameReuse.meshChanged();
    }

    @Inject(method = "reset", at = {@At("HEAD"), @At("RETURN")})
    private void metalcraft$resetting(CallbackInfo callback) {
        ShadowFrameReuse.meshChanged();
    }
}
