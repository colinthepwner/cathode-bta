package com.btemu.mixin;

import net.minecraft.core.lang.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Mixin(value = {Language.class, Language.Default.class}, remap = false)
public abstract class LanguageMixin {
	private static Properties btemu$own;

	private static Properties btemu$own() {
		if (btemu$own == null) {
			Properties p = new Properties();
			try (InputStream in = LanguageMixin.class.getResourceAsStream("/assets/btemu/lang/en_US/btemu.lang")) {
				if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
			} catch (Exception ignored) {

			}
			btemu$own = p;
		}
		return btemu$own;
	}

	@Inject(method = "translateKey", at = @At("HEAD"), cancellable = true)
	private void btemu$ownKeys(String key, CallbackInfoReturnable<String> cir) {
		if (key == null || !(key.startsWith("tile.btemu.") || key.startsWith("item.btemu.") || key.startsWith("btemu."))) return;
		String v = btemu$own().getProperty(key);
		if (v != null) cir.setReturnValue(v);
	}
}
