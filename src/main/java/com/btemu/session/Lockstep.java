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
	public boolean soundLocked;

	public long frame;
	public int curMask, prevMask;

	public final TreeMap<Long, Integer> inputs = new TreeMap<>();

	public synchronized MonitorMenu.Action step() {
		Integer m = inputs.remove(frame);
		if (m != null) curMask = m;

		while (!inputs.isEmpty() && inputs.firstKey() < frame) curMask = inputs.pollFirstEntry().getValue();
		int pressed = curMask & ~prevMask;
		prevMask = curMask;
		MonitorMenu.Action a = MonitorMenu.Action.NONE;
		if (gb == null || menu.open) {
			if (gb != null) gb.setButtons(0);
			a = menu.step(pressed, gb != null, rom, soundLocked);
		} else if ((pressed & MonitorMenu.BTN_MENU) != 0) {
			menu.openAt(rom);
			gb.setButtons(0);
		} else {
			gb.setButtons(curMask & ~0xF00);
			if (!skipCoreFrames || !(gb instanceof com.btemu.emu.RetroCore)) gb.runFrame();
		}
		frame++;
		return a;
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
			} else {
				if (gb != null) gb.close();
				gb = null;
			}
			inputs.clear();
			int n = in.readInt();
			for (int i = 0; i < n; i++) inputs.put(in.readLong(), in.readInt());
		}
	}
}
