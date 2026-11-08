package com.btemu.mixin.client;

import com.btemu.client.WebcamFeed;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererMixin {
	@Inject(method = "updateCameraAndRender", at = @At("TAIL"))
	private void btemu$webcam(float pt, CallbackInfo ci) {
		if (!WebcamFeed.rendering) WebcamFeed.afterFrame(pt);
	}

	@Inject(method = "renderWorld", at = @At("HEAD"))
	private void btemu$webcamBeforeWorld(float pt, long finish, CallbackInfo ci) {
		if (!WebcamFeed.rendering) WebcamFeed.beforeWorld(pt);
	}

	@Inject(method = "getMouseOver", at = @At("HEAD"), cancellable = true)
	private void btemu$noAimFromWebcam(float pt, CallbackInfo ci) {
		if (WebcamFeed.rendering) ci.cancel();
	}

	@Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
	private void btemu$noHandOnWebcam(float pt, CallbackInfo ci) {
		if (WebcamFeed.rendering) ci.cancel();
	}
}
