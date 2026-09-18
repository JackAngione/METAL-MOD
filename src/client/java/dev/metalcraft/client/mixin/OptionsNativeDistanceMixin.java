package dev.metalcraft.client.mixin;

import dev.metalcraft.client.chunk.NativeChunkDistance;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(Options.class)
abstract class OptionsNativeDistanceMixin {
    // Field initializers also construct IntRanges. Scope to the render option itself.
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/OptionInstance$IntRange;<init>(IIZ)V", ordinal = 0), index = 1,
            slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=options.renderDistance"),
                    to = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;renderDistance:Lnet/minecraft/client/OptionInstance;")))
    private int metalcraft$renderDistanceMaximum(int original) { return NativeChunkDistance.MAX; }
}
