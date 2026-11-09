package com.btemu.client;

import com.btemu.BtEmu;
import com.btemu.gb.GameBoy;
import com.btemu.session.Sessions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.block.BlockLogicRotatable;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.util.helper.Direction;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.TilePos;

public final class SpTest {
	private SpTest() {}

	private static final String PROP = System.getProperty("btemu.sptest");
	private static long startMs;
	private static int step;
	private static TilePos pos;

	private static final String WORLD = System.getProperty("btemu.sptest.world");
	private static long titleSinceMs;

	private static long lastDiagMs;

	public static void beforeWorld() {
		if (PROP == null) return;
		Minecraft mc = Minecraft.getMinecraft();
		long t = System.currentTimeMillis();
		if (startMs == 0 && t - lastDiagMs > 5000) {

			lastDiagMs = t;
			BtEmu.LOGGER.info("SPTEST waiting: world={} player={} screen={}", mc.currentWorld != null, mc.thePlayer != null,
					mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getName());
		}
		tick();
		if (WORLD == null || titleSinceMs < 0) return;
		if (mc.currentWorld != null) {
			titleSinceMs = -1;
			return;
		}
		if (mc.currentScreen == null) return;
		long now = System.currentTimeMillis();
		if (titleSinceMs == 0) titleSinceMs = now;
		else if (now - titleSinceMs > 4000) {
			titleSinceMs = -1;
			BtEmu.LOGGER.info("SPTEST opening world {}", WORLD);

			mc.playerController = new net.minecraft.client.player.controller.PlayerControllerSP(mc);
			mc.startWorld(WORLD);
			mc.displayScreen(null);
		}
	}

	public static void tick() {
		if (PROP == null) return;
		Minecraft mc = Minecraft.getMinecraft();
		Player p = mc.thePlayer;
		World w = mc.currentWorld;
		if (p == null || w == null || mc.currentScreen != null) return;
		long now = System.currentTimeMillis();
		if (startMs == 0) {
			startMs = now;
			BtEmu.LOGGER.info("SPTEST world open, multiplayer={}", w.isClientSide);
		}
		String[] roms = PROP.split("\\|");
		double sec = (now - startMs) / 1000.0;

		if (step == 0 && sec > 3) {
			int x = (int) Math.floor(p.x) + 3, y = (int) Math.floor(p.y), z = (int) Math.floor(p.z);
			pos = new TilePos(x, y, z);
			int meta = BlockLogicRotatable.setDirection(0, Direction.WEST);
			for (int dz = 0; dz < 2; dz++) {
				TilePos c = new TilePos(x, y, z + dz);
				w.setBlockTypeNotify(c, BtEmu.DIAMOND_MONITOR);
				w.setBlockDataNotify(c, meta);
			}
			p.yRot = -90f;
			p.xRot = 0f;
			BtEmu.LOGGER.info("SPTEST placed monitor at {}", pos);
			step++;
		} else if (step == 1 && sec > 5) {
			Sessions.onUse(w, pos, p);
			BtEmu.LOGGER.info("SPTEST powered on");
			step++;
		} else if (step >= 2) {
			int slot = (int) ((sec - 8) / 40);
			double t = (sec - 8) - slot * 40;
			int rom = step - 2;
			if (rom >= roms.length) {
				if (step == 2 + roms.length) {
					BtEmu.LOGGER.info("SPTEST DONE");
					step++;
				}
				return;
			}
			if (slot > rom) {
				step++;
				return;
			}
			if (slot < rom) return;

			int phase = (int) (t * 10);
			if (phase == 0 && lastPhase != 0) {
				Sessions.autotestPlay(w, pos, roms[rom]);
				BtEmu.LOGGER.info("SPTEST playing {}", roms[rom]);
			}
			int mask = 0;
			if ((t > 15 && t < 15.3) || (t > 20 && t < 20.3)) mask = GameBoy.BTN_START;
			else if (t > 25 && t < 35) mask = GameBoy.BTN_A | GameBoy.BTN_RIGHT;
			if (mask != lastMask) {
				Sessions.onInput(p, pos, mask);
				lastMask = mask;
			}
			if (phase == 380 && lastPhase != 380) BtEmu.LOGGER.info("SPTEST {} ran its slot", roms[rom]);
			lastPhase = phase;
		}
	}

	private static int lastMask, lastPhase = -1;
}
