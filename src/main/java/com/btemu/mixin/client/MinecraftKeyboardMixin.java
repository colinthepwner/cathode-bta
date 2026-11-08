package com.btemu.mixin.client;

import com.btemu.client.MonitorInput;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = Minecraft.class, remap = false)
public abstract class MinecraftKeyboardMixin {
	@Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Keyboard;next()Z"))
	private boolean btemu$swallowKeysWhileCaptured() {
		if (!MonitorInput.isCapturing()) {

			while (Keyboard.next()) {
				int k = Keyboard.getEventKey();
				boolean arrow = k == Keyboard.KEY_UP || k == Keyboard.KEY_DOWN || k == Keyboard.KEY_LEFT || k == Keyboard.KEY_RIGHT;
				if (arrow && Keyboard.getEventKeyState() && MonitorInput.arrowFocus()) continue;
				return true;
			}
			return false;
		}
		if (com.btemu.client.WebInput.active()) {
			com.btemu.client.WebInput.keyEvents((Minecraft) (Object) this);
			return false;
		}
		while (Keyboard.next()) {
			if (Keyboard.getEventKeyState() && Keyboard.getEventKey() == Keyboard.KEY_ESCAPE) {
				((Minecraft) (Object) this).displayInGameMenu();
				break;
			}
		}
		return false;
	}
}
