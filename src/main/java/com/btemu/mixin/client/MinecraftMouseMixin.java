package com.btemu.mixin.client;

import com.btemu.client.WebInput;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class, remap = false)
public abstract class MinecraftMouseMixin {
	@Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;next()Z"))
	private boolean btemu$mouseToBrowser() {
		return WebInput.mouseNext();
	}

	@Inject(method = "runTick", at = @At("HEAD"))
	private void btemu$spTest(CallbackInfo ci) {
		com.btemu.client.SpTest.beforeWorld();
	}
}
