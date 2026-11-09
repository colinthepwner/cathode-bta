package com.btemu.mixin;

import net.minecraft.core.net.NetworkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = NetworkManager.class, remap = false)
public interface NetworkManagerAccessor {
	@Accessor("estimatedRemaining")
	int btemu$getQueuedBytes();
}
