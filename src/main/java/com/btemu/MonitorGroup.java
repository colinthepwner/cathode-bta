package com.btemu;

import net.minecraft.core.util.helper.Direction;
import net.minecraft.core.world.WorldSource;
import net.minecraft.core.world.pos.TilePos;
import net.minecraft.core.world.pos.TilePosc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MonitorGroup {

	public static final int MAX = 16;

	public static final int MAX_CANVAS = 64;
	private static final int MAX_CELLS = 2048;

	public static final int UP_SHIFT = 4, UP_MASK = 3 << UP_SHIFT;

	public static final class Face {
		public final TilePos origin;
		public final int w, h;
		public final int rx, ry, rz, ux, uy, uz, fx, fy, fz;

		public final int cu, cv;

		Face(TilePos origin, int w, int h, int[] r, int[] u, int[] f, int cu, int cv) {
			this.origin = origin;
			this.w = w;
			this.h = h;
			rx = r[0]; ry = r[1]; rz = r[2];
			ux = u[0]; uy = u[1]; uz = u[2];
			fx = f[0]; fy = f[1]; fz = f[2];
			this.cu = cu;
			this.cv = cv;
		}

		public TilePos cell(int i, int j) {
			return new TilePos(origin.x + i * rx + j * ux, origin.y + i * ry + j * uy, origin.z + i * rz + j * uz);
		}

		public double[] corner() {
			return new double[]{
				origin.x + 0.5 + 0.5 * (fx - rx - ux),
				origin.y + 0.5 + 0.5 * (fy - ry - uy),
				origin.z + 0.5 + 0.5 * (fz - rz - uz)};
		}
	}

	public final List<Face> faces;

	public final TilePos origin;

	public final int w, h;

	public final int rx, ry, rz, ux, uy, uz, fx, fy, fz;

	public final double cx, cy, cz;
	public final int blockId;

	private MonitorGroup(List<Face> faces, int w, int h, int blockId) {
		this.faces = Collections.unmodifiableList(faces);
		Face m = faces.get(0);
		origin = m.origin;
		this.w = w;
		this.h = h;
		rx = m.rx; ry = m.ry; rz = m.rz;
		ux = m.ux; uy = m.uy; uz = m.uz;
		fx = m.fx; fy = m.fy; fz = m.fz;
		double sx = 0, sy = 0, sz = 0;
		int n = 0;
		for (Face f : faces)
			for (int i = 0; i < f.w; i++)
				for (int j = 0; j < f.h; j++) {
					TilePos c = f.cell(i, j);
					sx += c.x + 0.5 + 0.5 * f.fx;
					sy += c.y + 0.5 + 0.5 * f.fy;
					sz += c.z + 0.5 + 0.5 * f.fz;
					n++;
				}
		cx = sx / n;
		cy = sy / n;
		cz = sz / n;
		this.blockId = blockId;
	}

	public boolean holo() {
		return faces.size() > 1;
	}

	public boolean flatWall() {
		return faces.size() == 1 && fy == 0;
	}

	public boolean isOrigin(TilePosc p) {
		return p.x() == origin.x && p.y() == origin.y && p.z() == origin.z;
	}

	public interface CellVisitor {
		void cell(TilePos pos, int canvasU, int canvasV);
	}

	public void forEachCell(CellVisitor v) {
		for (Face f : faces)
			for (int i = 0; i < f.w; i++)
				for (int j = 0; j < f.h; j++) v.cell(f.cell(i, j), f.cu + i, f.cv + j);
	}

	public List<TilePos> cells() {
		List<TilePos> out = new ArrayList<>();
		forEachCell((p, u, v) -> out.add(p));
		return out;
	}

	public double[] hit(double ex, double ey, double ez, double lx, double ly, double lz, double reach) {
		double[] best = null;
		double bestT = reach;
		for (Face f : faces) {
			double denom = lx * f.fx + ly * f.fy + lz * f.fz;
			if (denom >= -1e-4) continue;
			double[] p0 = f.corner();
			double t = ((p0[0] - ex) * f.fx + (p0[1] - ey) * f.fy + (p0[2] - ez) * f.fz) / denom;
			if (t <= 0 || t > bestT) continue;
			double hx = ex + lx * t - p0[0], hy = ey + ly * t - p0[1], hz = ez + lz * t - p0[2];
			double a = hx * f.rx + hy * f.ry + hz * f.rz;
			double b = hx * f.ux + hy * f.uy + hz * f.uz;
			if (a < 0 || a > f.w || b < 0 || b > f.h) continue;
			bestT = t;
			best = new double[]{f.cu + a, f.cv + b};
		}
		return best;
	}

	private static final int[][] AXES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	public static int[] facing3(int meta) {
		int id = meta & 7;
		if (id >= Direction.OFFSETS[0].length) id = Direction.SOUTH.id;
		int ox = Direction.OFFSETS[0][id], oy = Direction.OFFSETS[1][id], oz = Direction.OFFSETS[2][id];
		if (ox == 0 && oy == 0 && oz == 0) return new int[]{0, 0, 1};
		return new int[]{ox, oy, oz};
	}

	public static int[] facing(int meta) {
		int[] f = facing3(meta);
		if (f[1] != 0) return new int[]{0, 1};
		return new int[]{f[0], f[2]};
	}

	public static int[] upFor(int meta, int[] f) {
		if (f[1] == 0) return new int[]{0, 1, 0};
		return switch ((meta & UP_MASK) >> UP_SHIFT) {
			case 0 -> new int[]{0, 0, -1};
			case 1 -> new int[]{0, 0, 1};
			case 2 -> new int[]{-1, 0, 0};
			default -> new int[]{1, 0, 0};
		};
	}

	public static int upBits(int x, int z) {
		int k = z < 0 ? 0 : z > 0 ? 1 : x < 0 ? 2 : 3;
		return k << UP_SHIFT;
	}

	static int[] cross(int[] a, int[] b) {
		return new int[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	static int dot(int[] a, int[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	static int[] neg(int[] a) {
		return new int[]{-a[0], -a[1], -a[2]};
	}

	private static long key(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
	}

	private static int meta(WorldSource w, int x, int y, int z) {
		return w.getBlockData(new TilePos(x, y, z));
	}

	private static final long TTL_MS = 1000;
	private static final Map<CacheKey, Cached> CACHE = new ConcurrentHashMap<>();

	private record CacheKey(Object world, long pos) {
		@Override
		public boolean equals(Object o) {
			return o instanceof CacheKey k && k.world == world && k.pos == pos;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(world) * 31 + Long.hashCode(pos);
		}
	}

	private record Cached(MonitorGroup g, long time) {}

	public static void invalidate() {
		CACHE.clear();
	}

	public static MonitorGroup of(WorldSource world, TilePosc start) {
		long now = System.currentTimeMillis();
		CacheKey k = new CacheKey(world, key(start.x(), start.y(), start.z()));
		Cached c = CACHE.get(k);
		if (c != null && now - c.time < TTL_MS) return c.g;
		if (CACHE.size() > 50_000) CACHE.clear();
		MonitorGroup g = compute(world, start);
		Cached e = new Cached(g, now);
		if (g.holo() || g.w * g.h > 1) g.forEachCell((p, u, v) -> CACHE.put(new CacheKey(world, key(p.x, p.y, p.z)), e));
		CACHE.put(k, e);
		return g;
	}

	private static MonitorGroup compute(WorldSource world, TilePosc start) {
		int id = world.getBlockId(start.x(), start.y(), start.z());
		int meta0 = meta(world, start.x(), start.y(), start.z());
		MonitorKind kind = MonitorKind.byBlockId(id);
		if (kind != null && kind.wooden) return single(start, meta0, id);
		MonitorGroup holo = holo(world, start, id);
		if (holo != null) return holo;
		return planar(world, start, id, meta0);
	}

	private static MonitorGroup single(TilePosc p, int meta, int id) {
		int[] f = facing3(meta), u = upFor(meta, f), r = cross(u, f);
		List<Face> faces = new ArrayList<>();
		faces.add(new Face(new TilePos(p.x(), p.y(), p.z()), 1, 1, r, u, f, 0, 0));
		return new MonitorGroup(faces, 1, 1, id);
	}

	private static MonitorGroup planar(WorldSource world, TilePosc start, int id, int meta0) {
		int[] f = facing3(meta0), u = upFor(meta0, f), r = cross(u, f);
		int dir = meta0 & 7, up = meta0 & UP_MASK;
		Set<Long> seen = new HashSet<>();
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		queue.add(new int[]{0, 0});
		seen.add(pairKey(0, 0));
		int imin = 0, imax = 0, jmin = 0, jmax = 0, count = 0;
		int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		while (!queue.isEmpty()) {
			int[] c = queue.poll();
			count++;
			imin = Math.min(imin, c[0]); imax = Math.max(imax, c[0]);
			jmin = Math.min(jmin, c[1]); jmax = Math.max(jmax, c[1]);
			for (int[] s : steps) {
				int i = c[0] + s[0], j = c[1] + s[1];
				if (Math.abs(i) >= MAX || Math.abs(j) >= MAX) continue;
				if (!seen.add(pairKey(i, j))) continue;
				int x = start.x() + i * r[0] + j * u[0], y = start.y() + i * r[1] + j * u[1], z = start.z() + i * r[2] + j * u[2];
				if (world.getBlockId(x, y, z) != id) continue;
				int m = meta(world, x, y, z);

				if ((m & 7) != dir || (f[1] != 0 && (m & UP_MASK) != up)) continue;
				queue.add(new int[]{i, j});
			}
		}
		int w = imax - imin + 1, h = jmax - jmin + 1;
		if (count != w * h || w > MAX || h > MAX) return single(start, meta0, id);
		TilePos origin = new TilePos(start.x() + imin * r[0] + jmin * u[0], start.y() + imin * r[1] + jmin * u[1], start.z() + imin * r[2] + jmin * u[2]);
		List<Face> faces = new ArrayList<>();
		faces.add(new Face(origin, w, h, r, u, f, 0, 0));
		return new MonitorGroup(faces, w, h, id);
	}

	private static long pairKey(int i, int j) {
		return ((long) i << 32) ^ (j & 0xFFFFFFFFL);
	}

	private record Cell(int x, int y, int z, int[] f) {}

	private static final class Region {
		final List<Cell> cells = new ArrayList<>();
		final int[] f;
		int minKey = Integer.MAX_VALUE;
		Face face;
		Region(int[] f) { this.f = f; }
	}

	private static boolean isMonitor(WorldSource w, int x, int y, int z, int id, int[] f) {
		if (w.getBlockId(x, y, z) != id) return false;
		int[] g = facing3(meta(w, x, y, z));
		return g[0] == f[0] && g[1] == f[1] && g[2] == f[2];
	}

	private static MonitorGroup holo(WorldSource world, TilePosc start, int id) {

		Map<Long, Cell> all = new HashMap<>();
		ArrayDeque<Cell> q = new ArrayDeque<>();
		Cell s0 = new Cell(start.x(), start.y(), start.z(), facing3(meta(world, start.x(), start.y(), start.z())));
		all.put(key(s0.x, s0.y, s0.z), s0);
		q.add(s0);
		boolean multiPlane = false;
		while (!q.isEmpty()) {
			Cell c = q.poll();
			for (int[] d : AXES) {
				if (dot(d, c.f) != 0) continue;
				int[][] cand = {
					{c.x + d[0], c.y + d[1], c.z + d[2]},
					{c.x + d[0] + c.f[0], c.y + d[1] + c.f[1], c.z + d[2] + c.f[2]}};
				int[][] candF = {c.f, neg(d)};
				for (int k = 0; k < 2; k++) {
					int x = cand[k][0], y = cand[k][1], z = cand[k][2];
					if (!isMonitor(world, x, y, z, id, candF[k])) continue;
					long kk = key(x, y, z);
					if (all.containsKey(kk)) continue;
					if (all.size() >= MAX_CELLS) return null;
					if (k == 1) multiPlane = true;
					Cell n = new Cell(x, y, z, candF[k]);
					all.put(kk, n);
					q.add(n);
				}
			}
		}
		if (!multiPlane) return null;

		Map<Long, Region> regionOf = new HashMap<>();
		List<Region> regions = new ArrayList<>();
		for (Cell c : all.values()) {
			if (regionOf.containsKey(key(c.x, c.y, c.z))) continue;
			Region r = new Region(c.f);
			ArrayDeque<Cell> rq = new ArrayDeque<>();
			rq.add(c);
			regionOf.put(key(c.x, c.y, c.z), r);
			while (!rq.isEmpty()) {
				Cell e = rq.poll();
				r.cells.add(e);
				for (int[] d : AXES) {
					if (dot(d, e.f) != 0) continue;
					long kk = key(e.x + d[0], e.y + d[1], e.z + d[2]);
					Cell n = all.get(kk);
					if (n == null || n.f != e.f && !java.util.Arrays.equals(n.f, e.f) || regionOf.containsKey(kk)) continue;
					regionOf.put(kk, r);
					rq.add(n);
				}
			}
			regions.add(r);
		}
		if (regions.size() < 2) return null;
		for (Region r : regions) {
			int[] u = upFor(0, r.f), rr = cross(u, r.f);
			int amin = Integer.MAX_VALUE, amax = Integer.MIN_VALUE, bmin = Integer.MAX_VALUE, bmax = Integer.MIN_VALUE;
			for (Cell c : r.cells) {
				int[] p = {c.x, c.y, c.z};
				int a = dot(p, rr), b = dot(p, u);
				amin = Math.min(amin, a); amax = Math.max(amax, a);
				bmin = Math.min(bmin, b); bmax = Math.max(bmax, b);
			}
			int w = amax - amin + 1, h = bmax - bmin + 1;
			if (w * h != r.cells.size() || w > MAX || h > MAX) return null;
		}

		Region master = null;
		long masterKey = Long.MAX_VALUE;
		for (Region r : regions) {
			for (Cell c : r.cells) {
				long kk = (r.f[1] != 0 ? 1L << 62 : 0) | (key(c.x, c.y, c.z) & ((1L << 62) - 1));
				if (kk < masterKey) { masterKey = kk; master = r; }
			}
		}

		int[] u0 = upFor(0, master.f);
		master.face = makeFace(master, cross(u0, master.f), u0, null, 0, 0);
		List<Face> faces = new ArrayList<>();
		faces.add(master.face);
		ArrayDeque<Region> open = new ArrayDeque<>();
		open.add(master);
		Set<Region> placed = new HashSet<>();
		placed.add(master);
		Map<Long, Integer> canvas = new HashMap<>();
		if (!claim(canvas, master.face)) return null;
		while (!open.isEmpty()) {
			Region a = open.poll();
			Face fa = a.face;
			int[] ra = {fa.rx, fa.ry, fa.rz}, ua = {fa.ux, fa.uy, fa.uz}, ff = {fa.fx, fa.fy, fa.fz};
			List<Region> next = new ArrayList<>();
			for (Cell c : a.cells) {
				for (int[] d : AXES) {
					if (dot(d, ff) != 0) continue;
					Cell n = all.get(key(c.x + d[0] + ff[0], c.y + d[1] + ff[1], c.z + d[2] + ff[2]));
					if (n == null) continue;
					Region b = regionOf.get(key(n.x, n.y, n.z));
					if (b == null || placed.contains(b) || !java.util.Arrays.equals(b.f, neg(d))) continue;
					placed.add(b);

					int[] rb = foldAxis(ra, d, ff), ub = foldAxis(ua, d, ff);

					double mx = c.x + 0.5 + 0.5 * ff[0] + 0.5 * d[0];
					double my = c.y + 0.5 + 0.5 * ff[1] + 0.5 * d[1];
					double mz = c.z + 0.5 + 0.5 * ff[2] + 0.5 * d[2];
					double[] pa = fa.corner();
					double ma = (mx - pa[0]) * ra[0] + (my - pa[1]) * ra[1] + (mz - pa[2]) * ra[2] + fa.cu;
					double mb = (mx - pa[0]) * ua[0] + (my - pa[1]) * ua[1] + (mz - pa[2]) * ua[2] + fa.cv;
					b.face = makeFace(b, rb, ub, new double[]{mx, my, mz}, ma, mb);
					if (!claim(canvas, b.face)) return null;
					faces.add(b.face);
					if (b.f[1] == 0) next.add(0, b); else next.add(b);
				}
			}
			open.addAll(next);
		}
		if (placed.size() != regions.size()) return null;

		int umin = Integer.MAX_VALUE, vmin = Integer.MAX_VALUE, umax = Integer.MIN_VALUE, vmax = Integer.MIN_VALUE;
		for (Face f : faces) {
			umin = Math.min(umin, f.cu); vmin = Math.min(vmin, f.cv);
			umax = Math.max(umax, f.cu + f.w); vmax = Math.max(vmax, f.cv + f.h);
		}
		int cw = umax - umin, ch = vmax - vmin;
		if (cw > MAX_CANVAS || ch > MAX_CANVAS) return null;
		List<Face> shifted = new ArrayList<>();
		for (Face f : faces)
			shifted.add(new Face(f.origin, f.w, f.h, new int[]{f.rx, f.ry, f.rz}, new int[]{f.ux, f.uy, f.uz},
				new int[]{f.fx, f.fy, f.fz}, f.cu - umin, f.cv - vmin));
		return new MonitorGroup(shifted, cw, ch, id);
	}

	private static int[] foldAxis(int[] axis, int[] d, int[] fa) {
		int s = dot(axis, d);
		if (s == 0) return axis;
		return new int[]{fa[0] * s, fa[1] * s, fa[2] * s};
	}

	private static Face makeFace(Region r, int[] rr, int[] u, double[] anchor, double aa, double ab) {
		int amin = Integer.MAX_VALUE, amax = Integer.MIN_VALUE, bmin = Integer.MAX_VALUE, bmax = Integer.MIN_VALUE;
		Cell oc = null;
		for (Cell c : r.cells) {
			int[] p = {c.x, c.y, c.z};
			int a = dot(p, rr), b = dot(p, u);
			amin = Math.min(amin, a); amax = Math.max(amax, a);
			bmin = Math.min(bmin, b); bmax = Math.max(bmax, b);
		}
		for (Cell c : r.cells) {
			int[] p = {c.x, c.y, c.z};
			if (dot(p, rr) == amin && dot(p, u) == bmin) { oc = c; break; }
		}
		TilePos origin = new TilePos(oc.x, oc.y, oc.z);
		int cu = 0, cv = 0;
		if (anchor != null) {
			double px = oc.x + 0.5 + 0.5 * (r.f[0] - rr[0] - u[0]);
			double py = oc.y + 0.5 + 0.5 * (r.f[1] - rr[1] - u[1]);
			double pz = oc.z + 0.5 + 0.5 * (r.f[2] - rr[2] - u[2]);
			cu = (int) Math.round(aa + (px - anchor[0]) * rr[0] + (py - anchor[1]) * rr[1] + (pz - anchor[2]) * rr[2]);
			cv = (int) Math.round(ab + (px - anchor[0]) * u[0] + (py - anchor[1]) * u[1] + (pz - anchor[2]) * u[2]);
		}
		return new Face(origin, amax - amin + 1, bmax - bmin + 1, rr, u, r.f, cu, cv);
	}

	private static boolean claim(Map<Long, Integer> canvas, Face f) {
		for (int i = 0; i < f.w; i++)
			for (int j = 0; j < f.h; j++)
				if (canvas.put(pairKey(f.cu + i, f.cv + j), 1) != null) return false;
		return true;
	}
}
