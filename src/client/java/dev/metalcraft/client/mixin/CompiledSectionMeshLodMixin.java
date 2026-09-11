package dev.metalcraft.client.mixin;

import dev.metalcraft.client.lod.LodCaptureOwner;
import dev.metalcraft.client.lod.LodCapturedMesh;
import dev.metalcraft.client.lod.LodCompilerCapture;
import dev.metalcraft.client.lod.LodMeshSource;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
abstract class CompiledSectionMeshLodMixin implements LodMeshSource {
    @Unique private @Nullable LodCapturedMesh metalcraft$lodCandidate;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void metalcraft$adoptLod(TranslucencyPointOfView pointOfView, SectionCompiler.Results results, CallbackInfo ci) {
        this.metalcraft$lodCandidate = ((LodCaptureOwner)(Object)results).metalcraft$takeLodCandidate();
        if (this.metalcraft$lodCandidate != null) LodCompilerCapture.transferred();
    }

    @Override public @Nullable LodCapturedMesh metalcraft$lodCandidate() { return this.metalcraft$lodCandidate; }

    @Inject(method = "close", at = @At("HEAD"))
    private void metalcraft$closeLod(CallbackInfo ci) {
        if (this.metalcraft$lodCandidate != null) this.metalcraft$lodCandidate.close();
        this.metalcraft$lodCandidate = null;
    }
}
