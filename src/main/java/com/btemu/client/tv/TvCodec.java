package com.btemu.client.tv;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public final class TvCodec {
	private TvCodec() {}

	public static final int SW = 320, SH = 180;
	public static final int AUDIO_RATE = 24000;
	private static final float QUALITY = 0.6f;

	public static byte[] encodeFit(int[] src, int w, int h, int limit) {
		int[][] tries = {{640, 60}, {640, 45}, {480, 50}, {SW, 60}, {SW, 40}};
		for (int[] t : tries) {
			int ow = Math.min(t[0], w);
			int oh = Math.max(2, Math.round(ow * (float) h / w));
			byte[] j = encode(src, w, h, ow, oh, t[1] / 100f);
			if (j != null && j.length < limit) return j;
		}
		return null;
	}

	public static byte[] encode(int[] src, int w, int h) {
		return encode(src, w, h, SW, SH, QUALITY);
	}

	public static byte[] encode(int[] src, int w, int h, int ow, int oh, float quality) {
		BufferedImage img = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
		int[] dst = new int[ow * oh];
		int bx = w / ow, by = h / oh;
		for (int y = 0; y < oh; y++)
			for (int x = 0; x < ow; x++) {
				int r = 0, g = 0, b = 0, n = 0;
				int x0 = x * w / ow, y0 = y * h / oh;
				for (int yy = 0; yy < Math.max(1, by); yy++)
					for (int xx = 0; xx < Math.max(1, bx); xx++) {
						int c = src[(y0 + yy) * w + x0 + xx];
						r += (c >> 16) & 0xFF;
						g += (c >> 8) & 0xFF;
						b += c & 0xFF;
						n++;
					}
				dst[y * ow + x] = ((r / n) << 16) | ((g / n) << 8) | (b / n);
			}
		img.setRGB(0, 0, ow, oh, dst, 0, ow);
		try {
			ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
			ImageWriteParam p = wr.getDefaultWriteParam();
			p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			p.setCompressionQuality(quality);
			ByteArrayOutputStream bo = new ByteArrayOutputStream(16 * 1024);
			try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bo)) {
				wr.setOutput(out);
				wr.write(null, new IIOImage(img, null, null), p);
			}
			wr.dispose();
			return bo.toByteArray();
		} catch (Exception e) {
			return null;
		}
	}

	public static int[] decode(byte[] jpeg, int[] dims) {
		try {
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
			if (img == null || img.getWidth() > 1280 || img.getHeight() > 1280) return null;
			int iw = img.getWidth(), ih = img.getHeight();
			int[] px = img.getRGB(0, 0, iw, ih, null, 0, iw);
			for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;
			dims[0] = iw;
			dims[1] = ih;
			return px;
		} catch (Exception e) {
			return null;
		}
	}

	public static byte[] packAudio(float[] stereo, int frames) {
		int n = frames / 2;
		byte[] out = new byte[n * 2];
		for (int i = 0; i < n; i++) {
			int a = i * 2;
			float m = (stereo[a * 2] + stereo[a * 2 + 1] + stereo[a * 2 + 2] + stereo[a * 2 + 3]) * 0.25f;
			int s = (int) (Math.max(-1f, Math.min(1f, m)) * 32767);
			out[i * 2] = (byte) s;
			out[i * 2 + 1] = (byte) (s >> 8);
		}
		return out;
	}

	public static void unpackAudio(byte[] pcm, TvAudio into) {
		int n = pcm.length / 2;
		float[] stereo = new float[n * 2];
		for (int i = 0; i < n; i++) {
			float v = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8)) / 32768f;
			stereo[i * 2] = v;
			stereo[i * 2 + 1] = v;
		}
		into.push(stereo, n, AUDIO_RATE);
	}
}
