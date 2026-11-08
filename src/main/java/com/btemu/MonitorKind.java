package com.btemu;

public enum MonitorKind {

	GOLD(3900, "monitor", 60, 15, 15, 3, false, false, false, false),

	IRON(3901, "iron_monitor", 45, 13, 5, 3, true, false, false, false),

	DIAMOND(3902, "diamond_monitor", 0, 15, 15, 6, false, true, false, false),

	WOOD(3903, "wooden_monitor", 30, 12, 4, 3, true, false, true, true),

	STEEL(3904, "steel_monitor", 0, 15, 15, 9, false, true, false, false);

	public final int blockId;
	public final String name;

	public final int fpsCap;

	public final int backlight;

	public final int maxEmission;

	public final int scale;

	public final boolean crt;

	public final boolean blendFrames;

	public final boolean forcedNoteBlocks;

	public final boolean wooden;

	MonitorKind(int blockId, String name, int fpsCap, int backlight, int maxEmission, int scale, boolean crt, boolean blendFrames, boolean forcedNoteBlocks, boolean wooden) {
		this.blockId = blockId;
		this.name = name;
		this.fpsCap = fpsCap;
		this.backlight = backlight;
		this.maxEmission = maxEmission;
		this.scale = scale;
		this.crt = crt;
		this.blendFrames = blendFrames;
		this.forcedNoteBlocks = forcedNoteBlocks;
		this.wooden = wooden;
	}

	public float maxVolume() {
		return wooden ? 0.5f : crt ? 0.75f : this == GOLD ? 0.85f : 1.0f;
	}

	public int inputDelay() {
		return crt ? 2 : this == GOLD ? 4 : 3;
	}

	public double playbackLag() {
		return crt ? 1.0 : this == GOLD ? 2.5 : 1.5;
	}

	public static MonitorKind byBlockId(int id) {
		for (MonitorKind k : values()) if (k.blockId == id) return k;
		return null;
	}
}
