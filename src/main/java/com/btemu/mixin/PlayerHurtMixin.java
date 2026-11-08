package com.btemu.mixin;

import com.btemu.session.Sessions;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.util.helper.DamageType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Player.class, remap = false)
public abstract class PlayerHurtMixin {
	@Inject(method = "hurt", at = @At("RETURN"))
	private void btemu$hurtLosesMonitor(Entity attacker, int damage, DamageType type, CallbackInfoReturnable<Boolean> cir) {
		Player self = (Player) (Object) this;
		if (cir.getReturnValueZ() && self.world != null && !self.world.isClientSide) Sessions.onPlayerHurt(self);
	}

	@Inject(method = "onDeath", at = @At("HEAD"))
	private void btemu$deathLosesMonitor(Entity killer, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (self.world != null && !self.world.isClientSide) Sessions.onPlayerHurt(self);
	}
}
