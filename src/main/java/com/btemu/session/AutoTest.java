package com.btemu.session;

import com.btemu.BtEmu;
import com.btemu.gb.GameBoy;
import net.minecraft.core.block.BlockLogicRotatable;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.util.helper.Direction;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.TilePos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.entity.player.PlayerServer;

public final class AutoTest {
	private AutoTest() {}

	public static void start() {
		String prop = System.getProperty("btemu.autotest");
		if (prop == null || prop.isEmpty()) return;
		String[] names = prop.split(",");
		if (names.length < 2) return;
		Thread t = new Thread(() -> {
			try {
				while (MinecraftServer.getInstance() == null) Thread.sleep(500);
				Thread.sleep(2000);
				MinecraftServer.getInstance().addPlayerListBox(new Runner(names[0].trim(), names[1].trim()));
				BtEmu.LOGGER.info("AUTOTEST armed for {} and {}", names[0], names[1]);
			} catch (InterruptedException ignored) {
			}
		}, "BTEmu autotest");
		t.setDaemon(true);
		t.start();
	}

	private static final class Runner implements net.minecraft.core.net.IUpdatePlayerListBox {
		final String a, b;
		int tick = -1, step;
		TilePos pos;
		World world;
		int held, heldTicks;

		Runner(String a, String b) {
			this.a = a;
			this.b = b;
		}

		PlayerServer player(String name) {
			for (PlayerServer p : MinecraftServer.getInstance().playerList.playerEntities)
				if (p.username.equals(name)) return p;
			return null;
		}

		Session session() {
			return Sessions.get(world, pos);
		}

		void log(String what) {
			Session s = session();
			BtEmu.LOGGER.info("AUTOTEST {} | powered={} controller={} rom={} tvHost={} tvWeb={} tvApp={}",
					what, s != null && s.powered, s == null ? "-" : s.controller, s == null ? "-" : s.rom,
					s == null ? "-" : s.tvHost, s != null && s.tvWeb, s == null ? 0 : s.tvApp);
		}

		void press(String who, int mask) {
			Player p = player(who);
			if (p == null) return;
			Sessions.onInput(p, pos, mask);
			held = mask;
			heldTicks = 6;
			heldBy = who;
		}

		String heldBy = "";

		@Override
		public void update() {
			if (heldTicks > 0 && --heldTicks == 0) {
				Player p = player(heldBy);
				if (p != null) Sessions.onInput(p, pos, 0);
			}
			PlayerServer pa = player(a), pb = player(b);
			if (tick < 0) {
				if (pa == null || pb == null) return;
				tick = 0;
				BtEmu.LOGGER.info("AUTOTEST both players online");
			}
			tick++;
			int sec = tick / 20;
			if (tick % 20 != 0) return;
			switch (sec) {
				case 5 -> {

					world = pa.world;
					int x = (int) Math.floor(pa.x) + 4, y = (int) Math.floor(pa.y), z = (int) Math.floor(pa.z);
					pos = new TilePos(x, y, z);
					int meta = BlockLogicRotatable.setDirection(0, Direction.WEST);
					for (int dz = 0; dz < 2; dz++) {
						TilePos c = new TilePos(x, y, z + dz);
						world.setBlockTypeNotify(c, BtEmu.DIAMOND_MONITOR);
						world.setBlockDataNotify(c, meta);
					}
					BtEmu.LOGGER.info("AUTOTEST placed monitor at {} {} {}", x, y, z);
				}
				case 8 -> {
					Sessions.onUse(world, pos, pa);
					log("A powered on");
				}
				case 10 -> {
					press(a, GameBoy.BTN_A);
					log("A pressed A on the menu");
				}
				case 20 -> log("playing (expect rom set, controller=A)");
				case 22 -> {
					Sessions.toggleFocus(world, pos, pb);
					log("B clicked while A holds it (expect controller=B)");
				}
				case 24 -> {
					press(b, GameBoy.BTN_START);
					log("B pressed Start (B's input must be accepted)");
				}
				case 26 -> {
					press(a, GameBoy.BTN_START);
					log("A pressed Start (must be ignored)");
				}
				case 30 -> {
					Sessions.toggleFocus(world, pos, pa);
					log("A clicked: took it back (expect controller=A)");
				}
				case 34 -> press(a, MonitorMenu.BTN_MENU);
				case 36 -> press(a, GameBoy.BTN_UP);
				case 37 -> press(a, GameBoy.BTN_UP);
				case 38 -> {
					press(a, GameBoy.BTN_A);
					log("A chose the menu row two above the first ROM");
				}
				case 40 -> log("after choosing (expect tvHost=A tvWeb=true tvApp=0)");
				case 60 -> {
					log("web browser has run 20s (check the clients' AUTOTEST view lines)");
					press(a, MonitorMenu.BTN_MENU);
				}
				case 63 -> press(a, GameBoy.BTN_UP);
				case 64 -> {
					press(a, GameBoy.BTN_A);
					log("A chose the row one above the first ROM");
				}
				case 66 -> log("after choosing (expect tvWeb=true tvApp=1)");
				case 90 -> {
					log("Minecraft app has run 24s");
					BtEmu.LOGGER.info("AUTOTEST DONE");
				}
				default -> {
				}
			}
		}
	}
}
