package com.btemu.emu;

import java.util.Locale;
import java.util.Map;

public enum Console {
	GAME_BOY("Game Boy", null, Map.of(), false, "gb"),
	GAME_BOY_COLOR("Game Boy Color", null, Map.of(), false, "gbc"),
	NES("NES", "fceumm", Map.of(), false, "nes"),
	SNES("Super Nintendo", "snes9x", Map.of(), false, "sfc", "smc"),
	N64("Nintendo 64", "parallel_n64", Map.of(

			"parallel-n64-gfxplugin", "angrylion",
			"parallel-n64-rspplugin", "cxd4",
			"parallel-n64-cpucore", "dynamic_recompiler",
			"parallel-n64-angrylion-multithread", "all threads",
			"parallel-n64-screensize", "320x240"), true, "z64", "n64", "v64"),
	GBA("Game Boy Advance", "mgba", Map.of(), false, "gba"),
	GENESIS("Genesis", "genesis_plus_gx", Map.of(), false, "md", "gen", "smd"),
	MASTER_SYSTEM("Master System", "genesis_plus_gx", Map.of(), false, "sms");

	public final String name;

	public final String core;

	public final Map<String, String> options;

	public final boolean dpadIsStick;
	final String[] exts;

	Console(String name, String core, Map<String, String> options, boolean dpadIsStick, String... exts) {
		this.name = name;
		this.core = core;
		this.options = options;
		this.dpadIsStick = dpadIsStick;
		this.exts = exts;
	}

	public static Console of(String file) {
		String n = file.toLowerCase(Locale.ROOT);
		int dot = n.lastIndexOf('.');
		if (dot < 0) return null;
		String ext = n.substring(dot + 1);
		for (Console c : values()) for (String e : c.exts) if (e.equals(ext)) return c;
		return null;
	}
}
