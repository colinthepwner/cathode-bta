package com.btemu.mixin.client;

import com.btemu.client.MonitorInput;
import net.minecraft.client.gui.hud.component.HudComponentCrosshair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = HudComponentCrosshair.class, remap = false)
public abstract class HudCrosshairMixin {
	@Inject(method = "isVisible", at = @At("HEAD"), cancellable = true)
	private void btemu$hideWhileFocused(CallbackInfoReturnable<Boolean> cir) {

		if (MonitorInput.hasMonitor() && !(com.btemu.client.WebInput.active() && !com.btemu.client.WebInput.mouseCaptured()))
			cir.setReturnValue(false);
	}
}
