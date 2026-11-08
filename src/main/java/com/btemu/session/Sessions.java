package com.btemu.session;

import com.btemu.MonitorGroup;
import com.btemu.MonitorKind;
import com.btemu.TileEntityMonitor;
import com.btemu.net.Msgs;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.TilePos;
import net.minecraft.core.world.pos.TilePosc;
import turniplabs.halplibe.helper.network.NetworkHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Sessions {
	private Sessions() {}

	private static final Map<String, Session> ALL = new ConcurrentHashMap<>();

	private static String key(int dim, int x, int y, int z) {
		return dim + ":" + x + "," + y + "," + z;
	}

	private static String key(World w, TilePosc p) {
		return key(w.dimension.id, p.x(), p.y(), p.z());
	}

	public static java.util.List<java.util.Set<TilePos>> runningCells(World w) {
		java.util.List<java.util.Set<TilePos>> out = new java.util.ArrayList<>();
		for (Session s : ALL.values())
			if (s.world == w && s.powered) out.add(new java.util.HashSet<>(MonitorGroup.of(w, s.pos).cells()));
		return out;
	}

	public static Session get(World w, TilePosc p) {
		return ALL.get(key(w, p));
	}

	public static void tick(TileEntityMonitor te) {
		World w = te.worldObj;
		MonitorGroup g = MonitorGroup.of(w, te.tilePos);
		if (!g.isOrigin(te.tilePos)) return;
		MonitorKind kind = te.kind();
		if (kind == null) return;
		String k = key(w, te.tilePos);
		Session s = ALL.get(k);
		if (s == null) {

			s = adoptFromCell(w, g, kind);
			if (s == null && te.powered) {
				s = new Session(w, new TilePos(te.tilePos.x, te.tilePos.y, te.tilePos.z), kind);
				ALL.put(k, s);
				s.powerOn(te.rom, te.noteBlocks, te.savedState);
				te.savedState = null;
			}
			if (s == null) return;
		}
		s.tick(g);

		if (!s.rom.equals(te.rom) || s.powered != te.powered || s.noteMode != te.noteBlocks) {
			te.rom = s.rom;
			te.powered = s.powered;
			te.noteBlocks = s.noteMode;
			te.setChanged();
		}
		if (!s.powered) ALL.remove(k);
	}

	private static Session adoptFromCell(World w, MonitorGroup g, MonitorKind kind) {
		for (TilePos c : g.cells()) {
				if (g.isOrigin(c)) continue;
				Session s = ALL.remove(key(w, c));
				if (s != null && s.kind == kind) {
					s.pos = new TilePos(g.origin.x, g.origin.y, g.origin.z);
					ALL.put(key(w, g.origin), s);
					s.broadcastState();
					s.broadcastSnapshot(null);
					return s;
				}
			}
		return null;
	}

	public static byte[] persistentState(TileEntityMonitor te) {
		Session s = te.worldObj == null ? null : get(te.worldObj, te.tilePos);
		return s == null ? null : s.persistentState();
	}

	public static void onUse(World w, TilePosc pos, Player p) {
		MonitorGroup g = MonitorGroup.of(w, pos);
		if (!(w.getTileEntity(g.origin) instanceof TileEntityMonitor te)) return;
		MonitorKind kind = te.kind();
		if (kind == null) return;
		String k = key(w, g.origin);
		Session s = ALL.get(k);
		if (s == null) {
			s = new Session(w, new TilePos(g.origin.x, g.origin.y, g.origin.z), kind);
			ALL.put(k, s);
		}
		s.onUse(p, te.rom, te.noteBlocks, null);
		te.powered = s.powered;
		te.setChanged();
		if (!s.powered) ALL.remove(k);
	}

	public static void toggleFocus(World w, TilePosc pos, Player p) {
		MonitorGroup g = MonitorGroup.of(w, pos);
		Session s = ALL.get(key(w, g.origin));
		if (s == null || !s.powered) return;
		s.onFocus(p, !p.username.equals(s.controller), true);
	}

	public static void onRemoved(World w, TilePosc pos) {
		Session s = ALL.remove(key(w, pos));
		if (s != null) s.powerOff();
	}

	public static volatile java.util.function.Function<TilePos, byte[]> localSnapshot;

	public static void onSync(Player p, TilePos pos) {
		Session s = p.world == null ? null : get(p.world, pos);
		if (s == null) {

			Msgs.State off = new Msgs.State();
			off.pos = pos;
			NetworkHandler.sendToPlayer(p, off);
			return;
		}
		NetworkHandler.sendToPlayer(p, s.stateMsg());
		s.broadcastSnapshot(p);
	}

	public static void onRomRequest(Player p, long crc) {
		for (Session s : ALL.values()) {
			if (s.crc == crc && s.romBytes != null) {
				final byte[] rom = s.romBytes;
				Msgs.chunks(rom, parts -> {
					for (int i = 0; i < parts.length; i++) {
						Msgs.RomData m = new Msgs.RomData();
						m.crc = crc;
						m.index = i;
						m.count = parts.length;
						m.data = parts[i];
						NetworkHandler.sendToPlayer(p, m);
					}
				});
				return;
			}
		}
	}

	public static void onInput(Player p, TilePos pos, int mask) {
		Session s = p.world == null ? null : get(p.world, pos);
		if (s != null) s.onInput(p, mask);
	}

	public static void onFocus(Player p, TilePos pos, boolean want) {
		Session s = p.world == null ? null : get(p.world, pos);
		if (s != null) s.onFocus(p, want);
	}

	public static void onTvFrame(Player p, TilePos pos, byte[] jpeg) {
		Session s = p.world == null ? null : get(p.world, pos);
		if (s != null) s.onTvFrame(p, jpeg);
	}

	public static void onTvAudio(Player p, TilePos pos, byte[] pcm) {
		Session s = p.world == null ? null : get(p.world, pos);
		if (s != null) s.onTvAudio(p, pcm);
	}

	public static void onPlayerHurt(Player p) {
		for (Session s : ALL.values()) {
			if (s.world == p.world && p.username != null && p.username.equals(s.controller)) s.releaseController(true);
		}
	}
}
