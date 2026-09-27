package dev.metalcraft.client.lod;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

/** The colour atlas a distant-terrain draw samples through {@code Sampler0} instead of the block atlas. */
public record LodTextureBinding(GpuTextureView view, GpuSampler sampler) { }
