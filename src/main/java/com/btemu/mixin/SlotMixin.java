package com.btemu.mixin;

import com.btemu.TileEntityMonitor;
import net.minecraft.core.crafting.LookupFuelFurnace;
import net.minecraft.core.item.ItemStack;
import net.minecraft.core.player.inventory.container.Container;
import net.minecraft.core.player.inventory.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Slot.class, remap = false)
public abstract class SlotMixin {
	@Shadow
	@org.spongepowered.asm.mixin.Final
	protected Container container;

	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void btemu$fuelOnly(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (container instanceof TileEntityMonitor && stack != null && LookupFuelFurnace.instance.getFuelYield(stack) <= 0)
			cir.setReturnValue(false);
	}
}
