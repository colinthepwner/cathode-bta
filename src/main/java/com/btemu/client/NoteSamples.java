package com.btemu.client;

import com.btemu.BtEmu;
import com.btemu.gb.NoteBlockSynth;
import net.minecraft.client.Minecraft;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;

final class NoteSamples {
	private static float[][] decoded;
	private static int[] rates;
	private static boolean loaded = false;

	private NoteSamples() {}

	private static final String[][] FILES = {
		{"HARP", "harp"}, {"BASS", "bass"}, {"SNARE", "snare"}, {"HAT", "hat"}, {"BASSDRUM", "bd"}, {"PLING", "pling"},
	};

	static synchronized void apply(NoteBlockSynth synth) {
		if (!loaded) load();
		for (int i = 0; i < FILES.length; i++) {
			if (decoded[i] != null) synth.setSample(NoteBlockSynth.Instrument.valueOf(FILES[i][0]), decoded[i], rates[i]);
		}
	}

	private static void load() {
		loaded = true;
		decoded = new float[FILES.length][];
		rates = new int[FILES.length];
		File dir = new File(Minecraft.getMinecraft().getMinecraftDir(), "resources/minecraft/sounds/note");
		for (int i = 0; i < FILES.length; i++) {
			try {
				byte[] bytes = read(new File(dir, FILES[i][1] + ".ogg"), "/assets/minecraft/sounds/note/" + FILES[i][1] + ".ogg");
				if (bytes == null) {
					BtEmu.LOGGER.warn("Note block sound {} not found; that instrument will be silent.", FILES[i][1]);
					continue;
				}
				decode(i, bytes);
			} catch (Throwable t) {
				BtEmu.LOGGER.warn("Couldn't decode note block sound {}", FILES[i][1], t);
			}
		}
	}

	private static byte[] read(File file, String classpath) throws Exception {
		if (file.isFile()) return Files.readAllBytes(file.toPath());
		try (InputStream in = NoteSamples.class.getResourceAsStream(classpath)) {
			return in == null ? null : in.readAllBytes();
		}
	}

	private static void decode(int i, byte[] bytes) {
		ByteBuffer data = ByteBuffer.allocateDirect(bytes.length);
		data.put(bytes).flip();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer channels = stack.mallocInt(1), rate = stack.mallocInt(1);
			ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(data, channels, rate);
			if (pcm == null) return;
			int ch = Math.max(1, channels.get(0));
			int frames = pcm.remaining() / ch;
			float[] mono = new float[frames];
			for (int f = 0; f < frames; f++) {
				float sum = 0;
				for (int c = 0; c < ch; c++) sum += pcm.get(f * ch + c);
				mono[f] = sum / ch / 32768f;
			}
			org.lwjgl.system.MemoryUtil.memFree(pcm);
			decoded[i] = mono;
			rates[i] = rate.get(0);
		}
	}
}
