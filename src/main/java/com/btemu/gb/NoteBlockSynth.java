package com.btemu.gb;

public final class NoteBlockSynth {
	public enum Instrument {

		HARP(369.99f), BASS(92.50f), SNARE(0), HAT(0), BASSDRUM(0),

		PLING(369.99f);

		final float baseHz;

		Instrument(float baseHz) {
			this.baseHz = baseHz;
		}
	}

	private final float[][] samples = new float[Instrument.values().length][];
	private final int[] rates = new int[Instrument.values().length];

	private static final int VOICES = 24;
	private final float[][] vSample = new float[VOICES][];
	private final double[] vPos = new double[VOICES], vStep = new double[VOICES];
	private final float[] vL = new float[VOICES], vR = new float[VOICES];
	private final int[] vChan = new int[VOICES];
	private final long[] vStart = new long[VOICES];
	private long clock = 0;

	public void setSample(Instrument inst, float[] mono, int rate) {
		samples[inst.ordinal()] = mono;
		rates[inst.ordinal()] = rate;
	}

	public boolean hasSamples() {
		for (float[] s : samples) if (s != null) return true;
		return false;
	}

	void note(int chan, Instrument inst, float hz, float vol, boolean left, boolean right) {
		float[] s = samples[inst.ordinal()];
		if (s == null || vol <= 0.01f || (!left && !right)) return;
		double ratio = 1.0;
		if (inst.baseHz > 0) {
			if (hz < 20 || hz > 20000) return;
			ratio = hz / inst.baseHz;
			while (ratio > 2.0) ratio *= 0.5;
			while (ratio < 0.5) ratio *= 2.0;
		}

		int slot = -1, mine = 0, oldestMine = -1, oldest = 0;
		for (int i = 0; i < VOICES; i++) {
			if (vSample[i] == null) { if (slot < 0) slot = i; continue; }
			if (vChan[i] == chan) {
				mine++;
				if (oldestMine < 0 || vStart[i] < vStart[oldestMine]) oldestMine = i;
			}
			if (vStart[i] < vStart[oldest]) oldest = i;
		}
		if (mine >= 3) slot = oldestMine;
		if (slot < 0) slot = oldest;
		vSample[slot] = s;
		vPos[slot] = 0;
		vStep[slot] = ratio * rates[inst.ordinal()] / Apu.SAMPLE_RATE;
		vL[slot] = left ? vol : 0;
		vR[slot] = right ? vol : 0;
		vChan[slot] = chan;
		vStart[slot] = clock++;
	}

	void render(float[] out) {
		float l = 0, r = 0;
		for (int i = 0; i < VOICES; i++) {
			float[] s = vSample[i];
			if (s == null) continue;
			int p = (int) vPos[i];
			if (p >= s.length - 1) { vSample[i] = null; continue; }
			float f = (float) (vPos[i] - p);
			float v = s[p] + (s[p + 1] - s[p]) * f;
			l += v * vL[i];
			r += v * vR[i];
			vPos[i] += vStep[i];
		}
		out[0] = l * 0.9f;
		out[1] = r * 0.9f;
	}

	public void silence() {
		for (int i = 0; i < VOICES; i++) vSample[i] = null;
	}
}
