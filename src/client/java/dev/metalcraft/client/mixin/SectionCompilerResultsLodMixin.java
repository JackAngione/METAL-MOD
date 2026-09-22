package dev.metalcraft.client.mixin;

import dev.metalcraft.client.lod.LodCaptureOwner;
import dev.metalcraft.client.lod.LodCapturedMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionCompiler.Results.class)
abstract class SectionCompilerResultsLodMixin implements LodCaptureOwner {
    @Unique private @Nullable LodCapturedMesh metalcraft$lodCandidate;

    @Override public void metalcraft$lodCandidate(@Nullable LodCapturedMesh candidate) {
        if (this.metalcraft$lodCandidate != null) this.metalcraft$lodCandidate.close();
        this.metalcraft$lodCandidate = candidate;
    }

    @Override public @Nullable LodCapturedMesh metalcraft$takeLodCandidate() {
        var candidate = this.metalcraft$lodCandidate;
        this.metalcraft$lodCandidate = null;
        return candidate;
    }

    @Inject(method = "release", at = @At("HEAD"))
    private void metalcraft$releaseLod(CallbackInfo ci) { this.metalcraft$lodCandidate(null); }
}
