package com.btemu.mixin.client;

import com.btemu.client.WebInput;
import net.minecraft.client.input.InputDeviceMouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = InputDeviceMouse.class, remap = false)
public abstract class InputDeviceMouseMixin {
	@Inject(method = {"isPressed", "isPressEvent", "isReleaseEvent"}, at = @At("HEAD"), cancellable = true)
	private void btemu$noBindingsWhileBrowsing(int button, CallbackInfoReturnable<Boolean> cir) {
		if (WebInput.mouseCaptured()) cir.setReturnValue(false);
	}
}
