package com.btemu.mixin.client;

import com.btemu.TileEntityMonitor;
import net.minecraft.client.gui.container.ScreenContainer;
import net.minecraft.client.gui.container.ScreenContainerAbstract;
import net.minecraft.core.player.inventory.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ScreenContainer.class, remap = false)
public abstract class ScreenContainerMixin {
	@Inject(method = "drawGuiContainerBackgroundLayer", at = @At("TAIL"))
	private void btemu$singleFireboxSlot(float partial, CallbackInfo ci) {
		ScreenContainerAbstract self = (ScreenContainerAbstract) (Object) this;
		Slot fuel = null;
		for (Slot s : self.inventorySlots.slots)
			if (s.getContainer() instanceof TileEntityMonitor && s.isAt(s.getContainer(), TileEntityMonitor.FUEL_SLOT)) fuel = s;
		if (fuel == null) return;
		int left = (self.width - self.xSize) / 2, top = (self.height - self.ySize) / 2;
		for (int i = 0; i < 9; i++) {
			if (i == TileEntityMonitor.FUEL_SLOT) continue;
			int x = left + fuel.x + (i - TileEntityMonitor.FUEL_SLOT) * 18 - 1, y = top + fuel.y - 1;
			self.drawRect(x, y, x + 18, y + 18, 0xFFC6C6C6);
		}
		for (Slot s : self.inventorySlots.slots)
			if (s.getContainer() instanceof TileEntityMonitor && !s.isAt(s.getContainer(), TileEntityMonitor.FUEL_SLOT)) {
				s.x = -10000;
				s.y = -10000;
			}
	}
}
