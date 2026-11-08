package com.btemu.client;

import com.btemu.MonitorKind;
import com.btemu.gb.GameBoy;

import java.util.Random;

final class GlitchFx {

	private int W = GameBoy.WIDTH, H = GameBoy.HEIGHT;
	private float sc = 1f;

	private final Random rng = new Random();
	private int[] tmp = new int[W * H];

	private final byte[] deadCol = new byte[GameBoy.WIDTH], deadRow = new byte[GameBoy.HEIGHT];
	private float lineFailLevel = 0;
	private long dropoutUntil = 0;
	private int dropoutSide = 0;
	private long flickerUntil = 0;
	private float flicker = 1f;

	private double roll = 0;
	private long tearUntil = 0;
	private int tearRow = 0, tearShift = 0;

	boolean apply(int[] px, MonitorKind kind, float wet, long nowNs) {
		return apply(px, GameBoy.WIDTH, GameBoy.HEIGHT, kind, wet, nowNs);
	}

	boolean apply(int[] px, int w, int h, MonitorKind kind, float wet, long nowNs) {
		if (wet <= 0.02f || kind.scale >= 6) {
			if (lineFailLevel > 0) healLines();
			return false;
		}
		if (w != W || h != H) {
			roll = roll / H * h;
			W = w;
			H = h;
			sc = w / (float) GameBoy.WIDTH;
		}
		if (tmp.length < W * H) tmp = new int[W * H];
		if (kind.crt) crt(px, wet, nowNs);
		else lcd(px, wet, nowNs);
		return true;
	}

	private void lcd(int[] px, float wet, long now) {
		long ms = now / 1_000_000L;

		while (lineFailLevel < wet) {
			lineFailLevel += 0.08f;
			if (rng.nextBoolean()) deadCol[rng.nextInt(deadCol.length)] = (byte) (rng.nextInt(3) == 0 ? 2 : 1);
			else deadRow[rng.nextInt(deadRow.length)] = (byte) (rng.nextInt(3) == 0 ? 2 : 1);
		}
		if (lineFailLevel > wet + 0.3f) healLines();

		int mode = wet > 0.45f ? (int) ((ms / 700) % 3) : 0;
		for (int i = 0; i < px.length; i++) {
			int c = px[i];
			if (mode == 1) c = 0xFF000000 | ((c & 0xFF) << 16) | ((c >> 16) & 0xFF) << 8 | ((c >> 8) & 0xFF);
			else if (mode == 2) c = 0xFF000000 | (~c & 0xFFFFFF);
			px[i] = c;
		}

		for (int x = 0; x < W; x++) {
			byte f = deadCol[Math.min(deadCol.length - 1, (int) (x / sc))];
			if (f == 0) continue;
			int v = f == 2 ? 0xFFFFFFFF : 0xFF000000;
			for (int y = 0; y < H; y++) px[y * W + x] = v;
		}
		for (int y = 0; y < H; y++) {
			byte f = deadRow[Math.min(deadRow.length - 1, (int) (y / sc))];
			if (f == 0) continue;
			int v = f == 2 ? 0xFFFFFFFF : 0xFF000000;
			for (int x = 0; x < W; x++) px[y * W + x] = v;
		}

		if (ms > dropoutUntil && rng.nextFloat() < 0.012f * wet) {
			dropoutUntil = ms + 150 + rng.nextInt(650);
			dropoutSide = rng.nextInt(4);
		}
		if (ms < dropoutUntil) {
			for (int y = 0; y < H; y++)
				for (int x = 0; x < W; x++) {
					boolean off = switch (dropoutSide) {
						case 0 -> x < W / 2;
						case 1 -> x >= W / 2;
						case 2 -> y < H / 2;
						default -> y >= H / 2;
					};
					if (off) px[y * W + x] = 0xFF000000;
				}
		}

		int flips = (int) (wet * wet * 220 * sc * sc);
		for (int k = 0; k < flips; k++) {
			int i = rng.nextInt(px.length);
			px[i] ^= 1 << (rng.nextInt(24));
			px[i] |= 0xFF000000;
		}

		if (ms > flickerUntil && rng.nextFloat() < 0.03f * wet) {
			flickerUntil = ms + 40 + rng.nextInt(120);
			flicker = 0.15f + rng.nextFloat() * 0.5f;
		}
		if (ms < flickerUntil) scale(px, flicker);
	}

	private void healLines() {
		java.util.Arrays.fill(deadCol, (byte) 0);
		java.util.Arrays.fill(deadRow, (byte) 0);
		lineFailLevel = 0;
	}

	private void crt(int[] px, float wet, long now) {
		double t = now / 1e9;
		System.arraycopy(px, 0, tmp, 0, W * H);

		roll = (roll + wet * wet * 2.2 * sc) % H;
		int r = (int) roll;

		long ms = now / 1_000_000L;
		if (ms > tearUntil && rng.nextFloat() < 0.02f * wet) {
			tearUntil = ms + 60 + rng.nextInt(200);
			tearRow = rng.nextInt(H);
			tearShift = (int) ((rng.nextBoolean() ? 1 : -1) * (6 + rng.nextInt(30)) * sc);
		}

		int conv = (int) Math.round(wet * (2 + Math.sin(t * 0.9) * 2) * sc);
		for (int y = 0; y < H; y++) {
			int sy = (y + r) % H;
			boolean blank = wet > 0.3f && ((y + H - (H - r) % H) % H) < 5 * sc;
			int shift = (int) Math.round(Math.sin(y / sc * 0.15 + t * 7) * wet * 3 * sc);
			if (ms < tearUntil && Math.abs(y - tearRow) < 6 * sc) shift += tearShift;
			for (int x = 0; x < W; x++) {
				if (blank) { px[y * W + x] = 0xFF000000; continue; }
				int rx = clamp(x + shift - conv, W), gx = clamp(x + shift, W), bx = clamp(x + shift + conv, W);
				int rr = (tmp[sy * W + rx] >> 16) & 0xFF, gg = (tmp[sy * W + gx] >> 8) & 0xFF, bb = tmp[sy * W + bx] & 0xFF;
				px[y * W + x] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
			}
		}

		float surge = (float) (1.0 + Math.sin(t * 3.1) * 0.25 * wet + (rng.nextFloat() < 0.02f * wet ? 0.6 : 0));
		scale(px, surge);
	}

	private static int clamp(int v, int n) {
		return v < 0 ? 0 : v >= n ? n - 1 : v;
	}

	private static void scale(int[] px, float k) {
		for (int i = 0; i < px.length; i++) {
			int c = px[i];
			int r = Math.min(255, (int) (((c >> 16) & 0xFF) * k));
			int g = Math.min(255, (int) (((c >> 8) & 0xFF) * k));
			int b = Math.min(255, (int) ((c & 0xFF) * k));
			px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
		}
	}
}
