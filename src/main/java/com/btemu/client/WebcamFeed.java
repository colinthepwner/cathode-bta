package com.btemu.client;

import com.btemu.MonitorGroup;
import com.btemu.client.tv.WebMedia;
import com.btemu.client.tv.WebWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScreenPhotoMode;
import net.minecraft.client.option.GameSettings;
import net.minecraft.client.render.camera.EntityCamera;
import net.minecraft.client.render.camera.EntityCameraFirstPerson;
import net.minecraft.client.render.camera.ICamera;
import net.minecraft.core.entity.Mob;
import net.minecraft.core.util.phys.HitResult;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL41;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.IntBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WebcamFeed {
	private WebcamFeed() {}

	public static boolean rendering;

	static Display renderingFor;

	private static final int SCREEN_W = 1280;
	private static final long SCREEN_NS = 1_000_000_000L / 15;

	private static final long CAM_NS = 1_000_000_000L / 30;
	private static long lastCamAny, lastScreen;
	private static final java.util.Map<Display, Long> lastCam = new java.util.WeakHashMap<>();

	private static final java.util.Map<Display, double[]> AIM = new java.util.WeakHashMap<>();

	static int[] camSpec(com.btemu.MonitorKind k) {
		return switch (k) {
			case WOOD -> null;
			case IRON -> new int[]{320, 12, 45};
			case GOLD -> new int[]{480, 15, 65};
			case DIAMOND -> new int[]{854, 24, 80};
			case STEEL -> new int[]{1280, 30, 90};
		};
	}

	public static int mediaTier(com.btemu.MonitorKind k) {
		return switch (k) {
			case WOOD -> 0;
			case IRON -> 1;
			case GOLD -> 2;
			case DIAMOND -> 3;
			case STEEL -> 4;
		};
	}
	private static int camTurn;

	private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "BTEmu webcam encoder");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});
	private static final AtomicBoolean BUSY = new AtomicBoolean();

	public static void afterFrame(float pt) {
		boolean cam = WebMedia.any(WebMedia.CAM), screen = WebMedia.any(WebMedia.SCREEN);
		if (!cam && !screen) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null || mc.currentWorld == null || mc.thePlayer == null) return;
		long now = System.nanoTime();
		try {
			if (screen && now - lastScreen >= SCREEN_NS && !BUSY.get()) {
				lastScreen = now;
				grabScreen(mc);
			}
		} catch (Throwable t) {
			rendering = false;
			org.slf4j.LoggerFactory.getLogger("btemu").warn("Webcam frame failed", t);
		}
	}

	public static void beforeWorld(float pt) {
		if (!WebMedia.any(WebMedia.CAM)) return;
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null || mc.currentWorld == null || mc.thePlayer == null) return;
		long now = System.nanoTime();
		if (now - lastCamAny < CAM_NS || BUSY.get()) return;
		lastCamAny = now;
		try {
			renderCamera(mc, pt);
		} catch (Throwable t) {
			rendering = false;
			org.slf4j.LoggerFactory.getLogger("btemu").warn("Webcam frame failed", t);
		}
	}

	private static int shareTurn;

	private static void grabScreen(Minecraft mc) {
		java.util.List<com.btemu.client.tv.WebTab> sharers = WebMedia.sharers();
		if (sharers.isEmpty()) return;
		com.btemu.client.tv.WebTab first = sharers.get(Math.floorMod(shareTurn++, sharers.size()));
		Object src = WebMedia.shareSource(first);
		java.util.List<com.btemu.client.tv.WebTab> targets = new java.util.ArrayList<>();
		for (com.btemu.client.tv.WebTab t : sharers) if (WebMedia.shareSource(t) == src) targets.add(t);
		if (src != WebMedia.SRC_GAME) {
			int[] dims = new int[2];
			int[] px = WebMedia.sourcePixels(first, dims);
			if (px == null || dims[0] <= 0 || dims[1] <= 0) return;
			encode(px, dims[0], dims[1], 0.8f, jpeg -> {
				for (com.btemu.client.tv.WebTab t : targets) WebMedia.sendScreenTo(t, jpeg);
			});
			return;
		}
		int ww = mc.gameWindow.getWidthPixels(), wh = mc.gameWindow.getHeightPixels();
		if (ww <= 0 || wh <= 0) return;
		int sw = Math.min(SCREEN_W, ww) & ~1, sh = Math.max(2, Math.round(sw * (float) wh / ww)) & ~1;
		int prevRead = GL41.glGetInteger(GL41.GL_READ_FRAMEBUFFER_BINDING), prevDraw = GL41.glGetInteger(GL41.GL_DRAW_FRAMEBUFFER_BINDING);

		FULL.ensure(ww, wh, false);
		SMALL.ensure(sw, sh, false);
		GL41.glBindFramebuffer(GL41.GL_READ_FRAMEBUFFER, prevDraw);
		GL41.glBindFramebuffer(GL41.GL_DRAW_FRAMEBUFFER, FULL.fbo);
		GL41.glBlitFramebuffer(0, 0, ww, wh, 0, 0, ww, wh, GL41.GL_COLOR_BUFFER_BIT, GL41.GL_NEAREST);
		GL41.glBindFramebuffer(GL41.GL_READ_FRAMEBUFFER, FULL.fbo);
		GL41.glBindFramebuffer(GL41.GL_DRAW_FRAMEBUFFER, SMALL.fbo);
		GL41.glBlitFramebuffer(0, 0, ww, wh, 0, 0, sw, sh, GL41.GL_COLOR_BUFFER_BIT, GL41.GL_LINEAR);
		int[] px = SMALL.read();
		GL41.glBindFramebuffer(GL41.GL_READ_FRAMEBUFFER, prevRead);
		GL41.glBindFramebuffer(GL41.GL_DRAW_FRAMEBUFFER, prevDraw);
		encode(px, sw, sh, 0.75f, jpeg -> {
			for (com.btemu.client.tv.WebTab t : targets) WebMedia.sendScreenTo(t, jpeg);
		});
	}

	private static void renderCamera(Minecraft mc, float pt) {
		java.util.List<Display> cams = new java.util.ArrayList<>();
		for (Display d : Display.all()) {
			WebWindow w = d.webBrowser();
			int[] spec = camSpec(d.kind);
			if (w == null || d.group == null || spec == null || !WebMedia.wants(w, WebMedia.CAM)) continue;
			Long last = lastCam.get(d);
			if (last == null || System.nanoTime() - last >= 1_000_000_000L / spec[1]) cams.add(d);
		}
		if (cams.isEmpty()) return;

		Display d = cams.get(Math.floorMod(camTurn++, cams.size()));
		WebWindow target = d.webBrowser();
		int[] spec = camSpec(d.kind);
		lastCam.put(d, System.nanoTime());
		if (!(mc.activeCamera instanceof EntityCamera ec) || ec.mob != mc.thePlayer) return;
		if (mc.thePlayer.isPlayerSleeping() || mc.currentScreen instanceof ScreenPhotoMode) return;

		int ww = mc.gameWindow.getWidthPixels(), wh = mc.gameWindow.getHeightPixels();
		if (ww <= 0 || wh <= 0) return;
		int cw = Math.min(spec[0], ww) & ~1, ch = Math.max(2, Math.round(cw * (float) wh / ww)) & ~1;

		MonitorCamera cam = new MonitorCamera(mc, mc.thePlayer, d.group, pt);
		cam.glide(AIM.computeIfAbsent(d, k -> new double[]{cam.yRot, cam.xRot, 0}));
		if (cam.covered) {

			int[] dark = new int[cw * ch];
			java.util.Arrays.fill(dark, 0xFF1A1310);
			encode(dark, cw, ch, 0.5f, jpeg -> WebMedia.sendFrame(target, WebMedia.CAM, jpeg));
			return;
		}
		ICamera prevCam = mc.activeCamera;
		Integer prevView = GameSettings.THIRD_PERSON_VIEW.value;
		Boolean prevBob = GameSettings.VIEW_BOBBING.value;
		HitResult prevHit = mc.objectMouseOver;
		int prevRead = GL41.glGetInteger(GL41.GL_READ_FRAMEBUFFER_BINDING), prevDraw = GL41.glGetInteger(GL41.GL_DRAW_FRAMEBUFFER_BINDING);
		IntBuffer vp = BufferUtils.createIntBuffer(16);
		GL41.glGetIntegerv(GL41.GL_VIEWPORT, vp);
		int[] px;
		try {
			GameSettings.THIRD_PERSON_VIEW.value = 0;
			GameSettings.VIEW_BOBBING.value = Boolean.FALSE;
			mc.activeCamera = cam;
			rendering = true;
			renderingFor = d;

			GL41.glViewport(0, 0, cw, ch);
			mc.worldRenderer.renderWorld(pt, System.nanoTime());
			int src = GL41.glGetInteger(GL41.GL_DRAW_FRAMEBUFFER_BINDING);
			IntBuffer vp2 = BufferUtils.createIntBuffer(16);
			GL41.glGetIntegerv(GL41.GL_VIEWPORT, vp2);
			int rw = vp2.get(2), rh = vp2.get(3);
			if (rw <= 0 || rh <= 0) {
				rw = cw;
				rh = ch;
			}

			CAMFULL.ensure(rw, rh, false);
			GL41.glBindFramebuffer(GL41.GL_READ_FRAMEBUFFER, src);
			GL41.glBindFramebuffer(GL41.GL_DRAW_FRAMEBUFFER, CAMFULL.fbo);
			GL41.glBlitFramebuffer(vp2.get(0), vp2.get(1), vp2.get(0) + rw, vp2.get(1) + rh, 0, 0, rw, rh, GL41.GL_COLOR_BUFFER_BIT, GL41.GL_NEAREST);
			CAMERA.ensure(cw, ch, false);
			GL41.glBindFramebuffer(GL41.GL_READ_FRAMEBUFFER, CAMFULL.fbo);
			GL41.glBindFramebuffer(GL41.GL_DRAW_FRAMEBUFFER, CAMERA.fbo);
			GL41.glBlitFramebuffer(0, 0, rw, rh, 0, 0, cw, ch, GL41.GL_COLOR_BUFFER_BIT, GL41.GL_LINEAR);
			px = CAMERA.read();
		} finally {
			rendering = false;
			renderingFor = null;
			mc.activeCamera = prevCam;
			GameSettings.THIRD_PERSON_VIEW.value = prevView;
			GameSettings.VIEW_BOBBING.value = prevBob;
			mc.objectMouseOver = prevHit;
			GL41.glBindFramebuffer(GL41.GL_READ_FRAMEBUFFER, prevRead);
			GL41.glBindFramebuffer(GL41.GL_DRAW_FRAMEBUFFER, prevDraw);
			GL41.glViewport(vp.get(0), vp.get(1), vp.get(2), vp.get(3));
		}
		encode(px, cw, ch, spec[2] / 100f, jpeg -> WebMedia.sendFrame(target, WebMedia.CAM, jpeg));
	}

	static final class MonitorCamera extends EntityCameraFirstPerson {
		private final double x, y, z;

		double yRot, xRot;

		boolean covered;

		MonitorCamera(Minecraft mc, Mob player, MonitorGroup g, float pt) {
			super(mc, player);

			double cx = g.origin.x + 0.5 + g.rx * (g.w - 1) / 2.0, cz = g.origin.z + 0.5 + g.rz * (g.w - 1) / 2.0;

			x = cx + g.fx * 0.35;
			z = cz + g.fz * 0.35;
			y = g.origin.y + g.h - 0.1;
			double px = player.xo + (player.x - player.xo) * pt, pz = player.zo + (player.z - player.zo) * pt;
			double py = player.yo + (player.y - player.yo) * pt + player.getHeadHeight() - 0.15;
			double dx = px - x, dy = py - y, dz = pz - z;

			double toYaw = Math.toDegrees(Math.atan2(-dx, dz));
			double toPitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

			double fwdYaw = Math.toDegrees(Math.atan2(-g.fx, g.fz)), fwdPitch = 13;

			double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

			double hx = x - px, hz = z - pz, hy = y - (py + 0.15);
			covered = Math.abs(hx) < 0.4 && Math.abs(hz) < 0.4 && hy > -0.35 && hy < 0.3;
			double t = Math.max(0, Math.min(1, (dist - 1.5) / 2.5));
			t = t * t * (3 - 2 * t);
			double off = ((toYaw - fwdYaw) % 360 + 540) % 360 - 180;
			off = Math.max(-60, Math.min(60, off));
			yRot = fwdYaw + off * t;
			xRot = fwdPitch + (Math.max(-45, Math.min(45, toPitch + 5)) - fwdPitch) * t;
		}

		void glide(double[] aim) {
			long now = System.nanoTime();
			double dt = aim[2] == 0 ? 1 : Math.min(1, (now - aim[2]) / 1e9);
			aim[2] = now;
			double k = 1 - Math.exp(-dt * 6);
			double dy = ((yRot - aim[0]) % 360 + 540) % 360 - 180;
			aim[0] += dy * k;
			aim[1] += (xRot - aim[1]) * k;
			yRot = aim[0];
			xRot = aim[1];
		}

		@Override
		public boolean showPlayer() {
			return true;
		}

		@Override
		public void tick() {}

		@Override
		public double getX(float pt) {
			return x;
		}

		@Override
		public double getY(float pt) {
			return y;
		}

		@Override
		public double getZ(float pt) {
			return z;
		}

		@Override
		public double getXRot(float pt) {
			return xRot;
		}

		@Override
		public double getYRot(float pt) {
			return yRot;
		}

		@Override
		public double getFov() {

			return 0.7;
		}
	}

	private static final class Target {
		int fbo, color, depth, w, h;
		IntBuffer buf;

		void ensure(int nw, int nh, boolean withDepth) {
			if (fbo != 0 && nw == w && nh == h) return;
			if (fbo == 0) {
				fbo = GL41.glGenFramebuffers();
				color = GL41.glGenRenderbuffers();
				if (withDepth) depth = GL41.glGenRenderbuffers();
			}
			w = nw;
			h = nh;
			int prev = GL41.glGetInteger(GL41.GL_FRAMEBUFFER_BINDING);
			GL41.glBindRenderbuffer(GL41.GL_RENDERBUFFER, color);
			GL41.glRenderbufferStorage(GL41.GL_RENDERBUFFER, GL41.GL_RGBA8, w, h);
			if (depth != 0) {
				GL41.glBindRenderbuffer(GL41.GL_RENDERBUFFER, depth);
				GL41.glRenderbufferStorage(GL41.GL_RENDERBUFFER, GL41.GL_DEPTH24_STENCIL8, w, h);
			}
			GL41.glBindFramebuffer(GL41.GL_FRAMEBUFFER, fbo);
			GL41.glFramebufferRenderbuffer(GL41.GL_FRAMEBUFFER, GL41.GL_COLOR_ATTACHMENT0, GL41.GL_RENDERBUFFER, color);
			if (depth != 0) GL41.glFramebufferRenderbuffer(GL41.GL_FRAMEBUFFER, GL41.GL_DEPTH_STENCIL_ATTACHMENT, GL41.GL_RENDERBUFFER, depth);
			GL41.glBindFramebuffer(GL41.GL_FRAMEBUFFER, prev);
			buf = BufferUtils.createIntBuffer(w * h);
		}

		int[] read() {
			return read(true);
		}

		int[] read(boolean flip) {
			GL41.glBindFramebuffer(GL41.GL_READ_FRAMEBUFFER, fbo);
			GL41.glPixelStorei(GL41.GL_PACK_ALIGNMENT, 4);
			buf.clear();
			GL41.glReadPixels(0, 0, w, h, GL41.GL_BGRA, GL41.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
			int[] out = new int[w * h];
			for (int y = 0; y < h; y++) buf.get((flip ? h - 1 - y : y) * w, out, y * w, w);
			return out;
		}
	}

	private static final Target FULL = new Target(), SMALL = new Target(), CAMFULL = new Target(), CAMERA = new Target();

	private static void encode(int[] px, int w, int h, float quality, java.util.function.Consumer<byte[]> sink) {
		if (!BUSY.compareAndSet(false, true)) return;
		ENCODER.execute(() -> {
			try {
				BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
				img.setRGB(0, 0, w, h, px, 0, w);
				ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
				ImageWriter wr = ImageIO.getImageWritersByFormatName("jpeg").next();
				try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
					wr.setOutput(ios);
					ImageWriteParam p = wr.getDefaultWriteParam();
					p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
					p.setCompressionQuality(quality);
					wr.write(null, new IIOImage(img, null, null), p);
				} finally {
					wr.dispose();
				}
				sink.accept(out.toByteArray());
			} catch (Throwable t) {
				org.slf4j.LoggerFactory.getLogger("btemu").warn("Webcam encode failed", t);
			} finally {
				BUSY.set(false);
			}
		});
	}
}
