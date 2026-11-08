package com.btemu.gb;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class Cartridge {
	private final byte[] rom;
	private final byte[] ram;
	private final int type;
	private final boolean battery;
	private final File saveFile;

	private int romBank = 1;
	private int ramBank = 0;
	private boolean ramEnabled = false;

	private int mbc1Mode = 0;
	private int mbc1Upper = 0;

	private int rtcSelect = -1;
	private boolean dirty = false;

	public final String title;
	public final boolean cgbFlag;

	public Cartridge(File romFile) throws IOException {
		this(Files.readAllBytes(romFile.toPath()), saveFileFor(romFile));
	}

	private static File saveFileFor(File romFile) {
		String name = romFile.getName();
		int dot = name.lastIndexOf('.');
		return new File(romFile.getParentFile(), (dot > 0 ? name.substring(0, dot) : name) + ".sav");
	}

	public Cartridge(byte[] romBytes, File saveFile) throws IOException {
		this.rom = romBytes;
		if (rom.length < 0x150) throw new IOException("Not a Game Boy ROM (too small)");
		this.type = rom[0x147] & 0xFF;
		int ramSize = switch (rom[0x149] & 0xFF) {
			case 1 -> 0x800;
			case 2 -> 0x2000;
			case 3 -> 0x8000;
			case 4 -> 0x20000;
			case 5 -> 0x10000;
			default -> 0;
		};

		if (type == 5 || type == 6) ramSize = 0x200;

		if (ramSize == 0 && isRamType(type)) ramSize = 0x8000;
		this.ram = new byte[Math.max(ramSize, 1)];
		this.battery = switch (type) {
			case 0x03, 0x06, 0x09, 0x0D, 0x0F, 0x10, 0x13, 0x1B, 0x1E, 0x22, 0xFF -> true;
			default -> false;
		};
		StringBuilder sb = new StringBuilder();
		for (int i = 0x134; i < 0x144; i++) {
			int c = rom[i] & 0xFF;
			if (c == 0) break;
			if (c >= 32 && c < 127) sb.append((char) c);
		}
		this.title = sb.toString().trim();
		this.cgbFlag = (rom[0x143] & 0x80) != 0;
		this.saveFile = saveFile;
		if (battery && saveFile != null && saveFile.isFile()) {
			byte[] saved = Files.readAllBytes(saveFile.toPath());
			System.arraycopy(saved, 0, ram, 0, Math.min(saved.length, ram.length));
		}
	}

	private static boolean isRamType(int t) {
		return switch (t) {
			case 0x02, 0x03, 0x08, 0x09, 0x0C, 0x0D, 0x10, 0x12, 0x13, 0x1A, 0x1B, 0x1D, 0x1E -> true;
			default -> false;
		};
	}

	private int mbc() {
		if (type == 0 || type == 8 || type == 9) return 0;
		if (type <= 3) return 1;
		if (type == 5 || type == 6) return 2;
		if (type >= 0x0F && type <= 0x13) return 3;
		if (type >= 0x19 && type <= 0x1E) return 5;
		return 1;
	}

	private int romBanks() {
		return Math.max(2, rom.length / 0x4000);
	}

	public int readRom(int addr) {
		if (addr < 0x4000) {
			int bank = 0;

			if (mbc() == 1 && mbc1Mode == 1) bank = (mbc1Upper << 5) % romBanks();
			return rom[(bank * 0x4000 + addr) % rom.length] & 0xFF;
		}
		int bank = currentRomBank() % romBanks();
		return rom[(bank * 0x4000 + (addr - 0x4000)) % rom.length] & 0xFF;
	}

	private int currentRomBank() {
		if (mbc() == 1) {
			int low = romBank & 0x1F;
			if (low == 0) low = 1;
			return low | (mbc1Upper << 5);
		}
		return romBank;
	}

	public void writeRom(int addr, int val) {
		switch (mbc()) {
			case 0 -> {}
			case 1 -> {
				if (addr < 0x2000) ramEnabled = (val & 0x0F) == 0x0A;
				else if (addr < 0x4000) romBank = val & 0x1F;
				else if (addr < 0x6000) mbc1Upper = val & 0x03;
				else mbc1Mode = val & 1;
			}
			case 2 -> {
				if (addr < 0x4000) {

					if ((addr & 0x100) == 0) ramEnabled = (val & 0x0F) == 0x0A;
					else { romBank = val & 0x0F; if (romBank == 0) romBank = 1; }
				}
			}
			case 3 -> {
				if (addr < 0x2000) ramEnabled = (val & 0x0F) == 0x0A;
				else if (addr < 0x4000) { romBank = val & 0x7F; if (romBank == 0) romBank = 1; }
				else if (addr < 0x6000) {
					if (val <= 0x07) { ramBank = val; rtcSelect = -1; }
					else if (val >= 0x08 && val <= 0x0C) rtcSelect = val;
				}

			}
			case 5 -> {
				if (addr < 0x2000) ramEnabled = (val & 0x0F) == 0x0A;
				else if (addr < 0x3000) romBank = (romBank & 0x100) | (val & 0xFF);
				else if (addr < 0x4000) romBank = (romBank & 0xFF) | ((val & 1) << 8);
				else if (addr < 0x6000) ramBank = val & 0x0F;
			}
		}
	}

	private int ramOffset(int addr) {
		int bank = switch (mbc()) {
			case 1 -> mbc1Mode == 1 ? mbc1Upper : 0;
			case 3, 5 -> ramBank;
			default -> 0;
		};
		return (bank * 0x2000 + (addr - 0xA000)) % ram.length;
	}

	public int readRam(int addr) {
		if (!ramEnabled) return 0xFF;
		if (mbc() == 3 && rtcSelect >= 0) return 0;
		if (mbc() == 2) return (ram[(addr - 0xA000) & 0x1FF] & 0x0F) | 0xF0;
		return ram[ramOffset(addr)] & 0xFF;
	}

	public void writeRam(int addr, int val) {
		if (!ramEnabled) return;
		if (mbc() == 3 && rtcSelect >= 0) return;
		if (mbc() == 2) ram[(addr - 0xA000) & 0x1FF] = (byte) (val & 0x0F);
		else ram[ramOffset(addr)] = (byte) val;
		dirty = true;
	}

	public byte[] romBytes() {
		return rom;
	}

	void saveState(java.io.DataOutputStream o) throws IOException {
		o.writeInt(ram.length);
		o.write(ram);
		o.writeInt(romBank);
		o.writeInt(ramBank);
		o.writeBoolean(ramEnabled);
		o.writeInt(mbc1Mode);
		o.writeInt(mbc1Upper);
		o.writeInt(rtcSelect);
	}

	void loadState(java.io.DataInputStream in) throws IOException {
		int n = in.readInt();
		byte[] r = new byte[n];
		in.readFully(r);
		System.arraycopy(r, 0, ram, 0, Math.min(n, ram.length));
		romBank = in.readInt();
		ramBank = in.readInt();
		ramEnabled = in.readBoolean();
		mbc1Mode = in.readInt();
		mbc1Upper = in.readInt();
		rtcSelect = in.readInt();
	}

	public void save() {
		if (!battery || !dirty || saveFile == null) return;
		try {
			Files.write(saveFile.toPath(), ram);
			dirty = false;
		} catch (IOException ignored) {

		}
	}
}
