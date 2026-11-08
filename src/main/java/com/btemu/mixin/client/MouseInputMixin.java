package com.btemu.mixin.client;

import com.btemu.client.WebInput;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MouseInput.class, remap = false)
public abstract class MouseInputMixin {
	@Inject(method = "update", at = @At("TAIL"))
	private void btemu$mouseToBrowser(CallbackInfo ci) {
		MouseInput self = (MouseInput) (Object) this;
		if (WebInput.takeMouseMotion(self.deltaX, self.deltaY)) {
			self.deltaX = 0;
			self.deltaY = 0;
		}
	}
}
