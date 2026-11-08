package com.btemu.client;

import com.btemu.MonitorKind;
import net.minecraft.client.render.renderer.BlendFactor;
import net.minecraft.client.render.renderer.GLRenderer;
import net.minecraft.client.render.tessellator.TessellatorShader;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL41;

import java.nio.IntBuffer;

final class SubpixelImage {
	private SubpixelImage() {}

	static int perBlock(MonitorKind kind) {
		if (kind == MonitorKind.STEEL) return 700;
		if (kind == MonitorKind.DIAMOND) return 400;
		if (kind.wooden) return 110;
		if (kind.crt) return 140;
		return 200;
	}

	static int cell(MonitorKind kind) {
		return kind.crt ? 6 : 3;
	}

	private static final int TILE = 48;

	private static final int[][] TEX = new int[2][2];

	static void draw(MonitorKind kind, float blocksW, float blocksH, float x0, float y0, float x1, float y1, float zf, float a) {
		drawCells(kind, blocksW * perBlock(kind), blocksH * perBlock(kind), x0, y0, x1, y1, zf, a);
	}

	static int cellsFor(MonitorKind kind, float blocks) {
		return Math.max(1, Math.round(blocks * perBlock(kind)));
	}

	static void drawCells(MonitorKind kind, float cellsW, float cellsH, float x0, float y0, float x1, float y1, float zf, float a) {
		int k = kind.crt ? 1 : 0;
		if (TEX[k][0] == 0) build(kind.crt);
		float tileCells = TILE / (float) cell(kind);
		float u = cellsW / tileCells, v = cellsH / tileCells;

		GLRenderer.setBlendFunc(BlendFactor.DST_COLOR, BlendFactor.ONE_MINUS_SRC_ALPHA);
		quad(TEX[k][1], x0, y0, x1, y1, zf, u, v, a, a);
	}

	private static void quad(int tex, float x0, float y0, float x1, float y1, float zf, float u, float v, float grey, float alpha) {
		GL41.glBindTexture(GL41.GL_TEXTURE_2D, tex);
		TessellatorShader tess = GLRenderer.getTessellator();
		tess.startDrawingQuads();
		int gv = Math.min(255, Math.round(grey * 255));
		tess.setColor2i((gv << 16) | (gv << 8) | gv, Math.min(255, Math.round(alpha * 255)));

		if (FaceClip.emit(tess, x0, y0, x1, y1, zf, 0, 0, u, v)) tess.draw();
	}

	private static float[][] pattern(boolean crt) {
		int c = crt ? 6 : 3;
		float[][] t = new float[TILE * TILE][3];
		for (int y = 0; y < TILE; y++) {
			int row = y / c, ry = y % c;
			int shift = crt && (row & 1) == 1 ? c / 2 : 0;
			for (int x = 0; x < TILE; x++) {
				int rx = (x + shift) % c;
				float[] m = t[y * TILE + x];

				if (crt) {

					for (int e = 0; e < 3; e++) {
						float dx = rx + 0.5f - (1f + 2f * e) - 0.5f, dy = ry + 0.5f - 3f;
						float dot = (float) Math.exp(-(dx * dx + dy * dy) / 1.4);

						m[e] = Math.min(1f, dot);
					}

					float r = m[0], g = m[1], bl = m[2];
					m[0] = Math.max(r, 0.08f * Math.max(g, bl));
					m[1] = Math.max(g, 0.08f * Math.max(r, bl));
					m[2] = Math.max(bl, 0.08f * Math.max(r, g));
				} else {
					float gap = ry == 2 ? 0.6f : 1f;

					for (int e = 0; e < 3; e++) m[e] = (e == rx ? 1f : 0.03f) * gap;
				}
			}
		}
		return t;
	}

	private static void build(boolean crt) {
		float[][] base = pattern(crt);
		int k = crt ? 1 : 0;
		for (int pass = 0; pass < 2; pass++) {
			int id = GL41.glGenTextures();
			GL41.glBindTexture(GL41.GL_TEXTURE_2D, id);
			GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_MIN_FILTER, GL41.GL_LINEAR_MIPMAP_LINEAR);
			GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_MAG_FILTER, crt ? GL41.GL_LINEAR : GL41.GL_NEAREST);
			GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_S, GL41.GL_REPEAT);
			GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_T, GL41.GL_REPEAT);
			GL41.glPixelStorei(GL41.GL_UNPACK_ALIGNMENT, 4);

			float neutral = pass == 0 ? 1f : 0f;
			float[][] cur = base;
			int s = TILE, L = 0;
			while (true) {

				float keep = pass == 1 || L == 0 ? 1f : L == 1 ? 0.35f : 0f;
				IntBuffer buf = BufferUtils.createIntBuffer(s * s);
				for (int i = 0; i < s * s; i++) {
					int r = to8(cur[i][0] * keep + neutral * (1 - keep));
					int g = to8(cur[i][1] * keep + neutral * (1 - keep));
					int b = to8(cur[i][2] * keep + neutral * (1 - keep));
					buf.put(0xFF000000 | (r << 16) | (g << 8) | b);
				}
				buf.flip();
				GL41.glTexImage2D(GL41.GL_TEXTURE_2D, L, GL41.GL_RGBA8, s, s, 0, GL41.GL_BGRA, GL41.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
				if (s == 1) break;
				int n = Math.max(1, s / 2);
				float[][] next = new float[n * n][3];
				for (int y = 0; y < n; y++)
					for (int x = 0; x < n; x++)
						for (int e = 0; e < 3; e++)
							next[y * n + x][e] = (cur[(2 * y) * s + 2 * x][e] + cur[(2 * y) * s + Math.min(s - 1, 2 * x + 1)][e]
									+ cur[Math.min(s - 1, 2 * y + 1) * s + 2 * x][e] + cur[Math.min(s - 1, 2 * y + 1) * s + Math.min(s - 1, 2 * x + 1)][e]) * 0.25f;
				cur = next;
				s = n;
				L++;
			}
			GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_MAX_LEVEL, L);
			TEX[k][pass] = id;
		}
	}

	private static int to8(float v) {
		return v >= 1f ? 255 : v <= 0f ? 0 : Math.round(v * 255);
	}
}
