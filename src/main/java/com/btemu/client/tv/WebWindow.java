package com.btemu.client.tv;

import org.cef.browser.CefBrowser;
import org.cef.handler.CefAudioHandlerAdapter;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WebWindow {
	public static final String HOME = "https://www.google.com";

	static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";

	static final Map<CefBrowser, WebTab> TABS = new ConcurrentHashMap<>();

	public static final int TAB_H = 30, ADDR_H = 38, BAR = TAB_H + ADDR_H;

	public int w, h;

	public final int bar;

	final String appUrl;

	public static final String MCRE = "https://mcrocket.lol";

	public static final String SPOTIFY = "https://open.spotify.com/";

	public static String appUrlFor(int app) {
		return switch (app) {
			case 1 -> MCRE;
			case 2 -> SPOTIFY;
			default -> null;
		};
	}

	public String appUrl() {
		return appUrl;
	}

	public volatile int mediaTier = 4;

	private volatile boolean holo;

	public boolean holo() {
		return holo;
	}

	public void setHolo(boolean on) {
		if (on == holo) return;
		holo = on;
		List<WebTab> all;
		synchronized (this) {
			all = new ArrayList<>(tabs);
		}
		String js = "window.__btHolo=" + (on ? 1 : 0) + ";";
		for (WebTab t : all) Chromium.later(() -> t.executeJavaScript(js, "", 0));
	}

	public boolean pano() {
		WebTab t = active;
		return holo && t != null && t.pano;
	}

	public volatile int[] frame;
	public volatile int frameCounter;
	public final TvAudio audio = new TvAudio();
	private final CefAudioHandlerAdapter audioHandler = TvBrowser.capture(audio);

	private final List<WebTab> tabs = new ArrayList<>();
	private WebTab active;
	private volatile boolean editing;
	private final StringBuilder edit = new StringBuilder();
	private volatile long blockedUntil;
	private volatile String blockedName = "";
	private BufferedImage barImg;
	private boolean disposed;

	public static int[] sizeFor(int blocksW, int blocksH, int panelPerBlock) {

		int w = Math.max(800, Math.min(1280, blocksW * panelPerBlock));
		int h = Math.round(w * blocksH / (float) Math.max(1, blocksW));
		h = Math.max(300, Math.min(1440, h));
		return new int[]{w & ~1, h & ~1};
	}

	private WebWindow(int w, int h, String appUrl) {
		this.w = w;
		this.h = h;
		this.appUrl = appUrl;
		this.bar = appUrl == null ? BAR : 0;
		frame = new int[w * h];
		barImg = new BufferedImage(w, Math.max(1, bar), BufferedImage.TYPE_INT_ARGB);
	}

	public boolean isApp() {
		return appUrl != null;
	}

	boolean allows(String url) {
		return appUrl == null || url == null || url.startsWith(appUrl) || sameSite(url) || url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:");
	}

	private boolean sameSite(String url) {
		String a = siteOf(appUrl), u = siteOf(url);
		return a != null && a.equals(u);
	}

	private static String siteOf(String url) {
		try {
			String h = java.net.URI.create(url).getHost();
			if (h == null) return null;
			String[] p = h.toLowerCase(java.util.Locale.ROOT).split("\\.");
			return p.length < 2 ? h : p[p.length - 2] + "." + p[p.length - 1];
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static WebWindow openApp(int w, int h, String url) {
		WebWindow win = new WebWindow(w, h, url);
		win.attachAudio();
		win.newTab(url);
		return win;
	}

	private void attachAudio() {
		try {
			Chromium.onUi(() -> {
				Chromium.client().addAudioHandler(audioHandler);
				return null;
			});
		} catch (Exception e) {
			throw new RuntimeException("Couldn't open the browser", e);
		}
	}

	public static WebWindow open(int w, int h) {
		WebWindow win = new WebWindow(w, h, null);
		win.attachAudio();
		win.newTab(HOME);
		return win;
	}

	public void newTab(String url) {

		synchronized (this) {
			if (appUrl != null && !tabs.isEmpty()) return;
		}
		try {
			WebTab t = Chromium.onUi(() -> {
				WebTab nt = new WebTab(this, url, w, h - bar);
				TABS.put(nt, nt);
				nt.start();
				return nt;
			});
			synchronized (this) {
				if (disposed) {
					closeNative(t);
					return;
				}
				tabs.add(t);
				activate(t);
			}
		} catch (Exception e) {
			org.slf4j.LoggerFactory.getLogger("btemu-chromium").warn("Couldn't open a tab", e);
		}
	}

	private void activate(WebTab t) {
		WebTab prev = active;
		active = t;

		Chromium.later(() -> {
			if (prev != null && prev != t) prev.setFocus(false);
			t.setFocus(true);
		});
		editing = false;
		compose();
	}

	public synchronized void closeTab(WebTab t) {
		int i = tabs.indexOf(t);
		if (i < 0) return;
		tabs.remove(i);
		closeNative(t);
		if (tabs.isEmpty()) {

			new Thread(() -> newTab(HOME), "BTEmu new tab").start();
			active = null;
			return;
		}
		if (t == active) activate(tabs.get(Math.min(i, tabs.size() - 1)));
		else compose();
	}

	public synchronized void closeActive() {
		if (active != null) closeTab(active);
	}

	public synchronized void nextTab(int dir) {
		if (tabs.size() < 2 || active == null) return;
		int i = (tabs.indexOf(active) + dir + tabs.size()) % tabs.size();
		activate(tabs.get(i));
	}

	private static void closeNative(WebTab t) {
		TABS.remove(t);
		Chromium.later(() -> {
			t.loadURL("about:blank");
			t.close(true);
		});
	}

	public void dispose() {
		List<WebTab> all;
		synchronized (this) {
			disposed = true;
			all = new ArrayList<>(tabs);
			tabs.clear();
			active = null;
		}
		Chromium.client().removeAudioHandler();
		audio.clear();
		for (WebTab t : all) closeNative(t);
	}

	public synchronized void resize(int nw, int nh) {
		if (nw == w && nh == h) return;
		w = nw;
		h = nh;
		synchronized (frameLock()) {
			frame = new int[w * h];
		}
		barImg = new BufferedImage(w, Math.max(1, bar), BufferedImage.TYPE_INT_ARGB);
		for (WebTab t : tabs) {
			Chromium.later(() -> {
				t.resizePage(nw, nh - bar);
				applyZoom(t);
			});
		}
		compose();
	}

	private final Object frameLockObj = new Object();

	public Object frameLock() {
		return frameLockObj;
	}

	static final int DESKTOP_W = 1280;

	public double pageScale() {
		return Math.min(1.0, w / (double) DESKTOP_W) * userZoom;
	}

	private volatile double userZoom = 1.0;

	public void zoomBy(int dir) {
		double z = dir == 0 ? 1.0 : userZoom * (dir > 0 ? 1.1 : 1 / 1.1);
		userZoom = Math.max(0.5, Math.min(3.0, z));
		java.util.List<WebTab> all;
		synchronized (this) {
			all = new java.util.ArrayList<>(tabs);
		}
		Chromium.later(() -> all.forEach(this::applyZoom));
	}

	double zoomLevel() {
		return Math.log(pageScale()) / Math.log(1.2);
	}

	void applyZoom(WebTab t) {
		double z = zoomLevel();
		if (Math.abs(t.getZoomLevel() - z) > 1e-3) t.setZoomLevel(z);
	}

	public boolean pointerLocked() {
		WebTab t = active;
		return t != null && t.pointerLocked;
	}

	private double lockX = -1, lockY = -1, lockFx, lockFy;

	private static final double LOCK_GAIN = 2.5;

	public void lockedMove(double dx, double dy) {
		WebTab t = active;
		if (t == null) return;
		int pw = w, ph = h - bar;
		if (lockX < 0) {
			lockX = pw / 2.0;
			lockY = ph / 2.0;
		}

		double k = pageScale() * LOCK_GAIN;
		lockFx += dx * k;
		lockFy += dy * k;
		int ix = (int) lockFx, iy = (int) lockFy;
		if (ix == 0 && iy == 0) return;
		lockFx -= ix;
		lockFy -= iy;
		lockX += ix;
		lockY += iy;

		int margin = 4;
		if (lockX < margin || lockY < margin || lockX > pw - margin || lockY > ph - margin) {
			t.mouseExit((int) (lockX - ix), (int) (lockY - iy));
			lockX = pw / 2.0;
			lockY = ph / 2.0;
			t.mouseMove((int) lockX, (int) lockY);
			lockX += ix;
			lockY += iy;
		}
		t.mouseMove((int) lockX, (int) lockY);
	}

	public void exitPointerLock() {
		WebTab t = active;
		if (t == null) return;
		t.pointerLocked = false;
		lockX = -1;
		org.cef.browser.CefFrame f = t.getFocusedFrame();
		if (f == null) f = t.getMainFrame();
		if (f != null) f.executeJavaScript("document.exitPointerLock&&document.exitPointerLock()", f.getURL(), 0);
		compose();
	}

	void tabPainted(WebTab t) {
		if (t == active) compose();
	}

	void titleChanged(WebTab t) {
		compose();
	}

	void blockedDownload(String name) {
		blockedName = name == null ? "" : name;
		blockedUntil = System.currentTimeMillis() + 3500;
		compose();
	}

	public boolean editing() {
		return editing;
	}

	public void mouseMove(int x, int y) {
		WebTab t = active;
		if (t != null && y >= bar) t.mouseMove(x, y - bar);
	}

	public void mouseButton(int x, int y, int button, boolean down) {
		WebTab t = active;
		if (t != null && t.pointerLocked) {

			if (lockX < 0) {
				lockX = w / 2.0;
				lockY = (h - bar) / 2.0;
			}
			t.mouseButton((int) lockX, (int) lockY, button, down);
			return;
		}
		if (y < bar) {
			if (down && button == WebTab.MOUSE_LEFT) barClick(x, y);
			else if (down && button == WebTab.MOUSE_MIDDLE && y < TAB_H) {
				WebTab hit = tabAt(x);
				if (hit != null) closeTab(hit);
			}
			return;
		}
		if (editing && down) {
			editing = false;
			compose();
		}
		if (t != null) t.mouseButton(x, y - bar, button, down);
	}

	public void wheel(int x, int y, int notches) {
		WebTab t = active;
		if (t != null && t.pointerLocked && lockX >= 0) {
			t.wheel((int) lockX, (int) lockY, notches);
			return;
		}
		if (t != null && y >= bar) t.wheel(x, y - bar, notches);
	}

	public void key(boolean down, int scancode, int keycode, int mods) {
		WebTab t = active;
		if (t != null) t.key(down, scancode, keycode, mods);
	}

	public void typed(char c) {
		WebTab t = active;
		if (t != null) t.typed(c);
	}

	public void goBack() {
		WebTab t = active;
		if (t != null && t.canGoBack()) t.goBack();
	}

	public void goForward() {
		WebTab t = active;
		if (t != null && t.canGoForward()) t.goForward();
	}

	public void reload() {
		WebTab t = active;
		if (t != null) t.reload();
	}

	private static final int CLOSE_W = 40;

	public volatile boolean exitRequested;

	private int tabWidth() {
		int n = Math.max(1, tabs.size());
		return Math.max(60, Math.min(220, (w - 44 - CLOSE_W) / n));
	}

	private synchronized WebTab tabAt(int x) {
		int tw = tabWidth();
		int i = x / tw;
		return i >= 0 && i < tabs.size() ? tabs.get(i) : null;
	}

	private synchronized void barClick(int x, int y) {
		if (y < TAB_H) {
			if (x >= w - CLOSE_W) {
				exitRequested = true;
				return;
			}
			int tw = tabWidth();
			int i = x / tw;
			if (i < tabs.size()) {
				WebTab t = tabs.get(i);
				if (x - i * tw > tw - 22) closeTab(t);
				else activate(t);
			} else if (x >= tabs.size() * tw && x < tabs.size() * tw + 34) {
				new Thread(() -> newTab(HOME), "BTEmu new tab").start();
			}
			return;
		}
		if (x < 36) goBack();
		else if (x < 72) goForward();
		else if (x < 108) reload();
		else startEditing();
		compose();
	}

	public void startEditing() {
		editing = true;
		synchronized (edit) {
			edit.setLength(0);
		}
		compose();
	}

	public void cancelEditing() {
		editing = false;
		compose();
	}

	public void barType(char c) {
		if (!editing || c < 32 || c == 127) return;
		synchronized (edit) {
			if (edit.length() < 2000) edit.append(c);
		}
		compose();
	}

	public void barBackspace() {
		synchronized (edit) {
			if (editing && edit.length() > 0) edit.setLength(edit.length() - 1);
		}
		compose();
	}

	public void barEnter() {
		String t;
		synchronized (edit) {
			t = edit.toString().trim();
		}
		editing = false;
		WebTab tab = active;
		if (!t.isEmpty() && tab != null) {
			String url;
			if (t.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") || t.startsWith("about:") || t.startsWith("data:"))
				url = t;
			else if (!t.contains(" ") && (t.contains(".") || t.contains(":") || t.startsWith("localhost")))
				url = t.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") || t.startsWith("about:") || t.startsWith("data:") ? t : "https://" + t;
			else
				url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, java.nio.charset.StandardCharsets.UTF_8);
			tab.loadURL(url);
		}
		compose();
	}

	private long lastTick;

	public void tick() {
		long now = System.currentTimeMillis();
		if (now - lastTick < 250) return;
		lastTick = now;
		compose();
	}

	private void compose() {
		WebTab t = active;
		int[] barPx = drawBar();
		synchronized (frameLock()) {
			int[] f = frame;
			if (f.length != w * h) return;
			System.arraycopy(barPx, 0, f, 0, Math.min(barPx.length, w * bar));
			if (t != null) {
				synchronized (t) {
					int n = Math.min(t.page.length, f.length - w * bar);
					if (t.pw == w) System.arraycopy(t.page, 0, f, w * bar, n);
				}
			}
			if (System.currentTimeMillis() < blockedUntil) drawBlocked(f);
		}
		frameCounter++;
	}

	private synchronized int[] drawBar() {
		if (bar == 0) return new int[0];
		BufferedImage img = barImg;
		Graphics2D g = img.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			g.setColor(new Color(0x1E1F22));
			g.fillRect(0, 0, w, TAB_H);
			int tw = tabWidth();
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
			FontMetrics fm = g.getFontMetrics();
			for (int i = 0; i < tabs.size(); i++) {
				WebTab t = tabs.get(i);
				int x0 = i * tw;
				g.setColor(new Color(t == active ? 0x2B2D31 : 0x26272B));
				g.fillRoundRect(x0 + 2, 4, tw - 4, TAB_H, 10, 10);
				g.setColor(new Color(t == active ? 0xEDEDED : 0x9A9A9A));
				g.drawString(fit(fm, t.title == null || t.title.isEmpty() ? t.url : t.title, tw - 34), x0 + 10, 22);
				g.drawString("×", x0 + tw - 18, 22);
			}
			g.setColor(new Color(0xBBBBBB));
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
			g.drawString("+", tabs.size() * tw + 10, 23);

			g.setColor(new Color(0xC42B1C));
			g.fillRect(w - CLOSE_W, 0, CLOSE_W, TAB_H);
			g.setColor(Color.WHITE);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
			g.drawString("✕", w - CLOSE_W + 12, 22);

			g.setColor(new Color(0x2B2D31));
			g.fillRect(0, TAB_H, w, ADDR_H);
			g.setColor(new Color(0x111214));
			g.fillRect(0, BAR - 1, w, 1);
			WebTab t = active;
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
			g.setColor(new Color(t != null && t.canGoBack() ? 0xE8E8E8 : 0x5E5E5E));
			g.drawString("←", 9, TAB_H + 26);
			g.setColor(new Color(t != null && t.canGoForward() ? 0xE8E8E8 : 0x5E5E5E));
			g.drawString("→", 45, TAB_H + 26);
			g.setColor(new Color(0xE8E8E8));
			g.drawString(t != null && t.loading ? "×" : "↻", 81, TAB_H + 26);
			int fx = 112;
			g.setColor(new Color(editing ? 0xFFFFFF : 0x3C3F45));
			g.fillRoundRect(fx, TAB_H + 5, w - fx - 8, ADDR_H - 10, 16, 16);
			if (editing) {
				g.setColor(new Color(0x4A90E2));
				g.drawRoundRect(fx, TAB_H + 5, w - fx - 9, ADDR_H - 11, 16, 16);
			}
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
			fm = g.getFontMetrics();
			String text;
			if (editing) {
				synchronized (edit) {
					text = edit + ((System.currentTimeMillis() / 500) % 2 == 0 ? "|" : " ");
				}
				int room = w - fx - 30;
				while (text.length() > 1 && fm.stringWidth(text) > room) text = text.substring(1);
			} else {
				text = fit(fm, t == null ? "" : t.url, w - fx - 30);
			}
			g.setColor(new Color(editing ? 0x111111 : 0xD8D8D8));
			g.drawString(text, fx + 12, TAB_H + 25);
		} finally {
			g.dispose();
		}
		return img.getRGB(0, 0, w, BAR, null, 0, w);
	}

	private static String fit(FontMetrics fm, String s, int room) {
		if (s == null) return "";
		if (fm.stringWidth(s) <= room) return s;
		while (s.length() > 1 && fm.stringWidth(s + "…") > room) s = s.substring(0, s.length() - 1);
		return s + "…";
	}

	private void drawBlocked(int[] f) {
		int bw = Math.min(w - 40, 520), bh = 110;
		BufferedImage img = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(0x202124));
			g.fillRoundRect(0, 0, bw, bh, 18, 18);
			g.setColor(new Color(0xE05050));
			g.drawRoundRect(1, 1, bw - 3, bh - 3, 18, 18);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
			FontMetrics fm = g.getFontMetrics();
			String a = "Can't download in Minecraft";
			g.setColor(Color.WHITE);
			g.drawString(a, (bw - fm.stringWidth(a)) / 2, 45);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
			fm = g.getFontMetrics();
			String b = fit(fm, blockedName, bw - 30);
			g.setColor(new Color(0xAAAAAA));
			g.drawString(b, (bw - fm.stringWidth(b)) / 2, 78);
		} finally {
			g.dispose();
		}
		int[] px = img.getRGB(0, 0, bw, bh, null, 0, bw);
		int x0 = (w - bw) / 2, y0 = bar + (h - bar - bh) / 2;
		for (int y = 0; y < bh; y++)
			for (int x = 0; x < bw; x++) {
				int c = px[y * bw + x];
				if ((c >>> 24) < 128) continue;
				int fy = y0 + y, fx = x0 + x;
				if (fy >= 0 && fy < h && fx >= 0 && fx < w) f[fy * w + fx] = c | 0xFF000000;
			}
	}
}
