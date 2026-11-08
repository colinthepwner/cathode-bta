package com.btemu.client;

import com.btemu.client.tv.WebTab;
import com.btemu.client.tv.WebWindow;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.sdl.SDLKeycode;

public final class WebInput {
	private WebInput() {}

	static float cx = 200, cy = 200;

	static boolean mouseCaptured = false;

	static volatile boolean dirty = false;
	private static Display lastWeb = null;
	private static long tabDownSince = 0;
	static final long TAB_HOLD_MS = 800;

	static WebWindow browser() {
		Display d = web();
		return d == null ? null : d.webBrowser();
	}

	static Display web() {
		Display d = MonitorInput.active();
		if (d == null || !d.tvWeb || !d.tvActive() || !d.isTvHostLocalPublic()) {
			if (lastWeb != null) {
				lastWeb.cursorX = cx;
				lastWeb.cursorY = cy;
				dirty = true;
			}
			lastWeb = null;
			mouseCaptured = false;
			return null;
		}
		if (d != lastWeb) {

			lastWeb = d;
			mouseCaptured = true;
			dirty = true;

			if (lastWeb != null) {
				lastWeb.cursorX = cx;
				lastWeb.cursorY = cy;
			}
			WebWindow w = d.webBrowser();
			cx = d.cursorX >= 0 ? d.cursorX : w == null ? 200 : w.w / 2f;
			cy = d.cursorY >= 0 ? d.cursorY : w == null ? 200 : w.h / 2f;
		}
		return d;
	}

	public static boolean active() {
		return web() != null;
	}

	public static boolean mouseCaptured() {
		return web() != null && mouseCaptured;
	}

	public static boolean altLook() {
		WebWindow b = browser();
		return mouseCaptured() && altHeld() && (b == null || !b.editing());
	}

	public static boolean takeMouseMotion(double dx, double dy) {
		if (!mouseCaptured() || altLook()) return false;
		if (dx == 0 && dy == 0) return true;
		WebWindow b = browser();
		if (b != null && b.pointerLocked()) {

			b.lockedMove(dx, -dy);
			return true;
		}
		int bw = b == null ? 960 : b.w, bh = b == null ? 540 : b.h;

		float k = bw / 960f;
		cx = Math.max(0, Math.min(bw - 1, cx + (float) dx * k));
		cy = Math.max(0, Math.min(bh - 1, cy - (float) dy * k));
		if (b != null) b.mouseMove((int) cx, (int) cy);
		dirty = true;
		return true;
	}

	public static boolean mouseNext() {
		Display d = web();
		if (d == null) return Mouse.next();
		while (Mouse.next()) {
			int btn = Mouse.getEventButton();
			boolean down = Mouse.getEventButtonState();
			int wheel = Mouse.getEventDWheel();
			Minecraft mc = Minecraft.getMinecraft();
			if (down && btn >= 0 && !mc.inGameHasFocus && mc.currentScreen == null) {

				mc.setIngameFocus();
				if (!mouseCaptured && MonitorInput.lookingAt(d)) mouseCaptured = true;
				dirty = true;
				continue;
			}
			if (mouseCaptured) {
				WebWindow b = d.webBrowser();
				if (b == null) continue;

				if (btn >= 0 && btn <= 2) {
					int id = btn == 0 ? WebTab.MOUSE_LEFT : btn == 1 ? WebTab.MOUSE_RIGHT : WebTab.MOUSE_MIDDLE;
					b.mouseButton((int) cx, (int) cy, id, down);
				}
				if (wheel != 0) b.wheel((int) cx, (int) cy, Integer.signum(wheel));
				continue;
			}

			if (down && (btn == 0 || btn == 1) && MonitorInput.lookingAt(d)) {
				mouseCaptured = true;
				if (!Mouse.isGrabbed()) Minecraft.getMinecraft().setIngameFocus();
				dirty = true;
				continue;
			}
			return true;
		}
		return false;
	}

	public static void keyEvents(Minecraft mc) {
		Display d = web();
		WebWindow b = d == null ? null : d.webBrowser();
		while (Keyboard.next()) {
			int key = Keyboard.getEventKey();
			boolean down = Keyboard.getEventKeyState();
			char ch = Keyboard.getEventCharacter();
			boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
			if (key == Keyboard.KEY_ESCAPE) {
				if (!down) continue;
				if (b != null && b.editing()) {
					b.cancelEditing();
				} else if (b != null && b.pointerLocked()) {
					b.exitPointerLock();
				} else if (b != null && (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT))) {

					b.key(true, 41, SDLKeycode.SDLK_ESCAPE, mods());
					b.key(false, 41, SDLKeycode.SDLK_ESCAPE, mods());
				} else {

					mouseCaptured = false;
					dirty = true;
					return;
				}
				continue;
			}
			if (b == null) continue;

			if (altHeld() && (key == Keyboard.KEY_MINUS || key == Keyboard.KEY_EQUALS || key == Keyboard.KEY_SUBTRACT || key == Keyboard.KEY_ADD))
				continue;

			if (key == Keyboard.KEY_LMENU || key == Keyboard.KEY_RMENU) {
				if (!down && !altHeld()) releaseToGame(mc);
				continue;
			}

			if (down && altLook() && !(key == Keyboard.KEY_LEFT || key == Keyboard.KEY_RIGHT)) {
				toGame(mc, key, true);
				continue;
			}
			if (!down && GAME_HELD.contains(key)) toGame(mc, key, false);

			if (ctrl && (key == Keyboard.KEY_EQUALS || key == Keyboard.KEY_ADD || key == Keyboard.KEY_MINUS || key == Keyboard.KEY_SUBTRACT || key == Keyboard.KEY_0 || key == Keyboard.KEY_NUMPAD0)) {
				if (down) b.zoomBy(key == Keyboard.KEY_EQUALS || key == Keyboard.KEY_ADD ? 1 : key == Keyboard.KEY_MINUS || key == Keyboard.KEY_SUBTRACT ? -1 : 0);
				continue;
			}

			boolean app = b.isApp();

			if (down && ctrl && key == Keyboard.KEY_L && !app) {
				b.startEditing();
				continue;
			}

			if (down && ctrl && key == Keyboard.KEY_T && !app) {
				new Thread(() -> b.newTab(WebWindow.HOME), "BTEmu new tab").start();
				continue;
			}
			if (down && ctrl && key == Keyboard.KEY_W && !app) {
				b.closeActive();
				continue;
			}
			if (ctrl && key == Keyboard.KEY_TAB && !app) {
				if (down) b.nextTab(Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT) ? -1 : 1);
				continue;
			}

			if (down && !app && Keyboard.isKeyDown(Keyboard.KEY_LMENU) && (key == Keyboard.KEY_LEFT || key == Keyboard.KEY_RIGHT)) {
				if (key == Keyboard.KEY_LEFT) b.goBack();
				else b.goForward();
				continue;
			}
			if (b.editing()) {
				if (!down) continue;
				if (key == Keyboard.KEY_RETURN) b.barEnter();
				else if (key == Keyboard.KEY_BACK) b.barBackspace();
				else if (ch >= 32 && ch != 127 && !ctrl) b.barType(ch);
				continue;
			}

			if (key == Keyboard.KEY_TAB) {
				if (down) {
					tabDownSince = System.currentTimeMillis();
				} else {
					boolean tap = System.currentTimeMillis() - tabDownSince < TAB_HOLD_MS;
					tabDownSince = 0;
					if (tap) {
						b.key(true, 43, SDLKeycode.SDLK_TAB, mods());
						b.key(false, 43, SDLKeycode.SDLK_TAB, mods());
					}
				}
				continue;
			}
			int sc = scancode(key);
			int kc = keycode(key, sc);
			if (sc != 0) b.key(down, sc, kc, mods());

			if (down && ch >= 32 && ch != 127 && !Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) && !Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)
					&& !Keyboard.isKeyDown(Keyboard.KEY_LMENU))
				b.typed(ch);
			if (down && key == Keyboard.KEY_RETURN) b.typed('\r');
		}
	}

	private static final java.util.Set<Integer> GAME_HELD = new java.util.HashSet<>();

	private static void toGame(Minecraft mc, int key, boolean down) {
		if (mc.thePlayer == null || mc.thePlayer.input == null) return;
		mc.thePlayer.input.keyEvent(key, -1, down);
		if (down) GAME_HELD.add(key);
		else GAME_HELD.remove(key);
	}

	private static void releaseToGame(Minecraft mc) {
		for (Integer k : GAME_HELD.toArray(new Integer[0])) toGame(mc, k, false);
	}

	static boolean altHeld() {
		return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
	}

	static boolean tabHeld() {
		return tabDownSince != 0 && Keyboard.isKeyDown(Keyboard.KEY_TAB) && System.currentTimeMillis() - tabDownSince >= TAB_HOLD_MS;
	}

	private static int mods() {
		int m = 0;
		if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) m |= 0x0001;
		if (Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) m |= 0x0002;
		if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)) m |= 0x0040;
		if (Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) m |= 0x0080;
		if (Keyboard.isKeyDown(Keyboard.KEY_LMENU)) m |= 0x0100;
		if (Keyboard.isKeyDown(Keyboard.KEY_RMENU)) m |= 0x0200;
		return m;
	}

	private static final java.util.Map<Integer, Integer> SCAN = new java.util.HashMap<>();
	static {
		SCAN.put(Keyboard.KEY_A, 4);
		SCAN.put(Keyboard.KEY_B, 5);
		SCAN.put(Keyboard.KEY_C, 6);
		SCAN.put(Keyboard.KEY_D, 7);
		SCAN.put(Keyboard.KEY_E, 8);
		SCAN.put(Keyboard.KEY_F, 9);
		SCAN.put(Keyboard.KEY_G, 10);
		SCAN.put(Keyboard.KEY_H, 11);
		SCAN.put(Keyboard.KEY_I, 12);
		SCAN.put(Keyboard.KEY_J, 13);
		SCAN.put(Keyboard.KEY_K, 14);
		SCAN.put(Keyboard.KEY_L, 15);
		SCAN.put(Keyboard.KEY_M, 16);
		SCAN.put(Keyboard.KEY_N, 17);
		SCAN.put(Keyboard.KEY_O, 18);
		SCAN.put(Keyboard.KEY_P, 19);
		SCAN.put(Keyboard.KEY_Q, 20);
		SCAN.put(Keyboard.KEY_R, 21);
		SCAN.put(Keyboard.KEY_S, 22);
		SCAN.put(Keyboard.KEY_T, 23);
		SCAN.put(Keyboard.KEY_U, 24);
		SCAN.put(Keyboard.KEY_V, 25);
		SCAN.put(Keyboard.KEY_W, 26);
		SCAN.put(Keyboard.KEY_X, 27);
		SCAN.put(Keyboard.KEY_Y, 28);
		SCAN.put(Keyboard.KEY_Z, 29);
		SCAN.put(Keyboard.KEY_1, 30);
		SCAN.put(Keyboard.KEY_2, 31);
		SCAN.put(Keyboard.KEY_3, 32);
		SCAN.put(Keyboard.KEY_4, 33);
		SCAN.put(Keyboard.KEY_5, 34);
		SCAN.put(Keyboard.KEY_6, 35);
		SCAN.put(Keyboard.KEY_7, 36);
		SCAN.put(Keyboard.KEY_8, 37);
		SCAN.put(Keyboard.KEY_9, 38);
		SCAN.put(Keyboard.KEY_0, 39);
		SCAN.put(Keyboard.KEY_RETURN, 40);
		SCAN.put(Keyboard.KEY_BACK, 42);
		SCAN.put(Keyboard.KEY_SPACE, 44);
		SCAN.put(Keyboard.KEY_MINUS, 45);
		SCAN.put(Keyboard.KEY_EQUALS, 46);
		SCAN.put(Keyboard.KEY_LBRACKET, 47);
		SCAN.put(Keyboard.KEY_RBRACKET, 48);
		SCAN.put(Keyboard.KEY_BACKSLASH, 49);
		SCAN.put(Keyboard.KEY_SEMICOLON, 51);
		SCAN.put(Keyboard.KEY_APOSTROPHE, 52);
		SCAN.put(Keyboard.KEY_GRAVE, 53);
		SCAN.put(Keyboard.KEY_COMMA, 54);
		SCAN.put(Keyboard.KEY_PERIOD, 55);
		SCAN.put(Keyboard.KEY_SLASH, 56);
		SCAN.put(Keyboard.KEY_F5, 62);
		SCAN.put(Keyboard.KEY_HOME, 74);
		SCAN.put(Keyboard.KEY_PRIOR, 75);
		SCAN.put(Keyboard.KEY_DELETE, 76);
		SCAN.put(Keyboard.KEY_END, 77);
		SCAN.put(Keyboard.KEY_NEXT, 78);
		SCAN.put(Keyboard.KEY_RIGHT, 79);
		SCAN.put(Keyboard.KEY_LEFT, 80);
		SCAN.put(Keyboard.KEY_DOWN, 81);
		SCAN.put(Keyboard.KEY_UP, 82);

		SCAN.put(Keyboard.KEY_LSHIFT, 225);
		SCAN.put(Keyboard.KEY_RSHIFT, 229);
		SCAN.put(Keyboard.KEY_LCONTROL, 224);
		SCAN.put(Keyboard.KEY_RCONTROL, 228);
		SCAN.put(Keyboard.KEY_LMENU, 226);
		SCAN.put(Keyboard.KEY_RMENU, 230);
		SCAN.put(Keyboard.KEY_LMETA, 227);
		SCAN.put(Keyboard.KEY_RMETA, 231);
		SCAN.put(Keyboard.KEY_F1, 58);
		SCAN.put(Keyboard.KEY_F2, 59);
		SCAN.put(Keyboard.KEY_F3, 60);
		SCAN.put(Keyboard.KEY_F4, 61);
		SCAN.put(Keyboard.KEY_F6, 63);
		SCAN.put(Keyboard.KEY_F7, 64);
		SCAN.put(Keyboard.KEY_F8, 65);
		SCAN.put(Keyboard.KEY_F9, 66);
		SCAN.put(Keyboard.KEY_F10, 67);
		SCAN.put(Keyboard.KEY_F11, 68);
		SCAN.put(Keyboard.KEY_F12, 69);
		SCAN.put(Keyboard.KEY_CAPITAL, 57);
		SCAN.put(Keyboard.KEY_INSERT, 73);
		SCAN.put(Keyboard.KEY_NUMLOCK, 83);
		SCAN.put(Keyboard.KEY_SCROLL, 71);
		SCAN.put(Keyboard.KEY_PAUSE, 72);
		SCAN.put(Keyboard.KEY_DIVIDE, 84);
		SCAN.put(Keyboard.KEY_MULTIPLY, 85);
		SCAN.put(Keyboard.KEY_SUBTRACT, 86);
		SCAN.put(Keyboard.KEY_ADD, 87);
		SCAN.put(Keyboard.KEY_NUMPADENTER, 88);
		SCAN.put(Keyboard.KEY_NUMPAD1, 89);
		SCAN.put(Keyboard.KEY_NUMPAD2, 90);
		SCAN.put(Keyboard.KEY_NUMPAD3, 91);
		SCAN.put(Keyboard.KEY_NUMPAD4, 92);
		SCAN.put(Keyboard.KEY_NUMPAD5, 93);
		SCAN.put(Keyboard.KEY_NUMPAD6, 94);
		SCAN.put(Keyboard.KEY_NUMPAD7, 95);
		SCAN.put(Keyboard.KEY_NUMPAD8, 96);
		SCAN.put(Keyboard.KEY_NUMPAD9, 97);
		SCAN.put(Keyboard.KEY_NUMPAD0, 98);
		SCAN.put(Keyboard.KEY_DECIMAL, 99);
	}

	private static int scancode(int k) {
		return SCAN.getOrDefault(k, 0);
	}

	private static int keycode(int k, int sc) {
		if (sc >= 4 && sc <= 29) return 'a' + (sc - 4);
		if (sc >= 30 && sc <= 38) return '1' + (sc - 30);
		switch (sc) {
			case 39: return '0';
			case 40: return SDLKeycode.SDLK_RETURN;
			case 42: return SDLKeycode.SDLK_BACKSPACE;
			case 44: return ' ';
			case 45: return '-'; case 46: return '='; case 47: return '['; case 48: return ']'; case 49: return '\\';
			case 51: return ';'; case 52: return '\''; case 53: return '`'; case 54: return ','; case 55: return '.'; case 56: return '/';
			default: return sc | SDLKeycode.SDLK_SCANCODE_MASK;
		}
	}

	static void paintCursor(Display self, int[] px, int w, int h) {

		if (self != lastWeb) return;
		int x0 = (int) cx, y0 = (int) cy;
		Display d = lastWeb;
		WebWindow win = d == null ? null : d.webBrowser();
		if (win != null && win.pointerLocked()) return;

		float pageScale = Math.min(1f, w / 1280f);

		String[] arrow = {
				"o", "oo", "oxo", "oxxo", "oxxxo", "oxxxxo", "oxxxxxo", "oxxxxxxo", "oxxxxoooo", "oxoxxo", "oo oxxo", "o  oxxo", "    oo"};

		int sc = Math.max(1, Math.round(2 * pageScale));
		int alpha = mouseCaptured ? 255 : 110;
		for (int r = 0; r < arrow.length; r++)
			for (int c = 0; c < arrow[r].length(); c++) {
				char ch = arrow[r].charAt(c);
				if (ch == ' ') continue;
				for (int dy = 0; dy < sc; dy++)
					for (int dx = 0; dx < sc; dx++) {
						int x = x0 + c * sc + dx, y = y0 + r * sc + dy;
						if (x < 0 || y < 0 || x >= w || y >= h) continue;
						int o = px[y * w + x];

						int inv = ~o & 0xFFFFFF;
						int lum = (((o >> 16) & 0xFF) * 3 + ((o >> 8) & 0xFF) * 6 + (o & 0xFF)) / 10;
						if (lum > 96 && lum < 160) inv = lum < 128 ? 0xFFFFFF : 0x000000;
						int invLum = (((inv >> 16) & 0xFF) * 3 + ((inv >> 8) & 0xFF) * 6 + (inv & 0xFF)) / 10;
						int col = ch == 'o' ? (invLum >= 128 ? 0x000000 : 0xFFFFFF) : inv;
						int ia = alpha, ic = 255 - ia;
						px[y * w + x] = 0xFF000000 | ((((o >> 16) & 0xFF) * ic + ((col >> 16) & 0xFF) * ia) / 255 << 16)
								| ((((o >> 8) & 0xFF) * ic + ((col >> 8) & 0xFF) * ia) / 255 << 8) | (((o & 0xFF) * ic + (col & 0xFF) * ia) / 255);
					}
			}
	}
}
