package dev.metalcraft.client.mixin;

import dev.metalcraft.client.metal.MetalShaderEngine;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the render-side static-emitter cache synchronized with the client chunk lifecycle. */
@Mixin(ClientLevel.class)
abstract class ClientLevelLightingMixin {
	@Inject(method = "onChunkLoaded", at = @At("RETURN"))
	private void metalcraft$cacheChunkEmitters(final ChunkPos position, final CallbackInfo callback) {
		ClientLevel level = (ClientLevel)(Object)this;
		MetalShaderEngine.localLightChunkLoaded(level, level.getChunk(position.x(), position.z()));
	}

	@Inject(method = "unload", at = @At("HEAD"))
	private void metalcraft$dropChunkEmitters(final LevelChunk chunk, final CallbackInfo callback) {
		MetalShaderEngine.localLightChunkUnloaded((ClientLevel)(Object)this, chunk);
	}

	@Inject(method = "setBlock", at = @At("RETURN"))
	private void metalcraft$replaceBlockEmitter(
		final BlockPos position,
		final BlockState state,
		final int flags,
		final int recursionLeft,
		final CallbackInfoReturnable<Boolean> callback
	) {
		if (callback.getReturnValueZ()) {
			MetalShaderEngine.localLightBlockChanged((ClientLevel)(Object)this, position, state);
		}
	}

	/*
	 * Network block updates enter through this method and call Level.setBlock with invokespecial,
	 * bypassing ClientLevel.setBlock above. Read the effective state after prediction handling so
	 * the cache follows what the client actually renders rather than an update held for replay.
	 */
	@Inject(method = "setServerVerifiedBlockState", at = @At("RETURN"))
	private void metalcraft$replaceServerVerifiedBlockEmitter(
		final BlockPos position,
		final BlockState state,
		final int flags,
		final CallbackInfo callback
	) {
		ClientLevel level = (ClientLevel)(Object)this;
		MetalShaderEngine.localLightBlockChanged(level, position, level.getBlockState(position));
	}
}
