package com.btemu.client;

import com.btemu.MonitorGroup;
import com.btemu.MonitorKind;
import com.btemu.TileEntityMonitor;
import com.btemu.gb.GameBoy;
import net.minecraft.client.option.GameSettings;
import net.minecraft.client.option.enums.LightmapStyle;
import net.minecraft.client.render.camera.ICamera;
import net.minecraft.client.render.renderer.BlendFactor;
import net.minecraft.client.render.renderer.GLRenderer;
import net.minecraft.client.render.renderer.State;
import net.minecraft.client.render.tessellator.TessellatorGeneral;
import net.minecraft.client.render.tessellator.TessellatorShader;
import net.minecraft.client.render.tileentity.TileEntityRenderer;
import net.minecraft.core.enums.LightLayer;
import net.minecraft.core.world.pos.TilePos;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL41;

import java.nio.IntBuffer;

final class TileEntityRendererMonitor extends TileEntityRenderer<TileEntityMonitor> {
	private static final String GLASS = "/assets/btemu/textures/block/screen_off.png";
	private static final String GLOW = "/assets/btemu/textures/block/glow.png";

	private static final float BORDER = 2 / 16f;
	private static final float EPS = 0.002f;

	private final ColorSpill spill = new ColorSpill();
	private final IntBuffer upload = BufferUtils.createIntBuffer(GameBoy.WIDTH * GameBoy.HEIGHT);

	private static final int MASK_LEVELS = 16;
	private final java.util.Map<MonitorKind, int[]> maskTex = new java.util.EnumMap<>(MonitorKind.class);

	@Override
	public boolean isVisible(@NotNull TileEntityMonitor te, @NotNull ICamera camera, float partialTick) {

		return true;
	}

	@Override
	public void doRender(TessellatorGeneral t, TileEntityMonitor te, double x, double y, double z, float partialTick) {
		Integer dead;
		while ((dead = Display.TEXTURES_TO_FREE.poll()) != null) GL41.glDeleteTextures(dead);

		if (te.isInvalid() || te.worldObj == null || te.kind() == null
			|| te.worldObj.getBlockId(te.tilePos.x, te.tilePos.y, te.tilePos.z) != te.kind().blockId) return;
		MonitorGroup g = GroupCache.get(te);
		if (!g.isOrigin(te.tilePos)) return;
		MonitorKind kind = te.kind();
		if (kind == null) return;
		Display d = Display.get(te.tilePos);
		if (d != null && d.te != te) {

			if (d.te.isInvalid() && d.world == te.worldObj) d.te = te;
			else d = null;
		}

		if (WebcamFeed.rendering && (d == null || d == WebcamFeed.renderingFor)) return;
		if (d != null && MonitorInput.active() == d && !WebcamFeed.rendering) MonitorInput.poll(d);

		boolean animated = false;
		for (MonitorGroup.Face f : g.faces) {

			TilePos front = new TilePos(f.origin.x + f.fx, f.origin.y + f.fy, f.origin.z + f.fz);
			int sky = te.worldObj.getSavedLightValue(LightLayer.Sky, front);
			int blk = te.worldObj.getSavedLightValue(LightLayer.Block, front);

			GLRenderer.pushFrame();
			GLRenderer.modelM4f().translate((float) (x + f.origin.x - te.tilePos.x) + 0.5f,
				(float) (y + f.origin.y - te.tilePos.y) + 0.5f, (float) (z + f.origin.z - te.tilePos.z) + 0.5f);

			GLRenderer.modelM4f().mul(new org.joml.Matrix4f(
				f.rx, f.ry, f.rz, 0,
				f.ux, f.uy, f.uz, 0,
				f.fx, f.fy, f.fz, 0,
				0, 0, 0, 1));
			GLRenderer.disableState(State.CULL_FACE);
			GLRenderer.setColor4f(1f, 1f, 1f, 1f);

			float lb = g.holo() ? 0 : BORDER;
			float x0 = -0.5f + lb, x1 = f.w - 0.5f - lb;
			float y0 = -0.5f + lb, y1 = f.h - 0.5f - lb;
			GLRenderer.setLightmapCoord2i(sky, blk);
			bindTexture(GLASS);
			int tint = d != null ? 0x000000 : 0xFFFFFF;
			for (int i = 0; i < f.w; i++)
				for (int j = 0; j < f.h; j++) {
					float cx0 = Math.max(x0, i - 0.5f), cx1 = Math.min(x1, i + 0.5f);
					float cy0 = Math.max(y0, j - 0.5f), cy1 = Math.min(y1, j + 0.5f);

					quad(cx0, cy0, cx1, cy1, 0.5f + EPS,
						cx0 - (i - 0.5f), 1 - (cy1 - (j - 0.5f)), cx1 - (i - 0.5f), 1 - (cy0 - (j - 0.5f)), tint);
				}

			if (!g.holo()) drawBezel(kind, f, x0, y0, x1, y1);

			if (d != null && d.fxCounter > 0) {
				if (!animated) upload(d);
				FaceClip.set(f.cu, f.cv, f.w, f.h);
				float cb = g.holo() ? 0 : BORDER;
				if (g.holo() && d.pano && (d.tvActive() || d.bigPicture) && d.tvW > 0 && !d.shuttingDown()) {
					if (!animated) uploadTv(d);
					if (d.tvTexId != 0) drawPano(d, g, f, kind);
				} else {
					drawPicture(d, kind, g, f, x, y, z, cb, cb, g.w - cb, g.h - cb, sky, blk, !animated);
				}
				animated = true;
			}

			GLRenderer.enableState(State.CULL_FACE);
			GLRenderer.popFrame();
		}

		if (d != null && g.flatWall() && d.fxCounter > 0 && !d.shuttingDown() && !kind.crt && GameSettings.LIGHTMAP_STYLE.value == LightmapStyle.COLORIZED) {
			bindTexture(GLOW);
			spill.render(te, g, d, x, y, z);
		}
	}

	private void drawPicture(Display d, MonitorKind kind, MonitorGroup g, MonitorGroup.Face face, double x, double y, double z,
			float x0, float y0, float x1, float y1, int sky, int blk, boolean firstFace) {
		long now = System.nanoTime();
		float bootMs = (now - d.bootNs) / 1e6f;
		float offMs = d.offNs != 0 ? (now - d.offNs) / 1e6f : -1f;

		float sx = 1f, sy = 1f, white = 0f, fade = 1f;
		boolean picture = true, backlitBlack = false;
		if (kind.crt) {
			float t = offMs >= 0 ? offMs : (bootMs < 450 ? 450 - bootMs : -1);
			if (t >= 0) {
				final float line = 0.012f;
				if (t < 200) {
					float p = ease(t / 200f);
					sy = 1f + (line - 1f) * p;
					white = 1.2f * p;
				} else if (t < 350) {
					sy = line;
					sx = 1f + (line - 1f) * ease((t - 200) / 150f);
					white = 1.2f;
				} else {
					sy = line;
					sx = line;
					white = 1.2f;
					fade = 1f - Math.min(1f, (t - 350) / 100f);
				}
			}
		} else if (offMs >= 0) {
			if (offMs < 120) white = 2f;
			else picture = false;
		} else if (bootMs < 500) {
			picture = false;
			backlitBlack = true;
		} else if (bootMs < 650) {
			fade = (bootMs - 500) / 150f;
			backlitBlack = true;
		}

		float cxm = (x0 + x1) / 2, cym = (y0 + y1) / 2;
		float hw = (x1 - x0) / 2 * sx, hh = Math.max(0.006f, (y1 - y0) / 2 * sy);
		if (sx < 1f) hw = Math.max(0.006f, hw);
		float px0 = cxm - hw, px1 = cxm + hw, py0 = cym - hh, py1 = cym + hh;
		float zf = 0.5f + 2 * EPS;

		GLRenderer.setLightmapCoord2i(Math.max(sky, kind.backlight), Math.max(blk, kind.backlight));
		GLRenderer.enableState(State.BLEND);
		GLRenderer.setBlendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);

		if (backlitBlack) {

			bindTexture(GLOW);
			solid(x0, y0, x1, y1, zf, 0x0E0F13, 1f);
		}
		int baseTex = 0;

		int[] baseSrc = null;
		int baseW = 0, baseH = 0;
		long baseKey = 0;
		if (picture && fade > 0.001f) {
			if ((d.tvActive() || d.bigPicture) && d.tvW > 0) {

				uploadTv(d);
				textured(d.tvTexId, px0, py0, px1, py1, zf, fade);
				baseTex = d.tvTexId;
				baseSrc = d.tvPixels;
				baseW = d.tvW;
				baseH = d.tvH;
				baseKey = (1L << 40) | d.tvCounter;
			} else if (d.playhead >= 0) {

				int a = (int) Math.floor(d.playhead);
				int ta = d.ringTex[a % Display.RING];
				textured(ta, px0, py0, px1, py1, zf, fade);
				baseTex = ta;
				baseSrc = d.ring[a % Display.RING];
				baseW = d.fx.PLAIN_W;
				baseH = d.fx.PLAIN_H;
				baseKey = d.ringSeq[a % Display.RING];
				if (kind.blendFrames) {
					float k = (float) (d.playhead - a);
					int tb = d.ringTex[(a + 1) % Display.RING];
					if (k > 0.001f) textured(tb, px0, py0, px1, py1, zf + EPS / 2, fade * k);
				}
			} else {
				textured(d.plainTexId, px0, py0, px1, py1, zf, fade);
				baseTex = d.plainTexId;
				baseSrc = d.plainOut;
				baseW = d.fx.PLAIN_W;
				baseH = d.fx.PLAIN_H;
				baseKey = (2L << 40) | d.fxCounter;
			}

			double cx = x + g.cx - te0(d).x, cy = y + g.cy - te0(d).y, cz = z + g.cz - te0(d).z;
			double dist = Math.max(0.05, Math.sqrt(cx * cx + cy * cy + cz * cz));

			double pixelPx = (1.0 / SubpixelImage.perBlock(kind)) / dist * 800.0;

			double start = kind == MonitorKind.GOLD ? 0.4 : 0.6;
			double ramp = kind == MonitorKind.GOLD ? 1.6 : 3.0;
			float lin = (float) Math.max(0, Math.min(1, (pixelPx - start) / ramp));
			float target = lin * lin * (3 - 2 * lin);

			target = 1f;

			if (firstFace) {
				float dt = d.lastAlphaNs == 0 ? 1f : Math.min(1f, (now - d.lastAlphaNs) / 1e9f);
				d.lastAlphaNs = now;
				d.subpixelAlpha += (target - d.subpixelAlpha) * (1f - (float) Math.exp(-dt / 0.25f));
			}
			float alpha = d.subpixelAlpha * fade;

			int cellsW = SubpixelImage.cellsFor(kind, x1 - x0), cellsH = SubpixelImage.cellsFor(kind, y1 - y0);

			int cellTex = alpha > 0.01f && baseSrc != null ? panelTexture(d, baseSrc, baseW, baseH, baseKey, cellsW, cellsH) : 0;
			if (cellTex != 0) baseTex = cellTex;
			if (alpha > 0.01f && sx >= 1f && sy >= 1f && baseTex != 0) {

				GLRenderer.setLightmapCoord2i(15, 15);

				if (cellTex != 0) {

					GLRenderer.setBlendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
					textured(baseTex, px0, py0, px1, py1, 0.5f + 3 * EPS, alpha);
				}
				GLRenderer.setBlendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE);
				textured(baseTex, px0, py0, px1, py1, 0.5f + 3 * EPS, alpha);

				textured(baseTex, px0, py0, px1, py1, 0.5f + 3 * EPS, kind.crt ? alpha * 0.85f : alpha);

				if (kind == MonitorKind.STEEL) textured(baseTex, px0, py0, px1, py1, 0.5f + 3 * EPS, alpha);
				if (cellTex != 0) SubpixelImage.drawCells(kind, cellsW, cellsH, px0, py0, px1, py1, 0.5f + 3 * EPS, alpha);
				else SubpixelImage.draw(kind, x1 - x0, y1 - y0, px0, py0, px1, py1, 0.5f + 3 * EPS, alpha);

				GLRenderer.setBlendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE);

				float glow = kind.crt ? 0.35f :
						 kind == MonitorKind.STEEL ? 0.7f : 0.5f;
				textured(baseTex, px0, py0, px1, py1, 0.5f + 3 * EPS, alpha * glow);
				GLRenderer.setLightmapCoord2i(Math.max(sky, kind.backlight), Math.max(blk, kind.backlight));
			}
		}
		if (white > 0.001f && (picture || kind.crt) && fade > 0.001f) {

			bindTexture(GLOW);
			GLRenderer.setBlendFunc(BlendFactor.ONE, BlendFactor.ONE);
			int v = (int) Math.min(255, 255 * Math.min(1f, white) * fade);
			int rgb = (v << 16) | (v << 8) | v;
			solid(px0, py0, px1, py1, 0.5f + 4 * EPS, rgb, 1f);
			if (white > 1f) solid(px0, py0, px1, py1, 0.5f + 4.5f * EPS, rgb, 1f);
		}
		GLRenderer.setBlendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
	}

	private void drawPano(Display d, MonitorGroup g, MonitorGroup.Face f, MonitorKind kind) {
		int cells = 0;
		for (MonitorGroup.Face o : g.faces) cells += o.w * o.h;

		int sub = Math.max(2, Math.min(8, (int) Math.sqrt(24000.0 / Math.max(1, cells))));
		float top = d.panoTop;
		int[] fwd = {-g.fx, -g.fy, -g.fz}, right = {g.rx, g.ry, g.rz};
		double ox = f.origin.x + 0.5 - g.cx, oy = f.origin.y + 0.5 - g.cy, oz = f.origin.z + 0.5 - g.cz;
		GLRenderer.setLightmapCoord2i(15, 15);
		GLRenderer.enableState(State.BLEND);
		GL41.glBindTexture(GL41.GL_TEXTURE_2D, d.tvTexId);
		GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_S, GL41.GL_REPEAT);
		float zf = 0.5f + 2 * EPS;
		float[] us = new float[4], vs = new float[4];
		float[] lxs = new float[4], lys = new float[4];
		for (int pass = 0; pass < 2; pass++) {

			if (pass == 0) GLRenderer.setBlendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
			else GLRenderer.setBlendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE);
			TessellatorShader tess = GLRenderer.getTessellator();
			tess.startDrawingQuads();
			tess.setColor2i(0xFFFFFF, pass == 0 ? 255 : (kind.crt ? 90 : 140));
			for (int i = 0; i < f.w * sub; i++)
				for (int j = 0; j < f.h * sub; j++) {
					float a0 = -0.5f + i / (float) sub, a1 = -0.5f + (i + 1) / (float) sub;
					float b0 = -0.5f + j / (float) sub, b1 = -0.5f + (j + 1) / (float) sub;

					lxs[0] = a0; lys[0] = b1;
					lxs[1] = a0; lys[1] = b0;
					lxs[2] = a1; lys[2] = b0;
					lxs[3] = a1; lys[3] = b1;
					float umin = 2, umax = -1;
					for (int k = 0; k < 4; k++) {
						double dx = ox + lxs[k] * f.rx + lys[k] * f.ux + 0.5 * f.fx;
						double dy = oy + lxs[k] * f.ry + lys[k] * f.uy + 0.5 * f.fy;
						double dz = oz + lxs[k] * f.rz + lys[k] * f.uz + 0.5 * f.fz;
						double ra = dx * right[0] + dy * right[1] + dz * right[2];
						double fb = dx * fwd[0] + dy * fwd[1] + dz * fwd[2];
						double lon = Math.atan2(ra, fb), lat = Math.atan2(dy, Math.sqrt(ra * ra + fb * fb));
						us[k] = (float) (0.5 + lon / (2 * Math.PI));
						vs[k] = top + (float) (0.5 - lat / Math.PI) * (1 - top);
						umin = Math.min(umin, us[k]);
						umax = Math.max(umax, us[k]);
					}
					if (umax - umin > 0.5f) for (int k = 0; k < 4; k++) if (us[k] < 0.5f) us[k] += 1f;
					for (int k = 0; k < 4; k++) tess.addVertexWithUV(lxs[k], lys[k], zf, us[k], vs[k]);
				}
			tess.draw();
		}
		GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_S, GL41.GL_CLAMP_TO_EDGE);
		GLRenderer.setBlendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
	}

	private static TilePos te0(Display d) {
		return d.te.tilePos;
	}

	private static float ease(float p) {
		p = Math.max(0f, Math.min(1f, p));
		return p * p * (3 - 2 * p);
	}

	private static void tinted(int tex, float x0, float y0, float x1, float y1, float zf, int rgb) {
		GL41.glBindTexture(GL41.GL_TEXTURE_2D, tex);
		TessellatorShader tess = GLRenderer.getTessellator();
		tess.startDrawingQuads();
		tess.setColorOpaque1i(rgb);
		if (FaceClip.emit(tess, x0, y0, x1, y1, zf, 0, 0, 1, 1)) tess.draw();
	}

	private static void textured(int tex, float x0, float y0, float x1, float y1, float zf, float alpha) {
		GL41.glBindTexture(GL41.GL_TEXTURE_2D, tex);
		TessellatorShader tess = GLRenderer.getTessellator();
		tess.startDrawingQuads();
		tess.setColor2i(0xFFFFFF, (int) (Math.min(1f, alpha) * 255));
		if (FaceClip.emit(tess, x0, y0, x1, y1, zf, 0, 0, 1, 1)) tess.draw();
	}

	private static void solid(float x0, float y0, float x1, float y1, float zf, int rgb, float alpha) {
		TessellatorShader tess = GLRenderer.getTessellator();
		tess.startDrawingQuads();
		tess.setColor2i(rgb, (int) (Math.min(1f, alpha) * 255));
		if (FaceClip.emit(tess, x0, y0, x1, y1, zf, 0.5f, 0.5f, 0.5f, 0.5f)) tess.draw();
	}

	private static void quad(float x0, float y0, float x1, float y1, float zf, float u0, float v0, float u1, float v1, int rgb) {
		TessellatorShader tess = GLRenderer.getTessellator();
		tess.startDrawingQuads();
		tess.setColorOpaque1i(rgb);
		tess.addVertexWithUV(x0, y1, zf, u0, v0);
		tess.addVertexWithUV(x0, y0, zf, u0, v1);
		tess.addVertexWithUV(x1, y0, zf, u1, v1);
		tess.addVertexWithUV(x1, y1, zf, u1, v0);
		tess.draw();
	}

	private static final float INSET = 1 / 16f;

	private static final int WALL_INNER = 0x9A9A9A, WALL_OUTER = 0xC8C8C8;

	private void drawBezel(MonitorKind kind, MonitorGroup.Face f, float x0, float y0, float x1, float y1) {
		bindTexture("/assets/btemu/textures/block/" + kind.name + "_front.png");
		float ox0 = -0.5f, oy0 = -0.5f, ox1 = f.w - 0.5f, oy1 = f.h - 0.5f;
		float zt = 0.5f + INSET, zb = 0.5f;

		ringStrip(ox0, oy0, x0, oy1, zt);
		ringStrip(x1, oy0, ox1, oy1, zt);
		ringStrip(x0, oy0, x1, y0, zt);
		ringStrip(x0, y1, x1, oy1, zt);

		float s0 = 1 / 16f, s1 = 2 / 16f;

		wall(x0, y0, x1, y0, zb, zt, s0, s1, WALL_INNER);
		wall(x0, y1, x1, y1, zb, zt, s0, s1, WALL_INNER);
		wall(x0, y0, x0, y1, zb, zt, s0, s1, WALL_INNER);
		wall(x1, y0, x1, y1, zb, zt, s0, s1, WALL_INNER);

		wall(ox0, oy0, ox1, oy0, zb, zt, s0, s1, WALL_OUTER);
		wall(ox0, oy1, ox1, oy1, zb, zt, s0, s1, WALL_OUTER);
		wall(ox0, oy0, ox0, oy1, zb, zt, s0, s1, WALL_OUTER);
		wall(ox1, oy0, ox1, oy1, zb, zt, s0, s1, WALL_OUTER);
	}

	private static void ringStrip(float x0, float y0, float x1, float y1, float zf) {
		if (x1 - x0 < 1e-5f || y1 - y0 < 1e-5f) return;
		for (int i = (int) Math.floor(x0 + 0.5f); i - 0.5f < x1; i++)
			for (int j = (int) Math.floor(y0 + 0.5f); j - 0.5f < y1; j++) {
				float cx0 = Math.max(x0, i - 0.5f), cx1 = Math.min(x1, i + 0.5f);
				float cy0 = Math.max(y0, j - 0.5f), cy1 = Math.min(y1, j + 0.5f);
				if (cx1 - cx0 < 1e-5f || cy1 - cy0 < 1e-5f) continue;
				quad(cx0, cy0, cx1, cy1, zf,
					cx0 - (i - 0.5f), 1 - (cy1 - (j - 0.5f)), cx1 - (i - 0.5f), 1 - (cy0 - (j - 0.5f)), 0xFFFFFF);
			}
	}

	private static void wall(float ax, float ay, float bx, float by, float z0, float z1, float s0, float s1, int rgb) {
		TessellatorShader tess = GLRenderer.getTessellator();
		tess.startDrawingQuads();
		tess.setColorOpaque1i(rgb);
		tess.addVertexWithUV(ax, ay, z1, s0, s0);
		tess.addVertexWithUV(ax, ay, z0, s0, s1);
		tess.addVertexWithUV(bx, by, z0, s1, s1);
		tess.addVertexWithUV(bx, by, z1, s1, s0);
		tess.draw();
	}

	private static final int MAX_CELLS = 4_000_000;

	private static final java.util.concurrent.ExecutorService PANEL_POOL = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "BTEmu panel");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	static final class PanelResult {
		final int[] px;
		final int w, h;
		final long key;

		PanelResult(int[] px, int w, int h, long key) {
			this.px = px;
			this.w = w;
			this.h = h;
			this.key = key;
		}
	}

	private int panelTexture(Display d, int[] src, int sw, int sh, long key, int pw, int ph) {
		if (sw <= 0 || sh <= 0 || src.length < sw * sh || (long) pw * ph > MAX_CELLS) return 0;
		PanelResult ready = d.panelReady;
		if (ready != null && ready.key != d.panelKey) {
			d.panelBuf = ready.px;
			uploadPanel(d, ready.w, ready.h, ready.key);
		}
		java.util.concurrent.Future<?> job = d.panelJob;
		if (key != d.panelRequested && (job == null || job.isDone())) {
			d.panelRequested = key;
			int[] copy = java.util.Arrays.copyOf(src, sw * sh);
			MonitorKind kind = d.kind;
			final int fpw = pw, fph = ph;
			d.panelJob = PANEL_POOL.submit(() -> {
				int[] dims = new int[2];
				int[] out = buildPanel(kind, copy, sw, sh, fpw, fph, dims);
				d.panelReady = new PanelResult(out, dims[0], dims[1], key);
			});
		}
		return d.panelTexId;
	}

	private static int[] buildPanel(MonitorKind kind, int[] src, int sw, int sh, int pw, int ph, int[] dims) {

		if (kind.crt && sh <= 300) {
			int[] out = new int[sw * 2 * sh * CRT_ROW];
			crtScanlines(src, sw, sh, out);
			dims[0] = sw * 2;
			dims[1] = sh * CRT_ROW;
			return out;
		}
		dims[0] = pw;
		dims[1] = ph;
		int[] out = new int[pw * ph];

		int[] xa = new int[pw + 1], ya = new int[ph + 1];
		for (int i = 0; i <= pw; i++) xa[i] = Math.min(sw, (int) ((long) i * sw / pw));
		for (int j = 0; j <= ph; j++) ya[j] = Math.min(sh, (int) ((long) j * sh / ph));

		if (pw >= sw && ph >= sh) {
			int[] col = new int[pw];
			for (int i = 0; i < pw; i++) col[i] = Math.min(sw - 1, (int) ((long) i * sw / pw));
			int lastSy = -1;
			for (int j = 0; j < ph; j++) {
				int sy = Math.min(sh - 1, (int) ((long) j * sh / ph));
				int o = j * pw;
				if (sy == lastSy) {
					System.arraycopy(out, o - pw, out, o, pw);
					continue;
				}
				lastSy = sy;
				int row = sy * sw;
				for (int i = 0; i < pw; i++) out[o + i] = src[row + col[i]] | 0xFF000000;
			}
		} else for (int j = 0; j < ph; j++) {
			int y0 = ya[j], y1 = Math.max(y0 + 1, ya[j + 1]);
			if (y0 >= sh) y0 = sh - 1;
			y1 = Math.min(sh, y1);
			for (int i = 0; i < pw; i++) {
				int x0 = xa[i], x1 = Math.max(x0 + 1, xa[i + 1]);
				if (x0 >= sw) x0 = sw - 1;
				x1 = Math.min(sw, x1);
				int r = 0, gg = 0, b = 0, n = 0;
				for (int yy = y0; yy < y1; yy++) {
					int row = yy * sw;
					for (int xx = x0; xx < x1; xx++) {
						int c = src[row + xx];
						r += (c >> 16) & 0xFF;
						gg += (c >> 8) & 0xFF;
						b += c & 0xFF;
						n++;
					}
				}
				out[j * pw + i] = 0xFF000000 | (r / n) << 16 | (gg / n) << 8 | (b / n);
			}
		}

		if (kind.crt) smearRows(out, pw, ph);
		return out;
	}

	private static void smearRows(int[] px, int w, int h) {
		int[] row = new int[w];
		for (int y = 0; y < h; y++) {
			int o = y * w;
			System.arraycopy(px, o, row, 0, w);
			for (int x = 0; x < w; x++) {
				int a = row[Math.max(0, x - 1)], c = row[x], e = row[Math.min(w - 1, x + 1)];
				int r = (12 * ((a >> 16) & 0xFF) + 76 * ((c >> 16) & 0xFF) + 12 * ((e >> 16) & 0xFF)) / 100;
				int g = (12 * ((a >> 8) & 0xFF) + 76 * ((c >> 8) & 0xFF) + 12 * ((e >> 8) & 0xFF)) / 100;
				int b = (12 * (a & 0xFF) + 76 * (c & 0xFF) + 12 * (e & 0xFF)) / 100;
				px[o + x] = 0xFF000000 | r << 16 | g << 8 | b;
			}
		}
	}

	private int uploadPanel(Display d, int pw, int ph, long key) {
		int[] out = d.panelBuf;
		if (d.panelTexId == 0 || d.panelW != pw || d.panelH != ph) {
			if (d.panelTexId != 0) GL41.glDeleteTextures(d.panelTexId);

			d.panelTexId = newTexture(d.kind.crt ? GL41.GL_LINEAR : GL41.GL_NEAREST, pw, ph);
			d.panelW = pw;
			d.panelH = ph;
		}

		if (d.panelUpload == null || d.panelUpload.capacity() < pw * ph) d.panelUpload = BufferUtils.createIntBuffer(pw * ph);
		java.nio.IntBuffer buf = d.panelUpload;
		buf.clear();
		buf.put(out, 0, pw * ph).flip();
		GL41.glBindTexture(GL41.GL_TEXTURE_2D, d.panelTexId);
		GL41.glPixelStorei(GL41.GL_UNPACK_ALIGNMENT, 4);
		GL41.glTexSubImage2D(GL41.GL_TEXTURE_2D, 0, 0, 0, pw, ph, GL41.GL_BGRA, GL41.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
		GL41.glGenerateMipmap(GL41.GL_TEXTURE_2D);
		d.panelKey = key;
		return d.panelTexId;
	}

	private static final int CRT_ROW = 4;

	private static final int BEAM_L = 32, BEAM_D = 64;
	private static final float[] BEAM = new float[BEAM_L * BEAM_D];
	static {
		for (int li = 0; li < BEAM_L; li++) {
			float sigma = 0.2f + 0.1f * li / (BEAM_L - 1f);
			for (int di = 0; di < BEAM_D; di++) {
				float dist = di * 2f / (BEAM_D - 1);
				BEAM[li * BEAM_D + di] = (float) Math.exp(-(dist * dist) / (2 * sigma * sigma));
			}
		}
	}

	private static void crtScanlines(int[] src, int sw, int sh, int[] out) {
		int ow = sw * 2;

		float[] rr = new float[sw * sh], gg = new float[sw * sh], bb = new float[sw * sh], lum = new float[sw * sh];
		for (int y = 0; y < sh; y++) {
			int row = y * sw;
			for (int x = 0; x < sw; x++) {
				int a = src[row + Math.max(0, x - 1)], c = src[row + x], e = src[row + Math.min(sw - 1, x + 1)];
				float r = (0.12f * ((a >> 16) & 0xFF) + 0.76f * ((c >> 16) & 0xFF) + 0.12f * ((e >> 16) & 0xFF)) / 255f;
				float g = (0.12f * ((a >> 8) & 0xFF) + 0.76f * ((c >> 8) & 0xFF) + 0.12f * ((e >> 8) & 0xFF)) / 255f;
				float b = (0.12f * (a & 0xFF) + 0.76f * (c & 0xFF) + 0.12f * (e & 0xFF)) / 255f;
				int k = row + x;
				rr[k] = r;
				gg[k] = g;
				bb[k] = b;
				lum[k] = 0.3f * r + 0.59f * g + 0.11f * b;
			}
		}

		for (int oy = 0; oy < sh * CRT_ROW; oy++) {
			float sy = (oy + 0.5f) / CRT_ROW - 0.5f;
			int r0 = (int) Math.floor(sy);
			for (int ox = 0; ox < ow; ox++) {
				float sx = (ox + 0.5f) / 2f - 0.5f;
				int x0 = Math.max(0, Math.min(sw - 1, (int) Math.floor(sx))), x1 = Math.min(sw - 1, x0 + 1);
				float tx = Math.max(0f, Math.min(1f, sx - x0));
				float R = 0, G = 0, B = 0;
				for (int ry = r0; ry <= r0 + 1; ry++) {
					if (ry < 0 || ry >= sh) continue;
					int k0 = ry * sw + x0, k1 = ry * sw + x1;
					float cr = rr[k0] + (rr[k1] - rr[k0]) * tx, cg = gg[k0] + (gg[k1] - gg[k0]) * tx, cb = bb[k0] + (bb[k1] - bb[k0]) * tx;
					float l = lum[k0] + (lum[k1] - lum[k0]) * tx;
					float dist = sy - ry;

					int li = Math.max(0, Math.min(BEAM_L - 1, (int) (l * (BEAM_L - 1))));
					int di = Math.min(BEAM_D - 1, (int) (Math.abs(dist) * (BEAM_D - 1) / 2f));
					float w = BEAM[li * BEAM_D + di];
					R += cr * w;
					G += cg * w;
					B += cb * w;
				}
				float gain = 1.0f;
				int ir = (int) Math.min(255, R * gain * 255), ig = (int) Math.min(255, G * gain * 255), ib = (int) Math.min(255, B * gain * 255);
				out[oy * ow + ox] = 0xFF000000 | ir << 16 | ig << 8 | ib;
			}
		}
	}

	private static int newTexture(int mag, int w, int h) {
		int id = GL41.glGenTextures();
		GL41.glBindTexture(GL41.GL_TEXTURE_2D, id);
		GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_MIN_FILTER, GL41.GL_LINEAR_MIPMAP_LINEAR);
		GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_MAG_FILTER, mag);
		GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_S, GL41.GL_CLAMP_TO_EDGE);
		GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_T, GL41.GL_CLAMP_TO_EDGE);
		GL41.glTexImage2D(GL41.GL_TEXTURE_2D, 0, GL41.GL_RGBA8, w, h, 0, GL41.GL_BGRA, GL41.GL_UNSIGNED_INT_8_8_8_8_REV, (IntBuffer) null);
		return id;
	}

	private void uploadTv(Display d) {
		synchronized (d.tvLock) {
			int w = d.tvW, h = d.tvH;
			if (w == 0) return;
			if (d.tvTexId == 0 || d.tvTexW != w || d.tvTexH != h) {
				if (d.tvTexId != 0) GL41.glDeleteTextures(d.tvTexId);
				d.tvTexId = newTexture(GL41.GL_LINEAR, w, h);
				d.tvTexW = w;
				d.tvTexH = h;
				d.tvUploaded = -1;
			}
			if (d.tvUploaded == d.tvCounter) return;
			if (d.tvUpload == null || d.tvUpload.capacity() < w * h) d.tvUpload = BufferUtils.createIntBuffer(w * h);
			java.nio.IntBuffer buf = d.tvUpload;
			buf.clear();
			buf.put(d.tvPixels, 0, Math.min(d.tvPixels.length, w * h)).flip();
			GL41.glBindTexture(GL41.GL_TEXTURE_2D, d.tvTexId);
			GL41.glPixelStorei(GL41.GL_UNPACK_ALIGNMENT, 4);
			GL41.glTexSubImage2D(GL41.GL_TEXTURE_2D, 0, 0, 0, w, h, GL41.GL_BGRA, GL41.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
			GL41.glGenerateMipmap(GL41.GL_TEXTURE_2D);
			d.tvUploaded = d.tvCounter;
		}
	}

	private int maskTexture(MonitorKind kind, int level) {
		int[] ids = maskTex.computeIfAbsent(kind, k -> new int[MASK_LEVELS + 1]);
		if (ids[level] == 0) {
			int[] tile = ScreenEffects.maskTile(kind, level / (float) MASK_LEVELS);
			int id = GL41.glGenTextures();
			GL41.glBindTexture(GL41.GL_TEXTURE_2D, id);
			GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_MIN_FILTER, GL41.GL_NEAREST);
			GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_MAG_FILTER, kind.crt ? GL41.GL_LINEAR : GL41.GL_NEAREST);
			GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_S, GL41.GL_REPEAT);
			GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_T, GL41.GL_REPEAT);
			upload.clear();
			upload.put(tile);
			upload.flip();
			GL41.glPixelStorei(GL41.GL_UNPACK_ALIGNMENT, 4);
			GL41.glTexImage2D(GL41.GL_TEXTURE_2D, 0, GL41.GL_RGBA8, 3, 3, 0, GL41.GL_BGRA, GL41.GL_UNSIGNED_INT_8_8_8_8_REV, upload);
			ids[level] = id;
		}
		return ids[level];
	}

	private void upload(Display d) {
		int mag = d.kind.crt ? GL41.GL_LINEAR : GL41.GL_NEAREST;
		if (d.plainTexId == 0) {
			d.plainTexId = newTexture(mag, d.fx.PLAIN_W, d.fx.PLAIN_H);
			for (int i = 0; i < Display.RING; i++) d.ringTex[i] = newTexture(mag, d.fx.PLAIN_W, d.fx.PLAIN_H);
		}
		GL41.glPixelStorei(GL41.GL_UNPACK_ALIGNMENT, 4);
		int latest;
		synchronized (d.plainOut) {
			latest = d.fxCounter;
			if (latest != d.uploadedCounter) {
				send(d.plainTexId, d.plainOut, d.fx.PLAIN_W, d.fx.PLAIN_H);
				d.uploadedCounter = latest;
			}
			for (int i = 0; i < Display.RING; i++) {
				if (d.ringSeq[i] != d.ringTexSeq[i]) {
					send(d.ringTex[i], d.ring[i], d.fx.PLAIN_W, d.fx.PLAIN_H);
					d.ringTexSeq[i] = d.ringSeq[i];
				}
			}
		}
		advancePlayhead(d, latest);
	}

	private static void advancePlayhead(Display d, int latest) {
		long now = System.nanoTime();
		double target = latest - d.kind.playbackLag();
		if (d.playhead < 0 || d.lastPlayNs == 0) {
			d.playhead = Math.max(1, target);
		} else {
			d.playhead += (now - d.lastPlayNs) * d.displayFps() / 1e9;
			double err = target - d.playhead;
			if (Math.abs(err) > 3.5) d.playhead = target;
			else d.playhead += err * 0.04;
		}
		d.lastPlayNs = now;
		d.playhead = Math.max(latest - (Display.RING - 2), Math.min(latest - 1, d.playhead));
		if (d.playhead < 1) d.playhead = 1;
	}

	private void send(int tex, int[] px, int w, int h) {
		upload.clear();
		upload.put(px, 0, w * h);
		upload.flip();
		GL41.glBindTexture(GL41.GL_TEXTURE_2D, tex);
		GL41.glTexSubImage2D(GL41.GL_TEXTURE_2D, 0, 0, 0, w, h, GL41.GL_BGRA, GL41.GL_UNSIGNED_INT_8_8_8_8_REV, upload);
		GL41.glGenerateMipmap(GL41.GL_TEXTURE_2D);
	}
}
