package dev.metalcraft.client.metal;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import java.util.OptionalDouble;

/** Blaze3D sampler adapter backed by one immutable Metal sampler state. */
final class MetalGpuSampler extends GpuSampler {
	private final MetalSampler metal;
	private final AddressMode addressModeU;
	private final AddressMode addressModeV;
	private final FilterMode minFilter;
	private final FilterMode magFilter;
	private final int maxAnisotropy;
	private final OptionalDouble maxLod;
	private final MetalGpuSampler[] distant = new MetalGpuSampler[2];

	MetalGpuSampler(
		final MetalSampler metal,
		final AddressMode addressModeU,
		final AddressMode addressModeV,
		final FilterMode minFilter,
		final FilterMode magFilter,
		final int maxAnisotropy,
		final OptionalDouble maxLod
	) {
		this.metal = metal;
		this.addressModeU = addressModeU;
		this.addressModeV = addressModeV;
		this.minFilter = minFilter;
		this.magFilter = magFilter;
		this.maxAnisotropy = maxAnisotropy;
		this.maxLod = maxLod;
	}

	MetalSampler metal() {
		return this.metal;
	}

	/** Reuses half/quarter-resolution atlas mips with anisotropy disabled. */
	synchronized MetalGpuSampler distant(int mip) {
		if (mip < 1 || mip > 2) throw new IllegalArgumentException("Distant texture mip must be 1 or 2");
		if (this.distant[mip-1] == null) {
			var base = this.metal.descriptor();
			var descriptor = new MetalSampler.Descriptor(base.minFilter(), base.magFilter(),
				base.addressModeU(), base.addressModeV(), 1, base.maxLod(), Math.min(mip, base.maxLod()));
			this.distant[mip-1] = new MetalGpuSampler(this.metal.device().createSampler(descriptor),
				this.addressModeU, this.addressModeV, this.minFilter, this.magFilter, 1, this.maxLod);
		}
		return this.distant[mip-1];
	}

	@Override
	public AddressMode getAddressModeU() {
		return this.addressModeU;
	}

	@Override
	public AddressMode getAddressModeV() {
		return this.addressModeV;
	}

	@Override
	public FilterMode getMinFilter() {
		return this.minFilter;
	}

	@Override
	public FilterMode getMagFilter() {
		return this.magFilter;
	}

	@Override
	public int getMaxAnisotropy() {
		return this.maxAnisotropy;
	}

	@Override
	public OptionalDouble getMaxLod() {
		return this.maxLod;
	}

	@Override
	public synchronized void close() {
		for (var sampler : this.distant) if (sampler != null) sampler.close();
		this.metal.close();
	}
}
