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
				String[] all = new String[names.length];
				for (int i = 0; i < names.length; i++) all[i] = names[i].trim();
				MinecraftServer.getInstance().addPlayerListBox(new Runner(all));
				BtEmu.LOGGER.info("AUTOTEST armed for {} and {}", names[0], names[1]);
			} catch (InterruptedException ignored) {
			}
		}, "BTEmu autotest");
		t.setDaemon(true);
		t.start();
	}

	private static final class Runner implements net.minecraft.core.net.IUpdatePlayerListBox {
		final String a, b;

		final String[] all;
		int tick = -1, step;
		TilePos pos;
		World world;
		int held, heldTicks;

		Runner(String[] all) {
			this.all = all;
			this.a = all[0];
			this.b = all[1];
		}

		String name(String letter) {
			int i = letter.charAt(0) - 'A';
			return i >= 0 && i < all.length ? all[i] : letter;
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

		final String coopRom = System.getProperty("btemu.autotest.coop", "");

		void logCoop(String what) {
			Session s = session();
			BtEmu.LOGGER.info("AUTOTEST {} | powered={} P1={} guests={} rom={} seats={} online={} menu={} frame={} epoch={}",
					what, s != null && s.powered, s == null ? "-" : s.controller,
					s == null ? "-" : java.util.Arrays.toString(s.guests), s == null ? "-" : s.rom,
					s == null ? 0 : s.seats(), MinecraftServer.getInstance().playerList.playerEntities.size(),
					s != null && s.ls.menu.open, s == null ? -1 : s.ls.frame, s == null ? -1 : s.epoch);
			if (s != null) BtEmu.LOGGER.info("AUTOTEST   detail | volume={} paused={} tvHost={} tvWeb={} menuRow={}", s.volume, s.paused, s.tvHost, s.tvWeb,
					s.ls.menu.open && s.ls.menu.sel >= 0 && s.ls.menu.sel < s.ls.menu.rowList.size() ? s.ls.menu.rowList.get(s.ls.menu.sel).label() : "-");
		}

		void coop(int sec, PlayerServer pa, PlayerServer pb) {
			switch (sec) {
				case 5 -> {

					world = pa.world;
					TilePos at = new TilePos((int) Math.floor(pa.x) + 4, (int) Math.floor(pa.y), (int) Math.floor(pa.z));
					if (world.getTileEntity(at) instanceof com.btemu.TileEntityMonitor te && te.powered) {
						pos = at;
						resumed = true;
						logCoop("existing monitor after restart (expect powered, rom=" + te.rom + ")");
					} else placeMonitor(pa);
				}
				case 8 -> {
					if (resumed) return;
					Sessions.onUse(world, pos, pa);
					logCoop("A powered on (expect P1=A)");
				}
				case 10 -> {
					Sessions.toggleFocus(world, pos, pb);
					logCoop("B clicked in the menu (expect guests[0]=B)");
				}
				case 12 -> {
					if (resumed) {
						logCoop("resumed game kept (no restart of the ROM)");
						return;
					}
					session().autotestPlay(coopRom);
					logCoop("started " + coopRom);
				}
				case 30 -> {
					press(a, GameBoy.BTN_START);
					logCoop("A pressed Start");
				}
				case 32 -> {
					press(b, GameBoy.BTN_A);
					logCoop("B pressed A (port 1)");
				}
				case 34 -> {
					Sessions.toggleFocus(world, pos, pb);
					logCoop("B clicked again: left (expect guests all empty, P1=A)");
				}
				case 36 -> {
					Sessions.toggleFocus(world, pos, pb);
					logCoop("B rejoined (expect guests[0]=B)");
				}
				case 40 -> {
					logCoop("driver mode: reading coop-cmds.txt (input delay " + session().delay() + " frames)");
					driving = true;
				}
				default -> {
				}
			}
		}

		void stage(PlayerServer pa, PlayerServer pb) {
			World w = pa.world;
			w.setWorldTime(6000);
			int x = (int) Math.floor(pa.x), z = (int) Math.floor(pa.z);
			int top = w.getHeightValue(x, z);

			boolean deck = w.getBlockType(new TilePos(x, top - 1, z)) == net.minecraft.core.block.Blocks.STONE
					&& w.getBlockType(new TilePos(x + 8, top - 1, z)) == net.minecraft.core.block.Blocks.STONE;
			int y = deck ? top : Math.min(250, top + 8);
			stageY = y;
			if (!deck)
				for (int dx = -3; dx <= 8; dx++)
					for (int dz = -4; dz <= 5; dz++)
						w.setBlockTypeNotify(new TilePos(x + dx, y - 1, z + dz), net.minecraft.core.block.Blocks.STONE);

			for (int i = 0; i < all.length; i++) {
				PlayerServer p = player(all[i]);
				if (p == null) continue;
				p.setGamemode(net.minecraft.core.player.gamemode.Gamemodes.CREATIVE);
				p.playerNetServerHandler.teleportAndRotate(x + 0.5, y, z + 0.5 + (i == 0 ? 0 : i == 1 ? 1 : i == 2 ? -1 : 2), -90f, 0f);
			}
		}

		boolean driving;
		final java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
		final java.util.Map<String, int[]> holds = new java.util.HashMap<>();
		int waitTicks, randTicks;
		double stageY;
		boolean resumed;
		java.util.Random rng = new java.util.Random(1);

		void drive() {
			java.io.File f = new java.io.File("coop-cmds.txt");
			if (f.exists()) {
				try {
					queue.addAll(java.nio.file.Files.readAllLines(f.toPath()));
				} catch (java.io.IOException ignored) {
				}
				f.delete();
				new java.io.File("coop-done.txt").delete();
			}
			for (java.util.Map.Entry<String, int[]> e : holds.entrySet()) {
				int[] h = e.getValue();
				if (h[1] > 0 && --h[1] == 0) {
					Player p = player(e.getKey());
					if (p != null) Sessions.onInput(p, pos, 0);
				}
			}
			if (randTicks > 0) {

				randTicks--;
				for (String n : all) {
					Player p = player(n);
					Session s = session();
					if (p == null || s == null || s.portOf(n) < 0) continue;
					int[] h = holds.get(n);
					if (h == null || h[1] <= 0) {
						int[] pool = {0x10, 0x11, 0x12, 0x14, 0x18, 0x20, 0x1, 0x2, 0x10 | 0x10000, 0x10 | 0x4000, 0};
						int mask = pool[rng.nextInt(pool.length)];
						Sessions.onInput(p, pos, mask);
						holds.put(n, new int[]{mask, 4 + rng.nextInt(37)});
					}
				}
				if (randTicks == 0) logCoop("random inputs done");
			}
			if (waitTicks > 0) {
				waitTicks--;
				return;
			}
			while (!queue.isEmpty() && waitTicks == 0) {
				String line = queue.poll().trim();
				if (line.isEmpty()) continue;
				String[] w = line.split("\s+");
				try {
					switch (w[0]) {
						case "A", "B", "C", "D" -> {
							String who = name(w[0]);
							int mask = Integer.decode(w[1]);
							int ticks = Integer.parseInt(w[2]);
							Player p = player(who);
							if (p != null) Sessions.onInput(p, pos, mask);
							holds.put(who, new int[]{mask, ticks});
						}
						case "join" -> {
							Player p = player(name(w[1]));
							if (p != null && session().portOf(p.username) < 0) Sessions.toggleFocus(world, pos, p);
							logCoop("join " + w[1]);
						}
						case "leave" -> {
							Player p = player(name(w[1]));
							if (p != null && session().portOf(p.username) >= 0) Sessions.toggleFocus(world, pos, p);
							logCoop("leave " + w[1]);
						}
						case "play" -> {
							session().autotestPlay(line.substring(5).trim());
							logCoop("play");
						}
						case "power" -> {
							Player p = player(name(w[1]));
							if (p != null) Sessions.onUse(world, pos, p);
							logCoop("power " + w[1]);
						}
						case "log" -> logCoop(line.substring(4));
						case "gaze" -> {

							Player p = player(name(w[1]));
							if (p != null) Sessions.onFocus(p, pos, true);
							logCoop("gaze " + w[1]);
						}
						case "lag" -> {

							int ms = Integer.parseInt(w[1]);
							for (PlayerServer p : MinecraftServer.getInstance().playerList.playerEntities)
								if (p.playerNetServerHandler != null) p.playerNetServerHandler.netManager.fakeLag = ms;
							logCoop("lag " + ms + " ms");
						}
						case "hurt" -> {
							Player p = player(name(w[1]));
							if (p != null) Sessions.onPlayerHurt(p);
							logCoop("hurt " + w[1]);
						}
						case "tp" -> {
							PlayerServer p = player(name(w[1]));

							if (p != null) p.playerNetServerHandler.teleport(p.x + Double.parseDouble(w[2]), stageY, p.z + Double.parseDouble(w[3]));
							logCoop("tp " + w[1] + (p == null ? "" : String.format(" now at %.1f %.1f %.1f", p.x, p.y, p.z)));
						}
						case "rand" -> {
							randTicks = Integer.parseInt(w[1]);
							rng = new java.util.Random(Long.parseLong(w[2]));
							logCoop("random inputs for " + randTicks + " ticks");
						}
						case "stopserver" -> {
							logCoop("stopping server");
							MinecraftServer.getInstance().initiateShutdown();
						}
						case "wait" -> waitTicks = Integer.parseInt(w[1]);
						case "shot" -> shot(w[1]);
						default -> BtEmu.LOGGER.warn("AUTOTEST bad command {}", line);
					}
				} catch (Exception ex) {
					BtEmu.LOGGER.warn("AUTOTEST bad command {}", line, ex);
				}
			}
			if (queue.isEmpty() && waitTicks == 0) {
				java.io.File done = new java.io.File("coop-done.txt");
				if (!done.exists()) {
					try {
						done.createNewFile();
					} catch (java.io.IOException ignored) {
					}
				}
			}
		}

		void shot(String name) throws java.io.IOException {
			Session s = session();
			com.btemu.emu.Machine m = s == null ? null : s.ls.gb;
			if (m == null) return;
			int w = m.width(), h = m.height();
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
			int[] px = m.frame();
			synchronized (px) {
				img.setRGB(0, 0, w, h, px, 0, w);
			}
			java.io.File dir = new java.io.File("shots");
			dir.mkdirs();
			javax.imageio.ImageIO.write(img, "png", new java.io.File(dir, name + ".png"));
			BtEmu.LOGGER.info("AUTOTEST shot {} at frame {}", name, s.ls.frame);
		}

		void placeMonitor(PlayerServer pa) {

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

		@Override
		public void update() {
			if (heldTicks > 0 && --heldTicks == 0) {
				Player p = player(heldBy);
				if (p != null) Sessions.onInput(p, pos, 0);
			}
			PlayerServer pa = player(a), pb = player(b);
			if (tick < 0) {
				for (String n : all) if (player(n) == null) return;
				tick = 0;
				BtEmu.LOGGER.info("AUTOTEST both players online");
				stage(pa, pb);
			}
			tick++;
			int sec = tick / 20;
			if (driving) {
				drive();
				return;
			}
			if (tick % 20 != 0) return;
			if (!coopRom.isEmpty()) {
				coop(sec, pa, pb);
				return;
			}
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
