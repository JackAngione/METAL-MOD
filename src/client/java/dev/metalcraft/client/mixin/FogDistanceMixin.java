package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Clear ordinary distance haze through the shared native/Metal fog uniforms. */
@Mixin(FogRenderer.class)
abstract class FogDistanceMixin {
    @Shadow @Final private static List<FogEnvironment> FOG_ENVIRONMENTS;

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void metalcraft$clearDistanceFog(Camera camera, int chunks, DeltaTracker delta, float darken,
                                            ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        if (!MetalCraftConfig.clearDistanceFog()
                || !"Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName())) return;
        FogType type = camera.getFluidInCamera();
        if (type == FogType.NONE) type = FogType.ATMOSPHERIC;
        // Respect the same priority as vanilla: fluids and status effects take precedence.
        for (FogEnvironment environment : FOG_ENVIRONMENTS) {
            if (!environment.isApplicable(type, camera.entity())) continue;
            if (!(environment instanceof AtmosphericFogEnvironment)) return;
            // Keep weather, including its fade-out, and boss-imposed visibility limits.
            if (((AtmosphericFogAccessor)environment).metalcraft$rainFogMultiplier() > 0.001F
                    || level.getRainLevel(delta.getGameTimeDeltaPartialTick(false)) > 0.0F
                    || Minecraft.getInstance().gui.hud.getBossOverlay().shouldCreateWorldFog()) return;
            FogData fog = cir.getReturnValue();
            // Finite, distinct endpoints beyond loaded and distant terrain avoid shader infinities/NaNs.
            float start = Math.max(1024.0F, Math.max(chunks, dev.metalcraft.client.lod.LodSystem.horizon()) * 16.0F * 4.0F);
            fog.renderDistanceStart = start;
            fog.renderDistanceEnd = start + 16.0F;
            fog.environmentalStart = start;
            fog.environmentalEnd = start + 16.0F;
            // Sky/cloud fades hide their finite geometry; retain those independently.
            return;
        }
    }
}
