package com.btemu.client;

import net.minecraft.client.sound.SoundCategoryHelper;
import net.minecraft.client.sound.SoundEngine;
import net.minecraft.core.sound.SoundCategory;

import java.lang.reflect.Method;

public final class MusicDucker {
	private MusicDucker() {}

	private static final float FADE_S = 1.2f;

	private static float duck = 0f;
	private static Method getSystem, setVolume;

	public static void tick() {
		float target = MonitorInput.hasMonitor() ? 1f : 0f;

		if (duck == 0f && target == 0f) return;
		float step = 1f / (FADE_S * 20f);
		duck = target > duck ? Math.min(target, duck + step) : Math.max(target, duck - step);
		try {
			if (getSystem == null) getSystem = SoundEngine.class.getMethod("getSoundSystem");
			Object sys = getSystem.invoke(null);
			if (sys == null) return;
			if (setVolume == null) setVolume = sys.getClass().getMethod("setVolume", String.class, float.class);
			float base = SoundCategoryHelper.getEffectiveVolume(SoundCategory.MUSIC);

			float k = 1f - duck;
			setVolume.invoke(sys, SoundEngine.BG_MUSIC, base * k * k);
		} catch (Exception ignored) {

		}
	}
}
