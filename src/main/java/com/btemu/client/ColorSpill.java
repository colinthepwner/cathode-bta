package com.btemu.client;

import com.btemu.MonitorGroup;
import com.btemu.TileEntityMonitor;
import net.minecraft.client.render.renderer.BlendFactor;
import net.minecraft.client.render.renderer.GLRenderer;
import net.minecraft.client.render.renderer.State;
import net.minecraft.client.render.tessellator.TessellatorShader;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.TilePos;

import java.util.ArrayList;
import java.util.List;

final class ColorSpill {
	private static final int DEPTH = 6, MARGIN = 3;
	private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	private record Face(float[] v, int nx, int ny, int nz) {}

	private List<Face> faces = new ArrayList<>();
	private long builtAt = 0;
	private TilePos builtFor = null;

	void render(TileEntityMonitor te, MonitorGroup g, Display d, double x, double y, double z) {
		long now = System.currentTimeMillis();
		if (builtFor == null || !builtFor.equals(te.tilePos) || now - builtAt > 1000) {
			faces = build(te.worldObj, g);
			builtAt = now;
			builtFor = new TilePos(te.tilePos.x, te.tilePos.y, te.tilePos.z);
		}
		if (faces.isEmpty()) return;

		float ox = 0.5f + g.fx * 0.5f, oz = 0.5f + g.fz * 0.5f;
		float size = (float) Math.sqrt(g.w * g.h);
		float ref = 1.2f + 0.9f * size;
		float strength = 0.9f;
		float cr = d.avgR / 255f * strength, cg = d.avgG / 255f * strength, cb = d.avgB / 255f * strength;
		if (cr + cg + cb < 0.01f) return;

		GLRenderer.pushFrame();
		GLRenderer.modelM4f().translate((float) x, (float) y, (float) z);
		GLRenderer.enableState(State.BLEND);

		GLRenderer.setBlendFunc(BlendFactor.DST_COLOR, BlendFactor.ONE);
		GLRenderer.setDepthMask(false);
		GLRenderer.setLightmapCoord2i(15, 15);
		TessellatorShader tess = GLRenderer.getTessellator();
		tess.startDrawingQuads();
		for (Face f : faces) {
			for (int k = 0; k < 4; k++) {
				float vx = f.v[k * 3], vy = f.v[k * 3 + 1], vz = f.v[k * 3 + 2];

				float along = (vx - ox) * g.rx + (vz - oz) * g.rz;
				along = Math.max(-0.5f, Math.min(g.w - 0.5f, along));
				float sx = ox + along * g.rx, sz = oz + along * g.rz;
				float sy = Math.max(0f, Math.min(g.h, vy));
				float dx = sx - vx, dy = sy - vy, dz = sz - vz;
				float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-4f;

				float facing = (dx * f.nx + dy * f.ny + dz * f.nz) / dist;
				float front = (-dx * g.fx - dz * g.fz) / dist;
				float w = Math.max(0f, facing) * Math.max(0f, front + 0.15f);
				w /= 1f + (dist / ref) * (dist / ref);
				tess.setColorOpaque1i(rgb(cr * w, cg * w, cb * w));
				tess.addVertexWithUV(vx, vy, vz, 0.5, 0.5);
			}
		}
		tess.draw();
		GLRenderer.setDepthMask(true);
		GLRenderer.setBlendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
		GLRenderer.popFrame();
	}

	private static int rgb(float r, float g, float b) {
		return ((int) (Math.min(1f, r) * 255) << 16) | ((int) (Math.min(1f, g) * 255) << 8) | (int) (Math.min(1f, b) * 255);
	}

	private static List<Face> build(World world, MonitorGroup g) {
		List<Face> out = new ArrayList<>();
		TilePos o = g.origin;
		final float e = 0.004f;
		for (int i = -MARGIN; i < g.w + MARGIN; i++)
			for (int j = -MARGIN; j < g.h + MARGIN; j++)
				for (int k = 1; k <= DEPTH; k++) {
					int ax = o.x + i * g.rx + k * g.fx, ay = o.y + j, az = o.z + i * g.rz + k * g.fz;
					if (solid(world, ax, ay, az)) continue;
					for (int[] dir : DIRS) {
						int bx = ax + dir[0], by = ay + dir[1], bz = az + dir[2];
						if (!solid(world, bx, by, bz)) continue;
						if (world.getTileEntity(new TilePos(bx, by, bz)) instanceof TileEntityMonitor) continue;

						int nx = -dir[0], ny = -dir[1], nz = -dir[2];
						float px = (ax - o.x) + 0.5f - nx * 0.5f + nx * e;
						float py = (ay - o.y) + 0.5f - ny * 0.5f + ny * e;
						float pz = (az - o.z) + 0.5f - nz * 0.5f + nz * e;
						out.add(new Face(corners(px, py, pz, nx, ny, nz), nx, ny, nz));
					}
				}
		return out;
	}

	private static boolean solid(World world, int x, int y, int z) {
		return world.getBlockType(new TilePos(x, y, z)).solid();
	}

	private static float[] corners(float px, float py, float pz, int nx, int ny, int nz) {
		float[] v = new float[12];
		float[][] c;
		if (nx != 0) c = new float[][]{{0, -.5f, -.5f}, {0, .5f, -.5f}, {0, .5f, .5f}, {0, -.5f, .5f}};
		else if (ny != 0) c = new float[][]{{-.5f, 0, -.5f}, {.5f, 0, -.5f}, {.5f, 0, .5f}, {-.5f, 0, .5f}};
		else c = new float[][]{{-.5f, -.5f, 0}, {.5f, -.5f, 0}, {.5f, .5f, 0}, {-.5f, .5f, 0}};
		for (int k = 0; k < 4; k++) {
			v[k * 3] = px + c[k][0];
			v[k * 3 + 1] = py + c[k][1];
			v[k * 3 + 2] = pz + c[k][2];
		}
		return v;
	}
}
