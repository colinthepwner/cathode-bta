package com.btemu.client.tv;

public final class TvAudio {
	public static final int RATE = 48000;
	private final float[] ring = new float[RATE * 2];
	private int w = 0, r = 0;
	private double phase = 0;
	private float lastL, lastR;

	public synchronized void push(float[] stereo, int frames, int rate) {
		if (frames <= 0) return;
		double step = rate / (double) RATE;

		while (phase < frames - 1) {
			int i = (int) Math.floor(phase);
			float f = (float) (phase - i);
			float l0 = i < 0 ? lastL : stereo[i * 2], r0 = i < 0 ? lastR : stereo[i * 2 + 1];
			float l1 = stereo[(i + 1) * 2], r1 = stereo[(i + 1) * 2 + 1];
			put(l0 + (l1 - l0) * f, r0 + (r1 - r0) * f);
			phase += step;
		}
		phase -= frames;
		lastL = stereo[(frames - 1) * 2];
		lastR = stereo[(frames - 1) * 2 + 1];
	}

	private void put(float l, float rr) {
		ring[w] = l;
		ring[w + 1] = rr;
		w = (w + 2) % ring.length;
		if (w == r) r = (r + 2) % ring.length;
	}

	public synchronized int drain(float[] out) {
		int n = 0;
		while (r != w && n * 2 + 1 < out.length) {
			out[n * 2] = ring[r];
			out[n * 2 + 1] = ring[r + 1];
			r = (r + 2) % ring.length;
			n++;
		}
		return n;
	}

	public synchronized int available() {
		return ((w - r + ring.length) % ring.length) / 2;
	}

	public synchronized void clear() {
		r = w;
		phase = 0;
	}
}
