package com.btemu.mixin.client;

import com.btemu.client.MonitorInput;
import net.minecraft.client.input.PlayerInput;
import net.minecraft.core.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PlayerInput.class, remap = false)
public abstract class PlayerInputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void btemu$freezeMovement(Player player, CallbackInfo ci) {
		com.btemu.client.MusicDucker.tick();
		PlayerInput self = (PlayerInput) (Object) this;
		if (!MonitorInput.isCapturing()) {

			if (!MonitorInput.hasMonitor() && MonitorInput.lookedAt() != null)
				self.pressedLookUp = self.pressedLookDown = self.pressedLookLeft = self.pressedLookRight = false;
			return;
		}
		if (com.btemu.client.WebInput.altLook()) return;
		self.moveForward = 0f;
		self.moveStrafe = 0f;
		self.jump = false;

		self.sneak = false;

		self.pressedLookUp = self.pressedLookDown = self.pressedLookLeft = self.pressedLookRight = false;
	}
}
