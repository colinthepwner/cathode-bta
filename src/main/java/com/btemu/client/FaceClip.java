package com.btemu.client;

import net.minecraft.client.render.tessellator.TessellatorShader;

final class FaceClip {
	private FaceClip() {}

	static float u0, v0, u1, v1;
	static float offU, offV;

	static void set(int cu, int cv, int w, int h) {
		u0 = cu;
		v0 = cv;
		u1 = cu + w;
		v1 = cv + h;
		offU = cu + 0.5f;
		offV = cv + 0.5f;
	}

	static boolean emit(TessellatorShader tess, float x0, float y0, float x1, float y1, float zf,
			float ul, float vt, float ur, float vb) {
		float cx0 = Math.max(x0, u0), cx1 = Math.min(x1, u1);
		float cy0 = Math.max(y0, v0), cy1 = Math.min(y1, v1);
		if (cx1 <= cx0 || cy1 <= cy0) return false;
		float dx = x1 - x0, dy = y1 - y0;
		float a0 = ul + (ur - ul) * (cx0 - x0) / dx, a1 = ul + (ur - ul) * (cx1 - x0) / dx;

		float bt = vt + (vb - vt) * (y1 - cy1) / dy, bb = vt + (vb - vt) * (y1 - cy0) / dy;
		float lx0 = cx0 - offU, lx1 = cx1 - offU, ly0 = cy0 - offV, ly1 = cy1 - offV;
		tess.addVertexWithUV(lx0, ly1, zf, a0, bt);
		tess.addVertexWithUV(lx0, ly0, zf, a0, bb);
		tess.addVertexWithUV(lx1, ly0, zf, a1, bb);
		tess.addVertexWithUV(lx1, ly1, zf, a1, bt);
		return true;
	}

	static boolean visible(float x0, float y0, float x1, float y1) {
		return Math.min(x1, u1) > Math.max(x0, u0) && Math.min(y1, v1) > Math.max(y0, v0);
	}
}
