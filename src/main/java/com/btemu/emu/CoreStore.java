package com.btemu.emu;

import com.btemu.BtEmu;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class CoreStore {
	private CoreStore() {}

	public static File dir = new File("btemu-cores");

	private static final String BASE = "https://buildbot.libretro.com/nightly/windows/x86_64/latest/";

	private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
	private static final Object LOCK = new Object();

	public static File dir() {
		if (!dir.isDirectory()) dir.mkdirs();
		return dir;
	}

	public static File systemDir() {
		File f = new File(dir(), "system");
		if (!f.isDirectory()) f.mkdirs();
		return f;
	}

	public static boolean ready(String core) {
		return file(core).isFile();
	}

	private static File file(String core) {
		return new File(dir(), core + "_libretro.dll");
	}

	public static File get(String core) throws IOException {
		synchronized (LOCK) {
			File f = file(core);
			String res = "/btemu/cores/" + core + "_libretro.dll";
			try (java.io.InputStream in = CoreStore.class.getResourceAsStream(res)) {
				if (in != null) {
					java.net.URLConnection con = CoreStore.class.getResource(res).openConnection();
					long size = con.getContentLengthLong();
					if (f.isFile() && (size < 0 || f.length() == size)) return f;
					File tmp = new File(dir(), core + ".part");
					Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
					Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
					unpackLicences();
					return f;
				}
			}
			if (f.isFile()) return f;
			download(core, f);
			return f;
		}
	}

	private static void unpackLicences() {
		for (String n : new String[]{"NOTICE.txt", "snes9x_LICENSE.txt", "parallel_n64_LICENSE.txt", "fceumm_LICENSE.txt",
				"genesis_plus_gx_LICENSE.txt", "mgba_LICENSE.txt"}) {
			try (java.io.InputStream in = CoreStore.class.getResourceAsStream("/btemu/cores/" + n)) {
				if (in != null) Files.copy(in, new File(dir(), n).toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
			}
		}
	}

	static boolean bundled(String core) {
		return CoreStore.class.getResource("/btemu/cores/" + core + "_libretro.dll") != null;
	}

	public static void prefetch(Collection<String> roms) {
		for (String r : roms) {
			Console c = Console.of(r);
			if (c == null || c.core == null || ready(c.core) || bundled(c.core) || FAILED.contains(c.core)) continue;
			Thread t = new Thread(() -> {
				try {
					get(c.core);
				} catch (IOException e) {
					FAILED.add(c.core);
					BtEmu.LOGGER.warn("Couldn't download the {} core", c.name, e);
				}
			}, "BTEmu core download");
			t.setDaemon(true);
			t.start();
		}
	}

	private static void download(String core, File dest) throws IOException {
		BtEmu.LOGGER.info("Downloading the {} libretro core", core);
		HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
		HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + core + "_libretro.dll.zip")).timeout(Duration.ofMinutes(5)).build();
		File tmp = new File(dir(), core + ".part");
		try {
			HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
			if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
			try (ZipInputStream zip = new ZipInputStream(res.body())) {
				ZipEntry e;
				while ((e = zip.getNextEntry()) != null) {
					if (e.getName().endsWith(".dll")) {
						Files.copy(zip, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
						Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
						return;
					}
				}
			}
			throw new IOException("no DLL in the download");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		} finally {
			tmp.delete();
		}
	}
}
