package com.btemu.client;

import com.btemu.BtEmu;
import com.btemu.MonitorGroup;
import com.btemu.MonitorKind;
import com.btemu.MonitorLight;
import com.btemu.TileEntityMonitor;
import com.btemu.gb.GameBoy;
import com.btemu.net.Msgs;
import com.btemu.session.Lockstep;
import com.btemu.session.MonitorMenu;
import com.btemu.session.RomStore;
import net.minecraft.client.Minecraft;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.TilePos;
import turniplabs.halplibe.helper.network.NetworkHandler;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

final class Display {
	private static final Map<TilePos, Display> ALL = new ConcurrentHashMap<>();

	static final ConcurrentLinkedQueue<Integer> TEXTURES_TO_FREE = new ConcurrentLinkedQueue<>();

	private static final Map<TilePos, Long> LAST_SYNC_ASK = new HashMap<>();

	private static final Map<Long, byte[][]> ROM_PARTS = new HashMap<>();
	private static final Map<Long, Long> ROM_ASKED = new HashMap<>();

	final TilePos pos;

	volatile TileEntityMonitor te;
	final World world;
	final MonitorKind kind;
	final Thread thread;
	volatile boolean running = true;
	volatile long lastHeartbeat = System.currentTimeMillis();

	volatile String rom = "";
	volatile long crc;
	volatile boolean noteMode;
	volatile String controller = "";

	volatile int volume = 7;
	volatile long volumeShownNs = 0;

	volatile int extraKeys = 0;

	volatile int fuelSecs = -1;

	volatile String tvHost = "";

	private com.btemu.client.tv.TvBrowser browser;

	private volatile com.btemu.client.tv.WebWindow webWin;
	private int lastBrowserFrame = -1;

	private final com.btemu.client.tv.TvAudio viewAudio = new com.btemu.client.tv.TvAudio();

	final Object tvLock = new Object();
	int[] tvPixels = new int[0];

	int[] tvClean = new int[0];
	private boolean tvCleanNew = false, tvGlitched = false, tvBarShown = false;
	private final GlitchFx tvGlitch = new GlitchFx();
	volatile int tvW = 0, tvH = 0, tvCounter = 0;

	int tvTexId = 0, tvTexW = 0, tvTexH = 0, tvUploaded = -1;
	private long lastStreamNs = 0;
	private final float[] tvScratch = new float[4800 * 2];
	private final java.io.ByteArrayOutputStream tvAudioOut = new java.io.ByteArrayOutputStream();
	private long lastAudioSendNs = 0;

	volatile boolean bigPicture = false;

	float cursorX = -1, cursorY = -1;

	volatile boolean tvWeb;

	volatile boolean pano;
	volatile float panoTop;

	volatile int tvApp;

	boolean tvActive() {
		return !tvHost.isEmpty();
	}

	volatile boolean alarm = false;
	private volatile long alarmSinceNs = 0;
	volatile int epoch = -1;
	volatile List<String> roms = new ArrayList<>();

	final Object lock = new Object();
	private Lockstep ls;
	private volatile boolean ready = false;

	private volatile boolean blocked = false;
	private volatile long bound = 0;

	private final TreeMap<Long, Integer> early = new TreeMap<>();
	private byte[][] snapParts;
	private byte[] pendingSnap;
	private byte[] romBytes;

	volatile int inputMask = 0;
	volatile boolean menuKey = false;
	private int lastSentMask = -1;

	private final int[] screen = new int[GameBoy.WIDTH * GameBoy.HEIGHT];
	final ScreenEffects fx;
	private final MonitorAudio audio = new MonitorAudio();
	private final float[] audioScratch = new float[4800 * 2];
	volatile int fxCounter = 0;
	private final int[] plainWork;
	final int[] plainOut;

	static final int RING = 6;
	final int[][] ring;
	final int[] ringSeq = new int[RING];
	volatile float avgR, avgG, avgB;

	volatile float wetness = 0f;
	private float wetTarget = 0f;
	private final GlitchFx glitch = new GlitchFx();
	private final java.util.Random glitchRng = new java.util.Random();

	private volatile boolean tainted = false;
	private volatile boolean resyncing = false;

	volatile long bootNs = System.nanoTime();
	volatile long offNs = 0;

	int plainTexId = 0;

	int structTex = 0, structTexW = 0, structTexH = 0;
	long structKey = Long.MIN_VALUE;
	int[] structSrc;
	final int[] ringTex = new int[RING], ringTexSeq = new int[RING];
	double playhead = -1;
	long lastPlayNs = 0;
	float subpixelAlpha = 0f;
	long lastAlphaNs = 0;

	int panelTexId = 0, panelW = 0, panelH = 0;
	long panelKey = -1;
	int[] panelBuf = new int[0];

	volatile java.util.concurrent.Future<?> panelJob;
	volatile TileEntityRendererMonitor.PanelResult panelReady;
	long panelRequested = -1;

	java.nio.IntBuffer panelUpload, tvUpload;
	int uploadedCounter = -1;
	private int lightLevel = -1;

	volatile MonitorGroup group;

	private Display(TileEntityMonitor te, MonitorKind kind) {
		this.pos = new TilePos(te.tilePos.x, te.tilePos.y, te.tilePos.z);
		this.te = te;
		this.world = te.worldObj;
		this.kind = kind;
		this.fx = new ScreenEffects(kind.scale >= 6, kind.wooden);
		this.plainWork = new int[fx.PLAIN_W * fx.PLAIN_H];
		this.plainOut = new int[fx.PLAIN_W * fx.PLAIN_H];
		this.ring = new int[RING][fx.PLAIN_W * fx.PLAIN_H];
		this.thread = new Thread(this::run, "BTEmu-monitor@" + pos);
		this.thread.setDaemon(true);
	}

	static java.util.Collection<Display> all() {
		return ALL.values();
	}

	static byte[] localSnapshot(TilePos p) {
		Display d = ALL.get(p);
		if (d == null) return null;
		synchronized (d.lock) {
			if (!d.ready || d.ls == null) return null;
			try {
				return d.ls.snapshot();
			} catch (Exception e) {
				return null;
			}
		}
	}

	static Display get(TilePos p) {
		return ALL.get(p);
	}

	double displayFps() {
		return kind.fpsCap > 0 ? Math.min(kind.fpsCap, 59.7275) : 59.7275;
	}

	boolean shuttingDown() {
		return offNs != 0;
	}

	boolean isMine() {
		Minecraft mc = Minecraft.getMinecraft();
		return mc.thePlayer != null && mc.thePlayer.username != null && mc.thePlayer.username.equals(controller);
	}

	long offAnimMs() {
		return kind.crt ? 450 : 160;
	}

	void startPowerOff() {
		if (offNs != 0) return;
		offNs = System.nanoTime();
		audio.cut();
		if (MonitorInput.active() == this) MonitorInput.release(false);
	}

	void dispose() {
		if (offNs == 0) audio.cut();
		running = false;
		ALL.remove(pos, this);
		if (MonitorInput.active() == this) MonitorInput.release(false);
	}

	static void onState(Msgs.State m) {
		Display d = ALL.get(m.pos);
		if (!m.powered) {
			LAST_SYNC_ASK.put(m.pos, System.currentTimeMillis());
			if (d != null) d.startPowerOff();
			return;
		}
		if (d != null && d.shuttingDown()) {
			d.dispose();
			d = null;
		}
		if (d == null) {
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.currentWorld == null || !(mc.currentWorld.getTileEntity(m.pos) instanceof TileEntityMonitor te)) return;
			MonitorKind kind = te.kind();
			if (kind == null) return;
			d = new Display(te, kind);
			ALL.put(d.pos, d);
			d.thread.start();
		}
		d.applyState(m);
	}

	private void applyState(Msgs.State m) {
		noteMode = m.noteMode;
		if (m.volume != volume) {
			volume = m.volume;
			volumeShownNs = System.nanoTime();
		}
		fuelSecs = m.fuelSecs;
		if (m.alarm && !alarm) alarmSinceNs = System.nanoTime();
		alarm = m.alarm;
		tvHost = m.tvHost == null ? "" : m.tvHost;
		tvWeb = m.tvWeb;
		tvApp = m.tvApp;
		roms = m.roms;
		com.btemu.emu.CoreStore.prefetch(m.roms);
		controller = m.controller;
		if (m.epoch != epoch) {
			synchronized (lock) {
				epoch = m.epoch;
				ready = false;
				blocked = false;
				early.clear();
				snapParts = null;
				pendingSnap = null;
				bound = 0;
			}
			rom = m.rom;
			crc = m.crc;
			romBytes = null;
			if (!rom.isEmpty()) {
				romBytes = RomStore.findByCrc(rom, crc);
				if (romBytes == null) requestRom(crc);
			}
		}

		if (isMine() && !shuttingDown()) {
			if (MonitorInput.active() != this) MonitorInput.capture(this);
		} else if (MonitorInput.active() == this) {
			MonitorInput.release(false);
		}
		tryStart();
	}

	static void onFrames(Msgs.Frames m) {
		Display d = ALL.get(m.pos);
		if (d == null || d.epoch != m.epoch) return;
		synchronized (d.lock) {
			for (int i = 0; i < m.frames.length; i++) {
				if (d.ready) d.ls.inputs.put(m.frames[i], m.masks[i]);
				else d.early.put(m.frames[i], m.masks[i]);
			}
			d.bound = Math.max(d.bound, m.bound);
		}
	}

	static void onSnapshot(Msgs.Snapshot m) {
		Display d = ALL.get(m.pos);
		if (d == null || d.epoch != m.epoch) return;
		if (d.ready && !d.resyncing) return;
		synchronized (d.lock) {
			if (d.snapParts == null || d.snapParts.length != m.count) d.snapParts = new byte[m.count][];
			d.snapParts[m.index] = m.data;
			for (byte[] part : d.snapParts) if (part == null) return;
			ByteArrayOutputStream bo = new ByteArrayOutputStream();
			for (byte[] part : d.snapParts) bo.writeBytes(part);
			d.snapParts = null;
			d.pendingSnap = bo.toByteArray();
		}
		d.tryStart();
	}

	static void onRomData(Msgs.RomData m) {
		byte[][] parts = ROM_PARTS.computeIfAbsent(m.crc, k -> new byte[m.count][]);
		if (parts.length != m.count) return;
		parts[m.index] = m.data;
		for (byte[] p : parts) if (p == null) return;
		ROM_PARTS.remove(m.crc);
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		for (byte[] p : parts) bo.writeBytes(p);
		byte[] rom = bo.toByteArray();
		if (RomStore.crc(rom) != m.crc) return;
		RomStore.cache(m.crc, rom);
		for (Display d : ALL.values()) {
			if (d.crc == m.crc && d.romBytes == null) {
				d.romBytes = rom;
				d.tryStart();
			}
		}
	}

	private static void requestRom(long crc) {
		Long t = ROM_ASKED.get(crc);
		if (t != null && System.currentTimeMillis() - t < 10_000) return;
		ROM_ASKED.put(crc, System.currentTimeMillis());
		NetworkHandler.sendToServer(new Msgs.RomRequest(crc));
	}

	private void tryStart() {
		synchronized (lock) {
			if ((ready && !resyncing) || pendingSnap == null) return;
			if (!rom.isEmpty() && romBytes == null) return;
			try {
				Lockstep l = new Lockstep();
				l.rom = rom;
				l.soundLocked = kind.forcedNoteBlocks;
				l.menu.tvAllowed = !kind.wooden;
				l.menu.setRoms(roms);
				l.restore(pendingSnap, rom.isEmpty() ? null : romBytes, null);
				for (Map.Entry<Long, Integer> e : early.entrySet()) if (e.getKey() >= l.frame) l.inputs.put(e.getKey(), e.getValue());
				early.clear();
				if (l.gb instanceof GameBoy g) {
					NoteSamples.apply(g.apu.notes);
					g.apu.noteMode = noteMode;
				}

				if (ls != null && ls.gb != null) ls.gb.close();
				ls = l;
				pendingSnap = null;
				ready = true;
				blocked = false;
				resyncing = false;
			} catch (Exception e) {
				BtEmu.LOGGER.error("Couldn't start monitor at {} from snapshot", pos, e);
				pendingSnap = null;
			}
		}
	}

	static void onBlockRemoved(World world, TilePos p) {
		Display d = ALL.get(p);
		if (d != null && d.world == world) d.dispose();
	}

	private static final boolean AUTOTEST_VIEW = System.getProperty("btemu.autotestView") != null;
	private static long autotestNext;

	private static void autotestLog() {
		long now = System.currentTimeMillis();
		if (now < autotestNext) return;
		autotestNext = now + 2000;
		for (Display d : ALL.values()) {
			int lit = 0;
			for (int v : d.screen) if ((v & 0xF0F0F0) != 0) lit++;
			int[] tv = d.tvPixels;
			int tvLit = 0;
			for (int i = 0; i < tv.length; i += 7) if ((tv[i] & 0xF0F0F0) != 0) tvLit++;
			com.btemu.BtEmu.LOGGER.info("AUTOTEST view {} | rom={} controller={} tvHost={} tvWeb={} tvApp={} screenLit={}/{} tv={}x{} tvLitSample={}/{} tvCounter={} mine={}",
					d.pos, d.rom, d.controller, d.tvHost, d.tvWeb, d.tvApp, lit, d.screen.length, d.tvW, d.tvH, tvLit, tv.length / 7, d.tvCounter,
					d.webWin != null || d.browser != null);
		}
	}

	static void heartbeat(TileEntityMonitor te) {
		if (AUTOTEST_VIEW) {
			autotestLog();

			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
			MonitorGroup g = GroupCache.get(te);
			if (mc.thePlayer != null && g.isOrigin(te.tilePos)) {
				double cx = g.cx - mc.thePlayer.x;
				double cy = g.cy - (mc.thePlayer.y + mc.thePlayer.getHeadHeight());
				double cz = g.cz - mc.thePlayer.z;
				mc.thePlayer.yRot = (float) Math.toDegrees(Math.atan2(-cx, cz));
				mc.thePlayer.xRot = (float) -Math.toDegrees(Math.atan2(cy, Math.sqrt(cx * cx + cz * cz)));
			}
		}
		MonitorGroup g = GroupCache.get(te);
		if (!g.isOrigin(te.tilePos)) {
			Display stale = ALL.get(te.tilePos);
			if (stale != null) stale.dispose();
			return;
		}
		Display d = ALL.get(te.tilePos);
		if (d != null && d.te != te) {
			if (d.te.isInvalid() && d.world == te.worldObj) d.te = te;
			else { d.dispose(); d = null; }
		}
		if (d == null) {
			TilePos key = new TilePos(te.tilePos.x, te.tilePos.y, te.tilePos.z);
			Long asked = LAST_SYNC_ASK.get(key);
			long now = System.currentTimeMillis();

			if (asked == null || now - asked > (te.powered ? 2000 : 10_000)) {
				LAST_SYNC_ASK.put(key, now);
				NetworkHandler.sendToServer(new Msgs.Sync(key));
			}
			setLight(te, g, 0);
			return;
		}
		d.lastHeartbeat = System.currentTimeMillis();
		d.group = g;
		if (d.shuttingDown()) {
			if ((System.nanoTime() - d.offNs) / 1_000_000L >= d.offAnimMs()) {
				d.dispose();
				setLight(te, g, 0);
				return;
			}
			d.updateLight(te, g);
			return;
		}
		MonitorInput.tickGaze();
		d.updateWetness(te, g);
		d.audio.speakers = d.refreshSpeakers(te.worldObj, g);
		d.audio.configure(g, d.kind);

		float top = d.kind.maxVolume() * 10f / 7f;
		float exp = 1.5f + 1.5f * d.kind.maxVolume();
		d.audio.volume = top * (float) Math.pow(d.volume / 10f, exp);
		d.audio.updateListener(g);

		if (!d.ready && d.pendingSnap == null) {
			Long asked = LAST_SYNC_ASK.get(d.pos);
			long now = System.currentTimeMillis();
			if (asked == null || now - asked > 3000) {
				LAST_SYNC_ASK.put(d.pos, now);
				NetworkHandler.sendToServer(new Msgs.Sync(d.pos));
			}
		}
		boolean booting = System.nanoTime() - d.bootNs < 700_000_000L;
		if (booting || te.worldObj.getWorldTime() % 5 == 0 || d.lightLevel < 0) d.updateLight(te, g);
	}

	private String wetSeen, lastWetWhy;

	private volatile float[] wetMask = {1f};
	private volatile int wetMaskW = 1, wetMaskH = 1;
	private int[] cleanCopy = new int[0];

	private void localGlitch(GlitchFx fx, int[] px, int w, int h, float wet, long nowNs) {
		float[] m = wetMask;
		int mw = wetMaskW, mh = wetMaskH;
		boolean whole = true;
		for (float v : m) if (v < 0.999f) whole = false;
		if (whole || wet <= 0.02f || m.length != mw * mh) {
			fx.apply(px, w, h, kind, wet, nowNs);
			return;
		}
		if (cleanCopy.length < w * h) cleanCopy = new int[w * h];
		System.arraycopy(px, 0, cleanCopy, 0, w * h);
		fx.apply(px, w, h, kind, wet, nowNs);
		for (int y = 0; y < h; y++) {

			float fy = (1f - (y + 0.5f) / h) * mh - 0.5f;
			int j0 = Math.max(0, Math.min(mh - 1, (int) Math.floor(fy))), j1 = Math.min(mh - 1, j0 + 1);
			float ty = Math.max(0f, Math.min(1f, fy - j0));
			for (int x = 0; x < w; x++) {
				float fx2 = (x + 0.5f) / w * mw - 0.5f;
				int i0 = Math.max(0, Math.min(mw - 1, (int) Math.floor(fx2))), i1 = Math.min(mw - 1, i0 + 1);
				float tx = Math.max(0f, Math.min(1f, fx2 - i0));
				float a = (m[j0 * mw + i0] * (1 - tx) + m[j0 * mw + i1] * tx) * (1 - ty)
						+ (m[j1 * mw + i0] * (1 - tx) + m[j1 * mw + i1] * tx) * ty;
				if (a >= 0.999f) continue;
				int k = y * w + x, c = cleanCopy[k];
				if (a <= 0.001f) { px[k] = c; continue; }
				int gch = px[k], ia = (int) (a * 256), ic = 256 - ia;
				px[k] = 0xFF000000 | ((((gch >> 16) & 0xFF) * ia + ((c >> 16) & 0xFF) * ic) >> 8 << 16)
						| ((((gch >> 8) & 0xFF) * ia + ((c >> 8) & 0xFF) * ic) >> 8 << 8)
						| (((gch & 0xFF) * ia + (c & 0xFF) * ic) >> 8);
			}
		}
	}

	private void updateWetness(TileEntityMonitor te, MonitorGroup g) {
		if (kind.scale >= 6) return;
		World w = te.worldObj;
		{
			int touching = 0, faces = 0;
			boolean rain = false;
			float[] cellWet = new float[g.w * g.h];
			var weather = w.getCurrentWeather();
			boolean raining = weather != null && weather.isDamp() && w.getWeatherManager().getWeatherIntensity() > 0.05f;
			int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
			for (MonitorGroup.Face fc : g.faces)
			for (int fi = 0; fi < fc.w; fi++)
				for (int fj = 0; fj < fc.h; fj++) {
					TilePos c = fc.cell(fi, fj);
					int i = fc.cu + fi, j = fc.cv + fj;
					int mine = 0;
					for (int[] dd : dirs) {
						faces++;
						if (w.getBlockMaterial(new TilePos(c.x + dd[0], c.y + dd[1], c.z + dd[2])) == net.minecraft.core.block.material.Materials.WATER) {
							touching++;
							mine++;
							if (wetSeen == null) wetSeen = (c.x + dd[0]) + " " + (c.y + dd[1]) + " " + (c.z + dd[2]);
						}
					}
					if (raining && (fc.fy == 0 ? fj == fc.h - 1 : fc.fy > 0) && w.canBlockSeeTheSky(c.x, c.y + 1, c.z)) rain = true;

					cellWet[j * g.w + i] = mine == 0 ? 0f : mine >= 3 ? 1f : 0.3f + 0.25f * (mine - 1);
				}
			float frac = faces == 0 ? 0 : touching / (float) faces;

			float water = touching == 0 ? 0f : frac > 0.6f ? 1f : Math.max(0.1f, Math.min(0.55f, frac * 2.5f));
			float rainLv = rain ? 0.22f : 0f;
			wetTarget = Math.max(water, rainLv);

			float top = 0f;
			for (float v : cellWet) top = Math.max(top, v);
			float[] mask = new float[cellWet.length];
			for (int k = 0; k < mask.length; k++) {
				float local = top > 0 ? cellWet[k] / top * water : 0f;
				mask[k] = wetTarget <= 0 ? 1f : Math.min(1f, Math.max(local, rainLv) / wetTarget);
			}
			wetMask = mask;
			wetMaskW = g.w;
			wetMaskH = g.h;

			String why = touching > 0 ? "water at " + wetSeen : rain ? "rain" : null;
			if (why != null && !why.equals(lastWetWhy)) com.btemu.BtEmu.LOGGER.info("Monitor at {} is wet: {}", pos, why);
			lastWetWhy = why;
			wetSeen = null;
		}

		float fuel = 0f;
		int fs = fuelSecs;
		if (kind.wooden && fs >= 0 && fs < 60) fuel = 0.15f + 0.5f * (1f - fs / 60f);
		float target = Math.max(wetTarget, fuel);

		float wet = wetness;
		wet = target > wet ? Math.min(target, wet + 0.05f) : target;
		wetness = wet;
		audio.glitchWet = wet;
		audio.glitchCrt = kind.crt;
		if (wet <= 0.01f && tainted && !resyncing) {

			tainted = false;
			resyncing = true;
			LAST_SYNC_ASK.put(pos, System.currentTimeMillis());
			NetworkHandler.sendToServer(new Msgs.Sync(pos));
		}
	}

	void sendInput() {
		if (!isMine()) return;
		int mask = inputMask | (menuKey ? MonitorMenu.BTN_MENU : 0) | extraKeys;
		if (mask != lastSentMask) {
			lastSentMask = mask;
			NetworkHandler.sendToServer(new Msgs.Input(pos, mask));
		}
	}

	void resetSentInput() {
		lastSentMask = -1;
	}

	private void updateLight(TileEntityMonitor te, MonitorGroup g) {
		int level = currentLightLevel();
		if (level != lightLevel) {
			lightLevel = level;
			setLight(te, g, level);
		}
	}

	private int currentLightLevel() {
		long now = System.nanoTime();
		float lum = (0.299f * avgR + 0.587f * avgG + 0.114f * avgB) / 255f;
		int max = kind.maxEmission;
		float bootMs = (now - bootNs) / 1e6f;
		float offMs = offNs != 0 ? (now - offNs) / 1e6f : -1f;
		if (kind.crt) {
			float t = offMs >= 0 ? offMs : (bootMs < 450 ? 450 - bootMs : -1);
			if (t >= 0) {
				float p = Math.min(1f, t / 200f);
				float area = t < 200 ? 1f - p * p * (3 - 2 * p) : 0f;
				float line = t < 350 ? 0.12f : 0.12f * (1f - Math.min(1f, (t - 350) / 100f));
				return Math.round(max * Math.min(1f, lum * area + line));
			}
		} else {
			if (offMs >= 0) return offMs < 120 ? max : 0;
			if (bootMs < 500) return Math.min(1, max);
			if (bootMs < 650) return Math.max(1, Math.round(lum * max * (bootMs - 500) / 150f));
		}
		return Math.round(lum * max);
	}

	private static void setLight(TileEntityMonitor te, MonitorGroup g, int level) {
		for (TilePos c : g.cells()) MonitorLight.set(te.worldObj, c.x, c.y, c.z, level);
	}

	private void run() {
		final double GB_FRAME_NS = 16_742_706.0;
		long lastFx = 0;
		long dormantSince = 0;
		long next = System.nanoTime();
		try {
			while (running) {
				Minecraft mc = Minecraft.getMinecraft();
				if (mc == null || mc.currentWorld != world) break;
				if (te.isInvalid()) {

					if (dormantSince == 0) dormantSince = System.currentTimeMillis();
					if (System.currentTimeMillis() - dormantSince > 30 * 60_000L) break;
					Thread.sleep(100);
					continue;
				}
				dormantSince = 0;
				if (offNs != 0) {
					endTv();
					audio.cut();
					Thread.sleep(10);
					continue;
				}
				if (alarm) {

					drawHighHeat();
					present();
					Thread.sleep(33);
					continue;
				}
				if (tvActive()) {
					tvStep();
					Thread.sleep(16);
					next = System.nanoTime();
					continue;
				}
				if (browser != null || webWin != null || (tvW != 0 && !bigPicture)) endTv();
				if (!ready || blocked || System.currentTimeMillis() - lastHeartbeat > 1000) {

					if ((blocked || (!ready && !rom.isEmpty())) && System.nanoTime() - lastLoadingNs > 100_000_000L) {
						lastLoadingNs = System.nanoTime();
						bigPicture = false;
						drawLoading();
						present();
					}
					Thread.sleep(5);
					next = System.nanoTime();
					continue;
				}

				long lead;
				MonitorMenu.Action a = MonitorMenu.Action.NONE;
				boolean ran = false;
				synchronized (lock) {
					lead = bound - ls.frame;
					if (lead > 0) {
						if (ls.gb != null) ls.gb.setNoteMode(noteMode);
						a = ls.step();
						ran = true;
						compose();
					}
				}
				if (!ran) {

					Thread.sleep(2);
					next = System.nanoTime();
					continue;
				}
				float wet = wetness;
				if (wet > 0.05f && ls.gb != null && !resyncing) {

					float rate = wet * wet * (kind.crt ? 3f : 5f);
					int n = (int) rate + (glitchRng.nextFloat() < rate - (int) rate ? 1 : 0);
					if (n > 0) {
						synchronized (lock) {
							ls.gb.glitchFlip(glitchRng, n, wet > 0.85f);
						}

						if (!(ls.gb instanceof com.btemu.emu.RetroCore && turniplabs.halplibe.helper.EnvironmentHelper.isSinglePlayer())) tainted = true;
					}
				}
				if (a == MonitorMenu.Action.SELECT) loadingName = ls.menu.chosen;
				if (MonitorMenu.appId(a) != 0) loadingName = MonitorMenu.appName(MonitorMenu.appId(a));
				if (a == MonitorMenu.Action.SELECT || a == MonitorMenu.Action.EJECT || a == MonitorMenu.Action.TV || a == MonitorMenu.Action.WEB || MonitorMenu.appId(a) != 0) {

					blocked = true;
				}
				long nowNs = System.nanoTime();
				if (kind.fpsCap <= 0 || nowNs - lastFx >= 1_000_000_000L / kind.fpsCap) {
					lastFx = nowNs;
					present();
				}
				com.btemu.emu.Machine g = ls.gb;
				if (g != null) {
					if (ls.menu.open) g.drainAudio(audioScratch);
					else audio.pumpNonBlocking(g);
				}

				if (lead > 12) {
					next = System.nanoTime();
					continue;
				}
				double frameNs = g != null ? g.frameNs() : GB_FRAME_NS;
				double period = frameNs * (lead <= 2 ? 1.08 : lead > 8 ? 0.92 : 1.0);
				next += (long) period;
				long wait = next - System.nanoTime();
				if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
				else if (wait < -frameNs * 10) next = System.nanoTime();
			}
		} catch (InterruptedException ignored) {
		} catch (Throwable t) {
			BtEmu.LOGGER.error("Monitor at {} crashed", pos, t);
		} finally {
			running = false;
			ALL.remove(pos, this);
			if (MonitorInput.active() == this) MonitorInput.release(false);
			endTv();
			synchronized (lock) {
				if (ls != null && ls.gb != null) ls.gb.close();
			}
			audio.close();
			if (tvTexId != 0) TEXTURES_TO_FREE.add(tvTexId);
			if (panelTexId != 0) TEXTURES_TO_FREE.add(panelTexId);
			if (plainTexId != 0) TEXTURES_TO_FREE.add(plainTexId);
			if (structTex != 0) TEXTURES_TO_FREE.add(structTex);
			for (int t : ringTex) if (t != 0) TEXTURES_TO_FREE.add(t);
		}
	}

	private String corrupt(String label, List<MonitorMenu.Row> rows, int i) {
		float wet = wetness;
		if (wet <= 0.05f || kind.scale >= 6) return label;
		if (glitchRng.nextFloat() < wet * 0.04f) {
			MonitorMenu.Row other = rows.get(glitchRng.nextInt(rows.size()));
			label = other.label();
		}
		char[] c = label.toCharArray();
		for (int k = 0; k < c.length; k++) {
			if (glitchRng.nextFloat() < wet * wet * 0.12f) c[k] = (char) (33 + glitchRng.nextInt(94));
		}
		return new String(c);
	}

	private long lastLoadingNs = 0;
	private volatile String loadingName = "";

	static java.util.List<java.util.Set<TilePos>> runningCells(World w) {
		java.util.List<java.util.Set<TilePos>> out = new java.util.ArrayList<>();
		for (Display d : ALL.values()) {
			MonitorGroup g = d.group;
			if (d.world == w && g != null && !d.shuttingDown()) out.add(new java.util.HashSet<>(g.cells()));
		}
		return out;
	}

	private java.util.List<double[]> refreshSpeakers(World w, MonitorGroup g) {
		java.util.Set<TilePos> cells = new java.util.HashSet<>(g.cells());
		java.util.List<java.util.Set<TilePos>> running = runningCells(w);
		java.util.List<double[]> out = new java.util.ArrayList<>();
		java.util.List<MonitorMenu.Speaker> menuList = new java.util.ArrayList<>();

		for (com.btemu.Speakers.Group sg : com.btemu.Speakers.nearby(w, cells)) {
			boolean on = com.btemu.Speakers.playsFor(sg, cells, running);
			menuList.add(new MonitorMenu.Speaker(sg.pos(), sg.label(), on));
			if (on) out.add(new double[]{sg.cx, sg.cy, sg.cz, sg.golden ? 1 : 0, sg.size()});
		}

		synchronized (lock) {
			if (ls != null) synchronized (ls) { ls.menu.setSpeakers(menuList); }
		}
		return out;
	}

	private void drawLoading() {
		final int W = GameBoy.WIDTH, H = GameBoy.HEIGHT;
		java.util.Arrays.fill(screen, 0xFF000000);
		int dots = (int) ((System.nanoTime() / 400_000_000L) % 4);
		String t = "Loading" + ".".repeat(dots);
		MonitorFont.draw(screen, W, H, t, (W - MonitorFont.width("Loading...")) / 2, H / 2 - 8, 0xFFE0E0E0);
		String name = tvWeb && tvApp != 0 ? MonitorMenu.appName(tvApp) : blocked ? loadingName : rom;
		if (name != null && !name.isEmpty()) {
			int dot = name.lastIndexOf('.');
			String n = MonitorFont.fit(dot > 0 ? name.substring(0, dot) : name, W - 12);
			MonitorFont.draw(screen, W, H, n, (W - MonitorFont.width(n)) / 2, H / 2 + 6, 0xFF808080);
		}
	}

	private void drawHighHeat() {
		java.util.Arrays.fill(screen, 0xFF000000);
		long ms = (System.nanoTime() - alarmSinceNs) / 1_000_000L;
		if ((ms / 320) % 2 == 0) {
			String t = "HIGH HEAT";
			int w = MonitorFont.width(t);

			int[] small = new int[GameBoy.WIDTH * GameBoy.HEIGHT];
			java.util.Arrays.fill(small, 0);
			MonitorFont.draw(small, GameBoy.WIDTH, GameBoy.HEIGHT, t, 0, 0, 0xFFFF3020);
			int ox = (GameBoy.WIDTH - w * 2) / 2, oy = (GameBoy.HEIGHT - 16) / 2;
			for (int y = 0; y < 8; y++)
				for (int x = 0; x < w; x++) {
					int c = small[y * GameBoy.WIDTH + x];
					if (c == 0) continue;
					for (int dy = 0; dy < 2; dy++)
						for (int dx = 0; dx < 2; dx++) {
							int px = ox + x * 2 + dx, py = oy + y * 2 + dy;
							if (px >= 0 && px < GameBoy.WIDTH && py >= 0 && py < GameBoy.HEIGHT) screen[py * GameBoy.WIDTH + px] = c;
						}
				}
		}
	}

	private void tvStep() {
		Minecraft mc = Minecraft.getMinecraft();
		boolean host = mc.thePlayer != null && tvHost.equals(mc.thePlayer.username);
		if (host) {
			com.btemu.client.tv.Chromium.ensure();
			if (com.btemu.client.tv.Chromium.state != com.btemu.client.tv.Chromium.Phase.READY) {
				if (tvWeb && tvApp != 0) drawLoading();
				else drawTvStatus(chromiumStatus());
				present();
				return;
			}
			if ((tvWeb && browser != null) || (!tvWeb && webWin != null)) endTv();
			if (tvWeb && webWin != null && !java.util.Objects.equals(webWin.appUrl(), com.btemu.client.tv.WebWindow.appUrlFor(tvApp))) endTv();
			int fc;
			com.btemu.client.tv.TvAudio srcAudio;
			if (tvWeb) {

				MonitorGroup g = group;
				int[] sz = com.btemu.client.tv.WebWindow.sizeFor(g == null ? 2 : g.w, g == null ? 1 : g.h, SubpixelImage.perBlock(kind));
				if (webWin == null) {
					webWin = tvApp != 0 ? com.btemu.client.tv.WebWindow.openApp(sz[0], sz[1], com.btemu.client.tv.WebWindow.appUrlFor(tvApp)) : com.btemu.client.tv.WebWindow.open(sz[0], sz[1]);
					lastBrowserFrame = -1;
				} else {
					webWin.resize(sz[0], sz[1]);
				}
				webWin.mediaTier = WebcamFeed.mediaTier(kind);
				webWin.setHolo(g != null && g.holo());
				webWin.tick();
				if (WebInput.dirty) {
					WebInput.dirty = false;
					synchronized (tvLock) {
						tvCleanNew = true;
					}
				}
				com.btemu.client.tv.WebWindow ww = webWin;
				fc = ww.frameCounter;
				if (fc != lastBrowserFrame) {
					lastBrowserFrame = fc;
					synchronized (tvLock) {
						synchronized (ww.frameLock()) {
							int[] f = ww.frame;
							if (tvClean.length != f.length) tvClean = new int[f.length];
							System.arraycopy(f, 0, tvClean, 0, f.length);
							tvW = ww.w;
							tvH = ww.h;
						}
						pano = ww.pano();
						panoTop = ww.h == 0 ? 0 : ww.bar / (float) ww.h;
						tvCleanNew = true;
					}
				}
				srcAudio = ww.audio;
			} else {
				if (browser == null) {
					browser = com.btemu.client.tv.TvBrowser.open();
					lastBrowserFrame = -1;
				}
				browser.buttons(inputMask);
				fc = browser.frameCounter;
				if (fc != lastBrowserFrame) {
					lastBrowserFrame = fc;
					synchronized (tvLock) {
						if (tvClean.length != browser.frame.length) tvClean = new int[browser.frame.length];
						synchronized (browser.frame) {
							System.arraycopy(browser.frame, 0, tvClean, 0, tvClean.length);
						}
						tvW = com.btemu.client.tv.TvBrowser.W;
						tvH = com.btemu.client.tv.TvBrowser.H;
						tvCleanNew = true;
					}
				}
				srcAudio = browser.audio;
			}
			refreshTvPicture();
			if (tvW == 0) {
				if (tvWeb && tvApp != 0) drawLoading();
				else drawTvStatus(tvWeb ? "Loading the web browser..." : "Loading YouTube TV...");
				present();
			}
			int n = srcAudio.drain(tvScratch);

			if (turniplabs.halplibe.helper.EnvironmentHelper.isMultiplayerClient()) stream(n);
			scaleTv(n);
			audio.pushFrames(tvScratch, n);
		} else {
			refreshTvPicture();
			int n = viewAudio.drain(tvScratch);
			scaleTv(n);
			audio.pushFrames(tvScratch, n);
			if (tvW == 0) {
				drawTvStatus("Watching " + tvHost + (tvWeb ? "'s browser..." : "'s YouTube TV..."));
				present();
			}
		}
	}

	private void refreshTvPicture() {
		float wet = wetness;
		boolean glitch = wet > 0.02f && kind.scale < 6;
		synchronized (tvLock) {
			if (tvClean.length == 0 || tvW == 0) return;

			boolean bar = volumeBarAlpha() > 0;
			if (!tvCleanNew && !glitch && !tvGlitched && !bar && !tvBarShown) return;
			tvBarShown = bar;
			if (tvPixels.length != tvClean.length) tvPixels = new int[tvClean.length];
			System.arraycopy(tvClean, 0, tvPixels, 0, tvClean.length);
			paintVolumeBar(tvPixels, tvW, tvH);
			if (tvWeb && webWin != null) WebInput.paintCursor(this, tvPixels, tvW, tvH);
			if (glitch) localGlitch(tvGlitch, tvPixels, tvW, tvH, wet, System.nanoTime());
			else tvGlitch.apply(tvPixels, tvW, tvH, kind, 0f, 0);
			tvGlitched = glitch;
			tvCleanNew = false;
			tvCounter++;
		}
		tvAverages();
	}

	private float volumeBarAlpha() {
		if (volumeShownNs == 0 || shuttingDown()) return 0f;
		long age = System.nanoTime() - volumeShownNs;
		if (age >= 1_500_000_000L) return 0f;
		return age > 1_200_000_000L ? (1_500_000_000L - age) / 300_000_000f : 1f;
	}

	private void paintVolumeBar(int[] px, int w, int h) {
		float a = volumeBarAlpha();
		if (a <= 0f || px.length < w * h) return;
		int bw = Math.round(w * 0.6f), seg = Math.max(2, bw / 10);
		bw = seg * 10;
		int bh = Math.max(3, Math.round(h * 0.05f));
		int pad = Math.max(1, bh / 3);
		int bx = (w - bw) / 2, by = h - Math.round(h * 0.08f) - bh;

		blend(px, w, h, bx - pad, by - pad, bx + bw + pad, by + bh + pad, 0x000000, 0.65f * a);
		int gap = Math.max(1, seg / 6);
		for (int k = 0; k < 10; k++)
			blend(px, w, h, bx + k * seg + gap, by, bx + (k + 1) * seg - gap, by + bh, k < volume ? 0x55FF77 : 0x333333, a);
	}

	private static void blend(int[] px, int w, int h, int x0, int y0, int x1, int y1, int rgb, float a) {
		int k = Math.round(a * 256), inv = 256 - k;
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		for (int y = Math.max(0, y0); y < Math.min(h, y1); y++)
			for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
				int c = px[y * w + x];
				px[y * w + x] = 0xFF000000 | ((((c >> 16) & 0xFF) * inv + r * k) >> 8 << 16)
						| ((((c >> 8) & 0xFF) * inv + g * k) >> 8 << 8) | (((c & 0xFF) * inv + b * k) >> 8);
			}
	}

	private void scaleTv(int frames) {
		for (int i = 0; i < frames * 2; i++) tvScratch[i] *= 0.3f;
	}

	private void stream(int audioFrames) {
		long now = System.nanoTime();
		if (audioFrames > 0) {
			byte[] pcm = com.btemu.client.tv.TvCodec.packAudio(tvScratch, audioFrames);
			tvAudioOut.writeBytes(pcm);
		}
		if (tvAudioOut.size() > 0 && now - lastAudioSendNs > 80_000_000L) {
			lastAudioSendNs = now;
			NetworkHandler.sendToServer(new Msgs.TvAudioUp(pos, tvAudioOut.toByteArray()));
			tvAudioOut.reset();
		}
		if (tvW != 0 && now - lastStreamNs > 66_000_000L) {
			lastStreamNs = now;
			int[] copy;
			int w, h;
			synchronized (tvLock) {
				copy = tvClean.clone();
				w = tvW;
				h = tvH;
			}

			byte[] jpeg = tvWeb ? com.btemu.client.tv.TvCodec.encodeFit(copy, w, h, Msgs.CHUNK) : com.btemu.client.tv.TvCodec.encode(copy, w, h);
			if (jpeg != null && pano) jpeg = withPano(jpeg, panoTop);
			if (jpeg != null && jpeg.length < Msgs.CHUNK) NetworkHandler.sendToServer(new Msgs.TvFrameUp(pos, jpeg));
		}
	}

	private static final byte[] PANO_MARK = {'P', '3', '6', '0'};

	static byte[] withPano(byte[] jpeg, float top) {
		byte[] out = java.util.Arrays.copyOf(jpeg, jpeg.length + 5);
		System.arraycopy(PANO_MARK, 0, out, jpeg.length, 4);
		out[jpeg.length + 4] = (byte) Math.round(Math.max(0, Math.min(1, top)) * 255);
		return out;
	}

	static float panoOf(byte[] b) {
		int n = b.length;
		if (n < 5) return -1;
		for (int i = 0; i < 4; i++) if (b[n - 5 + i] != PANO_MARK[i]) return -1;
		return (b[n - 1] & 0xFF) / 255f;
	}

	static void onTvFrame(Msgs.TvFrameDown m) {
		Display d = ALL.get(m.pos);
		if (d == null || !d.tvActive() || d.isTvHostLocal()) return;
		int[] dims = new int[2];
		int[] px = com.btemu.client.tv.TvCodec.decode(m.jpeg, dims);
		if (px == null) return;
		float top = panoOf(m.jpeg);
		d.pano = top >= 0;
		if (top >= 0) d.panoTop = top;
		synchronized (d.tvLock) {
			d.tvClean = px;
			d.tvW = dims[0];
			d.tvH = dims[1];
			d.tvCleanNew = true;
		}
	}

	static void onTvAudio(Msgs.TvAudioDown m) {
		Display d = ALL.get(m.pos);
		if (d == null || !d.tvActive() || d.isTvHostLocal()) return;
		com.btemu.client.tv.TvCodec.unpackAudio(m.pcm, d.viewAudio);
	}

	boolean isTvHostLocalPublic() {
		return isTvHostLocal();
	}

	com.btemu.client.tv.WebWindow webBrowser() {
		return webWin;
	}

	private boolean isTvHostLocal() {
		Minecraft mc = Minecraft.getMinecraft();
		return mc.thePlayer != null && tvHost.equals(mc.thePlayer.username);
	}

	private void tvAverages() {
		long r = 0, g = 0, b = 0, n = 0;
		synchronized (tvLock) {
			for (int i = 0; i < tvPixels.length; i += 37) {
				int c = tvPixels[i];
				r += (c >> 16) & 0xFF;
				g += (c >> 8) & 0xFF;
				b += c & 0xFF;
				n++;
			}
		}
		if (n == 0) return;
		avgR = r / (float) n;
		avgG = g / (float) n;
		avgB = b / (float) n;
	}

	private void endTv() {
		if (browser != null) {
			try {
				browser.dispose();
			} catch (Throwable ignored) {
			}
			browser = null;
		}
		if (webWin != null) {
			try {
				webWin.dispose();
			} catch (Throwable ignored) {
			}
			webWin = null;
		}
		synchronized (tvLock) {
			tvW = 0;
			tvH = 0;
			tvPixels = new int[0];
			tvClean = new int[0];
		}
		viewAudio.clear();
		tvAudioOut.reset();
	}

	private static String chromiumStatus() {
		var c = com.btemu.client.tv.Chromium.state;
		int pct = Math.round(com.btemu.client.tv.Chromium.progress * 100);
		return switch (c) {
			case DOWNLOADING -> "Downloading Chromium " + pct + "%";
			case EXTRACTING -> "Unpacking Chromium " + pct + "%";
			case STARTING -> "Starting Chromium...";
			case FAILED -> "Chromium failed: " + com.btemu.client.tv.Chromium.error;
			default -> "Starting YouTube TV...";
		};
	}

	private void drawTvStatus(String msg) {
		final int W = GameBoy.WIDTH, H = GameBoy.HEIGHT;
		java.util.Arrays.fill(screen, 0xFF0F0F0F);
		String t = "YouTube TV";
		MonitorFont.draw(screen, W, H, t, (W - MonitorFont.width(t)) / 2, 50, 0xFFFF3030);

		String[] words = msg.split(" ");
		StringBuilder line = new StringBuilder();
		int y = 70;
		for (String w : words) {
			if (MonitorFont.width(line + " " + w) > W - 12 && line.length() > 0) {
				MonitorFont.draw(screen, W, H, line.toString(), (W - MonitorFont.width(line.toString())) / 2, y, 0xFFDDDDDD);
				y += MonitorFont.LINE;
				line.setLength(0);
			}
			if (line.length() > 0) line.append(' ');
			line.append(w);
		}
		if (line.length() > 0) MonitorFont.draw(screen, W, H, line.toString(), (W - MonitorFont.width(line.toString())) / 2, y, 0xFFDDDDDD);
	}

	private void compose() {
		if (ls.gb instanceof GameBoy gb && !ls.menu.open) {
			bigPicture = false;
			synchronized (gb.frame) {
				System.arraycopy(gb.frame, 0, screen, 0, screen.length);
			}
		} else if (ls.gb != null && !ls.menu.open) {

			com.btemu.emu.Machine m = ls.gb;
			int[] f;
			int w, h;
			synchronized (m) {
				w = m.width();
				h = m.height();
				f = m.frame();
			}
			synchronized (tvLock) {
				if (tvClean.length != w * h) tvClean = new int[w * h];
				System.arraycopy(f, 0, tvClean, 0, Math.min(f.length, w * h));
				tvW = w;
				tvH = h;
				tvCleanNew = true;
			}
			bigPicture = true;
			refreshTvPicture();
		} else {
			if (bigPicture) {
				bigPicture = false;
				synchronized (tvLock) {
					tvW = 0;
					tvH = 0;
				}
			}
			drawMenu(ls.menu);
		}
	}

	private void present() {
		if (bigPicture) return;
		long r = 0, gsum = 0, b = 0;
		for (int c : screen) {
			r += (c >> 16) & 0xFF;
			gsum += (c >> 8) & 0xFF;
			b += c & 0xFF;
		}
		avgR = r / (float) screen.length;
		avgG = gsum / (float) screen.length;
		avgB = b / (float) screen.length;
		paintVolumeBar(screen, com.btemu.gb.GameBoy.WIDTH, com.btemu.gb.GameBoy.HEIGHT);
		localGlitch(glitch, screen, GameBoy.WIDTH, GameBoy.HEIGHT, wetness, System.nanoTime());
		if (kind.crt) fx.crt(screen, plainWork);
		else fx.lcd(screen, plainWork);
		synchronized (plainOut) {
			System.arraycopy(plainWork, 0, plainOut, 0, plainOut.length);
			fxCounter++;
			int slot = fxCounter % RING;
			System.arraycopy(plainWork, 0, ring[slot], 0, plainWork.length);
			ringSeq[slot] = fxCounter;
		}
	}

	private void drawMenu(MonitorMenu menu) {
		final int W = GameBoy.WIDTH, H = GameBoy.HEIGHT;
		final int bg = 0xFF0B1220, bar = 0xFF27508F, title = 0xFFFFD84A, text = 0xFFE8EEF8, dim = 0xFF7A8699, off = 0xFFFF7A6E, head = 0xFF79B8FF;
		java.util.Arrays.fill(screen, bg);
		for (int x = 0; x < W; x++) for (int y = 0; y < 15; y++) screen[y * W + x] = 0xFF16213A;
		String t = "Select Game";
		MonitorFont.draw(screen, W, H, t, (W - MonitorFont.width(t)) / 2, 4, title);

		List<MonitorMenu.Row> rows = menu.rowList;
		int top = 20, visible = (H - top - 4) / MonitorFont.LINE;
		int sel = menu.sel;

		int scroll = Math.max(0, Math.min(sel - visible / 2, rows.size() - visible));
		for (int i = scroll; i < Math.min(rows.size(), scroll + visible); i++) {
			int y = top + (i - scroll) * MonitorFont.LINE;
			MonitorMenu.Row row = rows.get(i);
			if (i == sel) for (int yy = y - 1; yy < y + 9 && yy < H; yy++) for (int x = 2; x < W - 2; x++) screen[yy * W + x] = bar;
			String label;
			int color;
			int indent = 6;
			switch (row.kind()) {
				case EJECT -> { label = row.label(); color = off; }
				case SOUND -> {
					label = noteMode ? "Sound: Note Blocks" : "Sound: Chiptune";
					color = kind.forcedNoteBlocks ? dim : 0xFF8FE3A0;
				}
				case TV -> {
					label = row.label();
					color = 0xFFFF5555;
				}
				case WEB -> {
					label = row.label();
					color = 0xFF66AAFF;
				}
				case MINECRAFT -> {
					label = row.label();
					color = 0xFF7FD25A;
				}
				case SPEAKERS -> {
					int n = menu.speakers().size();
					label = (menu.speakersOpen ? "v " : "> ") + "Speakers (" + n + ")";
					color = n == 0 ? dim : 0xFFE0C060;
				}
				case SPEAKER -> {

					label = row.label();
					color = row.on() ? text : dim;
					indent = 20;
					int bx = 10, by = y;
					int bc = row.on() ? 0xFF8FE3A0 : dim;
					for (int k = 0; k < 7; k++) {
						screen[by * W + bx + k] = bc;
						screen[(by + 6) * W + bx + k] = bc;
						screen[(by + k) * W + bx] = bc;
						screen[(by + k) * W + bx + 6] = bc;
					}
					if (row.on()) for (int yy = by + 2; yy < by + 5; yy++) for (int xx = bx + 2; xx < bx + 5; xx++) screen[yy * W + xx] = bc;
				}
				case SPOTIFY -> {
					label = row.label();
					color = 0xFF1DB954;
				}
				case HEADER -> {
					label = "- " + row.label() + " -";
					color = head;
					indent = 3;
				}
				default -> {
					label = row.label();
					color = row.rom().equals(rom) ? title : text;
					indent = 10;
				}
			}
			label = corrupt(label, rows, i);
			MonitorFont.draw(screen, W, H, MonitorFont.fit(label, W - indent - 4), indent, y, color);
		}

		float wet = wetness;
		if (wet > 0.1f && glitchRng.nextFloat() < wet * 0.15f) {
			int gy = top + glitchRng.nextInt(Math.max(1, visible)) * MonitorFont.LINE;
			for (int yy = gy - 1; yy < gy + 9 && yy < H; yy++) for (int x = 2; x < W - 2; x++) screen[yy * W + x] ^= 0x00305080;
		}
		if (menu.roms().isEmpty()) {

			int y0 = top + (Math.min(rows.size(), visible) + 1) * MonitorFont.LINE;
			MonitorFont.draw(screen, W, H, "No ROMs found.", 6, y0, dim);
			MonitorFont.draw(screen, W, H, "Put game files in", 6, y0 + MonitorFont.LINE, dim);
			MonitorFont.draw(screen, W, H, "the roms folder", 6, y0 + 2 * MonitorFont.LINE, dim);
		}
	}
}
