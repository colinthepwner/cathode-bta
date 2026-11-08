package com.btemu.client;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

final class MonitorFont {
	static final int LINE = 10;
	private static boolean[][] glyphs;
	private static int[] widths;

	private MonitorFont() {}

	private static void load() {
		glyphs = new boolean[256][64];
		widths = new int[256];
		try (InputStream in = MonitorFont.class.getResourceAsStream("/assets/minecraft/textures/gui/font/default/font_00.png")) {
			BufferedImage img = ImageIO.read(in);
			int cell = img.getWidth() / 16;
			int step = Math.max(1, cell / 8);
			for (int c = 0; c < 256; c++) {
				int x0 = (c % 16) * cell, y0 = (c / 16) * cell;
				int right = -1;
				for (int y = 0; y < 8; y++)
					for (int x = 0; x < 8; x++) {
						boolean on = (img.getRGB(x0 + x * step, y0 + y * step) >>> 24) > 128;
						glyphs[c][y * 8 + x] = on;
						if (on) right = Math.max(right, x);
					}
				widths[c] = c == ' ' ? 4 : right + 2;
			}
		} catch (Exception e) {

			for (int c = 0; c < 256; c++) widths[c] = 6;
		}
	}

	static int width(String s) {
		if (glyphs == null) load();
		int w = 0;
		for (int i = 0; i < s.length(); i++) w += widths[s.charAt(i) & 0xFF];
		return w;
	}

	static void draw(int[] buf, int stride, int height, String s, int x, int y, int color) {
		if (glyphs == null) load();
		for (int i = 0; i < s.length(); i++) {
			int c = s.charAt(i) & 0xFF;
			boolean[] g = glyphs[c];
			for (int gy = 0; gy < 8; gy++)
				for (int gx = 0; gx < 8; gx++) {
					if (!g[gy * 8 + gx]) continue;
					int px = x + gx, py = y + gy;
					if (px >= 0 && py >= 0 && px < stride && py < height) buf[py * stride + px] = color;
				}
			x += widths[c];
		}
	}

	static String fit(String s, int max) {
		if (width(s) <= max) return s;
		while (s.length() > 1 && width(s + "..") > max) s = s.substring(0, s.length() - 1);
		return s + "..";
	}
}
