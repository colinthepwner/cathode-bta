package com.btemu.mixin.client;

import com.btemu.client.MonitorInput;
import net.minecraft.client.input.InputDeviceKeyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = InputDeviceKeyboard.class, remap = false)
public abstract class InputDeviceKeyboardMixin {
	@Inject(method = {"isPressed", "isPressEvent", "isReleaseEvent"}, at = @At("HEAD"), cancellable = true)
	private void btemu$noBindingsWhileCaptured(int keyCode, CallbackInfoReturnable<Boolean> cir) {
		if (MonitorInput.isCapturing() && !com.btemu.client.WebInput.altLook()) cir.setReturnValue(false);
	}
}
