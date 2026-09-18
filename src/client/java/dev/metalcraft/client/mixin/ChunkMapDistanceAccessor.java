package dev.metalcraft.client.mixin;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkMap.class)
public interface ChunkMapDistanceAccessor {
    @Accessor("serverViewDistance") int metalcraft$serverViewDistance();
}
