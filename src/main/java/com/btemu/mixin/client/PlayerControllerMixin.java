package com.btemu.mixin.client;

import com.btemu.MonitorGroup;
import com.btemu.MonitorKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.controller.PlayerController;
import net.minecraft.core.util.helper.Side;
import net.minecraft.core.world.pos.TilePosc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PlayerController.class, remap = false)
public abstract class PlayerControllerMixin {
	@Shadow
	@Final
	protected Minecraft mc;

	private boolean btemu$screenClick(TilePosc pos, Side side) {
		if (mc.thePlayer == null || mc.currentWorld == null || pos == null || side == null || mc.thePlayer.isSneaking()) return false;
		int id = mc.currentWorld.getBlockId(pos.x(), pos.y(), pos.z());
		if (MonitorKind.byBlockId(id) == null) return false;
		int[] f = MonitorGroup.facing3(mc.currentWorld.getBlockData(pos));
		return side.offsetX() == f[0] && side.offsetY() == f[1] && side.offsetZ() == f[2];
	}

	@Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void btemu$clickScreen(TilePosc pos, Side side, double xHit, double yHit, boolean repeat, CallbackInfo ci) {
		if (!btemu$screenClick(pos, side)) return;
		ci.cancel();

		if (!repeat) {
			PlayerController self = (PlayerController) (Object) this;
			self.useOrPlaceItemStackOnTile(mc.thePlayer, mc.currentWorld, mc.thePlayer.getHeldItem(), pos, side, xHit, yHit);
		}
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void btemu$noMiningScreen(TilePosc pos, Side side, double xHit, double yHit, CallbackInfo ci) {
		if (btemu$screenClick(pos, side)) ci.cancel();
	}
}
