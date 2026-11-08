package com.btemu;

import net.minecraft.core.entity.player.Player;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.TilePos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Speakers {
	private Speakers() {}

	public static final int RANGE = 6, GOLDEN_RANGE = 30;

	private static final int MAX_GROUP = 256;

	public static final class Group {
		public final List<TileEntitySpeaker> members = new ArrayList<>();
		public final TileEntitySpeaker rep;
		public final boolean golden;
		public final double cx, cy, cz;

		Group(List<TileEntitySpeaker> m, boolean golden) {
			members.addAll(m);
			this.golden = golden;
			TileEntitySpeaker r = m.get(0);
			double sx = 0, sy = 0, sz = 0;
			for (TileEntitySpeaker s : m) {
				if (lower(s.tilePos, r.tilePos)) r = s;
				sx += s.tilePos.x + 0.5;
				sy += s.tilePos.y + 0.5;
				sz += s.tilePos.z + 0.5;
			}
			rep = r;
			cx = sx / m.size();
			cy = sy / m.size();
			cz = sz / m.size();
		}

		public int size() {
			return members.size();
		}

		public TilePos pos() {
			return new TilePos(rep.tilePos.x, rep.tilePos.y, rep.tilePos.z);
		}

		public int range() {
			return golden ? GOLDEN_RANGE : RANGE;
		}

		public double distance(Collection<TilePos> cells) {
			double best = Double.MAX_VALUE;
			for (TileEntitySpeaker s : members) best = Math.min(best, Speakers.distance(s, cells));
			return best;
		}

		public String label() {
			List<TileEntitySpeaker> sorted = new ArrayList<>(members);
			sorted.sort((a, b) -> lower(a.tilePos, b.tilePos) ? -1 : lower(b.tilePos, a.tilePos) ? 1 : 0);
			for (TileEntitySpeaker s : sorted) {
				String n = s.name == null ? "" : s.name.trim();
				if (!n.isEmpty()) return n;
			}
			String kind = golden ? "Gold Speaker" : "Speaker";
			if (size() > 1) kind += " x" + size();
			return kind + " " + rep.tilePos.x + "," + rep.tilePos.y + "," + rep.tilePos.z;
		}
	}

	static boolean lower(TilePos a, TilePos b) {
		if (a.x != b.x) return a.x < b.x;
		if (a.y != b.y) return a.y < b.y;
		return a.z < b.z;
	}

	public static boolean golden(TileEntitySpeaker s) {
		return s.getBlock() != null && s.getBlock().getLogic() instanceof BlockLogicSpeaker b && b.golden;
	}

	public static double distance(TileEntitySpeaker s, Collection<TilePos> cells) {
		double best = Double.MAX_VALUE;
		for (TilePos c : cells) {
			double dx = c.x - s.tilePos.x, dy = c.y - s.tilePos.y, dz = c.z - s.tilePos.z;
			best = Math.min(best, dx * dx + dy * dy + dz * dz);
		}
		return Math.sqrt(best);
	}

	public static List<Group> groups(World w) {
		Map<TilePos, TileEntitySpeaker> byPos = new HashMap<>();
		for (java.util.Iterator<TileEntitySpeaker> it = TileEntitySpeaker.LOADED.iterator(); it.hasNext(); ) {
			TileEntitySpeaker s = it.next();
			if (s.isInvalid() || s.worldObj == null || s.worldObj.getTileEntity(s.tilePos) != s) { it.remove(); continue; }
			if (s.worldObj == w) byPos.put(new TilePos(s.tilePos.x, s.tilePos.y, s.tilePos.z), s);
		}
		List<Group> out = new ArrayList<>();
		Set<TilePos> seen = new HashSet<>();
		int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (Map.Entry<TilePos, TileEntitySpeaker> e : byPos.entrySet()) {
			if (seen.contains(e.getKey())) continue;
			int id = e.getValue().getBlockId();
			List<TileEntitySpeaker> m = new ArrayList<>();
			ArrayDeque<TilePos> q = new ArrayDeque<>();
			q.add(e.getKey());
			seen.add(e.getKey());
			while (!q.isEmpty() && m.size() < MAX_GROUP) {
				TilePos p = q.poll();
				m.add(byPos.get(p));
				for (int[] d : dirs) {
					TilePos n = new TilePos(p.x + d[0], p.y + d[1], p.z + d[2]);
					TileEntitySpeaker ns = byPos.get(n);
					if (ns != null && !seen.contains(n) && ns.getBlockId() == id) {
						seen.add(n);
						q.add(n);
					}
				}
			}
			out.add(new Group(m, golden(e.getValue())));
		}
		return out;
	}

	public static Group groupAt(World w, TilePos p) {
		for (Group g : groups(w))
			for (TileEntitySpeaker s : g.members)
				if (s.tilePos.x == p.x && s.tilePos.y == p.y && s.tilePos.z == p.z) return g;
		return null;
	}

	public static boolean playsFor(Group g, Set<TilePos> cells, List<Set<TilePos>> running) {
		TileEntitySpeaker r = g.rep;
		if (r.manual) return r.link != null && cells.contains(r.link);
		double mine = g.distance(cells);
		if (mine > g.range()) return false;
		for (Set<TilePos> other : running) {
			if (other.equals(cells)) continue;
			double d = g.distance(other);

			if (d < mine || (d == mine && firstKey(other) < firstKey(cells))) return false;
		}
		return true;
	}

	private static long firstKey(Set<TilePos> cells) {
		long best = Long.MAX_VALUE;
		for (TilePos c : cells) best = Math.min(best, ((long) c.x << 40) ^ ((long) c.y << 20) ^ c.z);
		return best;
	}

	public static List<Group> nearby(World w, Set<TilePos> cells) {
		List<Group> out = new ArrayList<>();
		for (Group g : groups(w)) {
			boolean linkedHere = g.rep.manual && g.rep.link != null && cells.contains(g.rep.link);
			if (linkedHere || g.distance(cells) <= g.range()) out.add(g);
		}
		out.sort((a, b) -> {
			int c = Double.compare(a.distance(cells), b.distance(cells));
			if (c != 0) return c;
			return lower(a.rep.tilePos, b.rep.tilePos) ? -1 : lower(b.rep.tilePos, a.rep.tilePos) ? 1 : 0;
		});
		return out;
	}

	public static void toggle(World w, TilePos speaker, TilePos tvCell, boolean wasOn) {
		Group g = groupAt(w, speaker);
		if (g == null) return;
		for (TileEntitySpeaker s : g.members) {
			s.manual = true;
			s.link = wasOn ? null : new TilePos(tvCell.x, tvCell.y, tvCell.z);
			changed(w, s);
		}
	}

	public static void onRename(Player p, TilePos speaker, String name) {
		World w = p.world;
		if (w == null) return;
		double dx = speaker.x + 0.5 - p.x, dy = speaker.y + 0.5 - p.y, dz = speaker.z + 0.5 - p.z;
		if (dx * dx + dy * dy + dz * dz > 12 * 12) return;
		Group g = groupAt(w, speaker);
		if (g == null) return;
		String n = name.length() > 32 ? name.substring(0, 32) : name;
		for (TileEntitySpeaker s : g.members) {
			if (n.equals(s.name)) continue;
			s.name = n;
			changed(w, s);
		}
	}

	private static void changed(World w, TileEntitySpeaker s) {
		s.setChanged();

		w.markBlockNeedsUpdate(s.tilePos);
	}
}
