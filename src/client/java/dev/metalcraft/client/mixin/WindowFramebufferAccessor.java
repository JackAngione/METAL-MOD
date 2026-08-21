package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Window.class)
public interface WindowFramebufferAccessor {
	@Accessor("framebufferWidth")
	void metalcraft$setFramebufferWidth(int width);

	@Accessor("framebufferHeight")
	void metalcraft$setFramebufferHeight(int height);
}
