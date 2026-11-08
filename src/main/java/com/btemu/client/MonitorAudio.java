package com.btemu.client;

import com.btemu.MonitorGroup;
import com.btemu.emu.Machine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.option.GameSettings;
import net.minecraft.core.entity.player.Player;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

final class MonitorAudio {
	private static final int RATE = com.btemu.gb.Apu.SAMPLE_RATE;

	private SourceDataLine line;
	private final float[] in = new float[RATE / 10 * 2];
	private byte[] out = new byte[RATE / 10 * 4];

	private volatile float gLL, gLR, gRL, gRR;
	private float cLL, cLR, cRL, cRR;

	private float hpA;

	private volatile float lpA;
	private float sizeLpHz = 18000f;
	private volatile float distLpHz = 18000f;
	private final float[] hpPrevIn = new float[2], hpOut = new float[2], lpOut = new float[2];
	private boolean failed = false;

	private boolean cabinet = false;

	private float stereoWidth = 0f;
	private final float[] combL = new float[160], combR = new float[160];
	private int combPos = 0;

	volatile java.util.List<double[]> speakers = java.util.List.of();

	volatile boolean hiFi = false;

	private int sampleBytes = 2;

	private boolean noHiFi = false;

	void configure(MonitorGroup g, com.btemu.MonitorKind kind) {
		hiFi = false;
		if (!speakers.isEmpty()) {

			cabinet = false;
			stereoWidth = 0f;

			boolean all = true;
			double biggest = 1;
			for (double[] sp : speakers) {
				if (sp[3] == 0) all = false;
				biggest = Math.max(biggest, sp[4]);
			}
			hiFi = all;
			float st = Math.min(1f, ((float) Math.sqrt(biggest) - 1f) / 3f);
			float shp = all ? logLerp(120, 25, st) : logLerp(350, 90, st);
			float slp = all ? logLerp(14000, 20000, st) : logLerp(8000, 14000, st);
			hpA = (float) (1.0 / (1.0 + 2 * Math.PI * shp / RATE));
			sizeLpHz = slp;
			applyLowpass();
			return;
		}

		float size = (float) Math.sqrt(g.w * g.h);
		float t = Math.min(1f, (size - 1f) / 3f);
		float hp = (float) Math.exp(lerp((float) Math.log(700), (float) Math.log(30), t));
		float lp = (float) Math.exp(lerp((float) Math.log(3500), (float) Math.log(18000), t));

		cabinet = kind.wooden;
		stereoWidth = kind.scale >= 6 ? 0.85f : 0f;
		if (kind.wooden) {
			hp = Math.max(900f, hp * 4f);
			lp = Math.min(2800f, lp * 0.6f);
		} else if (kind.crt) {
			hp *= 2.5f;
			lp *= 0.7f;
		} else if (kind.scale < 6) {
			hp *= 1.6f;
			lp *= 0.85f;
		}
		hpA = (float) (1.0 / (1.0 + 2 * Math.PI * hp / RATE));
		sizeLpHz = lp;
		applyLowpass();
	}

	private void applyLowpass() {
		float lp = Math.min(sizeLpHz, distLpHz);
		float x = (float) (2 * Math.PI * lp / RATE);
		lpA = x / (x + 1f);
	}

	private static float logLerp(float a, float b, float t) {
		return (float) Math.exp(lerp((float) Math.log(a), (float) Math.log(b), t));
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	void updateListener(MonitorGroup g) {
		Player p = Minecraft.getMinecraft().thePlayer;
		if (p == null) { gLL = gLR = gRL = gRR = 0; return; }
		float master = GameSettings.MASTER_VOLUME.get() * GameSettings.WORLD_SOUNDS_VOLUME.get();
		float size = (float) Math.sqrt(g.w * g.h);

		float loud = master * volume * 1.1f * (0.5f + 0.5f * Math.min(1f, (size - 1f) / 3f));

		float ref = cabinet ? 1.0f : 1.5f + 0.75f * size;

		double cy = g.cy;
		double fx = g.origin.x + 0.5 + g.fx * 0.5, fz = g.origin.z + 0.5 + g.fz * 0.5;
		double lx = fx - g.rx * 0.5, lz = fz - g.rz * 0.5;
		double rx = fx + g.rx * (g.w - 0.5), rz = fz + g.rz * (g.w - 0.5);
		if (!g.flatWall()) {

			lx = rx = g.cx;
			lz = rz = g.cz;
		}

		double yaw = Math.toRadians(p.yRot);
		double earRx = -Math.cos(yaw), earRz = -Math.sin(yaw);

		java.util.List<double[]> spk = speakers;
		if (!spk.isEmpty()) { surround(p, g, spk, earRx, earRz, master); return; }

		if (cabinet) {

			double mx0 = g.origin.x + 0.5, mz0 = g.origin.z + 0.5;
			lx = rx = mx0;
			lz = rz = mz0;
		}
		if (stereoWidth > 0 && g.flatWall()) {

			lx -= g.rx * 0.75; lz -= g.rz * 0.75;
			rx += g.rx * 0.75; rz += g.rz * 0.75;
		}
		float[] left = edge(p, lx, cy, lz, earRx, earRz, ref, loud, g);
		float[] right = edge(p, rx, cy, rz, earRx, earRz, ref, loud, g);

		double mx = (lx + rx) / 2 - p.x, my = cy - (p.y + p.getHeadHeight()), mz = (lz + rz) / 2 - p.z;
		double dist = Math.sqrt(mx * mx + my * my + mz * mz);
		distLpHz = (float) Math.max(900, 18000 / (1 + Math.max(0, dist - ref) / 4.0));
		applyLowpass();
		gLL = left[0];
		gLR = left[1];
		gRL = right[0];
		gRR = right[1];
	}

	private void surround(Player p, MonitorGroup g, java.util.List<double[]> spk, double earRx, double earRz, float master) {
		double rX = g.rx, rZ = g.rz;
		double rn = Math.sqrt(rX * rX + rZ * rZ);
		if (rn < 1e-6) { rX = earRx; rZ = earRz; } else { rX /= rn; rZ /= rn; }
		double band = Math.max(1.0, g.w / 2.0);
		int n = spk.size();
		float[] cl = new float[n], cr = new float[n];
		float sumL = 0, sumR = 0;
		for (int i = 0; i < n; i++) {
			double[] s = spk.get(i);
			double side = (s[0] - g.cx) * rX + (s[2] - g.cz) * rZ;
			double t = Math.max(-1, Math.min(1, side / band));
			double a = (t + 1) * Math.PI / 4;
			cl[i] = (float) Math.cos(a);
			cr[i] = (float) Math.sin(a);
			sumL += cl[i] * cl[i];
			sumR += cr[i] * cr[i];
		}

		float lack = Math.max(0f, 0.5f - Math.min(sumL, sumR)) / 0.5f;
		if (lack > 0) for (int i = 0; i < n; i++) {
			cl[i] += (0.7071f - cl[i]) * lack;
			cr[i] += (0.7071f - cr[i]) * lack;
		}

		float base = master * volume * 1.25f / (float) Math.sqrt(spk.size());
		float ll = 0, lr = 0, rl = 0, rr = 0;
		double nearest = Double.MAX_VALUE;
		float nearestRef = 2.5f;
		for (int i = 0; i < n; i++) {
			double[] s = spk.get(i);
			float chL = cl[i], chR = cr[i];

			float size = (float) Math.sqrt(s[4]);
			float t = Math.min(1f, (size - 1f) / 3f);
			float loud = base * (0.5f + 0.5f * t);
			float ref = 1.5f + 0.75f * size;
			float fadeEnd = 16 + 8 * size;
			float[] e = speakerEars(p, s[0], s[1], s[2], earRx, earRz, ref, loud, fadeEnd);
			ll += chL * e[0];
			lr += chL * e[1];
			rl += chR * e[0];
			rr += chR * e[1];
			double dx = s[0] - p.x, dy = s[1] - (p.y + p.getHeadHeight()), dz = s[2] - p.z;
			double dd = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (dd < nearest) { nearest = dd; nearestRef = ref; }
		}
		distLpHz = (float) Math.max(900, 18000 / (1 + Math.max(0, nearest - nearestRef) / 4.0));
		applyLowpass();
		gLL = ll;
		gLR = lr;
		gRL = rl;
		gRR = rr;
	}

	private static float[] speakerEars(Player p, double sx, double sy, double sz, double earRx, double earRz, float ref, float loud, float fadeEnd) {
		double dx = sx - p.x, dy = sy - (p.y + p.getHeadHeight()), dz = sz - p.z;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		float att = (float) (dist <= ref ? 1.0 : ref / dist);
		att *= (float) Math.max(0, Math.min(1, (fadeEnd - dist) / 8));
		double hd = Math.sqrt(dx * dx + dz * dz);
		double pan = hd < 1e-3 ? 0 : (dx * earRx + dz * earRz) / hd;
		pan *= Math.min(1, hd / 1.5) * 0.6;
		double a = (pan + 1) * Math.PI / 4;
		return new float[]{(float) Math.cos(a) * att * loud, (float) Math.sin(a) * att * loud};
	}

	private static float[] edge(Player p, double sx, double sy, double sz, double earRx, double earRz, float ref, float loud, MonitorGroup g) {
		double eyeY = p.y + p.getHeadHeight();
		double dx = sx - p.x, dy = sy - eyeY, dz = sz - p.z;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

		float att = (float) (dist <= ref ? 1.0 : ref / dist);

		float fadeEnd = 16 + 8 * (float) Math.sqrt(g.w * g.h);
		att *= (float) Math.max(0, Math.min(1, (fadeEnd - dist) / 8));

		if ((p.x - sx) * g.fx + (p.z - sz) * g.fz < 0) att *= 0.35f;
		double hd = Math.sqrt(dx * dx + dz * dz);
		double pan = hd < 1e-3 ? 0 : (dx * earRx + dz * earRz) / hd;

		pan *= Math.min(1, hd / 1.5);

		pan *= 0.6;
		double a = (pan + 1) * Math.PI / 4;
		return new float[]{(float) Math.cos(a) * att * loud, (float) Math.sin(a) * att * loud};
	}

	synchronized void pumpNonBlocking(Machine apu) {

		if (failed || cutDone) { apu.drainAudio(in); return; }
		matchFormat();
		if (line == null && !open()) return;
		int frames = apu.drainAudio(in);
		if (frames == 0) return;
		int space = line.available() / (sampleBytes * 2);
		if (space <= 0) return;
		frames = Math.min(frames, space);
		writeFrames(frames);
	}

	synchronized void pushFrames(float[] src, int frames) {
		if (failed || cutDone || frames <= 0) return;
		matchFormat();
		if (line == null && !open()) return;
		int space = line.available() / (sampleBytes * 2);
		frames = Math.min(Math.min(frames, space), in.length / 2);
		if (frames <= 0) return;
		System.arraycopy(src, 0, in, 0, frames * 2);
		writeFrames(frames);
	}

	boolean pump(Machine apu) {
		if (failed || cutDone) { apu.drainAudio(in); return false; }
		matchFormat();
		if (line == null && !open()) return false;
		int frames = apu.drainAudio(in);
		if (frames == 0) return false;
		writeFrames(frames);
		return true;
	}

	private static final int BUFFER_FRAMES = RATE / 4;

	private void writeFrames(int frames) {

		int fb = sampleBytes * 2;
		if (line.available() >= BUFFER_FRAMES * fb - 64) {
			byte[] cushion = new byte[(RATE / 20) * fb];
			line.write(cushion, 0, cushion.length);
		}
		if (out.length < frames * fb) out = new byte[frames * fb];
		final float k = 0.002f;
		float tLL = gLL, tLR = gLR, tRL = gRL, tRR = gRR;
		for (int i = 0; i < frames; i++) {
			cLL += (tLL - cLL) * k;
			cLR += (tLR - cLR) * k;
			cRL += (tRL - cRL) * k;
			cRR += (tRR - cRR) * k;
			float l = filter(0, in[i * 2]), r = filter(1, in[i * 2 + 1]);
			if (cabinet) {

				float m = (l + r) * 0.5f;
				float e = combL[combPos];
				float y = m + e * 0.45f;
				combL[combPos] = y;
				combPos = (combPos + 1) % combL.length;
				l = r = y * 0.8f;
			}
			float wet = glitchWet;
			if (wet > 0.02f) {
				if (glitchCrt) {

					humPhase += 2 * Math.PI * 50 / RATE;
					float hum = (float) (Math.sin(humPhase) * 0.6 + Math.sin(humPhase * 3) * 0.25) * 0.08f * wet;
					l += hum;
					r += hum;
					if (--swapTimer <= 0) {
						swapTimer = RATE / 4 + rng.nextInt(RATE);
						swapped = rng.nextFloat() < wet * 0.7f;
					}
					if (swapped) { float t = l; l = r; r = t; }
				} else {

					if (dropout > 0) { dropout--; l = 0; r = 0; }
					else if (rng.nextFloat() < 0.0004f * wet) dropout = rng.nextInt(RATE / 12);
					if (rng.nextFloat() < 0.002f * wet) { float c = (rng.nextFloat() - 0.5f) * 0.8f * wet; l += c; r += c; }
				}
			}
			float el = l * cLL + r * cRL;
			float er = l * cLR + r * cRR;
			if (sampleBytes == 3) {
				put24(out, i * 6, el);
				put24(out, i * 6 + 3, er);
			} else {
				put(out, i * 4, el);
				put(out, i * 4 + 2, er);
			}
		}
		line.write(out, 0, frames * fb);
	}

	private float filter(int ch, float x) {
		float hp = hpA * (hpOut[ch] + x - hpPrevIn[ch]);
		hpPrevIn[ch] = x;
		hpOut[ch] = hp;
		lpOut[ch] += lpA * (hp - lpOut[ch]);
		return lpOut[ch];
	}

	private static void put(byte[] b, int o, float v) {
		int s = (int) (Math.max(-1f, Math.min(1f, v * 3f)) * 32767);
		b[o] = (byte) s;
		b[o + 1] = (byte) (s >> 8);
	}

	private static void put24(byte[] b, int o, float v) {
		int s = (int) (Math.max(-1f, Math.min(1f, v * 3f)) * 8388607);
		b[o] = (byte) s;
		b[o + 1] = (byte) (s >> 8);
		b[o + 2] = (byte) (s >> 16);
	}

	private void matchFormat() {
		int want = hiFi && !noHiFi ? 3 : 2;
		if (line != null && want != sampleBytes) {
			line.stop();
			line.flush();
			line.close();
			line = null;
		}
	}

	private boolean open() {
		if (hiFi && !noHiFi) {
			sampleBytes = 3;
			if (openLine()) return true;
			noHiFi = true;
		}
		sampleBytes = 2;
		if (openLine()) return true;
		failed = true;
		return false;
	}

	private boolean openLine() {
		try {
			int fb = sampleBytes * 2;
			AudioFormat fmt = new AudioFormat(RATE, sampleBytes * 8, 2, true, false);
			line = AudioSystem.getSourceDataLine(fmt);
			line.open(fmt, BUFFER_FRAMES * fb);

			byte[] silence = new byte[RATE / 16 * fb];
			line.write(silence, 0, silence.length);
			line.start();
			return true;
		} catch (Exception e) {

			line = null;
			return false;
		}
	}

	private boolean cutDone = false;

	volatile float glitchWet = 0f;

	volatile float volume = 1f;
	volatile boolean glitchCrt = false;
	private final java.util.Random rng = new java.util.Random();
	private double humPhase = 0;
	private int dropout = 0;
	private boolean swapped = false;
	private int swapTimer = 0;

	synchronized void cut() {
		if (cutDone) return;
		cutDone = true;
		if (line != null) {
			try {
				line.flush();
				if (sampleBytes != 2) { line.drain(); close(); return; }
				byte[] pop = new byte[24 * 4];
				for (int i = 0; i < 8; i++) {
					put(pop, i * 4, 0.5f / 5f);
					put(pop, i * 4 + 2, 0.5f / 5f);
				}
				line.write(pop, 0, pop.length);
				line.drain();
			} catch (Exception ignored) {
			}
		}
		close();
	}

	synchronized void close() {
		cutDone = true;
		if (line != null) {
			line.stop();
			line.flush();
			line.close();
			line = null;
		}
	}
}
