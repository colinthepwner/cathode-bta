package com.btemu.client;

import com.btemu.MonitorGroup;
import com.btemu.gb.GameBoy;
import net.minecraft.core.entity.player.Player;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

public final class MonitorInput {
	private MonitorInput() {}

	private static volatile Display active = null;

	private static final double REACH = 10.0;

	private static final boolean STARE_TO_FOCUS = false;

	private static final long STARE_MS = 1000;
	private static Display staringAt = null;
	private static long stareStart = 0;

	private static Display ignoreUntilLookAway = null;
	private static long lastGazeTick = 0;

	private static final long LOOK_GRACE_MS = 500;
	private static volatile long lastLook = 0;
	private static float lastYaw, lastPitch;
	private static long lastPollMs = 0;
	private static final boolean HAND_BACK_ON_LOOK = false;

	private static final double RELEASE_DEG = 30;
	private static boolean wasSuspended = false;

	public static boolean isCapturing() {

		if (active != null && WebInput.active()) return WebInput.mouseCaptured;
		return active != null && System.currentTimeMillis() - lastLook >= LOOK_GRACE_MS;
	}

	public static boolean hasMonitor() {
		return active != null;
	}

	static Display active() {
		return active;
	}

	static void capture(Display d) {
		if (d == null || !d.running || d.shuttingDown()) return;
		Display prev = active;
		if (prev != null && prev != d) {
			prev.inputMask = 0;
			prev.menuKey = false;
			prev.sendInput();

			turniplabs.halplibe.helper.network.NetworkHandler.sendToServer(new com.btemu.net.Msgs.Focus(prev.pos, false));
		}
		active = d;
		d.resetSentInput();
		Minecraft mc0 = Minecraft.getMinecraft();
		if (mc0.thePlayer != null) { lastYaw = mc0.thePlayer.yRot; lastPitch = mc0.thePlayer.xRot; }
		lastLook = 0;
		wasSuspended = false;

		Minecraft mc = Minecraft.getMinecraft();
		if (mc.thePlayer != null && mc.thePlayer.input != null) mc.thePlayer.input.onGameUnfocused();
	}

	public static void release() {
		release(true);
	}

	static void release(boolean tellServer) {
		Display d = active;
		active = null;
		ignoreUntilLookAway = d;
		if (d != null) {
			d.inputMask = 0;
			d.menuKey = false;
			d.sendInput();
			if (tellServer) turniplabs.halplibe.helper.network.NetworkHandler.sendToServer(new com.btemu.net.Msgs.Focus(d.pos, false));
		}
	}

	private static long lastWantSent = 0;

	static void tickGaze() {
		long now = System.currentTimeMillis();
		if (now - lastGazeTick < 40) return;
		lastGazeTick = now;
		Minecraft mc = Minecraft.getMinecraft();
		Player p = mc.thePlayer;
		if (p == null || mc.currentScreen != null) return;

		org.joml.Vector3dc eye = p.getPosition(1f, true), view = p.getViewVector(1f);
		double ex = eye.x(), ey = eye.y(), ez = eye.z();
		double lx = view.x(), ly = view.y(), lz = view.z();

		Display cur = active;
		if (cur != null && (!cur.running || cur.te.isInvalid() || cur.shuttingDown())) {
			release();
			cur = null;
		}
		Display seen = null;
		for (Display d : Display.all()) {

			if (d.world == mc.currentWorld && d.group != null && d.running && !d.shuttingDown()
				&& looksAt(ex, ey, ez, lx, ly, lz, d.group)) { seen = d; break; }
		}

		if (cur != null && cur.group != null && (seen == null || seen == cur)) {
			if (WebInput.active()) { staringAt = null; return; }
			MonitorGroup g = cur.group;
			double cx = g.cx - ex, cy = g.cy - ey, cz = g.cz - ez;

			double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
			double cos = len < 1e-6 ? 1 : (cx * lx + cy * ly + cz * lz) / len;
			double half = Math.atan2(Math.max(g.w, g.h) / 2.0, Math.max(0.5, len));
			if (Math.acos(Math.max(-1, Math.min(1, cos))) > half + Math.toRadians(RELEASE_DEG)) release();
			staringAt = null;
			return;
		}

		if (seen != ignoreUntilLookAway) ignoreUntilLookAway = null;
		if (seen == null || seen == ignoreUntilLookAway) { staringAt = null; return; }
		if (seen != staringAt) { staringAt = seen; stareStart = now; return; }

		if (STARE_TO_FOCUS && now - stareStart >= STARE_MS && now - lastWantSent > 900) {
			lastWantSent = now;
			turniplabs.halplibe.helper.network.NetworkHandler.sendToServer(new com.btemu.net.Msgs.Focus(seen.pos, true));
		}
	}

	public static Display lookedAt() {
		Minecraft mc = Minecraft.getMinecraft();
		Player p = mc.thePlayer;
		if (p == null || mc.currentScreen != null) return null;
		org.joml.Vector3dc eye = p.getPosition(1f, true), view = p.getViewVector(1f);
		for (Display d : Display.all()) {
			if (d.world == mc.currentWorld && d.group != null && d.running && !d.shuttingDown()
				&& looksAt(eye.x(), eye.y(), eye.z(), view.x(), view.y(), view.z(), d.group)) return d;
		}
		return null;
	}

	public static boolean arrowFocus() {
		if (active != null) return false;
		Display d = lookedAt();
		if (d == null) return false;
		ignoreUntilLookAway = null;
		lastWantSent = System.currentTimeMillis();
		turniplabs.halplibe.helper.network.NetworkHandler.sendToServer(new com.btemu.net.Msgs.Focus(d.pos, true));
		return true;
	}

	static boolean lookingAt(Display d) {
		Minecraft mc = Minecraft.getMinecraft();
		Player p = mc.thePlayer;
		if (p == null || d.group == null) return false;
		org.joml.Vector3dc eye = p.getPosition(1f, true), view = p.getViewVector(1f);
		return looksAt(eye.x(), eye.y(), eye.z(), view.x(), view.y(), view.z(), d.group);
	}

	private static boolean looksAt(double ex, double ey, double ez, double lx, double ly, double lz, MonitorGroup g) {
		return g.hit(ex, ey, ez, lx, ly, lz, REACH) != null;
	}

	static void poll(Display d) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.thePlayer == null || mc.currentWorld != d.world || !d.running) {
			release();
			return;
		}
		double dx = mc.thePlayer.x - (d.te.tilePos.x + 0.5), dy = mc.thePlayer.y - (d.te.tilePos.y + 0.5), dz = mc.thePlayer.z - (d.te.tilePos.z + 0.5);
		if (dx * dx + dy * dy + dz * dz > REACH * REACH) {
			release();
			return;
		}

		if (mc.currentScreen != null) {
			release();
			return;
		}

		float yaw = mc.thePlayer.yRot, pitch = mc.thePlayer.xRot;
		long nowMs = System.currentTimeMillis();
		float dYaw = Math.abs(((yaw - lastYaw) % 360 + 540) % 360 - 180), dPitch = Math.abs(pitch - lastPitch);
		float frameSec = lastPollMs == 0 ? 0.016f : Math.max(0.001f, (nowMs - lastPollMs) / 1000f);
		lastPollMs = nowMs;
		lastYaw = yaw;
		lastPitch = pitch;
		boolean fast = (dYaw + dPitch) / frameSec > 180f;
		boolean offScreen = false;
		if (d.group != null) {
			MonitorGroup g = d.group;
			org.joml.Vector3dc eye = mc.thePlayer.getPosition(1f, true), view = mc.thePlayer.getViewVector(1f);
			double cx = g.cx - eye.x(), cy = g.cy - eye.y(), cz = g.cz - eye.z();
			double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
			double cos = len < 1e-6 ? 1 : (cx * view.x() + cy * view.y() + cz * view.z()) / len;

			double half = Math.atan2(Math.max(g.w, g.h) / 2.0, Math.max(0.5, len));
			offScreen = Math.acos(Math.max(-1, Math.min(1, cos))) > Math.toRadians(45) + half;
		}

		if (HAND_BACK_ON_LOOK && (fast || offScreen)) lastLook = nowMs;
		if (WebInput.active()) {

			d.inputMask = 0;

			int vol = 0;
			if (mc.currentScreen == null && WebInput.mouseCaptured && WebInput.altHeld()) {
				if (Keyboard.isKeyDown(Keyboard.KEY_EQUALS) || Keyboard.isKeyDown(Keyboard.KEY_ADD)) vol |= com.btemu.session.MonitorMenu.BTN_VOL_UP;
				if (Keyboard.isKeyDown(Keyboard.KEY_MINUS) || Keyboard.isKeyDown(Keyboard.KEY_SUBTRACT)) vol |= com.btemu.session.MonitorMenu.BTN_VOL_DOWN;
			}
			d.extraKeys = vol;
			d.menuKey = mc.currentScreen == null && WebInput.mouseCaptured && WebInput.tabHeld();
			com.btemu.client.tv.WebWindow win = d.webBrowser();

			if (win != null && win.isApp() && mc.currentScreen == null && !WebInput.mouseCaptured && Keyboard.isKeyDown(Keyboard.KEY_TAB))
				d.menuKey = true;
			if (win != null && win.exitRequested) {

				win.exitRequested = false;
				d.menuKey = true;
			}
			d.sendInput();
			wasSuspended = false;
			return;
		}
		boolean suspended = !isCapturing();
		if (!suspended && wasSuspended && mc.thePlayer.input != null) {

			mc.thePlayer.input.onGameUnfocused();
		}
		wasSuspended = suspended;
		if (suspended) {
			d.inputMask = 0;
			d.menuKey = false;
			d.extraKeys = 0;
			d.sendInput();
			return;
		}
		int m = 0;

		com.btemu.emu.Console con = com.btemu.emu.Console.of(d.rom);
		boolean n64 = con == com.btemu.emu.Console.N64;
		if (Keyboard.isKeyDown(Keyboard.KEY_UP)) m |= GameBoy.BTN_UP;
		if (Keyboard.isKeyDown(Keyboard.KEY_DOWN)) m |= GameBoy.BTN_DOWN;
		if (Keyboard.isKeyDown(Keyboard.KEY_LEFT)) m |= GameBoy.BTN_LEFT;
		if (Keyboard.isKeyDown(Keyboard.KEY_RIGHT)) m |= GameBoy.BTN_RIGHT;

		if ((m & (GameBoy.BTN_UP | GameBoy.BTN_DOWN)) == (GameBoy.BTN_UP | GameBoy.BTN_DOWN)) m &= ~(GameBoy.BTN_UP | GameBoy.BTN_DOWN);
		if ((m & (GameBoy.BTN_LEFT | GameBoy.BTN_RIGHT)) == (GameBoy.BTN_LEFT | GameBoy.BTN_RIGHT)) m &= ~(GameBoy.BTN_LEFT | GameBoy.BTN_RIGHT);
		if (Keyboard.isKeyDown(Keyboard.KEY_X) || Keyboard.isKeyDown(Keyboard.KEY_SPACE)) m |= GameBoy.BTN_A;
		if (Keyboard.isKeyDown(Keyboard.KEY_Z)) m |= GameBoy.BTN_B;
		if (Keyboard.isKeyDown(Keyboard.KEY_RETURN)) m |= GameBoy.BTN_START;
		if (Keyboard.isKeyDown(Keyboard.KEY_BACK) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) m |= GameBoy.BTN_SELECT;
		if (Keyboard.isKeyDown(Keyboard.KEY_Q)) m |= com.btemu.emu.Machine.BTN_L;
		if (n64) {
			if (Keyboard.isKeyDown(Keyboard.KEY_E)) m |= com.btemu.emu.Machine.BTN_R;
			if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) m |= com.btemu.emu.Machine.BTN_Z;
			if (Keyboard.isKeyDown(Keyboard.KEY_W)) m |= com.btemu.emu.Machine.BTN_C_UP;
			if (Keyboard.isKeyDown(Keyboard.KEY_S)) m |= com.btemu.emu.Machine.BTN_C_DOWN;
			if (Keyboard.isKeyDown(Keyboard.KEY_A)) m |= com.btemu.emu.Machine.BTN_C_LEFT;
			if (Keyboard.isKeyDown(Keyboard.KEY_D)) m |= com.btemu.emu.Machine.BTN_C_RIGHT;
		} else {
			if (Keyboard.isKeyDown(Keyboard.KEY_S)) m |= com.btemu.emu.Machine.BTN_X;
			if (Keyboard.isKeyDown(Keyboard.KEY_A)) m |= com.btemu.emu.Machine.BTN_Y;
			if (Keyboard.isKeyDown(Keyboard.KEY_W)) m |= com.btemu.emu.Machine.BTN_R;
		}
		d.inputMask = m;
		d.menuKey = Keyboard.isKeyDown(Keyboard.KEY_TAB);
		int extra = 0;
		if (Keyboard.isKeyDown(Keyboard.KEY_EQUALS) || Keyboard.isKeyDown(Keyboard.KEY_ADD)) extra |= com.btemu.session.MonitorMenu.BTN_VOL_UP;
		if (Keyboard.isKeyDown(Keyboard.KEY_MINUS) || Keyboard.isKeyDown(Keyboard.KEY_SUBTRACT)) extra |= com.btemu.session.MonitorMenu.BTN_VOL_DOWN;
		d.extraKeys = extra;
		d.sendInput();
	}
}
