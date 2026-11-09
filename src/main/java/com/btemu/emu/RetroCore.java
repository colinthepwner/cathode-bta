package com.btemu.emu;

import com.btemu.BtEmu;
import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public final class RetroCore implements Machine {

	private static final int ENV_GET_CAN_DUPE = 3, ENV_SET_PERFORMANCE_LEVEL = 8, ENV_GET_SYSTEM_DIRECTORY = 9,
			ENV_SET_PIXEL_FORMAT = 10, ENV_SET_INPUT_DESCRIPTORS = 11, ENV_SET_HW_RENDER = 14, ENV_GET_VARIABLE = 15,
			ENV_SET_VARIABLES = 16, ENV_GET_VARIABLE_UPDATE = 17, ENV_GET_LOG_INTERFACE = 27,
			ENV_GET_SAVE_DIRECTORY = 31, ENV_SET_SYSTEM_AV_INFO = 32, ENV_SET_GEOMETRY = 37,
			ENV_GET_LANGUAGE = 39, ENV_GET_INPUT_BITMASKS = 51 | 0x10000, ENV_GET_CORE_OPTIONS_VERSION = 52,
			ENV_GET_AUDIO_VIDEO_ENABLE = 47 | 0x10000;
	private static final int FMT_0RGB1555 = 0, FMT_XRGB8888 = 1, FMT_RGB565 = 2;
	private static final int DEVICE_JOYPAD = 1, DEVICE_ANALOG = 5, JOYPAD_MASK = 256;

	private static final int ID_B = 0, ID_Y = 1, ID_SELECT = 2, ID_START = 3, ID_UP = 4, ID_DOWN = 5, ID_LEFT = 6,
			ID_RIGHT = 7, ID_A = 8, ID_X = 9, ID_L = 10, ID_R = 11, ID_L2 = 12;

	private static final int GB_RIGHT = 1, GB_LEFT = 2, GB_UP = 4, GB_DOWN = 8, GB_A = 16, GB_B = 32, GB_SELECT = 64, GB_START = 128;

	private final Console console;
	private final NativeLibrary lib;
	private final File dllCopy;

	private File romCopy;
	private final Map<String, String> options = new HashMap<>();

	private final Map<String, Memory> strings = new HashMap<>();
	private Memory romMem, gameInfo;

	private int pixelFormat = FMT_0RGB1555;
	private int w = 1, h = 1;
	private int[] front = new int[1];
	private double fps = 60, sampleRate = 44100;
	private volatile int buttons;

	private final int[] otherButtons = new int[3];
	private boolean closed;

	private final float[] ring = new float[48000];
	private int ringRead, ringWrite;
	private double resamplePos;
	private float lastL, lastR;

	private final EnvCb envCb = this::environment;
	private final VideoCb videoCb = this::video;
	private final AudioCb audioCb = (l, r) -> pushSample(l, r);
	private final AudioBatchCb audioBatchCb = this::audioBatch;
	private final PollCb pollCb = () -> {};
	private final StateCb stateCb = this::inputState;

	public interface EnvCb extends Callback { byte invoke(int cmd, Pointer data); }
	public interface VideoCb extends Callback { void invoke(Pointer data, int width, int height, long pitch); }
	public interface AudioCb extends Callback { void invoke(short l, short r); }
	public interface AudioBatchCb extends Callback { long invoke(Pointer data, long frames); }
	public interface PollCb extends Callback { void invoke(); }
	public interface StateCb extends Callback { short invoke(int port, int device, int index, int id); }

	public RetroCore(Console console, File dll, byte[] rom, String romName) throws IOException {
		this.console = console;
		options.putAll(console.options);
		sweepCopies(dll.getParentFile());
		dllCopy = new File(dll.getParentFile(), "run-" + Long.toHexString(System.nanoTime()) + "-" + dll.getName());
		Files.copy(dll.toPath(), dllCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);
		lib = NativeLibrary.getInstance(dllCopy.getAbsolutePath());
		try {
			call("retro_set_environment", envCb);
			call("retro_set_video_refresh", videoCb);
			call("retro_set_audio_sample", audioCb);
			call("retro_set_audio_sample_batch", audioBatchCb);
			call("retro_set_input_poll", pollCb);
			call("retro_set_input_state", stateCb);
			call("retro_init");

			Memory info = new Memory(32);
			info.clear();
			call("retro_get_system_info", info);
			boolean fullPath = info.getByte(24) != 0;

			gameInfo = new Memory(32);
			gameInfo.clear();
			if (fullPath) {

				File tmp = new File(dllCopy.getParentFile(), dllCopy.getName() + "-" + romName.replaceAll("[^A-Za-z0-9._-]", "_"));
				Files.write(tmp.toPath(), rom);
				tmp.deleteOnExit();
				romCopy = tmp;
				gameInfo.setPointer(0, str(tmp.getAbsolutePath()));
			} else {
				gameInfo.setPointer(0, str(romName));
				romMem = new Memory(Math.max(1, rom.length));
				romMem.write(0, rom, 0, rom.length);
				gameInfo.setPointer(8, romMem);
				gameInfo.setLong(16, rom.length);
			}

			if ((fn("retro_load_game").invokeInt(new Object[]{gameInfo}) & 0xFF) == 0)
				throw new IOException(console.name + " core couldn't load " + romName);

			for (int p = 0; p < console.players; p++) call("retro_set_controller_port_device", p, DEVICE_JOYPAD);
			readAvInfo();
			warmUp();
		} catch (Throwable t) {
			close();
			if (t instanceof IOException e) throw e;
			throw new IOException("Couldn't start the " + console.name + " core", t);
		}
	}

	private void warmUp() {
		for (int i = 0; i < 240; i++) {
			long size = fn("retro_serialize_size").invokeLong(new Object[0]);
			if (size > 0) {
				Memory m = new Memory(size);
				if ((fn("retro_serialize").invokeInt(new Object[]{m, size}) & 0xFF) != 0) break;
			}
			call("retro_run");
		}
		synchronized (this) {
			ringRead = ringWrite;
		}
	}

	private void readAvInfo() {

		Memory av = new Memory(40);
		av.clear();
		call("retro_get_system_av_info", av);
		applyAv(av);
	}

	private void applyAv(Pointer av) {
		int bw = av.getInt(0), bh = av.getInt(4);
		double f = av.getDouble(24), sr = av.getDouble(32);
		if (f > 1 && f < 200) fps = f;
		if (sr > 1000) sampleRate = sr;
		if (bw > 0 && bh > 0) resize(bw, bh);
	}

	private synchronized void resize(int nw, int nh) {
		if (nw == w && nh == h) return;
		w = nw;
		h = nh;
		front = new int[w * h];
		java.util.Arrays.fill(front, 0xFF000000);
	}

	@Override public synchronized int width() { return w; }
	@Override public synchronized int height() { return h; }
	@Override public synchronized int[] frame() { return front; }
	@Override public double frameNs() { return 1e9 / fps; }

	@Override
	public void setButtons(int mask) {
		buttons = mask;
	}

	@Override
	public int maxPlayers() {
		return console.players;
	}

	@Override
	public void setButtons(int port, int mask) {
		if (port == 0) buttons = mask;
		else if (port < console.players) otherButtons[port - 1] = mask;
	}

	@Override
	public void runFrame() {
		if (closed) return;
		call("retro_run");
	}

	@Override
	public void saveState(DataOutputStream o) throws IOException {
		long size = fn("retro_serialize_size").invokeLong(new Object[0]);
		if (size <= 0 || size > 256L << 20) throw new IOException(console.name + " core can't save state");
		Memory m = new Memory(size);
		if ((fn("retro_serialize").invokeInt(new Object[]{m, size}) & 0xFF) == 0) throw new IOException(console.name + " core failed to save state");
		o.writeInt(0x52455431);
		o.writeInt((int) size);
		o.write(m.getByteArray(0, (int) size));

		synchronized (this) {
			o.writeInt(w);
			o.writeInt(h);
			for (int c : front) o.writeInt(c);
		}
	}

	@Override
	public void loadState(DataInputStream in) throws IOException {
		if (in.readInt() != 0x52455431) throw new IOException("Not a core save state");
		int size = in.readInt();
		byte[] b = new byte[size];
		in.readFully(b);
		Memory m = new Memory(Math.max(1, size));
		m.write(0, b, 0, size);
		if ((fn("retro_unserialize").invokeInt(new Object[]{m, (long) size}) & 0xFF) == 0) throw new IOException(console.name + " core rejected the save state");
		int sw = in.readInt(), sh = in.readInt();
		int[] px = new int[sw * sh];
		for (int i = 0; i < px.length; i++) px[i] = in.readInt();
		synchronized (this) {
			w = sw;
			h = sh;
			front = px;
		}
	}

	@Override
	public synchronized int drainAudio(float[] out) {
		int frames = 0;
		while (ringRead != ringWrite && frames * 2 + 1 < out.length) {
			out[frames * 2] = ring[ringRead];
			out[frames * 2 + 1] = ring[ringRead + 1];
			ringRead = (ringRead + 2) % ring.length;
			frames++;
		}
		return frames;
	}

	@Override
	public void glitchFlip(Random rng, int count, boolean deep) {
		if (rng.nextInt(deep ? 2 : 6) == 0) buttons ^= 1 << rng.nextInt(8);
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		try {
			call("retro_unload_game");
			call("retro_deinit");
		} catch (Throwable ignored) {
		}
		try {
			if (lib != null) lib.close();
		} catch (Throwable ignored) {
		}
		if (dllCopy != null && !dllCopy.delete()) dllCopy.deleteOnExit();

		if (romCopy != null) romCopy.delete();
	}

	private byte environment(int cmd, Pointer data) {
		try {
			switch (cmd) {
				case ENV_GET_CAN_DUPE:
					data.setByte(0, (byte) 1);
					return 1;
				case ENV_SET_PIXEL_FORMAT: {
					int f = data.getInt(0);
					if (f != FMT_XRGB8888 && f != FMT_RGB565 && f != FMT_0RGB1555) return 0;
					pixelFormat = f;
					return 1;
				}
				case ENV_GET_SYSTEM_DIRECTORY:
				case ENV_GET_SAVE_DIRECTORY:
					data.setPointer(0, str(CoreStore.systemDir().getAbsolutePath()));
					return 1;
				case ENV_SET_VARIABLES: {

					for (int i = 0; ; i++) {
						Pointer k = data.getPointer(i * 16L);
						if (k == null) break;
						String key = k.getString(0), desc = data.getPointer(i * 16L + 8).getString(0);
						int semi = desc.indexOf("; ");
						String vals = semi >= 0 ? desc.substring(semi + 2) : desc;
						int bar = vals.indexOf('|');
						options.putIfAbsent(key, bar >= 0 ? vals.substring(0, bar) : vals);
					}
					return 1;
				}
				case ENV_GET_VARIABLE: {
					Pointer k = data.getPointer(0);
					if (k == null) return 0;
					String v = options.get(k.getString(0));
					if (v == null) {
						data.setPointer(8, null);
						return 0;
					}
					data.setPointer(8, str(v));
					return 1;
				}
				case ENV_GET_VARIABLE_UPDATE:
					data.setByte(0, (byte) 0);
					return 1;
				case ENV_GET_INPUT_BITMASKS:
					return 1;
				case ENV_GET_LANGUAGE:
					data.setInt(0, 0);
					return 1;
				case ENV_GET_AUDIO_VIDEO_ENABLE:
					data.setInt(0, 3);
					return 1;
				case ENV_SET_GEOMETRY:
					if (data.getInt(0) > 0 && data.getInt(4) > 0) resize(data.getInt(0), data.getInt(4));
					return 1;
				case ENV_SET_SYSTEM_AV_INFO:
					applyAv(data);
					return 1;
				case ENV_SET_PERFORMANCE_LEVEL:
				case ENV_SET_INPUT_DESCRIPTORS:
					return 1;
				case ENV_SET_HW_RENDER:
				case ENV_GET_CORE_OPTIONS_VERSION:
				case ENV_GET_LOG_INTERFACE:
				default:
					return 0;
			}
		} catch (Throwable t) {
			BtEmu.LOGGER.warn("{} core environment call {} failed", console.name, cmd, t);
			return 0;
		}
	}

	private void video(Pointer data, int vw, int vh, long pitch) {
		if (data == null || vw <= 0 || vh <= 0) return;
		int[] px = new int[vw * vh];
		if (pixelFormat == FMT_XRGB8888) {
			for (int y = 0; y < vh; y++) {
				data.read(y * pitch, px, y * vw, vw);
			}
			for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;
		} else {
			short[] row = new short[vw];
			for (int y = 0; y < vh; y++) {
				data.read(y * pitch, row, 0, vw);
				for (int x = 0; x < vw; x++) {
					int c = row[x] & 0xFFFF, r, g, b;
					if (pixelFormat == FMT_RGB565) {
						r = (c >> 11) & 31; g = (c >> 5) & 63; b = c & 31;
						r = r << 3 | r >> 2; g = g << 2 | g >> 4; b = b << 3 | b >> 2;
					} else {
						r = (c >> 10) & 31; g = (c >> 5) & 31; b = c & 31;
						r = r << 3 | r >> 2; g = g << 3 | g >> 2; b = b << 3 | b >> 2;
					}
					px[y * vw + x] = 0xFF000000 | r << 16 | g << 8 | b;
				}
			}
		}
		synchronized (this) {
			w = vw;
			h = vh;
			front = px;
		}
	}

	private long audioBatch(Pointer data, long frames) {
		short[] s = new short[(int) frames * 2];
		data.read(0, s, 0, s.length);
		for (int i = 0; i < frames; i++) pushSample(s[i * 2], s[i * 2 + 1]);
		return frames;
	}

	private synchronized void pushSample(short l, short r) {
		float fl = l / 32768f * 0.3f, fr = r / 32768f * 0.3f;
		double step = sampleRate / 48000.0;
		while (resamplePos < 1.0) {
			float t = (float) resamplePos;
			ring[ringWrite] = lastL + (fl - lastL) * t;
			ring[ringWrite + 1] = lastR + (fr - lastR) * t;
			ringWrite = (ringWrite + 2) % ring.length;
			if (ringWrite == ringRead) ringRead = (ringRead + 2) % ring.length;
			resamplePos += step;
		}
		resamplePos -= 1.0;
		lastL = fl;
		lastR = fr;
	}

	private short inputState(int port, int device, int index, int id) {
		if (port < 0 || port >= console.players) return 0;
		int m = port == 0 ? buttons : otherButtons[port - 1];
		if (device == DEVICE_JOYPAD) {
			if (id == JOYPAD_MASK) {
				int bits = 0;
				for (int i = 0; i <= ID_L2; i++) if (pad(m, i)) bits |= 1 << i;
				return (short) bits;
			}
			return (short) (pad(m, id) ? 1 : 0);
		}
		if (device == DEVICE_ANALOG && index == 0 && console.dpadIsStick) {

			if (id == 0) return (short) ((m & GB_RIGHT) != 0 ? 32767 : (m & GB_LEFT) != 0 ? -32767 : 0);
			if (id == 1) return (short) ((m & GB_DOWN) != 0 ? 32767 : (m & GB_UP) != 0 ? -32767 : 0);
		}
		if (device == DEVICE_ANALOG && index == 1 && console.dpadIsStick) {

			if (id == 0) return (short) ((m & BTN_C_RIGHT) != 0 ? 32767 : (m & BTN_C_LEFT) != 0 ? -32767 : 0);
			if (id == 1) return (short) ((m & BTN_C_DOWN) != 0 ? 32767 : (m & BTN_C_UP) != 0 ? -32767 : 0);
		}
		return 0;
	}

	private boolean pad(int m, int id) {
		if (console.dpadIsStick) {
			return switch (id) {
				case ID_B -> (m & GB_A) != 0;
				case ID_Y -> (m & GB_B) != 0;
				case ID_L2 -> (m & BTN_Z) != 0;
				case ID_L -> (m & BTN_L) != 0;
				case ID_R -> (m & BTN_R) != 0;
				case ID_START -> (m & GB_START) != 0;
				case ID_UP -> (m & GB_UP) != 0;
				case ID_DOWN -> (m & GB_DOWN) != 0;
				case ID_LEFT -> (m & GB_LEFT) != 0;
				case ID_RIGHT -> (m & GB_RIGHT) != 0;
				default -> false;
			};
		}
		return switch (id) {
			case ID_A -> (m & GB_A) != 0;
			case ID_B -> (m & GB_B) != 0;
			case ID_SELECT -> (m & GB_SELECT) != 0;
			case ID_START -> (m & GB_START) != 0;
			case ID_UP -> (m & GB_UP) != 0;
			case ID_DOWN -> (m & GB_DOWN) != 0;
			case ID_LEFT -> (m & GB_LEFT) != 0;
			case ID_RIGHT -> (m & GB_RIGHT) != 0;
			case ID_X -> (m & BTN_X) != 0;
			case ID_Y -> (m & BTN_Y) != 0;
			case ID_L -> (m & BTN_L) != 0;
			case ID_R -> (m & BTN_R) != 0;
			case ID_L2 -> (m & BTN_Z) != 0;
			default -> false;
		};
	}

	private Function fn(String name) {
		return lib.getFunction(name);
	}

	private void call(String name, Object... args) {
		fn(name).invoke(Void.class, args);
	}

	private Pointer str(String s) {
		return strings.computeIfAbsent(s, k -> {
			byte[] b = k.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			Memory m = new Memory(b.length + 1);
			m.write(0, b, 0, b.length);
			m.setByte(b.length, (byte) 0);
			return m;
		});
	}

	private static void sweepCopies(File dir) {
		File[] fs = dir.listFiles();
		if (fs == null) return;
		for (File f : fs) if (f.getName().startsWith("run-")) f.delete();
	}
}
