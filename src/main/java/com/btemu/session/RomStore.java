package com.btemu.session;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

public final class RomStore {
	private RomStore() {}

	public static File dir = System.getProperty("btaroom.lan") != null ? new File("..", "roms") : new File("roms");

	public static File dir() {
		if (!dir.isDirectory()) dir.mkdirs();
		return dir;
	}

	public static List<String> list() {
		File[] files = dir().listFiles();
		List<String> out = new ArrayList<>();
		if (files == null) return out;
		for (File f : files) {
			String n = f.getName().toLowerCase(Locale.ROOT);
			if (f.isFile() && com.btemu.emu.Console.of(n) != null) out.add(f.getName());
		}
		out.sort(String::compareToIgnoreCase);
		return out;
	}

	public static long crc(byte[] data) {
		CRC32 c = new CRC32();
		c.update(data);
		return c.getValue();
	}

	public static byte[] read(String name) {
		try {
			File f = new File(dir(), name);
			if (!f.isFile() || name.contains("..") || name.contains("/") || name.contains("\\")) return null;
			return Files.readAllBytes(f.toPath());
		} catch (Exception e) {
			return null;
		}
	}

	public static byte[] findByCrc(String name, long crc) {
		byte[] b = read(name);
		if (b != null && crc(b) == crc) return b;
		try {
			File c = cacheFile(crc);
			if (c.isFile()) {
				b = Files.readAllBytes(c.toPath());
				if (crc(b) == crc) return b;
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	public static void cache(long crc, byte[] data) {
		try {
			File c = cacheFile(crc);
			c.getParentFile().mkdirs();
			Files.write(c.toPath(), data);
		} catch (Exception ignored) {
		}
	}

	private static File cacheFile(long crc) {
		return new File(new File(dir(), ".btemu-cache"), Long.toHexString(crc) + ".rom");
	}
}
