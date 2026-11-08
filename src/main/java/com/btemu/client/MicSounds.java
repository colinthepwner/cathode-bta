package com.btemu.client;

import com.btemu.MonitorGroup;
import com.btemu.client.tv.WebMedia;
import com.btemu.client.tv.WebWindow;
import net.minecraft.client.sound.SoundEntry;

import javax.sound.sampled.AudioFormat;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class MicSounds {
	private MicSounds() {}

	private static final int RATE = 48000, CHUNK = RATE / 25;
	private static final double RANGE = 16;

	private static final float LEVEL = 0.35f;

	private static final class Mix {
		final float[] ring = new float[RATE * 8];
		long read;

		synchronized void add(float[] clip, float gain) {
			int n = Math.min(clip.length, ring.length - CHUNK * 2);
			long start = read + CHUNK;
			for (int i = 0; i < n; i++) ring[(int) ((start + i) % ring.length)] += clip[i] * gain;
		}

		synchronized byte[] take() {
			byte[] out = null;
			for (int i = 0; i < CHUNK; i++) {
				int at = (int) ((read + i) % ring.length);
				float v = ring[at];
				ring[at] = 0;
				if (v == 0) continue;
				if (out == null) out = new byte[CHUNK * 2];
				int s = Math.max(-32768, Math.min(32767, Math.round(v * 32767)));
				out[2 * i] = (byte) s;
				out[2 * i + 1] = (byte) (s >> 8);
			}
			read += CHUNK;
			return out;
		}
	}

	private static final Map<WebWindow, Mix> MIXES = new java.util.concurrent.ConcurrentHashMap<>();

	private static final Map<String, Clip> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Clip> e) {
			return size() > 160;
		}
	};

	private record Clip(float[] mono, float rate) {}

	private static final ScheduledExecutorService WORK = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "BTEmu mic sounds");
		t.setDaemon(true);
		return t;
	});

	static {
		WORK.scheduleAtFixedRate(MicSounds::pump, 40, 40, TimeUnit.MILLISECONDS);
	}

	public static void played(SoundEntry entry, float x, float y, float z, float volume, float pitch) {
		if (entry == null || !WebMedia.any(WebMedia.MIC)) return;
		java.util.List<Object[]> hits = null;
		for (Display d : Display.all()) {
			WebWindow w = d.webBrowser();
			MonitorGroup g = d.group;
			if (w == null || g == null || WebcamFeed.mediaTier(d.kind) == 0 || !WebMedia.wants(w, WebMedia.MIC)) continue;
			double cx = g.cx + g.fx * 0.1, cy = g.cy + g.fy * 0.1, cz = g.cz + g.fz * 0.1;
			double dist = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz));
			if (dist > RANGE) continue;
			float fall = (float) (1 - dist / RANGE);
			if (hits == null) hits = new java.util.ArrayList<>();
			hits.add(new Object[]{w, Math.min(1f, volume * entry.volume) * fall * fall * LEVEL});
		}
		if (hits == null) return;
		URL url;
		try {
			url = entry.getURL();
		} catch (Throwable t) {
			return;
		}
		if (url == null) return;
		float p = pitch * (entry.pitch > 0 ? entry.pitch : 1f);
		java.util.List<Object[]> fh = hits;
		WORK.execute(() -> {
			Clip c = clip(url);
			if (c == null) return;
			float[] out = resample(c, p);
			for (Object[] h : fh) MIXES.computeIfAbsent((WebWindow) h[0], k -> new Mix()).add(out, (Float) h[1]);
		});
	}

	private static void pump() {
		try {
			for (Map.Entry<WebWindow, Mix> e : MIXES.entrySet()) {
				if (!WebMedia.wants(e.getKey(), WebMedia.MIC)) {
					MIXES.remove(e.getKey());
					continue;
				}
				byte[] pcm = e.getValue().take();
				if (pcm != null) WebMedia.sendPcm(e.getKey(), pcm);
			}
		} catch (Throwable ignored) {

		}
	}

	private static Clip clip(URL url) {
		String key = url.toString();
		synchronized (CACHE) {
			Clip c = CACHE.get(key);
			if (c != null) return c;
		}

		Object codec = null;
		try {
			String cls = key.toLowerCase(java.util.Locale.ROOT).endsWith(".wav") ? "paulscode.sound.codecs.CodecWav" : "paulscode.sound.codecs.CodecJOrbis";
			codec = Class.forName(cls).getConstructor().newInstance();
			Class<?> ic = Class.forName("paulscode.sound.ICodec");
			if (!(Boolean) ic.getMethod("initialize", URL.class).invoke(codec, url)) return null;
			Object b = ic.getMethod("readAll").invoke(codec);
			if (b == null) return null;
			byte[] data = (byte[]) b.getClass().getField("audioData").get(b);
			AudioFormat f = (AudioFormat) b.getClass().getField("audioFormat").get(b);
			if (f == null) f = (AudioFormat) ic.getMethod("getAudioFormat").invoke(codec);
			if (data == null) return null;
			Clip c = toMono(data, f);
			if (c == null) return null;
			synchronized (CACHE) {
				CACHE.put(key, c);
			}
			return c;
		} catch (Throwable t) {
			return null;
		} finally {
			if (codec != null) {
				try {
					codec.getClass().getMethod("cleanup").invoke(codec);
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private static Clip toMono(byte[] data, AudioFormat f) {
		if (f == null || f.getSampleSizeInBits() != 16) return null;
		int ch = Math.max(1, f.getChannels());
		boolean big = f.isBigEndian();
		int frames = data.length / (2 * ch);
		float[] mono = new float[frames];
		for (int i = 0; i < frames; i++) {
			float sum = 0;
			for (int c = 0; c < ch; c++) {
				int at = (i * ch + c) * 2;
				int s = big ? (data[at] << 8) | (data[at + 1] & 0xFF) : (data[at + 1] << 8) | (data[at] & 0xFF);
				sum += (short) s / 32768f;
			}
			mono[i] = sum / ch;
		}
		return new Clip(mono, f.getSampleRate());
	}

	private static float[] resample(Clip c, float pitch) {
		double step = c.rate() * Math.max(0.25f, Math.min(4f, pitch)) / RATE;
		int n = (int) Math.min((long) (c.mono().length / step), RATE * 6L);
		float[] out = new float[n];
		float[] in = c.mono();
		for (int i = 0; i < n; i++) {
			double pos = i * step;
			int a = (int) pos;
			if (a + 1 >= in.length) {
				out[i] = in[in.length - 1];
				continue;
			}
			float t = (float) (pos - a);
			out[i] = in[a] + (in[a + 1] - in[a]) * t;
		}
		return out;
	}
}
