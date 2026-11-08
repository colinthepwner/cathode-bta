package com.btemu.client.tv;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.lwjgl.sdl.SDLKeycode;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

public final class WebTab extends CefBrowserOsr {
	public static final int MOUSE_LEFT = 1, MOUSE_MIDDLE = 2, MOUSE_RIGHT = 3;

	final WebWindow owner;
	volatile String title = "New tab";
	volatile String url = "";
	volatile boolean loading;

	volatile boolean pointerLocked;

	volatile boolean pano;

	final boolean[] media = new boolean[3];
	private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID = new java.util.concurrent.atomic.AtomicInteger();

	final int id = NEXT_ID.incrementAndGet();

	volatile Object shareSrc;

	@SuppressWarnings("unchecked")
	final java.util.concurrent.atomic.AtomicReference<String>[] pendingVideo = new java.util.concurrent.atomic.AtomicReference[]{
			new java.util.concurrent.atomic.AtomicReference<String>(), new java.util.concurrent.atomic.AtomicReference<String>()};

	int[] page;
	int pw, ph;
	private int mouseButton, clicks;
	private long lastClickMs;

	WebTab(WebWindow owner, String startUrl, int pw, int ph) {
		super(Chromium.client(), startUrl, false, null, null);
		this.owner = owner;
		this.url = startUrl;
		this.pw = pw;
		this.ph = ph;
		page = new int[pw * ph];
		java.util.Arrays.fill(page, 0xFFFFFFFF);
		browser_rect_ = new Rectangle(0, 0, pw, ph);
	}

	void start() {
		createImmediately();
		setWindowlessFrameRate(30);
		wasResized(pw, ph);
	}

	void resizePage(int nw, int nh) {
		synchronized (this) {
			pw = nw;
			ph = nh;
			page = new int[nw * nh];
			java.util.Arrays.fill(page, 0xFFFFFFFF);
		}
		browser_rect_ = new Rectangle(0, 0, nw, nh);
		wasResized(nw, nh);
	}

	@Override
	public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
		if (popup) return;
		synchronized (this) {
			if (width != pw || height != ph) return;
			IntBuffer px = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
			px.get(page, 0, Math.min(page.length, px.remaining()));
			for (int i = 0; i < page.length; i++) page[i] |= 0xFF000000;
		}
		owner.tabPainted(this);
	}

	@Override
	public boolean startDragging(CefBrowser browser, org.cef.callback.CefDragData dragData, int mask, int x, int y) {
		return false;
	}

	void mouseMove(int x, int y) {
		sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, x, y, clicks, mouseButton, held()));
	}

	void mouseExit(int x, int y) {
		sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_EXIT, x, y, 0, 0, 0));
	}

	private int held() {
		return mouseButton >= 1 && mouseButton <= 3 ? CefMouseEvent.BUTTON1_MASK << (mouseButton - 1) : 0;
	}

	void mouseButton(int x, int y, int button, boolean down) {
		if (down) {

			Chromium.later(() -> setFocus(true));
			long now = System.currentTimeMillis();
			clicks = now - lastClickMs < 500 ? 2 : 1;
			lastClickMs = now;
			mouseButton = button;
			sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_PRESSED, x, y, clicks, button, held()));
		} else {
			sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_RELEASED, x, y, clicks, button, held()));
			mouseButton = 0;
		}
	}

	void wheel(int x, int y, int notches) {
		sendMouseWheelEvent(new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, x, y, notches * 3, 0));
	}

	void key(boolean down, int scancode, int keycode, int mods) {
		char ch = (keycode & (SDLKeycode.SDLK_SCANCODE_MASK | SDLKeycode.SDLK_EXTENDED_MASK)) == 0 ? (char) keycode : (char) 0;
		CefKeyEvent e = new CefKeyEvent(down ? CefKeyEvent.KEY_PRESS : CefKeyEvent.KEY_RELEASE, keycode, ch, mods);
		e.scancode = scancode;
		sendKeyEvent(e);
	}

	void typed(char c) {
		sendKeyEvent(new CefKeyEvent(CefKeyEvent.KEY_TYPE, c, c, 0));
	}
}
