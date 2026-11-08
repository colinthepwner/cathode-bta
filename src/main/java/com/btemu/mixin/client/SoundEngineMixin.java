package com.btemu.mixin.client;

import com.btemu.client.MonitorInput;
import net.minecraft.client.sound.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SoundEngine.class, remap = false)
public abstract class SoundEngineMixin {

	@Inject(method = "playSoundWithIdAtPos(Lnet/minecraft/client/sound/SoundEntry;Lnet/minecraft/core/sound/SoundCategory;FFFFFLjava/lang/String;Z)V", at = @At("HEAD"))
	private void btemu$toMic(net.minecraft.client.sound.SoundEntry entry, net.minecraft.core.sound.SoundCategory cat, float x, float y, float z, float volume, float pitch, String id, boolean flag, CallbackInfo ci) {
		com.btemu.client.MicSounds.played(entry, x, y, z, volume, pitch);
	}

	@Inject(method = "tickBackgroundMusic", at = @At("HEAD"), cancellable = true)
	private void btemu$noNewMusicWhileFocused(CallbackInfo ci) {
		if (MonitorInput.hasMonitor()) ci.cancel();
	}
}
