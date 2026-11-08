package com.btemu.emu;

import com.btemu.gb.GameBoy;

import java.io.File;
import java.io.IOException;

public final class Machines {
	private Machines() {}

	public static Machine create(String romName, byte[] rom, File saveFile) throws IOException {
		Console c = Console.of(romName);
		if (c == null || c.core == null) return new GameBoy(rom, saveFile);
		return new RetroCore(c, CoreStore.get(c.core), rom, romName);
	}
}
