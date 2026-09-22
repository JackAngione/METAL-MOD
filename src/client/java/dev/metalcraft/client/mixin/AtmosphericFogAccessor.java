package dev.metalcraft.client.mixin;

import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AtmosphericFogEnvironment.class)
public interface AtmosphericFogAccessor {
    @Accessor("rainFogMultiplier") float metalcraft$rainFogMultiplier();
}
