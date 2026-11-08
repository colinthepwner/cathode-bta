package com.btemu.gb;

public final class Apu {
	public static final int SAMPLE_RATE = 48000;
	private static final double CYCLES_PER_SAMPLE = 4194304.0 / SAMPLE_RATE;

	private static final int[][] DUTY = {
		{0, 0, 0, 0, 0, 0, 0, 1},
		{1, 0, 0, 0, 0, 0, 0, 1},
		{1, 0, 0, 0, 0, 1, 1, 1},
		{0, 1, 1, 1, 1, 1, 1, 0},
	};
	private static final int[] NOISE_DIV = {8, 16, 32, 48, 64, 80, 96, 112};

	private static final int[] READ_OR = {
		0x80, 0x3F, 0x00, 0xFF, 0xBF, 0xFF, 0x3F, 0x00, 0xFF, 0xBF,
		0x7F, 0xFF, 0x9F, 0xFF, 0xBF, 0xFF, 0xFF, 0x00, 0x00, 0xBF,
		0x00, 0x00, 0x70, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
	};

	public final NoteBlockSynth notes = new NoteBlockSynth();
	public volatile boolean noteMode = false;
	private final float[] nb = new float[2];

	private final float[] lastNoteHz = new float[4];

	private final float[] lastHz = new float[3];
	private final long[] lastNoteAt = new long[4];
	private long samplesOut = 0;
	private final boolean[] legatoPending = new boolean[3];

	private final long[] sfxUntil = new long[4];

	private final int[] slideCount = new int[3];
	private final long[] slideWindow = new long[3];
	private float sfxL, sfxR;

	private final int[] regs = new int[0x30];
	private final int[] wave = new int[16];
	private boolean power = true;
	private int frameSeqCounter = 0, frameSeqStep = 0;

	private final boolean[] sqOn = new boolean[2];
	private final int[] sqLen = new int[2], sqTimer = new int[2], sqPos = new int[2], sqVol = new int[2], sqEnvTimer = new int[2];
	private int sweepTimer, sweepShadow;
	private boolean sweepOn;

	private boolean waOn;
	private int waLen, waTimer, waPos;

	private boolean noOn;
	private int noLen, noTimer, noVol, noEnvTimer, lfsr = 0x7FFF;

	private final float[] chOut = new float[4];

	private double sampleClock = 0;
	private float accL, accR;
	private int accN;
	private float hpL, hpR, hpPrevInL, hpPrevInR;
	private final float[] ring = new float[SAMPLE_RATE / 2 * 2];
	private int ringWrite = 0, ringRead = 0;

	public Apu() {

		regs[0x24] = 0x77;
		regs[0x25] = 0xF3;
		regs[0x26] = 0xF1;
	}

	void saveState(java.io.DataOutputStream o) throws java.io.IOException {
		for (int v : regs) o.writeInt(v);
		for (int v : wave) o.writeInt(v);
		o.writeBoolean(power);
		o.writeInt(frameSeqCounter);
		o.writeInt(frameSeqStep);
		for (int ch = 0; ch < 2; ch++) {
			o.writeBoolean(sqOn[ch]);
			o.writeInt(sqLen[ch]); o.writeInt(sqTimer[ch]); o.writeInt(sqPos[ch]); o.writeInt(sqVol[ch]); o.writeInt(sqEnvTimer[ch]);
		}
		o.writeInt(sweepTimer); o.writeInt(sweepShadow); o.writeBoolean(sweepOn);
		o.writeBoolean(waOn); o.writeInt(waLen); o.writeInt(waTimer); o.writeInt(waPos);
		o.writeBoolean(noOn); o.writeInt(noLen); o.writeInt(noTimer); o.writeInt(noVol); o.writeInt(noEnvTimer); o.writeInt(lfsr);
		o.writeDouble(sampleClock);
	}

	void loadState(java.io.DataInputStream in) throws java.io.IOException {
		for (int i = 0; i < regs.length; i++) regs[i] = in.readInt();
		for (int i = 0; i < wave.length; i++) wave[i] = in.readInt();
		power = in.readBoolean();
		frameSeqCounter = in.readInt();
		frameSeqStep = in.readInt();
		for (int ch = 0; ch < 2; ch++) {
			sqOn[ch] = in.readBoolean();
			sqLen[ch] = in.readInt(); sqTimer[ch] = in.readInt(); sqPos[ch] = in.readInt(); sqVol[ch] = in.readInt(); sqEnvTimer[ch] = in.readInt();
		}
		sweepTimer = in.readInt(); sweepShadow = in.readInt(); sweepOn = in.readBoolean();
		waOn = in.readBoolean(); waLen = in.readInt(); waTimer = in.readInt(); waPos = in.readInt();
		noOn = in.readBoolean(); noLen = in.readInt(); noTimer = in.readInt(); noVol = in.readInt(); noEnvTimer = in.readInt(); lfsr = in.readInt();
		sampleClock = in.readDouble();
		notes.silence();
	}

	int read(int addr) {
		if (addr >= 0xFF30) return wave[addr - 0xFF30];
		int r = addr - 0xFF10;
		if (r == 0x16) {
			return 0x70 | (power ? 0x80 : 0) | (sqOn[0] ? 1 : 0) | (sqOn[1] ? 2 : 0) | (waOn ? 4 : 0) | (noOn ? 8 : 0);
		}
		return regs[r + 0x10] | READ_OR[r];
	}

	void write(int addr, int v) {
		if (addr >= 0xFF30) { wave[addr - 0xFF30] = v; return; }
		int r = addr - 0xFF10;
		if (r == 0x16) {
			boolean on = (v & 0x80) != 0;
			if (power && !on) {
				for (int i = 0x10; i < 0x26; i++) regs[i] = 0;
				sqOn[0] = sqOn[1] = waOn = noOn = false;
			}
			if (!power && on) frameSeqStep = 0;
			power = on;
			return;
		}
		if (!power) return;
		regs[r + 0x10] = v;
		switch (r) {
			case 0x01 -> sqLen[0] = 64 - (v & 0x3F);
			case 0x06 -> sqLen[1] = 64 - (v & 0x3F);
			case 0x0B -> waLen = 256 - v;
			case 0x10 -> noLen = 64 - (v & 0x3F);
			case 0x02 -> { if ((v & 0xF8) == 0) sqOn[0] = false; }
			case 0x07 -> { if ((v & 0xF8) == 0) sqOn[1] = false; }
			case 0x0A -> { if ((v & 0x80) == 0) waOn = false; }
			case 0x11 -> { if ((v & 0xF8) == 0) noOn = false; }
			case 0x04 -> { if ((v & 0x80) != 0) triggerSquare(0); else legato(0); }
			case 0x09 -> { if ((v & 0x80) != 0) triggerSquare(1); else legato(1); }
			case 0x0E -> { if ((v & 0x80) != 0) triggerWave(); else legato(2); }
			case 0x03 -> legato(0);
			case 0x08 -> legato(1);
			case 0x0D -> legato(2);
			case 0x13 -> { if ((v & 0x80) != 0) triggerNoise(); }
			default -> {}
		}
	}

	private int nr(int r) { return regs[0x10 + r]; }

	private int sqFreq(int ch) {
		int b = ch * 5;
		return nr(b + 3) | ((nr(b + 4) & 7) << 8);
	}

	private void triggerSquare(int ch) {
		int b = ch * 5;
		sqOn[ch] = (nr(b + 2) & 0xF8) != 0;
		if (sqLen[ch] == 0) sqLen[ch] = 64;
		sqTimer[ch] = (2048 - sqFreq(ch)) * 4;
		sqVol[ch] = nr(b + 2) >> 4;
		sqEnvTimer[ch] = nr(b + 2) & 7;
		boolean lengthOn = (nr(b + 4) & 0x40) != 0;
		boolean sweep = ch == 0 && ((nr(0) >> 4) & 7) != 0 && (nr(0) & 7) != 0;
		if (sweep) markSfx(ch, 0.4f);
		else if (lengthOn && sqLen[ch] < 16) markSfx(ch, 0.08f);
		if (sqOn[ch]) noteEvent(ch, NoteBlockSynth.Instrument.HARP, 131072f / (2048 - sqFreq(ch)), sqVol[ch] / 15f);
		if (ch == 0) {
			sweepShadow = sqFreq(0);
			int period = (nr(0) >> 4) & 7, shift = nr(0) & 7;
			sweepTimer = period == 0 ? 8 : period;
			sweepOn = period != 0 || shift != 0;
			if (shift != 0 && sweepCalc() > 2047) sqOn[0] = false;
		}
	}

	private int sweepCalc() {
		int delta = sweepShadow >> (nr(0) & 7);
		return (nr(0) & 8) != 0 ? sweepShadow - delta : sweepShadow + delta;
	}

	private void triggerWave() {
		waOn = (nr(0x0A) & 0x80) != 0;
		if (waLen == 0) waLen = 256;
		waTimer = (2048 - (nr(0x0D) | ((nr(0x0E) & 7) << 8))) * 2;
		waPos = 0;
		if (waOn) noteEvent(2, NoteBlockSynth.Instrument.BASS, waveHz(), waveVol());
	}

	private float waveHz() {
		return 65536f / (2048 - (nr(0x0D) | ((nr(0x0E) & 7) << 8)));
	}

	private float waveVol() {
		int code = (nr(0x0C) >> 5) & 3;
		return code == 0 ? 0f : 1f / (1 << (code - 1));
	}

	private void triggerNoise() {
		noOn = (nr(0x11) & 0xF8) != 0;
		if (noLen == 0) noLen = 64;
		noTimer = noisePeriod();
		noVol = nr(0x11) >> 4;
		noEnvTimer = nr(0x11) & 7;
		lfsr = 0x7FFF;
		int env = nr(0x11);
		boolean longNoise = (nr(0x13) & 0x40) == 0 && ((env & 7) == 0 || (env & 7) >= 3 || (env & 8) != 0);
		if (longNoise) markSfx(3, 0.5f);
		if (noOn) {

			int shift = nr(0x12) >> 4;
			NoteBlockSynth.Instrument drum = shift <= 2 ? NoteBlockSynth.Instrument.HAT
				: shift <= 6 ? NoteBlockSynth.Instrument.SNARE : NoteBlockSynth.Instrument.BASSDRUM;
			noteEvent(3, drum, 0, noVol / 15f);
		}
	}

	private void followPitch(int ch, float hz, float vol) {
		float last = lastNoteHz[ch];
		if (last > 0 && Math.abs(hz / last - 1f) < 0.03f) return;
		if (samplesOut - lastNoteAt[ch] < SAMPLE_RATE * 60 / 1000) return;
		noteEvent(ch, NoteBlockSynth.Instrument.PLING, hz, vol * 0.6f);
	}

	private void tickNoiseRoll() {
		if (!noOn || !isSfx(3) || noVol == 0) return;
		if (samplesOut - lastNoteAt[3] < SAMPLE_RATE * 90 / 1000) return;
		int shift = nr(0x12) >> 4;
		NoteBlockSynth.Instrument drum = shift <= 4 ? NoteBlockSynth.Instrument.HAT : NoteBlockSynth.Instrument.SNARE;
		noteEvent(3, drum, 0, noVol / 15f * 0.45f);
	}

	private void markSfx(int ch, float seconds) {
		sfxUntil[ch] = Math.max(sfxUntil[ch], samplesOut + (long) (seconds * SAMPLE_RATE));
	}

	private boolean isSfx(int ch) {
		return sfxUntil[ch] > samplesOut;
	}

	private void noteEvent(int ch, NoteBlockSynth.Instrument inst, float hz, float vol) {
		if (!noteMode) return;
		if (isSfx(ch) && ch < 3) { inst = NoteBlockSynth.Instrument.PLING; vol *= 0.6f; }
		int pan = nr(0x15);
		lastNoteHz[ch] = hz;
		lastNoteAt[ch] = samplesOut;
		notes.note(ch, inst, hz, vol, (pan & (1 << (ch + 4))) != 0, (pan & (1 << ch)) != 0);
	}

	private void legato(int ch) {

		if (noteMode) legatoPending[ch] = true;
	}

	private void checkLegato(int ch) {
		boolean on = ch < 2 ? sqOn[ch] : waOn;
		if (!on) return;
		float hz = ch < 2 ? 131072f / (2048 - sqFreq(ch)) : waveHz();
		float prev = lastHz[ch];
		lastHz[ch] = hz;
		if (prev > 0 && Math.abs(hz / prev - 1f) > 0.03f) {

			if (samplesOut - slideWindow[ch] > SAMPLE_RATE * 80 / 1000) { slideWindow[ch] = samplesOut; slideCount[ch] = 0; }
			if (++slideCount[ch] >= 3) markSfx(ch, 0.25f);
		}
		float last = lastNoteHz[ch];
		if (isSfx(ch)) {
			followPitch(ch, hz, ch < 2 ? sqVol[ch] / 15f : waveVol());
			return;
		}
		if (last > 0 && Math.abs(hz / last - 1f) < 0.055f) return;
		if (samplesOut - lastNoteAt[ch] < SAMPLE_RATE * 60 / 1000) return;
		if (ch < 2) noteEvent(ch, NoteBlockSynth.Instrument.HARP, hz, sqVol[ch] / 15f);
		else noteEvent(2, NoteBlockSynth.Instrument.BASS, hz, waveVol());
	}

	private int noisePeriod() {
		int v = nr(0x12);
		return NOISE_DIV[v & 7] << (v >> 4);
	}

	void tick(int cycles) {
		for (int c = 0; c < cycles; c += 4) step4();
	}

	private void step4() {
		if (power) {
			frameSeqCounter += 4;
			if (frameSeqCounter >= 8192) {
				frameSeqCounter -= 8192;
				frameSequencer();
			}
			for (int ch = 0; ch < 2; ch++) {
				sqTimer[ch] -= 4;
				while (sqTimer[ch] <= 0) {
					sqTimer[ch] += (2048 - sqFreq(ch)) * 4;
					sqPos[ch] = (sqPos[ch] + 1) & 7;
				}
			}
			waTimer -= 4;
			while (waTimer <= 0) {
				waTimer += (2048 - (nr(0x0D) | ((nr(0x0E) & 7) << 8))) * 2;
				waPos = (waPos + 1) & 31;
			}
			noTimer -= 4;
			while (noTimer <= 0) {
				noTimer += noisePeriod();
				int bit = (lfsr ^ (lfsr >> 1)) & 1;
				lfsr = (lfsr >> 1) | (bit << 14);
				if ((nr(0x12) & 8) != 0) lfsr = (lfsr & ~0x40) | (bit << 6);
			}
		}
		mix();
	}

	private void frameSequencer() {
		if ((frameSeqStep & 1) == 0) {
			for (int ch = 0; ch < 2; ch++)
				if ((nr(ch * 5 + 4) & 0x40) != 0 && sqLen[ch] > 0 && --sqLen[ch] == 0) sqOn[ch] = false;
			if ((nr(0x0E) & 0x40) != 0 && waLen > 0 && --waLen == 0) waOn = false;
			if ((nr(0x13) & 0x40) != 0 && noLen > 0 && --noLen == 0) noOn = false;
		}
		if (frameSeqStep == 2 || frameSeqStep == 6) {
			if (sweepOn && --sweepTimer <= 0) {
				int period = (nr(0) >> 4) & 7;
				sweepTimer = period == 0 ? 8 : period;
				if (period != 0) {
					int f = sweepCalc();
					if (f > 2047) sqOn[0] = false;
					else if ((nr(0) & 7) != 0) {
						sweepShadow = f;
						regs[0x13] = f & 0xFF;
						regs[0x14] = (regs[0x14] & ~7) | (f >> 8);
						if (noteMode && sqOn[0]) followPitch(0, 131072f / (2048 - f), sqVol[0] / 15f);
						if (sweepCalc() > 2047) sqOn[0] = false;
					}
				}
			}
		}
		if (frameSeqStep == 7) {
			for (int ch = 0; ch < 2; ch++) {
				int env = nr(ch * 5 + 2);
				if ((env & 7) != 0 && --sqEnvTimer[ch] <= 0) {
					sqEnvTimer[ch] = env & 7;
					if ((env & 8) != 0 && sqVol[ch] < 15) sqVol[ch]++;
					else if ((env & 8) == 0 && sqVol[ch] > 0) sqVol[ch]--;
				}
			}
			int env = nr(0x11);
			if ((env & 7) != 0 && --noEnvTimer <= 0) {
				noEnvTimer = env & 7;
				if ((env & 8) != 0 && noVol < 15) noVol++;
				else if ((env & 8) == 0 && noVol > 0) noVol--;
			}
		}
		frameSeqStep = (frameSeqStep + 1) & 7;
	}

	private void mix() {
		float l = 0, r = 0;
		if (power) {
			float[] out = chOut;
			out[0] = out[1] = out[2] = out[3] = 0;
			for (int ch = 0; ch < 2; ch++)
				if (sqOn[ch]) out[ch] = DUTY[nr(ch * 5 + 1) >> 6][sqPos[ch]] * sqVol[ch] / 15f;
			if (waOn) {
				int b = wave[waPos >> 1];
				int s = (waPos & 1) == 0 ? b >> 4 : b & 0xF;
				int code = (nr(0x0C) >> 5) & 3;
				out[2] = code == 0 ? 0 : (s >> (code - 1)) / 15f;
			}
			if (noOn) out[3] = ((~lfsr) & 1) * noVol / 15f;
			int pan = nr(0x15);
			float el = 0, er = 0;
			for (int ch = 0; ch < 4; ch++) {
				boolean sfx = noteMode && isSfx(ch);
				if ((pan & (1 << (ch + 4))) != 0) { l += out[ch]; if (sfx) el += out[ch]; }
				if ((pan & (1 << ch)) != 0) { r += out[ch]; if (sfx) er += out[ch]; }
			}
			int vol = nr(0x14);
			float vl = (((vol >> 4) & 7) + 1) / 8f, vr = ((vol & 7) + 1) / 8f;
			l *= vl;
			r *= vr;
			sfxL += el * vl;
			sfxR += er * vr;
		}
		accL += l;
		accR += r;
		accN++;
		sampleClock += 4;
		if (sampleClock >= CYCLES_PER_SAMPLE) {
			sampleClock -= CYCLES_PER_SAMPLE;
			float sl = accL / accN / 4f, sr = accR / accN / 4f;
			samplesOut++;
			if (noteMode) {
				for (int ch = 0; ch < 3; ch++) if (legatoPending[ch]) { legatoPending[ch] = false; checkLegato(ch); }
				notes.render(nb);
				int vol = nr(0x14);

				sl = power ? nb[0] * (((vol >> 4) & 7) + 1) / 8f : 0f;
				sr = power ? nb[1] * ((vol & 7) + 1) / 8f : 0f;
				tickNoiseRoll();
			}
			sfxL = sfxR = 0;
			accL = accR = 0;
			accN = 0;

			hpL = 0.9974f * (hpL + sl - hpPrevInL);
			hpPrevInL = sl;
			hpR = 0.9974f * (hpR + sr - hpPrevInR);
			hpPrevInR = sr;
			ring[ringWrite] = hpL;
			ring[ringWrite + 1] = hpR;
			ringWrite = (ringWrite + 2) % ring.length;
			if (ringWrite == ringRead) ringRead = (ringRead + 2) % ring.length;
		}
	}

	public int drain(float[] out) {
		int frames = 0;
		while (ringRead != ringWrite && frames * 2 + 1 < out.length) {
			out[frames * 2] = ring[ringRead];
			out[frames * 2 + 1] = ring[ringRead + 1];
			ringRead = (ringRead + 2) % ring.length;
			frames++;
		}
		return frames;
	}
}
