package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Promotes Fabulous/transparency internals to HDR and registers linear post contracts while a
 * fabulous session token is active. Does not begin a session.
 */
public final class MetalLinearWorldPostActivation {
	private static final Identifier TRANSPARENCY_CHAIN_ID = Identifier.withDefaultNamespace("transparency");
	private static final ThreadLocal<Boolean> LOADING_VERIFIED_TRANSPARENCY = new ThreadLocal<>();
	private static volatile boolean liveTransparencyVerified;

	private MetalLinearWorldPostActivation() { }

	public static void beginChainLoad(final Identifier id, final PostChainConfig config) {
		boolean transparency = TRANSPARENCY_CHAIN_ID.equals(id);
		boolean verified = transparency && LinearWorldTransparencyConfig.tryVerify(config);
		LOADING_VERIFIED_TRANSPARENCY.set(verified);
		if (transparency) liveTransparencyVerified = verified;
	}

	public static boolean finishChainLoad() {
		try {
			return Boolean.TRUE.equals(LOADING_VERIFIED_TRANSPARENCY.get());
		} finally {
			LOADING_VERIFIED_TRANSPARENCY.remove();
		}
	}

	public static boolean liveTransparencyVerified() {
		return liveTransparencyVerified;
	}

	public static RenderTargetDescriptor promoteFabulousLayers(
		final @Nullable MetalGpuDevice device,
		final RenderTargetDescriptor descriptor
	) {
		return promote(descriptor, fabulousSession(device) && liveTransparencyVerified);
	}

	public static RenderTargetDescriptor promoteTransparencyInternal(
		final @Nullable MetalGpuDevice device,
		final boolean verifiedTransparencyChain,
		final RenderTargetDescriptor descriptor
	) {
		return promote(descriptor, fabulousSession(device) && verifiedTransparencyChain);
	}

	public static void registerCreatedPass(final @Nullable MetalGpuDevice device, final RenderPipeline pipeline) {
		if (device == null || pipeline == null || !Boolean.TRUE.equals(LOADING_VERIFIED_TRANSPARENCY.get())) {
			return;
		}
		LinearWorldPostShaders.Semantic semantic = semanticFor(pipeline.getFragmentShader());
		if (semantic != null) device.registerLinearWorldPostContract(pipeline, semantic);
	}

	static RenderTargetDescriptor promote(final RenderTargetDescriptor descriptor, final boolean promote) {
		if (descriptor == null) throw new NullPointerException("descriptor");
		if (!promote || descriptor.format() == GpuFormat.RGBA16_FLOAT) return descriptor;
		if (descriptor.format() != GpuFormat.RGBA8_UNORM) return descriptor;
		return new RenderTargetDescriptor(
			descriptor.width(), descriptor.height(), descriptor.useDepth(),
			descriptor.clearColor(), GpuFormat.RGBA16_FLOAT);
	}

	static boolean fabulousSession(final @Nullable MetalGpuDevice device) {
		if (device == null) return false;
		MetalLinearWorldSession session = device.linearWorldSession();
		return session != null && session.token().fabulous();
	}

	static LinearWorldPostShaders.@Nullable Semantic semanticFor(final @Nullable Identifier fragmentShader) {
		if (fragmentShader == null || !"minecraft".equals(fragmentShader.getNamespace())) return null;
		return switch (fragmentShader.getPath()) {
			case "post/transparency" -> LinearWorldPostShaders.Semantic.FABULOUS_TRANSPARENCY;
			case "post/blit" -> LinearWorldPostShaders.Semantic.LINEAR_COPY;
			default -> null;
		};
	}

	static void resetForTest() {
		liveTransparencyVerified = false;
		LOADING_VERIFIED_TRANSPARENCY.remove();
	}
}
