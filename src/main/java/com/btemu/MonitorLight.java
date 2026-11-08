package com.btemu;

import net.minecraft.core.block.Blocks;
import net.minecraft.core.enums.LightLayer;
import net.minecraft.core.world.World;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MonitorLight {
	private MonitorLight() {}

	private static final Map<Long, Integer> LEVELS = new ConcurrentHashMap<>();
	private static int lx, ly, lz;
	private static int[] shadow;

	public static long pack(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
	}

	public static void lookingAt(int x, int y, int z) {
		lx = x;
		ly = y;
		lz = z;
	}

	public static int[] emissionArray() {
		int[] src = Blocks.lightEmission;
		if (shadow == null || shadow.length != src.length) shadow = src.clone();
		int level = LEVELS.getOrDefault(pack(lx, ly, lz), 0);
		for (MonitorKind k : MonitorKind.values()) {
			if (k.blockId < shadow.length) shadow[k.blockId] = level;
		}
		return shadow;
	}

	public static void set(World world, int x, int y, int z, int level) {
		long k = pack(x, y, z);
		Integer old = level > 0 ? LEVELS.put(k, level) : LEVELS.remove(k);
		if ((old == null ? 0 : old) != level) {
			world.scheduleLightingUpdate(LightLayer.Block, x, y, z, x, y, z);
		}
	}
}
