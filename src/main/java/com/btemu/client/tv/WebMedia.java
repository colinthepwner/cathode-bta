package com.btemu.client.tv;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;

import java.util.Base64;

public final class WebMedia {
	private WebMedia() {}

	public static final int CAM = 0, SCREEN = 1, MIC = 2;
	private static final String[] KEYS = {"cam", "scr", "mic"};
	static final String TAG = "__btemu_media__";

	static boolean onConsole(CefBrowser browser, String message) {
		if (message == null || !message.startsWith(TAG)) return false;
		WebTab t = WebWindow.TABS.get(browser);
		if (t == null) return true;
		String rest = message.substring(TAG.length());
		if (rest.equals("pick")) {
			sendTabList(t);
			return true;
		}
		if (rest.startsWith("src:")) {
			String v = rest.substring(4);
			Object src = SRC_GAME;
			if (v.equals("browser")) src = SRC_BROWSER;
			else if (v.startsWith("tab:")) {
				for (WebTab o : WebWindow.TABS.values()) if (String.valueOf(o.id).equals(v.substring(4))) src = o;
			}
			t.shareSrc = src;
			return true;
		}
		boolean on = rest.endsWith("1");
		for (int k = 0; k < KEYS.length; k++) if (rest.startsWith(KEYS[k])) t.media[k] = on;
		if (!t.media[SCREEN]) t.shareSrc = null;
		return true;
	}

	static void pageChanged(CefBrowser browser) {
		WebTab t = WebWindow.TABS.get(browser);
		if (t != null) java.util.Arrays.fill(t.media, false);
	}

	public static final Object SRC_GAME = "game", SRC_BROWSER = "browser";

	private static void sendTabList(WebTab asker) {
		StringBuilder js = new StringBuilder("window.__btPick&&__btPick([");
		boolean first = true;
		for (WebTab o : WebWindow.TABS.values()) {
			if (o.owner != asker.owner) continue;
			if (!first) js.append(',');
			first = false;
			js.append("{id:").append(o.id).append(",title:").append(jsString(o.title)).append(",me:").append(o == asker).append('}');
		}
		js.append("])");
		String code = js.toString();
		Chromium.later(() -> {
			CefFrame f = asker.getMainFrame();
			if (f != null) f.executeJavaScript(code, "", 0);
		});
	}

	private static String jsString(String s) {
		StringBuilder b = new StringBuilder("'");
		for (char c : (s == null ? "" : s).toCharArray()) {
			if (c == '\\' || c == '\'') b.append('\\').append(c);
			else if (c < 32 || c == 0x2028 || c == 0x2029 || c == '<') b.append(String.format("\\u%04x", (int) c));
			else b.append(c);
		}
		return b.append('\'').toString();
	}

	public static java.util.List<WebTab> sharers() {
		java.util.List<WebTab> out = new java.util.ArrayList<>();
		for (WebTab t : WebWindow.TABS.values()) if (t.media[SCREEN] && t.shareSrc != null) out.add(t);
		return out;
	}

	public static Object shareSource(WebTab t) {
		return t.shareSrc;
	}

	public static int[] sourcePixels(WebTab sharer, int[] dims) {
		Object src = sharer.shareSrc;
		if (src == SRC_BROWSER) {
			WebWindow w = sharer.owner;
			synchronized (w.frameLock()) {
				int[] f = w.frame;
				if (f == null || f.length != w.w * w.h) return null;
				dims[0] = w.w;
				dims[1] = w.h;
				return f.clone();
			}
		}
		if (src instanceof WebTab tab) {
			if (!WebWindow.TABS.containsValue(tab)) {
				endShare(sharer);
				return null;
			}
			synchronized (tab) {
				dims[0] = tab.pw;
				dims[1] = tab.ph;
				return tab.page.clone();
			}
		}
		return null;
	}

	private static void endShare(WebTab t) {
		t.media[SCREEN] = false;
		t.shareSrc = null;
		Chromium.later(() -> {
			CefFrame f = t.getMainFrame();
			if (f != null) f.executeJavaScript("window.__btMedia&&__btMedia.ended('scr')", "", 0);
		});
	}

	public static void sendScreenTo(WebTab t, byte[] jpeg) {
		sendVideo(t, SCREEN, "window.__btMedia&&__btMedia.frame('scr','" + Base64.getEncoder().encodeToString(jpeg) + "')");
	}

	private static void sendVideo(WebTab t, int kind, String js) {
		if (t.pendingVideo[kind].getAndSet(js) != null) return;
		Chromium.later(() -> {
			String latest = t.pendingVideo[kind].getAndSet(null);
			if (latest == null || !t.media[kind]) return;
			CefFrame f = t.getMainFrame();
			if (f != null) f.executeJavaScript(latest, "", 0);
		});
	}

	public static boolean wants(WebWindow w, int kind) {
		for (WebTab t : WebWindow.TABS.values()) if (t.owner == w && t.media[kind]) return true;
		return false;
	}

	public static boolean any(int kind) {
		for (WebTab t : WebWindow.TABS.values()) if (t.media[kind]) return true;
		return false;
	}

	public static void sendFrame(WebWindow w, int kind, byte[] jpeg) {
		send(w, kind, "window.__btMedia&&__btMedia.frame('" + KEYS[kind] + "','" + Base64.getEncoder().encodeToString(jpeg) + "')");
	}

	public static void sendPcm(WebWindow w, byte[] pcm16le) {
		send(w, MIC, "window.__btMedia&&__btMedia.pcm('" + Base64.getEncoder().encodeToString(pcm16le) + "')");
	}

	private static void send(WebWindow w, int kind, String js) {
		for (WebTab t : WebWindow.TABS.values()) {
			if (t.owner != w || !t.media[kind]) continue;
			if (kind != MIC) {
				sendVideo(t, kind, js);
				continue;
			}
			Chromium.later(() -> {
				CefFrame f = t.getMainFrame();
				if (f != null) f.executeJavaScript(js, "", 0);
			});
		}
	}

	static final String JS = "(function(){if(window.__btMedia)return;var md=navigator.mediaDevices;if(!md)return;"
			+ "var T='" + TAG + "';"
			+ "var og=md.getUserMedia?md.getUserMedia.bind(md):null,oe=md.enumerateDevices?md.enumerateDevices.bind(md):null;"

			+ "var S={cam:{w:640,h:360,n:0},scr:{w:1280,h:720,n:0},mic:{n:0}};"
			+ "function cv(k){var s=S[k];if(!s.c){s.c=document.createElement('canvas');s.c.width=s.w;s.c.height=s.h;"
			+ "s.x=s.c.getContext('2d');s.x.fillStyle='#000';s.x.fillRect(0,0,s.w,s.h)}return s}"
			+ "function hold(k,tr,label){var s=S[k];(s.ts=s.ts||[]).push(tr);s.n++;if(s.n===1)console.log(T+k+'1');var st=tr.stop.bind(tr),done=false;"
			+ "tr.stop=function(){if(!done){done=true;s.n--;if(s.n===0){console.log(T+k+'0');if(k==='mic'&&A&&A.real){A.real.getTracks().forEach(function(x){x.stop()});A.real=null;A.src=null}}}st()};"
			+ "try{Object.defineProperty(tr,'label',{value:label})}catch(e){}return tr}"
			+ "function vt(k){var s=cv(k);var tr=s.c.captureStream(30).getVideoTracks()[0];"
			+ "if(k==='scr'){var gs=tr.getSettings.bind(tr);tr.getSettings=function(){var o=gs();o.displaySurface='monitor';return o}}"
			+ "return hold(k,tr,k==='cam'?'Minecraft Camera':'Minecraft')}"

			+ "function tier(){var t=window.__btTier;return t===undefined?4:t}"
			+ "var A=null;function ac(){if(!A){var c=new AudioContext({sampleRate:48000});var d=c.createMediaStreamDestination();"
			+ "var lp=c.createBiquadFilter();lp.type='lowpass';lp.frequency.value=[3400,3400,7000,12000,20000][tier()];lp.Q.value=0.7;lp.connect(d);"
			+ "var g=c.createGain();g.connect(lp);"
			+ "var hiss=[0,0.006,0.003,0,0][tier()];if(hiss>0){var nb=c.createBuffer(1,48000,48000),nd=nb.getChannelData(0);for(var i=0;i<48000;i++)nd[i]=(Math.random()*2-1)*hiss;"
			+ "var ns=c.createBufferSource();ns.buffer=nb;ns.loop=true;ns.connect(lp);ns.start()}"
			+ "A={c:c,d:d,lp:lp,g:g,t:0,real:null,src:null}}if(A.c.state==='suspended')A.c.resume();return A}"
			+ "function at(c){var a=ac();var p=(!a.real&&og)?og({audio:c===true?true:c}).then(function(ms){a.real=ms;a.src=a.c.createMediaStreamSource(ms);a.src.connect(a.lp)},function(){}):Promise.resolve();"
			+ "return p.then(function(){return hold('mic',a.d.stream.getAudioTracks()[0].clone(),'Microphone (Minecraft)')})}"

			+ "md.getUserMedia=function(c){c=c||{};if(tier()===0&&(c.audio||c.video))return Promise.reject(new DOMException('Requested device not found','NotFoundError'));var tr=[];var p=c.audio?at(c.audio).then(function(t){tr.push(t)}):Promise.resolve();"
			+ "return p.then(function(){if(c.video)tr.push(vt('cam'));if(!tr.length)throw new DOMException('No media requested','TypeError');return new MediaStream(tr)})};"

			+ "function pick(){return new Promise(function(res,rej){window.__btPick=function(list){window.__btPick=null;"
			+ "var o=document.createElement('div');o.style.cssText='position:fixed;inset:0;z-index:2147483647;background:rgba(0,0,0,.6);display:flex;align-items:center;justify-content:center;font:15px system-ui,sans-serif';"
			+ "var p=document.createElement('div');p.style.cssText='background:#202124;color:#e8eaed;border-radius:10px;padding:20px 22px;min-width:340px;max-width:520px;max-height:80vh;overflow:auto;box-shadow:0 8px 30px rgba(0,0,0,.5)';"
			+ "p.innerHTML='<div style=\"font-size:18px;margin-bottom:14px\">Choose what to share</div>';o.appendChild(p);"
			+ "function btn(label,sub,v){var b=document.createElement('button');b.style.cssText='display:block;width:100%;text-align:left;margin:6px 0;padding:10px 12px;border:1px solid #5f6368;border-radius:6px;background:#303134;color:#e8eaed;font:inherit;cursor:pointer';"
			+ "b.textContent=label;if(sub){var s=document.createElement('div');s.style.cssText='font-size:12px;color:#9aa0a6;margin-top:2px';s.textContent=sub;b.appendChild(s)}"
			+ "b.onclick=function(){o.remove();if(v===null){rej(new DOMException('Permission denied','NotAllowedError'))}else{console.log(T+'src:'+v);res()}};p.appendChild(b)}"
			+ "btn('Entire screen','The whole browser, whichever tab is showing','browser');"
			+ "var h=document.createElement('div');h.style.cssText='margin:12px 0 2px;color:#9aa0a6;font-size:13px';h.textContent='Tabs';p.appendChild(h);"
			+ "list.forEach(function(t){btn(t.title||'Tab',t.me?'This tab':'','tab:'+t.id)});btn('Cancel','',null);"
			+ "(document.body||document.documentElement).appendChild(o)};console.log(T+'pick')})}"
			+ "md.getDisplayMedia=function(c){return pick().then(function(){return new MediaStream([vt('scr')])})};"
			+ "md.enumerateDevices=function(){var p=oe?oe():Promise.resolve([]);return p.then(function(l){var o=[],mic=false;"
			+ "l.forEach(function(d){if(d.kind==='videoinput')return;if(d.kind==='audioinput')mic=true;o.push(d)});"
			+ "function dev(id,kind,label){return{deviceId:id,kind:kind,label:label,groupId:'btemu',toJSON:function(){return{deviceId:id,kind:kind,label:label,groupId:'btemu'}}}}"
			+ "if(tier()===0)return o.filter(function(d){return d.kind==='audiooutput'});"
			+ "if(!mic)o.push(dev('default','audioinput','Microphone (Minecraft)'));o.push(dev('btemu-camera','videoinput','Minecraft Camera'));return o})};"
			+ "try{var pq=navigator.permissions.query.bind(navigator.permissions);navigator.permissions.query=function(d){"
			+ "if(d&&/^(camera|microphone|display-capture)$/.test(d.name))return Promise.resolve({state:'granted',name:d.name,onchange:null,addEventListener:function(){},removeEventListener:function(){}});return pq(d)}}catch(e){}"

			+ "try{var noH=function(sdp){if(!sdp)return sdp;var L=sdp.split('\\r\\n'),bad={},i;"
			+ "for(i=0;i<L.length;i++){var m=/^a=rtpmap:(\\d+) H264\\//i.exec(L[i]);if(m)bad[m[1]]=1}"
			+ "for(i=0;i<L.length;i++){var r=/^a=fmtp:(\\d+) apt=(\\d+)/.exec(L[i]);if(r&&bad[r[2]])bad[r[1]]=1}"
			+ "if(!Object.keys(bad).length)return sdp;"
			+ "return L.filter(function(l){var m=/^a=(rtpmap|fmtp|rtcp-fb):(\\d+)/.exec(l);return !(m&&bad[m[2]])}).map(function(l){"
			+ "if(l.indexOf('m=video')!==0)return l;var p=l.split(' ');return p.slice(0,3).concat(p.slice(3).filter(function(x){return !bad[x]})).join(' ')}).join('\\r\\n')};"
			+ "var gc=RTCRtpSender.getCapabilities;if(gc)RTCRtpSender.getCapabilities=function(k){var c=gc.call(RTCRtpSender,k);"
			+ "if(c&&k==='video')c.codecs=c.codecs.filter(function(x){return !/h264/i.test(x.mimeType)});return c};"
			+ "var PC=RTCPeerConnection.prototype;['setLocalDescription','setRemoteDescription'].forEach(function(n){var o=PC[n];"
			+ "PC[n]=function(d){if(d&&d.sdp){try{d={type:d.type,sdp:noH(d.sdp)}}catch(e){}}return o.apply(this,d===undefined?[]:[d].concat([].slice.call(arguments,1)))}});"
			+ "var co=PC.createOffer,ca=PC.createAnswer;"
			+ "PC.createOffer=function(){return co.apply(this,arguments).then(function(d){return d&&d.sdp?{type:d.type,sdp:noH(d.sdp)}:d})};"
			+ "PC.createAnswer=function(){return ca.apply(this,arguments).then(function(d){return d&&d.sdp?{type:d.type,sdp:noH(d.sdp)}:d})};"
			+ "if(window.RTCRtpTransceiver&&RTCRtpTransceiver.prototype.setCodecPreferences){var sp=RTCRtpTransceiver.prototype.setCodecPreferences;"
			+ "RTCRtpTransceiver.prototype.setCodecPreferences=function(c){return sp.call(this,(c||[]).filter(function(x){return !/h264/i.test(x.mimeType)}))}}"
			+ "}catch(e){}"
			+ "window.__btMedia={ended:function(k){var s=S[k],ts=(s.ts||[]).slice();s.ts=[];ts.forEach(function(tr){try{tr.stop();tr.dispatchEvent(new Event('ended'))}catch(e){}})},"

			+ "frame:function(k,b){var s0=cv(k);if(s0.busy)return;s0.busy=1;var i=new Image();i.onerror=function(){s0.busy=0};i.onload=function(){var s=cv(k);s.busy=0;"
			+ "if(i.width!==s.c.width||i.height!==s.c.height){s.c.width=i.width;s.c.height=i.height}s.x.drawImage(i,0,0)};i.src='data:image/jpeg;base64,'+b},"

			+ "pcm:function(b){var a=ac(),s=atob(b),n=s.length>>1,buf=a.c.createBuffer(1,n,48000),f=buf.getChannelData(0);"
			+ "for(var i=0;i<n;i++){var v=s.charCodeAt(2*i)|(s.charCodeAt(2*i+1)<<8);f[i]=(v>32767?v-65536:v)/32768}"
			+ "var src=a.c.createBufferSource();src.buffer=buf;src.connect(a.g);var now=a.c.currentTime;if(a.t<now+0.03)a.t=now+0.08;src.start(a.t);a.t+=n/48000}};"
			+ "})();";
}
