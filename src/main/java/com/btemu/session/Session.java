package com.btemu.session;

import com.btemu.BtEmu;
import com.btemu.MonitorKind;
import com.btemu.MonitorLight;
import com.btemu.MonitorGroup;
import com.btemu.gb.GameBoy;
import com.btemu.net.Msgs;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.TilePos;
import turniplabs.halplibe.helper.EnvironmentHelper;
import turniplabs.halplibe.helper.network.NetworkHandler;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Session {
	static final double FRAME_NS = 16_742_706.0;

	static final double RADIUS = 96;

	final World world;
	TilePos pos;
	final MonitorKind kind;

	boolean powered = false;
	String rom = "";
	long crc = 0;
	byte[] romBytes;
	boolean noteMode;
	int epoch = 0;

	String controller = "";

	final String[] guests = {"", "", ""};
	final Lockstep ls = new Lockstep();

	boolean alarm = false;

	String tvHost = "";

	boolean tvWeb;

	int tvApp;

	int volume = 7;

	boolean paused = false;
	private int prevVolMask = 0;
	private int lastFuelBucket = -1;
	private long alarmStart = 0;

	static final long ALARM_MS = 3200;
	long bound = 0;

	private long lastStamp = -1;

	private final long[] lastPortStamp = {-1, -1, -1, -1};
	private double acc = 0;
	private long lastNs = 0;
	private final List<long[]> outbox = new ArrayList<>();

	final Map<String, Long> candidates = new HashMap<>();
	private long lastSave = System.currentTimeMillis();
	private int lightLevel = -1;
	long lastTickMs = System.currentTimeMillis();

	Session(World world, TilePos pos, MonitorKind kind) {
		this.world = world;
		this.pos = pos;
		this.kind = kind;
		this.ls.soundLocked = kind.forcedNoteBlocks;
		this.ls.menu.tvAllowed = !kind.wooden;
	}

	int delay() {
		return kind.inputDelay() + (EnvironmentHelper.isMultiplayerServer() ? 3 : 0);
	}

	int dim() {
		return world.dimension.id;
	}

	void powerOn(String cartridge, boolean notes, byte[] savedState) {
		powered = true;
		com.btemu.TileEntityMonitor tv = te();
		if (tv != null) volume = tv.volume;
		noteMode = kind.forcedNoteBlocks || notes;
		ls.menu.setRoms(RomStore.list());
		com.btemu.emu.CoreStore.prefetch(ls.menu.roms());
		boolean restored = false;
		if (savedState != null) restored = restoreFrom(cartridge, savedState);
		if (!restored) {
			loadCartridge(cartridge);
			ls.menu.openAt(rom);
		}
		newEpoch();
	}

	private boolean restoreFrom(String cartridge, byte[] state) {
		try {
			byte[] bytes = cartridge.isEmpty() ? null : RomStore.read(cartridge);
			synchronized (ls) {
				ls.rom = cartridge;
				ls.restore(state, bytes, cartridge.isEmpty() ? null : saveFile(cartridge));
				setRom(cartridge, bytes);
				ls.frame = 0;
				ls.inputs.clear();
			}
			return true;
		} catch (Exception e) {
			BtEmu.LOGGER.warn("Couldn't resume saved monitor state at {}; starting fresh", pos, e);
			return false;
		}
	}

	private static File saveFile(String rom) {

		return null;
	}

	private void setRom(String name, byte[] bytes) {
		rom = bytes == null ? "" : name;
		romBytes = bytes;
		crc = bytes == null ? 0 : RomStore.crc(bytes);
		ls.rom = rom;
	}

	private void loadCartridge(String name) {
		awaitWorker();
		synchronized (ls) {
			loadCartridgeLocked(name);
		}
	}

	private void loadCartridgeLocked(String name) {
		if (ls.gb != null) {
			ls.gb.flush();
			ls.gb.close();
		}
		ls.gb = null;
		byte[] bytes = name.isEmpty() ? null : RomStore.read(name);
		if (bytes != null) {
			try {
				ls.gb = com.btemu.emu.Machines.create(name, bytes, saveFile(name));
				ls.fresh = true;
			} catch (Exception e) {
				BtEmu.LOGGER.error("Bad ROM {}", name, e);
				bytes = null;
			}
		}
		setRom(name, bytes);
	}

	boolean rebaseWanted;

	boolean needsRebase() {
		return ls.gb instanceof com.btemu.emu.RetroCore && !ls.fresh;
	}

	private long lastRebaseMs;

	private void rebase() {

		long now = System.currentTimeMillis();
		if (now - lastRebaseMs < 10_000) return;
		lastRebaseMs = now;
		newEpoch();
	}

	private void newEpoch() {
		epoch++;
		awaitWorker();
		synchronized (ls) {
			if (needsRebase()) {
				try {
					ls.restore(ls.snapshot(), romBytes, null);
				} catch (Exception e) {
					BtEmu.LOGGER.error("Couldn't reload the machine at {} for a resync", pos, e);
				}
			}
			ls.frame = 0;
			ls.inputs.clear();
		}
		bound = delay();
		lastStamp = -1;
		java.util.Arrays.fill(lastPortStamp, -1);
		outbox.clear();
		acc = 0;
		lastNs = 0;
		broadcastState();
		broadcastSnapshot(null);
	}

	private java.util.concurrent.ExecutorService worker;
	private java.util.concurrent.Future<MonitorMenu.Action> job;

	private java.util.concurrent.ExecutorService worker() {
		if (worker == null) {
			worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "BTEmu-session@" + pos);
				t.setDaemon(true);
				return t;
			});
		}
		return worker;
	}

	private void awaitWorker() {
		java.util.concurrent.Future<MonitorMenu.Action> j = job;
		job = null;
		if (j == null) return;
		try {
			j.get(5, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception ignored) {
		}
	}

	void autotestPlay(String rom) {
		awaitWorker();
		loadCartridge(rom);
		ls.menu.open = ls.gb == null;
		newEpoch();
		broadcastState();
	}

	void powerOff() {
		awaitWorker();
		synchronized (ls) {
			if (ls.gb != null) {
				ls.gb.flush();
				ls.gb.close();
			}
			ls.gb = null;
		}
		powered = false;
		alarm = false;
		tvHost = "";
		controller = "";
		java.util.Arrays.fill(guests, "");
		candidates.clear();
		broadcastState();
		setLight(0);
	}

	private void woodBurnOnly() {
		com.btemu.TileEntityMonitor t = te();
		if (t == null) return;
		if (t.burnTicks <= 0) {
			t.burnTicks = t.takeFuel();
			if (t.burnTicks <= 0) {
				powerOff();
				return;
			}
		}
		t.burnTicks--;
		if (++t.heatTicks >= com.btemu.TileEntityMonitor.OVERHEAT_TICKS) triggerAlarm();
	}

	void exitTv() {
		if (tvHost.isEmpty()) return;
		tvHost = "";
		tvWeb = false;
		tvApp = 0;
		ls.menu.openAt(rom);
		newEpoch();
	}

	void onTvFrame(Player p, byte[] jpeg) {
		if (tvHost.isEmpty() || !p.username.equals(tvHost) || jpeg.length > 60_000) return;
		NetworkHandler.sendToAllAround(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, RADIUS, dim(), new Msgs.TvFrameDown(pos, jpeg));
	}

	void onTvAudio(Player p, byte[] pcm) {
		if (tvHost.isEmpty() || !p.username.equals(tvHost) || pcm.length > 60_000) return;
		NetworkHandler.sendToAllAround(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, RADIUS, dim(), new Msgs.TvAudioDown(pos, pcm));
	}

	public boolean isRunning() {
		return powered && !alarm;
	}

	private com.btemu.TileEntityMonitor te() {
		return world.getTileEntity(pos) instanceof com.btemu.TileEntityMonitor t ? t : null;
	}

	void triggerAlarm() {
		if (alarm) return;
		alarm = true;
		alarmStart = System.currentTimeMillis();
		broadcastState();
	}

	private void refreshSpeakers(MonitorGroup g) {
		java.util.Set<TilePos> cells = new java.util.HashSet<>(g.cells());
		java.util.List<java.util.Set<TilePos>> running = Sessions.runningCells(world);
		java.util.List<MonitorMenu.Speaker> list = new java.util.ArrayList<>();
		for (com.btemu.Speakers.Group sg : com.btemu.Speakers.nearby(world, cells))
			list.add(new MonitorMenu.Speaker(sg.pos(), sg.label(), com.btemu.Speakers.playsFor(sg, cells, running)));
		synchronized (ls) {
			ls.menu.setSpeakers(list);
		}
	}

	void tick(MonitorGroup g) {
		lastTickMs = System.currentTimeMillis();
		if (!powered) return;
		if (rebaseWanted) {
			rebaseWanted = false;
			rebase();
		}
		dropAbsentController();
		refreshSpeakers(g);
		if (alarm) {
			if (System.currentTimeMillis() - alarmStart >= ALARM_MS) {
				com.btemu.TileEntityMonitor t = te();
				if (t != null) {
					t.heatTicks = 0;
					if (t.coolTicks <= 0) t.coolTicks = com.btemu.TileEntityMonitor.COOL_TICKS;
					t.setChanged();
				}
				alarm = false;
				powerOff();
			}
			return;
		}
		if (!tvHost.isEmpty()) {

			if (kind.wooden) woodBurnOnly();
			return;
		}
		if (kind.wooden) {
			com.btemu.TileEntityMonitor t = te();
			if (t != null) {

				if (t.burnTicks <= 0) {
					t.burnTicks = t.takeFuel();
					if (t.burnTicks <= 0) {
						powerOff();
						return;
					}
				}
				t.burnTicks--;

				int secs = t.fuelTicksRemaining() / 20;
				int bucket = secs >= 60 ? 60 : secs / 2;
				if (bucket != lastFuelBucket) {
					lastFuelBucket = bucket;
					broadcastState();
				}
				if (++t.heatTicks >= com.btemu.TileEntityMonitor.OVERHEAT_TICKS) {
					triggerAlarm();
					return;
				}
			}
		}

		long now = System.nanoTime();
		if (lastNs == 0) lastNs = now;

		if (paused || now - lastNs > 250_000_000L) {
			lastNs = now;
			acc = 0;
			if (paused) {
				bound = Math.max(bound, Math.max(ls.frame + delay(), lastStamp + 1));
				sendFrames();
				return;
			}
		}
		acc += (now - lastNs) / (ls.gb != null ? ls.gb.frameNs() : FRAME_NS);
		lastNs = now;

		MonitorMenu.Action done = MonitorMenu.Action.NONE;
		if (job != null) {
			if (!job.isDone()) {
				acc = Math.min(acc, 12);
				sendFrames();
				return;
			}
			try {
				done = job.get();
			} catch (Exception e) {
				BtEmu.LOGGER.error("Monitor at {} crashed", pos, e);
			}
			job = null;
		}
		int n = (int) Math.min(acc, 12);
		acc = Math.min(acc - n, 12);
		for (int i = 0; i < 1; i++) {
			MonitorMenu.Action a = done;
			if (a == MonitorMenu.Action.SELECT) {
				loadCartridge(ls.menu.chosen);
				ls.menu.open = ls.gb == null;
				newEpoch();
				return;
			} else if (a == MonitorMenu.Action.EJECT) {
				loadCartridge("");
				ls.menu.openAt("");
				newEpoch();
				return;
			} else if (a == MonitorMenu.Action.TV || a == MonitorMenu.Action.WEB || MonitorMenu.appId(a) != 0) {
				if (!owner().isEmpty()) {
					tvHost = owner();
					tvWeb = a == MonitorMenu.Action.WEB || MonitorMenu.appId(a) != 0;
					tvApp = MonitorMenu.appId(a);
					broadcastState();
				}
				return;
			} else if (a == MonitorMenu.Action.TOGGLE_SPEAKER) {
				if (ls.menu.chosenSpeaker != null)
					com.btemu.Speakers.toggle(world, ls.menu.chosenSpeaker, g.origin, ls.menu.chosenSpeakerOn);
				refreshSpeakers(g);
			} else if (a == MonitorMenu.Action.TOGGLE_SOUND) {
				noteMode = !noteMode;
				broadcastState();
			}
		}
		long target;
		synchronized (ls) {
			target = ls.frame + n;
		}

		bound = Math.max(bound, Math.max(target + delay(), lastStamp + 1));
		sendFrames();
		ls.skipCoreFrames = turniplabs.halplibe.helper.EnvironmentHelper.isSinglePlayer();
		if (n > 0) {
			final long stop = target;
			job = worker().submit(() -> {
				while (true) {
					MonitorMenu.Action a;
					synchronized (ls) {
						if (ls.frame >= stop) return MonitorMenu.Action.NONE;
						a = ls.step();
					}
					if (a != MonitorMenu.Action.NONE) return a;
				}
			});
		}

		if (System.currentTimeMillis() - lastSave > 5000 && ls.gb != null) {
			ls.gb.flush();
			lastSave = System.currentTimeMillis();
		}

		if (EnvironmentHelper.isMultiplayerServer() && world.getWorldTime() % 5 == 0) {
			int level = 0;
			if (ls.gb != null && !ls.menu.open) {
				long sum = 0;
				int[] f = ls.gb.frame();
				for (int i = 0; i < f.length; i += 7) sum += (f[i] >> 8) & 0xFF;
				level = Math.round(sum / (float) (f.length / 7) / 255f * kind.maxEmission);
			} else {
				level = Math.min(3, kind.maxEmission);
			}
			if (level != lightLevel) {
				lightLevel = level;
				setLight(level);
			}
		}
	}

	private void setLight(int level) {
		MonitorGroup g = MonitorGroup.of(world, pos);
		for (TilePos c : g.cells()) MonitorLight.set(world, c.x, c.y, c.z, level);
	}

	private void sendFrames() {
		Msgs.Frames m = new Msgs.Frames();
		m.pos = pos;
		m.epoch = epoch;
		m.bound = bound;
		m.frames = new long[outbox.size()];
		m.masks = new int[outbox.size()];
		for (int i = 0; i < outbox.size(); i++) {
			m.frames[i] = outbox.get(i)[0];
			m.masks[i] = (int) outbox.get(i)[1];
		}
		outbox.clear();
		NetworkHandler.sendToAllAround(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, RADIUS, dim(), m);
	}

	private int tvPrevMask = 0;

	int portOf(String name) {
		if (name == null || name.isEmpty()) return -1;
		if (name.equals(controller)) return 0;
		for (int i = 0; i < guests.length; i++) if (name.equals(guests[i])) return i + 1;
		return -1;
	}

	String owner() {
		if (!controller.isEmpty()) return controller;
		for (String g : guests) if (!g.isEmpty()) return g;
		return "";
	}

	boolean anyPlayers() {
		return !owner().isEmpty();
	}

	int seats() {
		com.btemu.emu.Machine m = ls.gb;
		return m == null ? Lockstep.PORTS : Math.max(1, Math.min(Lockstep.PORTS, m.maxPlayers()));
	}

	void onInput(Player p, int mask) {
		if (!powered) return;
		int port = portOf(p.username);
		if (port < 0) return;
		if (!p.username.equals(owner())) {

			mask &= ~(MonitorMenu.BTN_MENU | MonitorMenu.BTN_VOL_UP | MonitorMenu.BTN_VOL_DOWN | MonitorMenu.BTN_PAUSE);
			if (!tvHost.isEmpty()) return;
			stamp(port, mask);
			return;
		}

		int pressed = mask & ~prevVolMask;
		prevVolMask = mask;
		int vol = volume;
		if ((pressed & MonitorMenu.BTN_VOL_UP) != 0) vol = Math.min(10, vol + 1);
		if ((pressed & MonitorMenu.BTN_VOL_DOWN) != 0) vol = Math.max(0, vol - 1);
		boolean pause = (mask & MonitorMenu.BTN_PAUSE) != 0;
		if (vol != volume) {
			volume = vol;
			com.btemu.TileEntityMonitor t = te();
			if (t != null) {
				t.volume = vol;
				t.setChanged();
			}
			broadcastState();
		}
		paused = pause && tvHost.isEmpty();

		mask &= ~(MonitorMenu.BTN_VOL_UP | MonitorMenu.BTN_VOL_DOWN | MonitorMenu.BTN_PAUSE);
		if (!tvHost.isEmpty()) {

			if ((mask & MonitorMenu.BTN_MENU) != 0 && (tvPrevMask & MonitorMenu.BTN_MENU) == 0) exitTv();
			tvPrevMask = mask;
			return;
		}
		tvPrevMask = mask;
		stamp(port, mask);
	}

	private void stamp(int port, int mask) {

		long f = Math.max(bound, lastPortStamp[port] + 1);
		lastPortStamp[port] = f;
		lastStamp = Math.max(lastStamp, f);
		long k = Lockstep.key(f, port);
		synchronized (ls) {
			ls.inputs.put(k, mask);
		}
		outbox.add(new long[]{k, mask});
	}

	void onFocus(Player p, boolean want) {
		onFocus(p, want, false);
	}

	void onFocus(Player p, boolean want, boolean takeover) {
		if (!powered) return;
		if (want) {
			candidates.put(p.username, System.currentTimeMillis());

			if (portOf(p.username) >= 0) {
				NetworkHandler.sendToPlayer(p, stateMsg());
				return;
			}
			if (!anyPlayers()) {
				setController(p.username);
				tellSeat(p, 0);
				return;
			}
			if (!takeover) return;
			int seat = freeSeat();
			if (seat == 0) {
				setController(p.username);
				tellSeat(p, 0);
			} else if (seat > 0) {
				guests[seat - 1] = p.username;
				stamp(seat, 0);
				broadcastState();
				tellSeat(p, seat);
			} else if (seats() == 1) {

				setController(p.username);
			}
			else p.sendStatusMessage("All " + seats() + " controllers are taken");

		} else {
			candidates.remove(p.username);
			int port = portOf(p.username);
			if (port == 0) releaseController(false);
			else if (port > 0) leaveSeat(port);
		}
	}

	private void tellSeat(Player p, int seat) {
		if (seats() > 1 || seat > 0) p.sendStatusMessage("You're Player " + (seat + 1));
	}

	private int freeSeat() {
		int n = seats();
		if (controller.isEmpty()) return 0;
		for (int i = 0; i + 1 < n; i++) if (guests[i].isEmpty()) return i + 1;
		return -1;
	}

	void leaveSeat(int port) {
		String who = guests[port - 1];
		guests[port - 1] = "";
		stamp(port, 0);
		if (!tvHost.isEmpty() && who.equals(tvHost)) exitTv();
		broadcastState();
	}

	void onUse(Player p, String cartridge, boolean notes, byte[] savedState) {
		if (powered) {
			if (!alarm) powerOff();
			return;
		}
		com.btemu.TileEntityMonitor t = kind.wooden ? te() : null;
		if (t != null) {
			if (t.coolTicks > 0) {

				controller = "";
				powerOn(cartridge, notes, null);
				triggerAlarm();
				return;
			}
			if (t.burnTicks <= 0 && !t.hasFuel()) return;
		}
		controller = p.username;
		java.util.Arrays.fill(guests, "");
		powerOn(cartridge, notes, savedState);
	}

	void setController(String name) {
		if (!name.isEmpty()) {
			for (int i = 0; i < guests.length; i++) {
				if (name.equals(guests[i])) {
					guests[i] = "";
					stamp(i + 1, 0);
				}
			}
		}
		String newOwner = name.isEmpty() ? owner() : name;
		if (!tvHost.isEmpty() && !tvHost.equals(newOwner)) {

			controller = name;
			exitTv();
		}
		controller = name;
		if (!name.isEmpty()) {

			stamp(0, 0);
		}
		broadcastState();
	}

	void releaseController(boolean toClosest) {
		paused = false;
		String old = controller;
		controller = "";

		stamp(0, 0);
		if (toClosest) {
			Player best = null;
			double bestD = Double.MAX_VALUE;
			long now = System.currentTimeMillis();
			for (Player pl : world.players) {
				if (pl.username.equals(old) || portOf(pl.username) >= 0) continue;
				Long t = candidates.get(pl.username);
				if (t == null || now - t > 2500) continue;
				double dx = pl.x - pos.x - 0.5, dy = pl.y - pos.y - 0.5, dz = pl.z - pos.z - 0.5;
				double d = dx * dx + dy * dy + dz * dz;
				if (d < bestD) { bestD = d; best = pl; }
			}
			if (best != null) controller = best.username;
		}
		String own = owner();
		if (!tvHost.isEmpty() && !own.isEmpty() && !own.equals(tvHost)) exitTv();
		broadcastState();
	}

	private void dropAbsentController() {
		if (!tvHost.isEmpty()) {
			boolean hostHere = false;
			for (Player pl : world.players) if (pl.username.equals(tvHost)) { hostHere = true; break; }
			if (!hostHere) exitTv();
		}
		for (int i = 0; i < guests.length; i++) {
			if (!guests[i].isEmpty() && !nearby(guests[i])) leaveSeat(i + 1);
		}
		if (!controller.isEmpty() && !nearby(controller)) releaseController(false);
	}

	private boolean nearby(String name) {
		for (Player pl : world.players) {
			if (pl.username.equals(name)) {
				double dx = pl.x - pos.x - 0.5, dz = pl.z - pos.z - 0.5;
				return dx * dx + dz * dz <= 24 * 24;
			}
		}
		return false;
	}

	Msgs.State stateMsg() {
		Msgs.State m = new Msgs.State();
		m.pos = pos;
		m.powered = powered;
		m.noteMode = noteMode;
		m.rom = rom;
		m.crc = crc;
		m.controller = controller;
		m.guests = guests.clone();
		m.epoch = epoch;
		m.alarm = alarm;
		m.tvHost = tvHost;
		m.tvWeb = tvWeb;
		m.tvApp = tvApp;
		m.volume = volume;
		com.btemu.TileEntityMonitor t = kind.wooden ? te() : null;
		m.fuelSecs = t == null ? -1 : t.fuelTicksRemaining() / 20;
		m.roms = new ArrayList<>(ls.menu.roms());
		return m;
	}

	void broadcastState() {
		NetworkHandler.sendToAllAround(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, RADIUS, dim(), stateMsg());
	}

	void broadcastSnapshot(Player to) {
		if (!powered) return;
		byte[] snap;
		try {
			snap = ls.snapshot();
		} catch (Exception e) {
			BtEmu.LOGGER.error("Snapshot failed", e);
			return;
		}

		List<Player> targets = new ArrayList<>();
		if (to != null) targets.add(to);
		else for (Player pl : world.players) {
			double dx = pl.x - pos.x - 0.5, dy = pl.y - pos.y - 0.5, dz = pl.z - pos.z - 0.5;
			if (dx * dx + dy * dy + dz * dz <= RADIUS * RADIUS) targets.add(pl);
		}
		for (Player pl : targets)
			com.btemu.net.BulkSend.cancel(pl, q -> q instanceof Msgs.Snapshot s && s.pos.equals(pos));
		Msgs.chunks(snap, parts -> {
			for (int i = 0; i < parts.length; i++) {
				Msgs.Snapshot m = new Msgs.Snapshot();
				m.pos = pos;
				m.epoch = epoch;
				m.index = i;
				m.count = parts.length;
				m.data = parts[i];
				for (Player pl : targets) com.btemu.net.BulkSend.send(pl, m);
			}
		});
	}

	byte[] persistentState() {
		if (!powered) return null;
		try {

			byte[] s = null;
			if (ls.skipCoreFrames && ls.gb instanceof com.btemu.emu.RetroCore && Sessions.localSnapshot != null)
				s = Sessions.localSnapshot.apply(pos);
			if (s == null) s = ls.snapshot();
			return s;
		} catch (Exception e) {
			return null;
		}
	}
}
