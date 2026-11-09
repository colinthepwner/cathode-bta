package com.btemu.session;

import com.btemu.emu.Machine;
import com.btemu.emu.Machines;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class Lockstep {

	public Machine gb;
	public final MonitorMenu menu = new MonitorMenu();

	public boolean skipCoreFrames;
	public String rom = "";

	public boolean fresh = true;
	public boolean soundLocked;

	public long frame;

	public int curMask, prevMask;

	public final int[] otherCur = new int[PORTS - 1], otherPrev = new int[PORTS - 1];

	public static final int PORTS = 4;

	public final TreeMap<Long, Integer> inputs = new TreeMap<>();

	public static long key(long frame, int port) {
		return frame * PORTS + port;
	}

	private void apply(long key, int mask) {
		int port = (int) Math.floorMod(key, (long) PORTS);
		if (port == 0) curMask = mask;
		else otherCur[port - 1] = mask;
	}

	public synchronized MonitorMenu.Action step() {

		long end = key(frame + 1, 0);
		while (!inputs.isEmpty() && inputs.firstKey() < end) {
			Map.Entry<Long, Integer> e = inputs.pollFirstEntry();
			apply(e.getKey(), e.getValue());
		}
		int pressed = curMask & ~prevMask;
		prevMask = curMask;

		int anyPressed = pressed;
		for (int i = 0; i < otherCur.length; i++) {
			anyPressed |= otherCur[i] & ~otherPrev[i];
			otherPrev[i] = otherCur[i];
		}
		MonitorMenu.Action a = MonitorMenu.Action.NONE;
		if (gb == null || menu.open) {
			if (gb != null) clearPads();
			a = menu.step(anyPressed, gb != null, rom, soundLocked);
		} else if ((anyPressed & MonitorMenu.BTN_MENU) != 0) {
			menu.openAt(rom);
			clearPads();
		} else {
			gb.setButtons(curMask & ~0xF00);
			for (int i = 0; i < otherCur.length; i++) gb.setButtons(i + 1, otherCur[i] & ~0xF00);
			if (!skipCoreFrames || !(gb instanceof com.btemu.emu.RetroCore)) {
				gb.runFrame();
				fresh = false;
				if (HASH_LOG && frame % 300 == 0) logHash();
			}
		}
		frame++;
		return a;
	}

	private static final boolean HASH_LOG = System.getProperty("btemu.hashlog") != null;

	private void logHash() {
		java.util.zip.CRC32 c = new java.util.zip.CRC32();
		int[] px = gb.frame();
		synchronized (px) {
			for (int v : px) c.update(v);
		}
		if (frame % 1500 == 0) {

			try {
				java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(gb.width(), gb.height(), java.awt.image.BufferedImage.TYPE_INT_RGB);
				synchronized (px) {
					img.setRGB(0, 0, gb.width(), gb.height(), px, 0, gb.width());
				}
				java.io.File dir = new java.io.File("hashshots");
				dir.mkdirs();
				javax.imageio.ImageIO.write(img, "png", new java.io.File(dir, frame + ".png"));
			} catch (Exception ignored) {
			}
		}
		com.btemu.BtEmu.LOGGER.info("HASH {} frame={} crc={} pads={},{},{},{}", rom, frame, Long.toHexString(c.getValue()),
				curMask, otherCur[0], otherCur[1], otherCur[2]);
	}

	private void clearPads() {
		gb.setButtons(0);
		for (int i = 0; i < otherCur.length; i++) gb.setButtons(i + 1, 0);
	}

	public synchronized byte[] snapshot() throws IOException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		try (DataOutputStream o = new DataOutputStream(new DeflaterOutputStream(bo))) {
			o.writeLong(frame);
			o.writeInt(curMask);
			o.writeInt(prevMask);
			menu.write(o);
			o.writeBoolean(gb != null);
			if (gb != null) gb.saveState(o);
			o.writeInt(inputs.size());
			for (Map.Entry<Long, Integer> e : inputs.entrySet()) {
				o.writeLong(e.getKey());
				o.writeInt(e.getValue());
			}

			for (int i = 0; i < otherCur.length; i++) {
				o.writeInt(otherCur[i]);
				o.writeInt(otherPrev[i]);
			}
		}
		return bo.toByteArray();
	}

	public synchronized void restore(byte[] snap, byte[] romBytes, java.io.File saveFile) throws IOException {
		try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(snap)))) {
			frame = in.readLong();
			curMask = in.readInt();
			prevMask = in.readInt();
			menu.read(in);
			boolean hasGb = in.readBoolean();
			if (hasGb) {
				if (romBytes == null) throw new IOException("snapshot has a cartridge but no ROM given");
				if (gb != null) gb.close();
				gb = Machines.create(rom, romBytes, saveFile);
				gb.loadState(in);
				fresh = true;
			} else {
				if (gb != null) gb.close();
				gb = null;
			}
			inputs.clear();
			int n = in.readInt();
			for (int i = 0; i < n; i++) inputs.put(in.readLong(), in.readInt());
			java.util.Arrays.fill(otherCur, 0);
			java.util.Arrays.fill(otherPrev, 0);
			try {
				for (int i = 0; i < otherCur.length; i++) {
					otherCur[i] = in.readInt();
					otherPrev[i] = in.readInt();
				}
			} catch (java.io.EOFException old) {

				java.util.Arrays.fill(otherCur, 0);
				java.util.Arrays.fill(otherPrev, 0);
			}
		}
	}
}
