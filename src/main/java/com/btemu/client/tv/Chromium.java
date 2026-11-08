package com.btemu.client.tv;

import net.minecraft.client.Minecraft;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

public final class Chromium {
	private Chromium() {}

	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("btemu-chromium");

	static final String COMMIT = "5f76004a4e7c63342e958c5727587af4bbb62f64";
	private static final String[] HOSTS = {
		"https://api.liquidbounce.net/api/v3/resource",
		"https://api.ccbluex.net/api/v3/resource",
	};

	static final String TV_USER_AGENT = "Mozilla/5.0 (PS4; Leanback Shell) Gecko/20100101 Firefox/65.0 LeanbackShell/01.00.01.75 Sony PS4/ (PS4, , no, CH)";

	public enum Phase { IDLE, DOWNLOADING, EXTRACTING, STARTING, READY, FAILED }

	public static volatile Phase state = Phase.IDLE;

	public static volatile float progress = 0f;
	public static volatile String error = "";
	private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> UI_QUEUE = new java.util.concurrent.ConcurrentLinkedQueue<>();

	public static <T> T onUi(java.util.concurrent.Callable<T> r) throws Exception {
		java.util.concurrent.CompletableFuture<T> f = new java.util.concurrent.CompletableFuture<>();
		UI_QUEUE.add(() -> {
			try {
				f.complete(r.call());
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		return f.get(30, java.util.concurrent.TimeUnit.SECONDS);
	}

	public static void later(Runnable r) {
		UI_QUEUE.add(() -> {
			try {
				r.run();
			} catch (Throwable t) {
				org.slf4j.LoggerFactory.getLogger("btemu-chromium").warn("Chromium UI task failed", t);
			}
		});
	}

	private static CefApp app;
	private static CefClient client;

	static final String NEW_TAB_TAG = "__btemu_newtab__";

	static final String LOCK_TAG = "__btemu_lock__";

	static final String NEW_TAB_JS = "(function(){if(window.__btNewTab)return;window.__btNewTab=1;"
			+ "var T='" + "__btemu_newtab__" + "';"
			+ "function abs(u){try{return new URL(u,location.href).href}catch(e){return String(u)}}"
			+ "window.open=function(u){if(u)console.log(T+abs(u));return null};"
			+ "function link(e){var n=e.target;while(n&&n.tagName!=='A')n=n.parentElement;return n&&n.href?n:null}"

			+ "document.addEventListener('click',function(e){if(e.defaultPrevented)return;var a=link(e);if(!a)return;var t=(a.target||'').toLowerCase();"
			+ "if(t&&t!=='_self'&&t!=='_top'&&t!=='_parent'){e.preventDefault();console.log(T+a.href)}},false);"
			+ "document.addEventListener('auxclick',function(e){if(e.button!==1||e.defaultPrevented)return;var a=link(e);if(a){e.preventDefault();console.log(T+a.href)}},false);"

			+ "var L='" + "__btemu_lock__" + "',lk=null;"
			+ "try{Object.defineProperty(Document.prototype,'pointerLockElement',{get:function(){return lk},configurable:true})}catch(e){}"

			+ "function chg(){setTimeout(function(){document.dispatchEvent(new Event('pointerlockchange'))},0)}"

			+ "Element.prototype.requestPointerLock=function(){var ua=navigator.userActivation;"
			+ "if(ua&&!ua.isActive){document.dispatchEvent(new Event('pointerlockerror'));"
			+ "return Promise.reject(new DOMException('Pointer lock needs a user gesture','NotAllowedError'))}"

			+ "var st=document.createElement('style');st.textContent='html.__btlk *{pointer-events:none!important}html.__btlk .__btlkel{pointer-events:auto!important}';"
			+ "function mark(on){try{if(!st.isConnected)(document.head||document.documentElement).appendChild(st);"
			+ "document.documentElement.classList.toggle('__btlk',on);if(lk)lk.classList.toggle('__btlkel',on)}catch(e){}}"
			+ "lk=this;mark(true);console.log(L+'1');chg();return Promise.resolve()};"
			+ "document.exitPointerLock=function(){if(!lk)return;try{document.documentElement.classList.remove('__btlk');lk.classList.remove('__btlkel')}catch(e){}"
			+ "lk=null;console.log(L+'0');chg()};"
			+ "window.__btMove=function(dx,dy){if(!lk)return;var r=lk.getBoundingClientRect();"
			+ "lk.dispatchEvent(new MouseEvent('mousemove',{bubbles:true,cancelable:true,view:window,movementX:dx,movementY:dy,"
			+ "clientX:r.left+r.width/2,clientY:r.top+r.height/2}))};"

			+ "try{var HD={};var rp=function(e){return e.__btr};"
			+ "window.addEventListener('keydown',function(e){var k=e.code||e.keyCode;if(HD[k]){try{Object.defineProperty(e,'repeat',{value:true})}catch(x){}}HD[k]=1},true);"
			+ "window.addEventListener('keyup',function(e){delete HD[e.code||e.keyCode]},true);"
			+ "window.addEventListener('blur',function(){HD={}},true)}catch(e){}"

			+ "try{var cd=Object.getOwnPropertyDescriptor(KeyboardEvent.prototype,'code');"
			+ "var M={' ':'Space','Shift':'Shift','Control':'Control','Alt':'Alt','Meta':'Meta','Enter':'Enter','Backspace':'Backspace',"
			+ "'Tab':'Tab','Escape':'Escape','ArrowUp':'ArrowUp','ArrowDown':'ArrowDown','ArrowLeft':'ArrowLeft','ArrowRight':'ArrowRight',"
			+ "'Delete':'Delete','Insert':'Insert','Home':'Home','End':'End','PageUp':'PageUp','PageDown':'PageDown','CapsLock':'CapsLock',"
			+ "'-':'Minus','=':'Equal','[':'BracketLeft',']':'BracketRight','\\\\':'Backslash',';':'Semicolon',\"'\":'Quote',"
			+ "'`':'Backquote',',':'Comma','.':'Period','/':'Slash'};"
			+ "Object.defineProperty(KeyboardEvent.prototype,'code',{configurable:true,get:function(){var c=cd.get.call(this);if(c)return c;"
			+ "var k=this.key||'',kc=this.keyCode;"
			+ "if(this.location===3){if(kc>=96&&kc<=105)return 'Numpad'+(kc-96);}"
			+ "if(kc>=65&&kc<=90)return 'Key'+String.fromCharCode(kc);if(kc>=48&&kc<=57)return 'Digit'+String.fromCharCode(kc);"
			+ "if(/^F\\d+$/.test(k))return k;var m=M[k];if(!m)return '';"
			+ "if(m==='Shift'||m==='Control'||m==='Alt'||m==='Meta')return m+(this.location===2?'Right':'Left');return m}})}catch(e){}"

			+ "try{var UA='" + WebWindow.DESKTOP_UA + "';"
			+ "Object.defineProperty(Navigator.prototype,'userAgent',{get:function(){return UA},configurable:true});"
			+ "Object.defineProperty(Navigator.prototype,'appVersion',{get:function(){return UA.substring(8)},configurable:true});"
			+ "Object.defineProperty(Navigator.prototype,'platform',{get:function(){return 'Win32'},configurable:true});"
			+ "Object.defineProperty(Navigator.prototype,'maxTouchPoints',{get:function(){return 0},configurable:true});"
			+ "try{delete window.ontouchstart;delete Document.prototype.ontouchstart}catch(e){}"
			+ "var mm=window.matchMedia;window.matchMedia=function(q){var r=mm.call(window,q);if(/pointer\\s*:\\s*coarse|hover\\s*:\\s*none/.test(q))"
			+ "{try{Object.defineProperty(r,'matches',{value:false})}catch(e){}}else if(/pointer\\s*:\\s*fine|hover\\s*:\\s*hover/.test(q)){try{Object.defineProperty(r,'matches',{value:true})}catch(e){}}return r}}catch(e){}"
			+ "})();" + WebMedia.JS + AdBlock.JS;

	static final String PANO_TAG = "__btemu_360__";

	static final String PANO_JS = "(function(){if(window.__btPanoJs)return;window.__btPanoJs=1;var on=0,cv=null,cx=null;"
			+ "function vid(){return document.querySelector('video.html5-main-video')||document.querySelector('video')}"
			+ "function sph(){try{var p=document.getElementById('movie_player');if(!p)return false;if(p.classList&&p.classList.contains('ytp-webgl-spherical'))return true;"
			+ "if(p.getSphericalProperties){var s=p.getSphericalProperties();if(s&&Object.keys(s).length)return true}}catch(e){}return false}"
			+ "function draw(){if(!on)return;var v=vid();if(v&&v.readyState>=2){var W=innerWidth,H=innerHeight;if(cv.width!==W||cv.height!==H){cv.width=W;cv.height=H}"
			+ "try{cx.drawImage(v,0,0,W,H)}catch(e){}}requestAnimationFrame(draw)}"
			+ "function set(n){if(n===on)return;on=n;console.log('" + PANO_TAG + "'+(n?1:0));"
			+ "if(n){if(!cv){cv=document.createElement('canvas');cv.style.cssText='position:fixed;left:0;top:0;width:100vw;height:100vh;z-index:2147483647;pointer-events:none;background:#000';cx=cv.getContext('2d')}"
			+ "document.documentElement.appendChild(cv);requestAnimationFrame(draw)}else if(cv&&cv.parentNode)cv.parentNode.removeChild(cv)}"
			+ "setInterval(function(){set(window.__btHolo&&location.hostname.indexOf('youtube.')>=0&&sph()?1:0)},500)"
			+ "})();";

	private static String tierJs(org.cef.browser.CefBrowser browser) {
		WebTab t = WebWindow.TABS.get(browser);
		return "window.__btTier=" + (t == null ? 4 : t.owner.mediaTier) + ";window.__btHolo=" + (t != null && t.owner.holo() ? 1 : 0) + ";";
	}

	public static CefClient client() {
		return client;
	}

	public static synchronized void ensure() {
		if (state != Phase.IDLE && state != Phase.FAILED) return;
		state = Phase.DOWNLOADING;
		progress = 0f;
		Thread t = new Thread(Chromium::prepare, "BTEmu-Chromium-setup");
		t.setDaemon(true);
		t.start();
	}

	private static String platform() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm64");
		String a = arm ? "arm64" : "amd64";
		if (os.contains("win")) return "windows_" + a;
		if (os.contains("mac")) return "macos_" + a;
		return "linux_" + a;
	}

	private static void prepare() {
		prepareAt(new File(Minecraft.getMinecraft().getMinecraftDir(), "btemu-cef/" + COMMIT));
	}

	static void prepareAt(File base) {
		try {
			String plat = platform();
			File platformDir = new File(base, plat);
			File ok = new File(base, plat + ".ok");
			if (!ok.isFile()) {
				base.mkdirs();
				File archive = new File(base, plat + ".tar.gz");
				String expected = null;
				IOException last = null;
				for (String host : HOSTS) {
					String url = host + "/mcef-cef/" + COMMIT + "/" + plat;
					try {
						expected = new String(get(url + "/checksum").readAllBytes()).trim();
						download(url, archive);
						last = null;
						break;
					} catch (IOException e) {
						last = e;
						LOGGER.warn("Chromium download from {} failed: {}", host, e.toString());
					}
				}
				if (last != null) throw last;
				if (expected != null && !expected.isEmpty() && !expected.equalsIgnoreCase(sha256(archive))) {
					archive.delete();
					throw new IOException("Chromium download is corrupt (checksum mismatch)");
				}
				state = Phase.EXTRACTING;
				progress = 0f;
				Tar.extractGz(archive, base, p -> progress = p);
				archive.delete();
				Files.writeString(ok.toPath(), "ok");
			}
			state = Phase.STARTING;
			start(platformDir);
			state = Phase.READY;
		} catch (Throwable t) {
			LOGGER.error("YouTube TV: Chromium setup failed", t);
			error = t.getMessage() == null ? t.toString() : t.getMessage();
			state = Phase.FAILED;
			return;
		}

		try {
			while (true) {
				Runnable r;
				while ((r = UI_QUEUE.poll()) != null) {
					try {
						r.run();
					} catch (Throwable t) {
						LOGGER.error("YouTube TV: Chromium task failed", t);
					}
				}
				app.N_DoMessageLoopWork();
				Thread.sleep(8);
			}
		} catch (Throwable t) {
			LOGGER.error("YouTube TV: Chromium setup failed", t);
			error = t.getMessage() == null ? t.toString() : t.getMessage();
			state = Phase.FAILED;
		}
	}

	private static void start(File platformDir) {
		System.setProperty("jcef.path", platformDir.getAbsolutePath());
		String[] switches = {
			"--autoplay-policy=no-user-gesture-required",
			"--disable-features=HardwareMediaKeyHandling",

			"--use-fake-ui-for-media-stream",
			"--enable-media-stream",

		};
		if (!CefApp.startup(switches)) throw new IllegalStateException("CefApp.startup failed");
		CefSettings s = new CefSettings();
		s.windowless_rendering_enabled = true;
		s.background_color = s.new ColorType(255, 0, 0, 0);
		s.user_agent = System.getProperty("btemu.tvUserAgent", TV_USER_AGENT);

		s.cache_path = System.getProperty("btemu.cefCache", new File(platformDir.getParentFile(), "cache").getAbsolutePath());

		s.log_file = new File(platformDir.getParentFile(), "chromium.log").getAbsolutePath();
		s.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
		app = CefApp.getInstance(switches, s);
		client = app.createClient();

		client.addRequestHandler(new org.cef.handler.CefRequestHandlerAdapter() {

			@Override
			public void onRenderProcessTerminated(org.cef.browser.CefBrowser browser, TerminationStatus status, int errorCode, String errorString) {
				WebTab t = WebWindow.TABS.get(browser);
				org.slf4j.LoggerFactory.getLogger("btemu-chromium").warn("Page process ended ({}, code {}, {}) on {}", status, errorCode, errorString, t != null ? t.url : browser.getURL());
				WebMedia.pageChanged(browser);
				later(browser::reload);
			}

			@Override
			public boolean onBeforeBrowse(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, org.cef.network.CefRequest request, boolean userGesture, boolean isRedirect) {
				WebTab t = WebWindow.TABS.get(browser);
				return t != null && frame != null && frame.isMain() && !t.owner.allows(request.getURL());
			}

			private final org.cef.handler.CefResourceRequestHandlerAdapter desktopUa = new org.cef.handler.CefResourceRequestHandlerAdapter() {
				@Override
				public boolean onBeforeResourceLoad(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, org.cef.network.CefRequest request) {
					request.setHeaderByName("User-Agent", WebWindow.DESKTOP_UA, true);
					return false;
				}

				@Override
				public org.cef.handler.CefResourceHandler getResourceHandler(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, org.cef.network.CefRequest request) {
					return AdBlock.blocks(request.getURL()) ? AdBlock.handler(request) : null;
				}
			};

			@Override
			public org.cef.handler.CefResourceRequestHandler getResourceRequestHandler(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame,
					org.cef.network.CefRequest request, boolean isNavigation, boolean isDownload, String requestInitiator, org.cef.misc.BoolRef disableDefaultHandling) {
				return browser instanceof WebTab ? desktopUa : null;
			}
		});

		client.addLifeSpanHandler(new org.cef.handler.CefLifeSpanHandlerAdapter() {
			@Override
			public boolean onBeforePopup(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, String targetUrl, String targetFrameName) {
				WebTab t = WebWindow.TABS.get(browser);
				if (t != null && targetUrl != null && !targetUrl.isEmpty()) {

					Thread th = new Thread(() -> t.owner.newTab(targetUrl), "BTEmu popup tab");
					th.setDaemon(true);
					th.start();
				}
				return true;
			}
		});
		client.addDisplayHandler(new org.cef.handler.CefDisplayHandlerAdapter() {
			@Override
			public void onTitleChange(org.cef.browser.CefBrowser browser, String title) {
				WebTab t = WebWindow.TABS.get(browser);
				if (t != null) {
					t.title = title;
					t.owner.titleChanged(t);
				}
			}

			@Override
			public boolean onConsoleMessage(org.cef.browser.CefBrowser browser, org.cef.CefSettings.LogSeverity level, String message, String source, int line) {
				if (WebMedia.onConsole(browser, message)) return true;
				if (message != null && message.startsWith(PANO_TAG)) {
					WebTab pt = WebWindow.TABS.get(browser);
					if (pt != null) pt.pano = message.endsWith("1");
					return true;
				}
				if (message != null && message.startsWith(LOCK_TAG)) {
					WebTab lt = WebWindow.TABS.get(browser);
					if (lt != null) {
						lt.pointerLocked = message.endsWith("1");
						lt.owner.titleChanged(lt);
					}
					return true;
				}
				if (message == null || !message.startsWith(NEW_TAB_TAG)) return false;
				WebTab t = WebWindow.TABS.get(browser);
				String url = message.substring(NEW_TAB_TAG.length());
				if (t != null && (url.startsWith("http:") || url.startsWith("https:") || url.startsWith("data:") || url.startsWith("about:"))) {
					Thread th = new Thread(() -> t.owner.newTab(url), "BTEmu new tab");
					th.setDaemon(true);
					th.start();
				}
				return true;
			}

			@Override
			public void onAddressChange(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, String url) {
				WebTab t = WebWindow.TABS.get(browser);
				if (t != null && (frame == null || frame.isMain())) {
					t.url = url;
					t.owner.applyZoom(t);
					t.owner.titleChanged(t);
				}
			}
		});
		client.addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {

			@Override
			public void onLoadStart(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, org.cef.network.CefRequest.TransitionType transitionType) {
				if (frame != null && frame.isMain()) WebMedia.pageChanged(browser);
				if (frame != null && WebWindow.TABS.containsKey(browser)) frame.executeJavaScript(tierJs(browser) + NEW_TAB_JS + PANO_JS, frame.getURL(), 0);
			}

			@Override
			public void onLoadEnd(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, int httpStatusCode) {
				if (frame != null && WebWindow.TABS.containsKey(browser)) frame.executeJavaScript(tierJs(browser) + NEW_TAB_JS + PANO_JS, frame.getURL(), 0);
			}

			@Override
			public void onLoadingStateChange(org.cef.browser.CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
				WebTab t = WebWindow.TABS.get(browser);
				if (t != null) {
					t.loading = isLoading;
					if (!isLoading) t.pointerLocked = false;
					t.owner.applyZoom(t);
					t.owner.titleChanged(t);
				}
			}
		});

		client.addDownloadHandler(new org.cef.handler.CefDownloadHandlerAdapter() {
			@Override
			public boolean onBeforeDownload(org.cef.browser.CefBrowser browser, org.cef.callback.CefDownloadItem item, String suggestedName, org.cef.callback.CefBeforeDownloadCallback callback) {
				WebTab t = WebWindow.TABS.get(browser);
				if (t != null) t.owner.blockedDownload(suggestedName);
				return true;
			}

			@Override
			public void onDownloadUpdated(org.cef.browser.CefBrowser browser, org.cef.callback.CefDownloadItem item, org.cef.callback.CefDownloadItemCallback callback) {
				if (callback != null) callback.cancel();
			}
		});
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				if (client != null) client.dispose();
				if (app != null) app.dispose();
			} catch (Throwable ignored) {
			}
		}, "BTEmu-Chromium-shutdown"));
	}

	private static InputStream get(String url) throws IOException {

		for (int hops = 0; hops < 6; hops++) {
			HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
			c.setConnectTimeout(15000);
			c.setReadTimeout(30000);
			c.setInstanceFollowRedirects(false);
			c.setRequestProperty("User-Agent", "BTEmu");
			int code = c.getResponseCode();
			if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
				url = URI.create(url).resolve(c.getHeaderField("Location")).toString();
				continue;
			}
			if (code != 200) throw new IOException("HTTP " + code + " for " + url);
			return c.getInputStream();
		}
		throw new IOException("Too many redirects");
	}

	private static void download(String url, File to) throws IOException {
		HttpURLConnection probe;
		long total = -1;
		String u = url;
		for (int hops = 0; hops < 6; hops++) {
			probe = (HttpURLConnection) URI.create(u).toURL().openConnection();
			probe.setInstanceFollowRedirects(false);
			probe.setRequestProperty("User-Agent", "BTEmu");
			int code = probe.getResponseCode();
			if (code >= 300 && code < 400 && probe.getHeaderField("Location") != null) {
				u = URI.create(u).resolve(probe.getHeaderField("Location")).toString();
				probe.disconnect();
				continue;
			}
			if (code != 200) throw new IOException("HTTP " + code + " for " + u);
			total = probe.getContentLengthLong();
			try (InputStream in = new BufferedInputStream(probe.getInputStream()); OutputStream out = new FileOutputStream(to)) {
				byte[] buf = new byte[1 << 16];
				long done = 0;
				int n;
				while ((n = in.read(buf)) > 0) {
					out.write(buf, 0, n);
					done += n;
					if (total > 0) progress = done / (float) total;
				}
			}
			return;
		}
		throw new IOException("Too many redirects");
	}

	private static String sha256(File f) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		try (InputStream in = new BufferedInputStream(Files.newInputStream(f.toPath()))) {
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
		}
		return HexFormat.of().formatHex(md.digest());
	}

	static final class Tar {
		interface Progress { void at(float p); }

		static void extractGz(File archive, File dest, Progress progress) throws IOException {
			long total = archive.length();
			try (var counting = new CountingInput(Files.newInputStream(archive.toPath()));
				 InputStream in = new GZIPInputStream(new BufferedInputStream(counting), 1 << 16)) {
				byte[] header = new byte[512];
				String longName = null;
				String destPath = dest.getCanonicalPath() + File.separator;
				while (true) {
					if (!readFully(in, header)) return;
					if (isZero(header)) return;
					String name = longName != null ? longName : cstr(header, 0, 100);
					longName = null;
					String prefix = cstr(header, 345, 155);
					if (!prefix.isEmpty()) name = prefix + "/" + name;
					long size = octal(header, 124, 12);
					char type = (char) header[156];
					if (type == 'L') {
						byte[] nb = new byte[(int) size];
						readFully(in, nb);
						skip(in, pad(size));
						longName = cstr(nb, 0, nb.length);
						continue;
					}
					File out = new File(dest, name);
					if (!out.getCanonicalPath().startsWith(destPath)) throw new IOException("Bad path in archive: " + name);
					if (type == '5') {
						out.mkdirs();
					} else if (type == '0' || type == 0) {
						out.getParentFile().mkdirs();
						try (OutputStream o = new FileOutputStream(out)) {
							copy(in, o, size);
						}
						skip(in, pad(size));
						if ((octal(header, 100, 8) & 0100) != 0) out.setExecutable(true);
					} else {
						skip(in, size + pad(size));
					}
					progress.at(counting.count / (float) total);
				}
			}
		}

		private static long pad(long size) {
			return (512 - (size % 512)) % 512;
		}

		private static boolean readFully(InputStream in, byte[] b) throws IOException {
			int off = 0;
			while (off < b.length) {
				int n = in.read(b, off, b.length - off);
				if (n < 0) return false;
				off += n;
			}
			return true;
		}

		private static void copy(InputStream in, OutputStream out, long n) throws IOException {
			byte[] buf = new byte[1 << 16];
			while (n > 0) {
				int r = in.read(buf, 0, (int) Math.min(buf.length, n));
				if (r < 0) throw new IOException("Truncated archive");
				out.write(buf, 0, r);
				n -= r;
			}
		}

		private static void skip(InputStream in, long n) throws IOException {
			byte[] buf = new byte[8192];
			while (n > 0) {
				int r = in.read(buf, 0, (int) Math.min(buf.length, n));
				if (r < 0) return;
				n -= r;
			}
		}

		private static boolean isZero(byte[] b) {
			for (byte x : b) if (x != 0) return false;
			return true;
		}

		private static String cstr(byte[] b, int off, int len) {
			int end = off;
			while (end < off + len && b[end] != 0) end++;
			return new String(b, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
		}

		private static long octal(byte[] b, int off, int len) {
			long v = 0;
			for (int i = off; i < off + len; i++) {
				byte c = b[i];
				if (c == 0 || c == ' ') {
					if (v != 0) break;
					continue;
				}
				if (c < '0' || c > '7') break;
				v = v * 8 + (c - '0');
			}
			return v;
		}

		private static final class CountingInput extends java.io.FilterInputStream {
			long count;

			CountingInput(InputStream in) {
				super(in);
			}

			@Override
			public int read() throws IOException {
				int r = super.read();
				if (r >= 0) count++;
				return r;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				int r = super.read(b, off, len);
				if (r > 0) count += r;
				return r;
			}
		}
	}
}
