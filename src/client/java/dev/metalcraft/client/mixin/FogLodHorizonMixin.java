package dev.metalcraft.client.mixin;

import dev.metalcraft.client.lod.LodDistantRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Change the renderer's distance cutoff, retaining environmental/water/status-effect fog. */
@Mixin(FogRenderer.class)
abstract class FogLodHorizonMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void metalcraft$horizonFog(Camera camera,int distance,DeltaTracker delta,float darken,ClientLevel level,
                                     CallbackInfoReturnable<FogData> cir) {
        int horizon=LodDistantRenderer.horizon();
        if(horizon<=distance) return;
        FogData fog=cir.getReturnValue();
        fog.renderDistanceStart=horizon*16*.9f;
        fog.renderDistanceEnd=horizon*16;
        // Scale long atmospheric haze, while preserving short biome/weather/status/fluid fog.
        if (camera.getFluidInCamera()==net.minecraft.world.level.material.FogType.NONE
                && fog.environmentalEnd>=distance*16) {
            float scale=(float)horizon/distance;
            fog.environmentalStart*=scale;
            fog.environmentalEnd*=scale;
            if (fog.skyEnd>=distance*16) fog.skyEnd=horizon*16;
        }
    }
}
