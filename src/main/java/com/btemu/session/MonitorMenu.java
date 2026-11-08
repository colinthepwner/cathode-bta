package com.btemu.session;

import com.btemu.gb.GameBoy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MonitorMenu {

	public static final int BTN_MENU = 0x100;

	public static final int BTN_VOL_UP = 0x200, BTN_VOL_DOWN = 0x400;

	public static final int BTN_PAUSE = 0x800;

	public enum Action { NONE, EJECT, TOGGLE_SOUND, SELECT, CLOSE, TV, WEB, MINECRAFT, SPOTIFY, TOGGLE_SPEAKER }

	public enum RowKind { EJECT, SOUND, TV, WEB, MINECRAFT, SPOTIFY, SPEAKERS, SPEAKER, HEADER, ROM }

	public static int appId(Action a) {
		return a == Action.MINECRAFT ? 1 : a == Action.SPOTIFY ? 2 : 0;
	}

	public static String appName(int app) {
		return app == 1 ? "Minecraft" : app == 2 ? "Spotify" : "";
	}

	public record Row(RowKind kind, String label, String rom, net.minecraft.core.world.pos.TilePos spk, boolean on) {
		public Row(RowKind kind, String label, String rom) {
			this(kind, label, rom, null, false);
		}

		boolean same(Row o) {
			return o.kind == kind && java.util.Objects.equals(o.rom, rom) && java.util.Objects.equals(o.spk, spk)
				&& (kind != RowKind.HEADER || o.label.equals(label));
		}
	}

	public record Speaker(net.minecraft.core.world.pos.TilePos pos, String label, boolean on) {}

	public boolean speakersOpen = false;
	private List<Speaker> speakers = new ArrayList<>();

	public net.minecraft.core.world.pos.TilePos chosenSpeaker;
	public boolean chosenSpeakerOn;

	public boolean tvAllowed = true;
	public boolean open;
	public int sel;
	private List<String> roms = new ArrayList<>();
	public final List<Row> rowList = new ArrayList<>();

	public String chosen;

	public MonitorMenu() {
		setRoms(new ArrayList<>());
	}

	public static String console(String file) {
		com.btemu.emu.Console c = com.btemu.emu.Console.of(file);
		return c == null ? "Other" : c.name;
	}

	public void setRoms(List<String> list) {
		roms = new ArrayList<>(list);
		rebuild();
	}

	public void setSpeakers(List<Speaker> list) {
		if (list.equals(speakers)) return;
		speakers = new ArrayList<>(list);
		rebuild();
	}

	public List<Speaker> speakers() {
		return speakers;
	}

	private void rebuild() {
		Row cur = sel >= 0 && sel < rowList.size() ? rowList.get(sel) : null;
		rowList.clear();
		rowList.add(new Row(RowKind.SOUND, "Sound", null));
		if (tvAllowed) rowList.add(new Row(RowKind.TV, "YouTube TV", null));
		if (tvAllowed) rowList.add(new Row(RowKind.WEB, "Web Browser", null));

		if (tvAllowed) rowList.add(new Row(RowKind.MINECRAFT, "Minecraft", null));
		if (tvAllowed) rowList.add(new Row(RowKind.SPOTIFY, "Spotify", null));

		rowList.add(new Row(RowKind.SPEAKERS, "Speakers", null));
		if (speakersOpen)
			for (Speaker sp : speakers) rowList.add(new Row(RowKind.SPEAKER, sp.label(), null, sp.pos(), sp.on()));
		Map<String, List<String>> groups = new LinkedHashMap<>();
		for (com.btemu.emu.Console c : com.btemu.emu.Console.values()) groups.put(c.name, new ArrayList<>());
		groups.put("Other", new ArrayList<>());
		for (String r : roms) groups.get(console(r)).add(r);
		for (Map.Entry<String, List<String>> e : groups.entrySet()) {
			if (e.getValue().isEmpty()) continue;
			rowList.add(new Row(RowKind.HEADER, e.getKey(), null));
			for (String r : e.getValue()) {
				int dot = r.lastIndexOf('.');
				rowList.add(new Row(RowKind.ROM, dot > 0 ? r.substring(0, dot) : r, r));
			}
		}
		if (cur != null)
			for (int i = 0; i < rowList.size(); i++)
				if (rowList.get(i).same(cur)) { sel = i; break; }
		if (sel >= rowList.size()) sel = 0;
	}

	public List<String> roms() {
		return roms;
	}

	public void openAt(String currentRom) {
		open = true;
		sel = -1;
		for (int i = 0; i < rowList.size(); i++) {
			Row r = rowList.get(i);
			if (r.kind == RowKind.ROM && sel < 0 && (currentRom.isEmpty() || r.rom.equals(currentRom))) sel = i;
		}
		if (sel < 0) sel = 0;
	}

	private void move(int dir) {
		int n = rowList.size();
		for (int k = 0; k < n; k++) {
			sel = (sel + dir + n) % n;
			if (rowList.get(sel).kind != RowKind.HEADER) return;
		}
	}

	public Action step(int pressed, boolean hasGame, String currentRom, boolean soundLocked) {
		if ((pressed & GameBoy.BTN_UP) != 0) move(-1);
		if ((pressed & GameBoy.BTN_DOWN) != 0) move(1);
		if ((pressed & (GameBoy.BTN_A | GameBoy.BTN_START)) != 0) {
			Row r = rowList.get(sel);
			switch (r.kind) {
				case EJECT: return Action.EJECT;
				case SOUND: return soundLocked ? Action.NONE : Action.TOGGLE_SOUND;
				case TV: return Action.TV;
				case WEB: return Action.WEB;
				case MINECRAFT: return Action.MINECRAFT;
				case SPOTIFY: return Action.SPOTIFY;
				case SPEAKERS:
					speakersOpen = !speakersOpen;
					rebuild();
					return Action.NONE;
				case SPEAKER:
					chosenSpeaker = r.spk;
					chosenSpeakerOn = r.on;
					return Action.TOGGLE_SPEAKER;
				case ROM:
					if (hasGame && r.rom.equals(currentRom)) {
						open = false;
						return Action.CLOSE;
					}
					chosen = r.rom;
					return Action.SELECT;
				default: return Action.NONE;
			}
		}
		if ((pressed & (BTN_MENU | GameBoy.BTN_B)) != 0 && hasGame) {
			open = false;
			return Action.CLOSE;
		}
		return Action.NONE;
	}

	public void write(java.io.DataOutputStream o) throws java.io.IOException {
		o.writeBoolean(open);
		o.writeInt(sel | (speakersOpen ? 1 << 30 : 0));
	}

	public void read(java.io.DataInputStream in) throws java.io.IOException {
		open = in.readBoolean();
		int v = in.readInt();
		boolean so = (v & (1 << 30)) != 0;
		v &= ~(1 << 30);
		if (so != speakersOpen) {
			speakersOpen = so;
			sel = -1;
			rebuild();
		}
		sel = v;
		if (sel < 0 || sel >= rowList.size()) sel = 0;
	}
}
