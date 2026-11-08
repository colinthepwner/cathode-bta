package com.btemu.client.tv;

import com.btemu.gb.GameBoy;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.event.CefKeyEvent;
import org.cef.handler.CefAudioHandlerAdapter;
import org.cef.misc.CefAudioParameters;
import org.cef.misc.DataPointer;
import org.lwjgl.sdl.SDLKeycode;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

public final class TvBrowser extends CefBrowserOsr {
	public static final int W = 960, H = 540;
	public static final String URL = "https://www.youtube.com/tv";

	static String startUrl = URL;

	public final int[] frame = new int[W * H];
	public volatile int frameCounter = 0;
	public final TvAudio audio = new TvAudio();
	private final CefAudioHandlerAdapter audioHandler = capture(audio);

	private TvBrowser() {
		super(Chromium.client(), startUrl, false, null, null);
		browser_rect_ = new Rectangle(0, 0, W, H);
	}

	public static TvBrowser open() {
		try {
			return Chromium.onUi(TvBrowser::openOnUi);
		} catch (Exception e) {
			throw new RuntimeException("Couldn't open YouTube TV", e);
		}
	}

	private static TvBrowser openOnUi() {
		TvBrowser b = new TvBrowser();
		Chromium.client().addAudioHandler(b.audioHandler);
		b.createImmediately();
		b.setWindowlessFrameRate(30);
		b.wasResized(W, H);
		b.setFocus(true);
		return b;
	}

	public void dispose() {
		Chromium.client().removeAudioHandler();
		audio.clear();
		try {
			Chromium.onUi(() -> {
				loadURL("about:blank");
				close(true);
				return null;
			});
		} catch (Exception e) {
			close(true);
		}
	}

	@Override
	public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
		if (popup || width != W || height != H) return;
		IntBuffer px = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		synchronized (frame) {
			px.get(frame, 0, Math.min(frame.length, px.remaining()));
			for (int i = 0; i < frame.length; i++) frame[i] |= 0xFF000000;
		}
		frameCounter++;
	}

	private int lastMask = 0;

	public void buttons(int mask) {
		int changed = mask ^ lastMask;
		lastMask = mask;
		if (changed == 0) return;
		key(changed, mask, GameBoy.BTN_UP, SDLKeycode.SDLK_UP, 82);
		key(changed, mask, GameBoy.BTN_DOWN, SDLKeycode.SDLK_DOWN, 81);
		key(changed, mask, GameBoy.BTN_LEFT, SDLKeycode.SDLK_LEFT, 80);
		key(changed, mask, GameBoy.BTN_RIGHT, SDLKeycode.SDLK_RIGHT, 79);
		key(changed, mask, GameBoy.BTN_A, SDLKeycode.SDLK_RETURN, 40);
		key(changed, mask, GameBoy.BTN_START, SDLKeycode.SDLK_RETURN, 40);
		key(changed, mask, GameBoy.BTN_B, SDLKeycode.SDLK_ESCAPE, 41);
		key(changed, mask, GameBoy.BTN_SELECT, SDLKeycode.SDLK_BACKSPACE, 42);
	}

	private void key(int changed, int mask, int bit, int keycode, int scancode) {
		if ((changed & bit) == 0) return;
		boolean down = (mask & bit) != 0;
		CefKeyEvent e = new CefKeyEvent(down ? CefKeyEvent.KEY_PRESS : CefKeyEvent.KEY_RELEASE, keycode, keycode == 0x0D ? '\r' : (char) 0, 0);
		e.scancode = scancode;
		sendKeyEvent(e);
		if (down && keycode == SDLKeycode.SDLK_RETURN) {
			CefKeyEvent t = new CefKeyEvent(CefKeyEvent.KEY_TYPE, '\r', '\r', 0);
			sendKeyEvent(t);
		}
	}

	static boolean DEBUG = false;

	static CefAudioHandlerAdapter capture(TvAudio audio) {
		return new CefAudioHandlerAdapter() {
			private int packets = 0;
			private int channels = 2;
			private int rate = 48000;

			@Override
			public boolean getAudioParameters(CefBrowser browser, CefAudioParameters params) {

				if (params != null && params.sampleRate > 0) rate = params.sampleRate;
				return true;
			}

			@Override
			public void onAudioStreamStarted(CefBrowser browser, CefAudioParameters params, int ch) {
				org.slf4j.LoggerFactory.getLogger("btemu-chromium").info("Browser audio captured: {} channel(s) at {} Hz", ch, rate);
				channels = Math.max(1, ch);
				if (params != null && params.sampleRate > 0) rate = params.sampleRate;
			}

			@Override
			public void onAudioStreamError(CefBrowser browser, String text) {
				if (DEBUG) System.err.println("[tv] stream error " + text);
			}

			@Override
			public void onAudioStreamPacket(CefBrowser browser, DataPointer data, int frames, long pts) {
				if (DEBUG && packets++ < 3) System.err.println("[tv] packet frames=" + frames);

				data.forCapacity(channels * 8);
				DataPointer l = data.getData(0).forCapacity(frames * 4).withAlignment(2);
				DataPointer r = channels > 1 ? data.getData(1).forCapacity(frames * 4).withAlignment(2) : l;
				float[] stereo = new float[frames * 2];
				for (int i = 0; i < frames; i++) {
					stereo[i * 2] = l.getFloat(i);
					stereo[i * 2 + 1] = r.getFloat(i);
				}
				audio.push(stereo, frames, rate);
			}
		};
	}
}
