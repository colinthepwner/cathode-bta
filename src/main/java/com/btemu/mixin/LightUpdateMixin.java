package com.btemu.mixin;

import com.btemu.MonitorLight;
import net.minecraft.core.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.core.world.lighting.LightingEngineLegacy$LightUpdate", remap = false)
public abstract class LightUpdateMixin {
	@Redirect(method = "performLightUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/world/World;getBlockId(III)I"))
	private int btemu$noteUpdatePos(World world, int x, int y, int z) {
		MonitorLight.lookingAt(x, y, z);
		return world.getBlockId(x, y, z);
	}

	@Redirect(method = "neighborLightPropagationChanged", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/world/World;getBlockId(III)I"))
	private static int btemu$noteNeighborPos(World world, int x, int y, int z) {
		MonitorLight.lookingAt(x, y, z);
		return world.getBlockId(x, y, z);
	}

	@Redirect(method = "performLightUpdate", at = @At(value = "FIELD", target = "Lnet/minecraft/core/block/Blocks;lightEmission:[I"))
	private int[] btemu$emissionUpdate() {
		return MonitorLight.emissionArray();
	}

	@Redirect(method = "neighborLightPropagationChanged", at = @At(value = "FIELD", target = "Lnet/minecraft/core/block/Blocks;lightEmission:[I"))
	private static int[] btemu$emissionNeighbor() {
		return MonitorLight.emissionArray();
	}
}
