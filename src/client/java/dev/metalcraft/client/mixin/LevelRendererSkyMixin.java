package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.sky.SkyFrameInputs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Executes inside vanilla's sky framegraph pass, before terrain/translucency. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererSkyMixin {
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow private SkyRenderer skyRenderer;

    @Inject(method = "lambda$addSkyPass$0", at = @At("HEAD"), cancellable = true)
    private void metalcraft$standardSky(GpuBufferSlice skyFog, SkyRenderState sky, CallbackInfo ci) {
        var gpu = MetalGpuDevices.current();
        var runtime = ShaderPackRuntime.active();
        var camera = this.levelRenderState.cameraRenderState;
        if (gpu == null || runtime == null || runtime.worldSky() == null
            || runtime.worldSky().rasterProjection() == null
            || gpu.linearWorldSession() == null || gpu.linearWorldSession().isPoisoned()
            || sky.skybox != DimensionType.Skybox.OVERWORLD || camera.fogType != FogType.NONE
            || !camera.initialized || camera.entityRenderState.doesMobEffectBlockSky) return;
        var client = Minecraft.getInstance();
        try {
            var inputs = SkyFrameInputs.create(camera, this.levelRenderState,
                client.gameRenderer.gameRenderState().optionsRenderState.cloudStatus,
                client.getDeltaTracker().getGameTimeDeltaPartialTick(false), runtime.worldSky().rasterProjection());
            RenderSystem.setShaderFog(skyFog);
            runtime.worldSky().render(gpu, inputs, () -> {
                if (sky.starBrightness <= 0) return;
                // Same rotations as SkyRenderer.renderSunMoonAndStars, without its textured quads.
                PoseStack pose = new PoseStack();
                pose.mulPose(Axis.YP.rotationDegrees(-90));
                pose.mulPose(Axis.XP.rotation(sky.starAngle));
                ((SkyRendererStarsInvoker)this.skyRenderer).metalcraft$renderStars(sky.starBrightness, pose);
            });
            ci.cancel();
        } catch (RuntimeException error) {
            runtime.markFailed("Could not render Standard sky: " + error.getMessage(), error);
        }
    }
}
