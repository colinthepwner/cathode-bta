package com.btemu.client;

import com.btemu.MonitorGroup;
import com.btemu.TileEntityMonitor;
import net.minecraft.core.world.pos.TilePos;

import java.util.HashMap;
import java.util.Map;

final class GroupCache {
	private static final long TTL_MS = 250;
	private static final Map<TilePos, Entry> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	private record Entry(MonitorGroup group, long time) {}

	private GroupCache() {}

	static void clear() {
		CACHE.clear();
	}

	static MonitorGroup get(TileEntityMonitor te) {
		long now = System.currentTimeMillis();
		Entry e = CACHE.get(te.tilePos);
		if (e != null && now - e.time < TTL_MS) return e.group;
		if (CACHE.size() > 4096) CACHE.clear();
		MonitorGroup g = MonitorGroup.of(te.worldObj, te.tilePos);
		CACHE.put(new TilePos(te.tilePos.x, te.tilePos.y, te.tilePos.z), new Entry(g, now));
		return g;
	}
}
