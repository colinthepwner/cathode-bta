package com.btemu.gb;

import java.io.File;
import java.io.IOException;

public final class GameBoy implements com.btemu.emu.Machine {
	public static final int WIDTH = 160, HEIGHT = 144;
	public static final int CYCLES_PER_FRAME = 70224;

	public static final int BTN_RIGHT = 1, BTN_LEFT = 2, BTN_UP = 4, BTN_DOWN = 8;
	public static final int BTN_A = 16, BTN_B = 32, BTN_SELECT = 64, BTN_START = 128;

	private static final int[] SHADES = {0xFFE0F8D0, 0xFF88C070, 0xFF346856, 0xFF081820};

	public final Cartridge cart;
	public final Cpu cpu;
	public final Apu apu = new Apu();

	private final byte[] vram = new byte[0x2000];
	private final byte[] wram = new byte[0x2000];
	private final byte[] oam = new byte[0xA0];
	private final byte[] hram = new byte[0x7F];
	private final byte[] io = new byte[0x80];
	int ie = 0;
	int iflag = 0xE1;

	public final int[] frame = new int[WIDTH * HEIGHT];
	private final int[] back = new int[WIDTH * HEIGHT];

	private final int[] lineBgIdx = new int[WIDTH];
	public volatile int frameCounter = 0;

	private volatile int buttons = 0;

	private int divCounter = 0xABCC;
	private int timaCounter = 0;
	private int lineCycles = 0;
	private int ly = 0;
	private int mode = 2;
	private int windowLine = 0;
	private boolean statLine = false;

	public GameBoy(File romFile) throws IOException {
		this(new Cartridge(romFile));
	}

	public GameBoy(byte[] rom, File saveFile) throws IOException {
		this(new Cartridge(rom, saveFile));
	}

	private GameBoy(Cartridge cart) {
		this.cart = cart;
		this.cpu = new Cpu(this);

		io[0x00] = (byte) 0xCF;
		io[0x40] = (byte) 0x91;
		io[0x47] = (byte) 0xFC;
		io[0x48] = (byte) 0xFF;
		io[0x49] = (byte) 0xFF;
	}

	public void saveState(java.io.DataOutputStream o) throws IOException {
		o.writeInt(0x47425331);
		cpu.saveState(o);
		cart.saveState(o);
		apu.saveState(o);
		o.write(vram); o.write(wram); o.write(oam); o.write(hram); o.write(io);
		for (int v : new int[]{ie, iflag, buttons, divCounter, timaCounter, lineCycles, ly, mode, windowLine}) o.writeInt(v);
		o.writeBoolean(statLine);
		byte[] shades = new byte[frame.length];
		synchronized (frame) {
			for (int i = 0; i < frame.length; i++) shades[i] = (byte) shadeIndex(frame[i]);
		}
		o.write(shades);
	}

	public void loadState(java.io.DataInputStream in) throws IOException {
		if (in.readInt() != 0x47425331) throw new IOException("Not a BTEmu save state");
		cpu.loadState(in);
		cart.loadState(in);
		apu.loadState(in);
		in.readFully(vram); in.readFully(wram); in.readFully(oam); in.readFully(hram); in.readFully(io);
		ie = in.readInt(); iflag = in.readInt(); buttons = in.readInt(); divCounter = in.readInt();
		timaCounter = in.readInt(); lineCycles = in.readInt(); ly = in.readInt(); mode = in.readInt(); windowLine = in.readInt();
		statLine = in.readBoolean();
		byte[] shades = new byte[frame.length];
		in.readFully(shades);
		synchronized (frame) {
			for (int i = 0; i < frame.length; i++) frame[i] = SHADES[shades[i] & 3];
		}
		frameCounter++;
	}

	private static int shadeIndex(int argb) {
		for (int i = 0; i < SHADES.length; i++) if (SHADES[i] == argb) return i;
		return 0;
	}

	public void glitchFlip(java.util.Random rng, int count, boolean includeWram) {
		for (int i = 0; i < count; i++) {
			int kind = rng.nextInt(includeWram ? 10 : 8);
			if (kind < 6) vram[rng.nextInt(vram.length)] ^= (byte) (1 << rng.nextInt(8));
			else if (kind < 8) oam[rng.nextInt(oam.length)] ^= (byte) (1 << rng.nextInt(8));
			else wram[rng.nextInt(wram.length)] ^= (byte) (1 << rng.nextInt(8));
		}
	}

	public void setButtons(int mask) {
		mask &= 0xFF;
		int old = buttons;
		buttons = mask;

		if ((mask & ~old) != 0) iflag |= 0x10;
	}

	@Override public int width() { return WIDTH; }
	@Override public int height() { return HEIGHT; }
	@Override public int[] frame() { return frame; }
	@Override public int drainAudio(float[] out) { return apu.drain(out); }
	@Override public void flush() { cart.save(); }
	@Override public void setNoteMode(boolean on) { apu.noteMode = on; }
	@Override public boolean supportsNoteMode() { return true; }

	public void runFrame() {
		int target = frameCounter;
		int budget = CYCLES_PER_FRAME * 2;
		while (frameCounter == target && budget > 0) {
			budget -= cpu.step();
		}
	}

	int read(int a) {
		a &= 0xFFFF;
		if (a < 0x8000) return cart.readRom(a);
		if (a < 0xA000) return vram[a - 0x8000] & 0xFF;
		if (a < 0xC000) return cart.readRam(a);
		if (a < 0xE000) return wram[a - 0xC000] & 0xFF;
		if (a < 0xFE00) return wram[a - 0xE000] & 0xFF;
		if (a < 0xFEA0) return oam[a - 0xFE00] & 0xFF;
		if (a < 0xFF00) return 0xFF;
		if (a < 0xFF80) return readIo(a);
		if (a < 0xFFFF) return hram[a - 0xFF80] & 0xFF;
		return ie;
	}

	void write(int a, int v) {
		a &= 0xFFFF;
		v &= 0xFF;
		if (a < 0x8000) cart.writeRom(a, v);
		else if (a < 0xA000) vram[a - 0x8000] = (byte) v;
		else if (a < 0xC000) cart.writeRam(a, v);
		else if (a < 0xE000) wram[a - 0xC000] = (byte) v;
		else if (a < 0xFE00) wram[a - 0xE000] = (byte) v;
		else if (a < 0xFEA0) oam[a - 0xFE00] = (byte) v;
		else if (a < 0xFF00) {  }
		else if (a < 0xFF80) writeIo(a, v);
		else if (a < 0xFFFF) hram[a - 0xFF80] = (byte) v;
		else ie = v;
	}

	private int readIo(int a) {
		int r = a - 0xFF00;
		if (r >= 0x10 && r < 0x40) return apu.read(a);
		switch (r) {
			case 0x00: {
				int sel = io[0] & 0x30;
				int nib = 0x0F;
				int b = buttons;
				if ((sel & 0x10) == 0) nib &= ~(b & 0x0F);
				if ((sel & 0x20) == 0) nib &= ~((b >> 4) & 0x0F);
				return 0xC0 | sel | nib;
			}
			case 0x04: return (divCounter >> 8) & 0xFF;
			case 0x0F: return iflag | 0xE0;
			case 0x41: return 0x80 | (io[0x41] & 0x78) | (ly == (io[0x45] & 0xFF) ? 4 : 0) | mode;
			case 0x44: return ly;
			default:

				return io[r] & 0xFF;
		}
	}

	private void writeIo(int a, int v) {
		int r = a - 0xFF00;
		if (r >= 0x10 && r < 0x40) { apu.write(a, v); return; }
		switch (r) {
			case 0x00 -> io[0] = (byte) (v & 0x30);
			case 0x04 -> divCounter = 0;
			case 0x0F -> iflag = v & 0x1F;
			case 0x40 -> {
				boolean wasOn = (io[0x40] & 0x80) != 0;
				io[0x40] = (byte) v;
				if (wasOn && (v & 0x80) == 0) { ly = 0; lineCycles = 0; mode = 0; windowLine = 0; }
				if (!wasOn && (v & 0x80) != 0) { mode = 2; }
			}
			case 0x41 -> io[0x41] = (byte) (v & 0x78);
			case 0x44 -> {}
			case 0x46 -> {
				int src = v << 8;
				for (int i = 0; i < 0xA0; i++) oam[i] = (byte) read(src + i);
			}
			default -> io[r] = (byte) v;
		}
	}

	void tick(int cycles) {
		divCounter = (divCounter + cycles) & 0xFFFF;
		int tac = io[0x07] & 0xFF;
		if ((tac & 4) != 0) {
			int period = switch (tac & 3) { case 0 -> 1024; case 1 -> 16; case 2 -> 64; default -> 256; };
			timaCounter += cycles;
			while (timaCounter >= period) {
				timaCounter -= period;
				int tima = (io[0x05] & 0xFF) + 1;
				if (tima > 0xFF) { tima = io[0x06] & 0xFF; iflag |= 0x04; }
				io[0x05] = (byte) tima;
			}
		}
		ppu(cycles);
		apu.tick(cycles);
	}

	private void ppu(int cycles) {
		int lcdc = io[0x40] & 0xFF;
		if ((lcdc & 0x80) == 0) {

			lineCycles += cycles;
			if (lineCycles >= CYCLES_PER_FRAME) { lineCycles -= CYCLES_PER_FRAME; presentFrame(); }
			return;
		}
		lineCycles += cycles;
		while (true) {
			if (ly < 144) {
				if (mode == 2 && lineCycles >= 80) { mode = 3; }
				else if (mode == 3 && lineCycles >= 252) { mode = 0; renderLine(); }
				else if (mode == 0 && lineCycles >= 456) {
					lineCycles -= 456;
					ly++;
					if (ly == 144) { mode = 1; iflag |= 0x01; presentFrame(); }
					else mode = 2;
				} else break;
			} else {
				if (lineCycles >= 456) {
					lineCycles -= 456;
					ly++;
					if (ly > 153) { ly = 0; mode = 2; windowLine = 0; }
				} else break;
			}
			updateStat();
		}
		updateStat();
	}

	private void updateStat() {
		int stat = io[0x41] & 0xFF;
		boolean line = ((stat & 0x40) != 0 && ly == (io[0x45] & 0xFF))
			|| ((stat & 0x20) != 0 && mode == 2)
			|| ((stat & 0x10) != 0 && mode == 1)
			|| ((stat & 0x08) != 0 && mode == 0);
		if (line && !statLine) iflag |= 0x02;
		statLine = line;
	}

	private void presentFrame() {
		synchronized (frame) {
			System.arraycopy(back, 0, frame, 0, frame.length);
		}
		frameCounter++;
	}

	private void renderLine() {
		int lcdc = io[0x40] & 0xFF;
		int base = ly * WIDTH;
		int bgp = io[0x47] & 0xFF;
		int scy = io[0x42] & 0xFF, scx = io[0x43] & 0xFF;
		int wy = io[0x4A] & 0xFF, wx = (io[0x4B] & 0xFF) - 7;
		boolean bgOn = (lcdc & 0x01) != 0;
		boolean winOn = (lcdc & 0x20) != 0 && bgOn && ly >= wy && wx < WIDTH;
		boolean signedTiles = (lcdc & 0x10) == 0;
		int bgMap = (lcdc & 0x08) != 0 ? 0x1C00 : 0x1800;
		int winMap = (lcdc & 0x40) != 0 ? 0x1C00 : 0x1800;

		for (int x = 0; x < WIDTH; x++) {
			int idx = 0;
			if (bgOn) {
				int px, py, map;
				if (winOn && x >= wx) { px = x - wx; py = windowLine; map = winMap; }
				else { px = (x + scx) & 0xFF; py = (ly + scy) & 0xFF; map = bgMap; }
				int tile = vram[map + (py >> 3) * 32 + (px >> 3)] & 0xFF;
				int addr = signedTiles ? 0x1000 + ((byte) tile) * 16 : tile * 16;
				addr += (py & 7) * 2;
				int lo = vram[addr] & 0xFF, hi = vram[addr + 1] & 0xFF;
				int bit = 7 - (px & 7);
				idx = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);
			}
			lineBgIdx[x] = idx;
			back[base + x] = SHADES[(bgp >> (idx * 2)) & 3];
		}
		if (winOn) windowLine++;

		if ((lcdc & 0x02) != 0) renderSprites(lcdc, base);
	}

	private void renderSprites(int lcdc, int base) {
		int h = (lcdc & 0x04) != 0 ? 16 : 8;

		int[] picked = new int[10];
		int n = 0;
		for (int i = 0; i < 40 && n < 10; i++) {
			int sy = (oam[i * 4] & 0xFF) - 16;
			if (ly >= sy && ly < sy + h) picked[n++] = i;
		}

		for (int i = 0; i < n; i++)
			for (int j = i + 1; j < n; j++) {
				int a = picked[i], b = picked[j];
				int xa = oam[a * 4 + 1] & 0xFF, xb = oam[b * 4 + 1] & 0xFF;
				boolean aBetter = xa < xb || (xa == xb && a < b);
				if (aBetter) { picked[i] = b; picked[j] = a; }
			}
		for (int k = 0; k < n; k++) {
			int i = picked[k];
			int sy = (oam[i * 4] & 0xFF) - 16;
			int sx = (oam[i * 4 + 1] & 0xFF) - 8;
			int tile = oam[i * 4 + 2] & 0xFF;
			int attr = oam[i * 4 + 3] & 0xFF;
			if (h == 16) tile &= 0xFE;
			int row = ly - sy;
			if ((attr & 0x40) != 0) row = h - 1 - row;
			int addr = tile * 16 + row * 2;
			int lo = vram[addr] & 0xFF, hi = vram[addr + 1] & 0xFF;
			int pal = io[(attr & 0x10) != 0 ? 0x49 : 0x48] & 0xFF;
			for (int px = 0; px < 8; px++) {
				int x = sx + px;
				if (x < 0 || x >= WIDTH) continue;
				int bit = (attr & 0x20) != 0 ? px : 7 - px;
				int idx = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);
				if (idx == 0) continue;
				if ((attr & 0x80) != 0 && lineBgIdx[x] != 0) continue;
				back[base + x] = SHADES[(pal >> (idx * 2)) & 3];
			}
		}
	}
}
