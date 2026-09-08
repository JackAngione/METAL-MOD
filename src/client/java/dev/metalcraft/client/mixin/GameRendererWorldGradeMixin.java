package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.metal.MetalLinearWorldActivation;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.world.level.material.FogType;
import dev.metalcraft.client.shader.water.WaterFrameInputs;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wraps {@code LevelRenderer.render} with an HDR session and grades at the first hand-depth clear. */
@Mixin(GameRenderer.class)
abstract class GameRendererWorldGradeMixin {
	@Unique
	private MetalLinearWorldActivation.@Nullable Frame metalcraft$linearWorld;

	@Unique private @Nullable Matrix4f metalcraft$waterProjection;

	@Redirect(method = "renderLevel", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
	private GpuBufferSlice metalcraft$captureWaterProjection(final ProjectionMatrixBuffer buffer, final Matrix4f projection) {
		this.metalcraft$waterProjection = new Matrix4f(projection);
		return buffer.getBuffer(projection);
	}

	@Redirect(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;render("
				+ "Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
				+ "Lnet/minecraft/client/DeltaTracker;"
				+ "Z"
				+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
				+ "Lorg/joml/Matrix4fc;"
				+ "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
				+ "Lorg/joml/Vector4f;"
				+ "Z)V"
		)
	)
	private void metalcraft$wrapLinearWorld(
		final LevelRenderer levelRenderer,
		final GraphicsResourceAllocator resourceAllocator,
		final DeltaTracker deltaTracker,
		final boolean renderOutline,
		final CameraRenderState cameraState,
		final Matrix4fc modelViewMatrix,
		final GpuBufferSlice terrainFog,
		final Vector4f fogColor,
		final boolean shouldRenderSky
	) {
		GameRenderer self = (GameRenderer)(Object)this;
		var target = self.mainRenderTarget();
		GpuTextureView color = target.getColorTextureView();
		GpuTextureView depth = target.getDepthTextureView();
		MetalGpuDevice gpu = MetalGpuDevices.current();
		boolean fabulous = levelRenderer instanceof LevelRendererAccessor accessor
			? accessor.metalcraft$getTransparencyChain() != null
			: self.gameRenderState().useShaderTransparency();
		MetalLinearWorldActivation.Frame frame = MetalLinearWorldActivation.beginLive(
			gpu, color, depth, fabulous);
		this.metalcraft$linearWorld = frame;
		try {
			if (gpu != null && gpu.linearWorldSession() != null) {
				gpu.linearWorldSession().waterFrameInputs(WaterFrameInputs.create(this.metalcraft$waterProjection,
					new Vector3d(cameraState.pos.x, cameraState.pos.y, cameraState.pos.z),
					self.gameRenderState().levelRenderState.gameTime, deltaTracker.getGameTimeDeltaPartialTick(false),
					cameraState.fogType == FogType.WATER)
					.map(inputs -> inputs.withSky(self.gameRenderState().levelRenderState.skyRenderState)
						.withRefraction(!fabulous))
					.orElse(null));
			}
			levelRenderer.render(resourceAllocator, deltaTracker, renderOutline, cameraState,
				modelViewMatrix, terrainFog, fogColor, shouldRenderSky);
		} catch (Throwable error) {
			this.metalcraft$linearWorld = null;
			throw error;
		} finally {
			this.metalcraft$waterProjection = null;
			frame.close();
		}
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target =
		"Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
	private void metalcraft$gradeWorld(final CallbackInfo callback) {
		MetalLinearWorldActivation.Frame frame = this.metalcraft$linearWorld;
		this.metalcraft$linearWorld = null;
		if (frame != null) {
			frame.grade();
			return;
		}
		ShaderPackRuntime runtime = ShaderPackRuntime.active();
		if (runtime == null || !runtime.isActive()) return;
		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		runtime.gradeWorld(target.getColorTextureView(), target.getDepthTextureView());
	}
}
