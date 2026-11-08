package com.btemu.gb;

public final class Cpu {
	private static final int FZ = 0x80, FN = 0x40, FH = 0x20, FC = 0x10;

	private final GameBoy gb;
	int a = 0x01, f = 0xB0, b = 0x00, c = 0x13, d = 0x00, e = 0xD8, h = 0x01, l = 0x4D;
	int sp = 0xFFFE, pc = 0x0100;
	private boolean ime = false;

	private int imeDelay = 0;
	private boolean halted = false;
	private int cycles;

	Cpu(GameBoy gb) {
		this.gb = gb;
	}

	void saveState(java.io.DataOutputStream o) throws java.io.IOException {
		for (int v : new int[]{a, f, b, c, d, e, h, l, sp, pc, imeDelay}) o.writeInt(v);
		o.writeBoolean(ime);
		o.writeBoolean(halted);
	}

	void loadState(java.io.DataInputStream in) throws java.io.IOException {
		a = in.readInt(); f = in.readInt(); b = in.readInt(); c = in.readInt();
		d = in.readInt(); e = in.readInt(); h = in.readInt(); l = in.readInt();
		sp = in.readInt(); pc = in.readInt(); imeDelay = in.readInt();
		ime = in.readBoolean();
		halted = in.readBoolean();
	}

	private int rd(int addr) { return gb.read(addr); }
	private void wr(int addr, int v) { gb.write(addr, v); }
	private int imm8() { int v = rd(pc); pc = (pc + 1) & 0xFFFF; return v; }
	private int imm16() { int lo = imm8(); return lo | (imm8() << 8); }
	private void push(int v) { sp = (sp - 1) & 0xFFFF; wr(sp, v >> 8); sp = (sp - 1) & 0xFFFF; wr(sp, v & 0xFF); }
	private int pop() { int lo = rd(sp); sp = (sp + 1) & 0xFFFF; int hi = rd(sp); sp = (sp + 1) & 0xFFFF; return lo | (hi << 8); }

	private int hl() { return (h << 8) | l; }
	private void setHl(int v) { h = (v >> 8) & 0xFF; l = v & 0xFF; }
	private int bc() { return (b << 8) | c; }
	private int de() { return (d << 8) | e; }

	private int getR(int i) {
		return switch (i) {
			case 0 -> b; case 1 -> c; case 2 -> d; case 3 -> e;
			case 4 -> h; case 5 -> l; case 6 -> { cycles += 4; yield rd(hl()); }
			default -> a;
		};
	}

	private void setR(int i, int v) {
		v &= 0xFF;
		switch (i) {
			case 0 -> b = v; case 1 -> c = v; case 2 -> d = v; case 3 -> e = v;
			case 4 -> h = v; case 5 -> l = v; case 6 -> { cycles += 4; wr(hl(), v); }
			default -> a = v;
		}
	}

	private int getRR(int i) {
		return switch (i) { case 0 -> bc(); case 1 -> de(); case 2 -> hl(); default -> sp; };
	}

	private void setRR(int i, int v) {
		v &= 0xFFFF;
		switch (i) {
			case 0 -> { b = v >> 8; c = v & 0xFF; }
			case 1 -> { d = v >> 8; e = v & 0xFF; }
			case 2 -> setHl(v);
			default -> sp = v;
		}
	}

	private boolean cond(int cc) {
		return switch (cc) {
			case 0 -> (f & FZ) == 0; case 1 -> (f & FZ) != 0;
			case 2 -> (f & FC) == 0; default -> (f & FC) != 0;
		};
	}

	public int step() {
		cycles = 0;
		int pending = gb.ie & gb.iflag & 0x1F;
		if (halted) {
			if (pending != 0) halted = false;
			else { gb.tick(4); return 4; }
		}
		if (ime && pending != 0) {
			int bit = Integer.numberOfTrailingZeros(pending);
			gb.iflag &= ~(1 << bit);
			ime = false;
			push(pc);
			pc = 0x40 + bit * 8;
			gb.tick(20);
			return 20;
		}
		boolean enableAfter = imeDelay > 0;

		int op = imm8();
		cycles = 4;
		exec(op);

		if (enableAfter && imeDelay > 0) { ime = true; imeDelay = 0; }
		gb.tick(cycles);
		return cycles;
	}

	private void exec(int op) {
		if (op >= 0x40 && op < 0x80) {
			if (op == 0x76) { halted = true; return; }
			setR((op >> 3) & 7, getR(op & 7));
			return;
		}
		if (op >= 0x80 && op < 0xC0) {
			alu((op >> 3) & 7, getR(op & 7));
			return;
		}
		switch (op) {
			case 0x00 -> {}
			case 0x10 -> imm8();
			case 0x01, 0x11, 0x21, 0x31 -> { setRR(op >> 4, imm16()); cycles += 8; }
			case 0x02 -> { wr(bc(), a); cycles += 4; }
			case 0x12 -> { wr(de(), a); cycles += 4; }
			case 0x22 -> { wr(hl(), a); setHl(hl() + 1); cycles += 4; }
			case 0x32 -> { wr(hl(), a); setHl(hl() - 1); cycles += 4; }
			case 0x0A -> { a = rd(bc()); cycles += 4; }
			case 0x1A -> { a = rd(de()); cycles += 4; }
			case 0x2A -> { a = rd(hl()); setHl(hl() + 1); cycles += 4; }
			case 0x3A -> { a = rd(hl()); setHl(hl() - 1); cycles += 4; }
			case 0x03, 0x13, 0x23, 0x33 -> { setRR(op >> 4, getRR(op >> 4) + 1); cycles += 4; }
			case 0x0B, 0x1B, 0x2B, 0x3B -> { setRR(op >> 4, getRR(op >> 4) - 1); cycles += 4; }
			case 0x04, 0x0C, 0x14, 0x1C, 0x24, 0x2C, 0x34, 0x3C -> {
				int r = (op >> 3) & 7;
				int v = getR(r);
				int res = (v + 1) & 0xFF;
				f = (f & FC) | (res == 0 ? FZ : 0) | ((v & 0xF) == 0xF ? FH : 0);
				setR(r, res);
			}
			case 0x05, 0x0D, 0x15, 0x1D, 0x25, 0x2D, 0x35, 0x3D -> {
				int r = (op >> 3) & 7;
				int v = getR(r);
				int res = (v - 1) & 0xFF;
				f = (f & FC) | FN | (res == 0 ? FZ : 0) | ((v & 0xF) == 0 ? FH : 0);
				setR(r, res);
			}
			case 0x06, 0x0E, 0x16, 0x1E, 0x26, 0x2E, 0x36, 0x3E -> { int v = imm8(); cycles += 4; setR((op >> 3) & 7, v); }
			case 0x07 -> { int cy = a >> 7; a = ((a << 1) | cy) & 0xFF; f = cy != 0 ? FC : 0; }
			case 0x0F -> { int cy = a & 1; a = (a >> 1) | (cy << 7); f = cy != 0 ? FC : 0; }
			case 0x17 -> { int cy = a >> 7; a = ((a << 1) | ((f & FC) != 0 ? 1 : 0)) & 0xFF; f = cy != 0 ? FC : 0; }
			case 0x1F -> { int cy = a & 1; a = (a >> 1) | ((f & FC) != 0 ? 0x80 : 0); f = cy != 0 ? FC : 0; }
			case 0x08 -> { int addr = imm16(); wr(addr, sp & 0xFF); wr(addr + 1, sp >> 8); cycles += 16; }
			case 0x09, 0x19, 0x29, 0x39 -> {
				int hlv = hl(), v = getRR(op >> 4);
				int res = hlv + v;
				f = (f & FZ) | (((hlv & 0xFFF) + (v & 0xFFF)) > 0xFFF ? FH : 0) | (res > 0xFFFF ? FC : 0);
				setHl(res);
				cycles += 4;
			}
			case 0x18 -> { int off = (byte) imm8(); pc = (pc + off) & 0xFFFF; cycles += 8; }
			case 0x20, 0x28, 0x30, 0x38 -> {
				int off = (byte) imm8();
				cycles += 4;
				if (cond((op >> 3) & 3)) { pc = (pc + off) & 0xFFFF; cycles += 4; }
			}
			case 0x27 -> {
				int adj = 0;
				boolean carry = (f & FC) != 0;
				if ((f & FN) == 0) {
					if (carry || a > 0x99) { adj |= 0x60; carry = true; }
					if ((f & FH) != 0 || (a & 0x0F) > 9) adj |= 0x06;
					a = (a + adj) & 0xFF;
				} else {
					if (carry) adj |= 0x60;
					if ((f & FH) != 0) adj |= 0x06;
					a = (a - adj) & 0xFF;
				}
				f = (f & FN) | (a == 0 ? FZ : 0) | (carry ? FC : 0);
			}
			case 0x2F -> { a ^= 0xFF; f = (f & (FZ | FC)) | FN | FH; }
			case 0x37 -> f = (f & FZ) | FC;
			case 0x3F -> f = (f & FZ) | ((f & FC) != 0 ? 0 : FC);

			case 0xC0, 0xC8, 0xD0, 0xD8 -> { cycles += 4; if (cond((op >> 3) & 3)) { pc = pop(); cycles += 12; } }
			case 0xC9 -> { pc = pop(); cycles += 12; }
			case 0xD9 -> { pc = pop(); ime = true; cycles += 12; }
			case 0xC2, 0xCA, 0xD2, 0xDA -> { int t = imm16(); cycles += 8; if (cond((op >> 3) & 3)) { pc = t; cycles += 4; } }
			case 0xC3 -> { pc = imm16(); cycles += 12; }
			case 0xE9 -> pc = hl();
			case 0xC4, 0xCC, 0xD4, 0xDC -> { int t = imm16(); cycles += 8; if (cond((op >> 3) & 3)) { push(pc); pc = t; cycles += 12; } }
			case 0xCD -> { int t = imm16(); push(pc); pc = t; cycles += 20; }
			case 0xC7, 0xCF, 0xD7, 0xDF, 0xE7, 0xEF, 0xF7, 0xFF -> { push(pc); pc = op & 0x38; cycles += 12; }
			case 0xC1 -> { setRR(0, pop()); cycles += 8; }
			case 0xD1 -> { setRR(1, pop()); cycles += 8; }
			case 0xE1 -> { setRR(2, pop()); cycles += 8; }
			case 0xF1 -> { int v = pop(); a = v >> 8; f = v & 0xF0; cycles += 8; }
			case 0xC5 -> { push(bc()); cycles += 12; }
			case 0xD5 -> { push(de()); cycles += 12; }
			case 0xE5 -> { push(hl()); cycles += 12; }
			case 0xF5 -> { push((a << 8) | f); cycles += 12; }
			case 0xC6, 0xCE, 0xD6, 0xDE, 0xE6, 0xEE, 0xF6, 0xFE -> { alu((op >> 3) & 7, imm8()); cycles += 4; }
			case 0xE0 -> { wr(0xFF00 + imm8(), a); cycles += 8; }
			case 0xF0 -> { a = rd(0xFF00 + imm8()); cycles += 8; }
			case 0xE2 -> { wr(0xFF00 + c, a); cycles += 4; }
			case 0xF2 -> { a = rd(0xFF00 + c); cycles += 4; }
			case 0xEA -> { wr(imm16(), a); cycles += 12; }
			case 0xFA -> { a = rd(imm16()); cycles += 12; }
			case 0xE8 -> { sp = addSpSigned(); cycles += 12; }
			case 0xF8 -> { setHl(addSpSigned()); cycles += 8; }
			case 0xF9 -> { sp = hl(); cycles += 4; }
			case 0xF3 -> { ime = false; imeDelay = 0; }
			case 0xFB -> imeDelay = 1;
			case 0xCB -> cb(imm8());
			default -> {}
		}
	}

	private int addSpSigned() {
		int off = (byte) imm8();
		int res = (sp + off) & 0xFFFF;
		f = (((sp & 0xF) + (off & 0xF)) > 0xF ? FH : 0) | (((sp & 0xFF) + (off & 0xFF)) > 0xFF ? FC : 0);
		return res;
	}

	private void alu(int opk, int v) {
		int res;
		switch (opk) {
			case 0 -> { res = a + v; f = ((res & 0xFF) == 0 ? FZ : 0) | (((a & 0xF) + (v & 0xF)) > 0xF ? FH : 0) | (res > 0xFF ? FC : 0); a = res & 0xFF; }
			case 1 -> { int cy = (f & FC) != 0 ? 1 : 0; res = a + v + cy; f = ((res & 0xFF) == 0 ? FZ : 0) | (((a & 0xF) + (v & 0xF) + cy) > 0xF ? FH : 0) | (res > 0xFF ? FC : 0); a = res & 0xFF; }
			case 2 -> { res = a - v; f = FN | ((res & 0xFF) == 0 ? FZ : 0) | ((a & 0xF) < (v & 0xF) ? FH : 0) | (res < 0 ? FC : 0); a = res & 0xFF; }
			case 3 -> { int cy = (f & FC) != 0 ? 1 : 0; res = a - v - cy; f = FN | ((res & 0xFF) == 0 ? FZ : 0) | ((a & 0xF) - (v & 0xF) - cy < 0 ? FH : 0) | (res < 0 ? FC : 0); a = res & 0xFF; }
			case 4 -> { a &= v; f = (a == 0 ? FZ : 0) | FH; }
			case 5 -> { a ^= v; f = a == 0 ? FZ : 0; }
			case 6 -> { a |= v; f = a == 0 ? FZ : 0; }
			default -> { res = a - v; f = FN | ((res & 0xFF) == 0 ? FZ : 0) | ((a & 0xF) < (v & 0xF) ? FH : 0) | (res < 0 ? FC : 0); }
		}
	}

	private void cb(int op) {
		cycles += 4;
		int r = op & 7;
		int y = (op >> 3) & 7;
		int v = getR(r);
		switch (op >> 6) {
			case 0 -> {
				int res, cy;
				switch (y) {
					case 0 -> { cy = v >> 7; res = (v << 1) | cy; }
					case 1 -> { cy = v & 1; res = (v >> 1) | (cy << 7); }
					case 2 -> { cy = v >> 7; res = (v << 1) | ((f & FC) != 0 ? 1 : 0); }
					case 3 -> { cy = v & 1; res = (v >> 1) | ((f & FC) != 0 ? 0x80 : 0); }
					case 4 -> { cy = v >> 7; res = v << 1; }
					case 5 -> { cy = v & 1; res = (v >> 1) | (v & 0x80); }
					case 6 -> { cy = 0; res = ((v & 0xF) << 4) | (v >> 4); }
					default -> { cy = v & 1; res = v >> 1; }
				}
				res &= 0xFF;
				f = (res == 0 ? FZ : 0) | (cy != 0 ? FC : 0);
				setR(r, res);
			}
			case 1 -> f = (f & FC) | FH | ((v & (1 << y)) == 0 ? FZ : 0);
			case 2 -> setR(r, v & ~(1 << y));
			default -> setR(r, v | (1 << y));
		}
	}
}
