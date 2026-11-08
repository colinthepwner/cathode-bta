package com.btemu.client;

import com.btemu.MonitorKind;
import com.btemu.gb.GameBoy;

final class ScreenEffects {
	private static final int N = GameBoy.WIDTH * GameBoy.HEIGHT;

	final boolean vivid;
	final int PLAIN_W = GameBoy.WIDTH, PLAIN_H = GameBoy.HEIGHT;

	private final float[] persist = new float[N * 3];
	private final float[] bloom = new float[N * 3];
	private final float[] tmp = new float[N * 3];
	private final float[] vignette = new float[N];

	private float beamCarry = 0.35f, persistKeep = 0.45f;

	ScreenEffects(boolean vivid, boolean wooden) {
		this(vivid);
		if (wooden) {
			beamCarry = 0.55f;
			persistKeep = 0.6f;
		}
	}

	ScreenEffects(boolean vivid) {
		this.vivid = vivid;
		for (int y = 0; y < GameBoy.HEIGHT; y++)
			for (int x = 0; x < GameBoy.WIDTH; x++) {
				float dx = (x + 0.5f) / GameBoy.WIDTH * 2 - 1, dy = (y + 0.5f) / GameBoy.HEIGHT * 2 - 1;
				vignette[y * GameBoy.WIDTH + x] = 1f - 0.28f * (dx * dx + dy * dy);
			}
	}

	void lcd(int[] src, int[] plain) {
		if (!vivid) {
			System.arraycopy(src, 0, plain, 0, N);
			return;
		}

		for (int i = 0; i < N; i++) {
			int c = src[i];
			float r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
			float l = 0.299f * r + 0.587f * g + 0.114f * b;
			r = (l + (r - l) * 1.25f) * 1.12f;
			g = (l + (g - l) * 1.25f) * 1.12f;
			b = (l + (b - l) * 1.25f) * 1.12f;
			plain[i] = pack(Math.max(0, r), Math.max(0, g), Math.max(0, b));
		}
	}

	void crt(int[] src, int[] plain) {

		final int W = GameBoy.WIDTH;
		float k = beamCarry, keep = persistKeep;
		for (int y = 0; y < GameBoy.HEIGHT; y++) {
			float sr = 0, sg = 0, sb = 0;
			for (int x = 0; x < W; x++) {
				int i = y * W + x;
				int c = src[i];
				sr = Math.max((c >> 16) & 0xFF, sr * k + ((c >> 16) & 0xFF) * (1 - k));
				sg = Math.max((c >> 8) & 0xFF, sg * k + ((c >> 8) & 0xFF) * (1 - k));
				sb = Math.max(c & 0xFF, sb * k + (c & 0xFF) * (1 - k));
				persist[i * 3] = Math.max(sr, persist[i * 3] * keep);
				persist[i * 3 + 1] = Math.max(sg, persist[i * 3 + 1] * keep);
				persist[i * 3 + 2] = Math.max(sb, persist[i * 3 + 2] * keep);
			}
		}

		blur(persist, tmp, 1, 0);
		blur(tmp, bloom, 0, 1);
		for (int i = 0; i < N; i++) {
			float v = vignette[i];

			float r = screen(persist[i * 3], bloom[i * 3] * 0.6f) * v;
			float g = screen(persist[i * 3 + 1], bloom[i * 3 + 1] * 0.6f) * v;
			float b = screen(persist[i * 3 + 2], bloom[i * 3 + 2] * 0.6f) * v;
			plain[i] = pack(r, g, b);
		}
	}

	static int[] maskTile(MonitorKind kind, float alpha) {
		int[] t = new int[9];
		for (int sy = 0; sy < 3; sy++)
			for (int sx = 0; sx < 3; sx++) {
				float[] m = new float[3];

				if (kind.crt) {
					float row = sy == 0 ? 1.08f : sy == 1 ? 1.15f : 0.77f;
					for (int ch = 0; ch < 3; ch++) m[ch] = row * (ch == sx ? 2f : 0.3f);
				} else {
					float row = sy == 2 ? 0.85f : 1.04f;
					for (int ch = 0; ch < 3; ch++) m[ch] = row * (ch == sx ? 2f : 0.2f);
				}
				int r = enc(m[0], alpha), g = enc(m[1], alpha), b = enc(m[2], alpha);
				t[sy * 3 + sx] = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		return t;
	}

	private static int enc(float m, float alpha) {
		float v = 0.5f + (Math.min(2f, m) / 2f - 0.5f) * alpha;
		return Math.max(0, Math.min(255, Math.round(v * 255f)));
	}

	private static float screen(float a, float b) {
		return 255f - (255f - a) * (255f - Math.min(255f, b)) / 255f;
	}

	private static void blur(float[] in, float[] out, int dx, int dy) {
		final int r = 3;
		final float norm = 1f / (2 * r + 1);
		int w = GameBoy.WIDTH, h = GameBoy.HEIGHT;
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				float sr = 0, sg = 0, sb = 0;
				for (int t = -r; t <= r; t++) {
					int xx = Math.min(w - 1, Math.max(0, x + t * dx));
					int yy = Math.min(h - 1, Math.max(0, y + t * dy));
					int j = (yy * w + xx) * 3;
					sr += in[j];
					sg += in[j + 1];
					sb += in[j + 2];
				}
				int o = (y * w + x) * 3;
				out[o] = sr * norm;
				out[o + 1] = sg * norm;
				out[o + 2] = sb * norm;
			}
	}

	private static int pack(float r, float g, float b) {
		int ri = (int) Math.min(255f, r), gi = (int) Math.min(255f, g), bi = (int) Math.min(255f, b);
		return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
	}
}
