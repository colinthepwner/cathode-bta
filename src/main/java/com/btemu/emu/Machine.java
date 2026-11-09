package com.btemu.emu;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

public interface Machine {

	int BTN_X = 0x1000, BTN_Y = 0x2000, BTN_L = 0x4000, BTN_R = 0x8000;

	int BTN_Z = 0x10000, BTN_C_UP = 0x20000, BTN_C_DOWN = 0x40000, BTN_C_LEFT = 0x80000, BTN_C_RIGHT = 0x100000;

	int width();

	int height();

	int[] frame();

	void setButtons(int mask);

	default int maxPlayers() {
		return 1;
	}

	default void setButtons(int port, int mask) {
		if (port == 0) setButtons(mask);
	}

	void runFrame();

	void saveState(DataOutputStream o) throws IOException;

	void loadState(DataInputStream in) throws IOException;

	int drainAudio(float[] out);

	default double frameNs() {
		return 16_742_706.0;
	}

	default void flush() {}

	default void glitchFlip(Random rng, int count, boolean deep) {}

	default void setNoteMode(boolean on) {}

	default boolean supportsNoteMode() {
		return false;
	}

	default void close() {}
}
