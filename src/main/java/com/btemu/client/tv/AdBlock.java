package com.btemu.client.tv;

import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandler;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class AdBlock {
	private AdBlock() {}

	private static final String[] HOSTS = {

		"doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
		"pagead2.googlesyndication.com", "googletagservices.com", "2mdn.net", "admob.com",
		"fundingchoicesmessages.google.com",

		"amazon-adsystem.com", "bat.bing.com", "ads.yahoo.com", "advertising.com", "adtech.de",
		"criteo.com", "criteo.net", "taboola.com", "outbrain.com", "outbrainimg.com",

		"adnxs.com", "adnxs-simple.com", "pubmatic.com", "rubiconproject.com", "openx.net",
		"adsrvr.org", "casalemedia.com", "indexww.com", "3lift.com", "triplelift.com",
		"sharethrough.com", "teads.tv", "smartadserver.com", "adform.net", "33across.com",
		"bidswitch.net", "contextweb.com", "lijit.com", "sovrn.com", "yieldmo.com", "gumgum.com",
		"media.net", "spotxchange.com", "spotx.tv", "springserve.com", "smaato.net",
		"adcolony.com", "applovin.com", "inmobi.com", "chartboost.com", "vungle.com",
		"unityads.unity3d.com", "mopub.com", "zedo.com", "adition.com", "yieldlab.net",
		"emxdgt.com", "districtm.io", "onetag-sys.com", "seedtag.com", "connectad.io",
		"adkernel.com", "rhythmone.com", "undertone.com", "kargo.com", "justpremium.com",
		"nativo.com", "revcontent.com", "mgid.com", "adskeeper.com", "adblade.com",
		"zergnet.com", "content.ad", "lockerdome.com", "dianomi.com", "sonobi.com",
		"ad.gt", "adlightning.com", "confiant-integrations.net", "clean.gg",

		"popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "exoclick.com",
		"exosrv.com", "juicyads.com", "adsterra.com", "hilltopads.net", "trafficjunky.net",
		"clickadu.com", "adcash.com", "admaven.com", "onclickads.net", "pushame.com",
		"a-ads.com", "coinzilla.com", "bitmedia.io", "galaksion.com", "monetag.com",

		"adsafeprotected.com", "doubleverify.com", "moatads.com", "moat.com", "iasds01.com",
		"scorecardresearch.com", "quantserve.com", "quantcount.com",

		"blockadblock.com", "fuckadblock.com", "pagefair.com", "pagefair.net", "admiral.mgr.consensu.org",
		"getadmiral.com", "adsbygoogle.com", "detectadblock.com", "adblockanalytics.com", "sourcepoint.com",
		"wpadmngr.com", "adthrive.com", "adthrive.app", "mediavine.com", "ezoic.net", "ezojs.com",
	};

	private static final String[] PATHS = {
		"/adsbygoogle.js", "/pagead/js/", "/gpt.js", "/prebid", "/pubads_impl", "/show_ads",
		"/fuckadblock", "/blockadblock", "/adblock-detect", "/ads-prebid", "/adframe.js",
	};

	private static volatile Set<String> hosts;

	private static Set<String> hosts() {
		Set<String> h = hosts;
		if (h != null) return h;
		h = new HashSet<>();
		for (String s : HOSTS) h.add(s);

		try {
			File f = new File(System.getProperty("user.dir"), "btemu-adblock.txt");
			if (f.isFile()) {
				for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
					String l = line.trim().toLowerCase(Locale.ROOT);
					if (l.isEmpty() || l.startsWith("!") || l.startsWith("#")) continue;
					if (l.startsWith("||")) l = l.substring(2);
					int end = l.indexOf('^');
					if (end >= 0) l = l.substring(0, end);
					if (l.matches("[a-z0-9.-]+\\.[a-z]{2,}")) h.add(l);
				}
			}
		} catch (Exception ignored) {

		}
		hosts = h;
		return h;
	}

	static boolean blocks(String url) {
		if (url == null || !(url.startsWith("http:") || url.startsWith("https:"))) return false;
		String host;
		try {
			host = URI.create(url).getHost();
		} catch (Exception e) {
			return false;
		}
		if (host == null) return false;
		host = host.toLowerCase(Locale.ROOT);
		Set<String> h = hosts();
		for (String d = host; ; ) {
			if (h.contains(d)) return true;
			int dot = d.indexOf('.');
			if (dot < 0 || d.indexOf('.', dot + 1) < 0) break;
			d = d.substring(dot + 1);
		}
		String lower = url.toLowerCase(Locale.ROOT);
		for (String p : PATHS) if (lower.contains(p)) return true;
		return false;
	}

	private static final byte[] GIF = {
		0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, (byte) 0x80, 0x00, 0x00, 0x00, 0x00, 0x00,
		(byte) 0xff, (byte) 0xff, (byte) 0xff, 0x21, (byte) 0xf9, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00,
		0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3b
	};

	static CefResourceHandler handler(CefRequest request) {
		String mime;
		byte[] body;
		CefRequest.ResourceType type = request.getResourceType();
		String url = request.getURL().toLowerCase(Locale.ROOT);
		if (type == CefRequest.ResourceType.RT_SCRIPT || url.contains(".js")) {
			mime = "application/javascript";
			body = "/* */".getBytes(StandardCharsets.UTF_8);
		} else if (type == CefRequest.ResourceType.RT_IMAGE || url.matches(".*\\.(gif|png|jpe?g|webp)(\\?.*)?")) {
			mime = "image/gif";
			body = GIF;
		} else if (type == CefRequest.ResourceType.RT_STYLESHEET) {
			mime = "text/css";
			body = new byte[0];
		} else if (type == CefRequest.ResourceType.RT_XHR) {
			mime = "application/json";
			body = "{}".getBytes(StandardCharsets.UTF_8);
		} else {
			mime = "text/html";
			body = "<!doctype html><title></title>".getBytes(StandardCharsets.UTF_8);
		}
		return new Dummy(mime, body);
	}

	private static final class Dummy extends CefResourceHandlerAdapter {
		private final String mime;
		private final byte[] body;
		private int pos;

		Dummy(String mime, byte[] body) {
			this.mime = mime;
			this.body = body;
		}

		@Override
		public boolean open(CefRequest request, BoolRef handleRequest, CefCallback callback) {
			handleRequest.set(true);
			return true;
		}

		@Override
		public boolean processRequest(CefRequest request, CefCallback callback) {
			callback.Continue();
			return true;
		}

		@Override
		public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
			response.setStatus(200);
			response.setStatusText("OK");
			response.setMimeType(mime);
			response.setHeaderByName("Access-Control-Allow-Origin", "*", true);
			response.setHeaderByName("Cache-Control", "no-store", true);
			responseLength.set(body.length);
		}

		private boolean copy(byte[] out, int max, IntRef read) {
			int n = Math.min(max, body.length - pos);
			if (n <= 0) {
				read.set(0);
				return false;
			}
			System.arraycopy(body, pos, out, 0, n);
			pos += n;
			read.set(n);
			return true;
		}

		@Override
		public boolean read(byte[] dataOut, int bytesToRead, IntRef bytesRead, org.cef.callback.CefResourceReadCallback callback) {
			return copy(dataOut, bytesToRead, bytesRead);
		}

		@Override
		public boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback) {
			return copy(dataOut, bytesToRead, bytesRead);
		}
	}

	static final String JS = "(function(){if(window.__btAB)return;window.__btAB=1;var W=window,noop=function(){};"

			+ "try{var ag=W.adsbygoogle;if(!ag||!ag.loaded){var a=[];a.loaded=true;a.push=function(){return 1};"
			+ "Object.defineProperty(W,'adsbygoogle',{get:function(){return a},set:function(){},configurable:true})}}catch(e){}"
			+ "try{if(!W.googletag||!W.googletag.apiReady){var slot={};['addService','setTargeting','defineSizeMapping','setCollapseEmptyDiv','setClickUrl','set','updateTargetingFromMap','clearTargeting','setForceSafeFrame','setSafeFrameConfig'].forEach(function(n){slot[n]=function(){return slot}});"
			+ "slot.getSlotElementId=function(){return ''};slot.getAdUnitPath=function(){return ''};slot.getTargeting=function(){return[]};slot.getTargetingKeys=function(){return[]};slot.getResponseInformation=function(){return null};"
			+ "var pa={};['addEventListener','removeEventListener','enableSingleRequest','collapseEmptyDivs','disableInitialLoad','enableLazyLoad','setTargeting','clearTargeting','setPrivacySettings','setRequestNonPersonalizedAds','setCentering','enableVideoAds','setLocation','setPublisherProvidedId','setSafeFrameConfig','setForceSafeFrame','refresh','clear','updateCorrelator','display'].forEach(function(n){pa[n]=function(){return pa}});"
			+ "pa.getSlots=function(){return[]};pa.getTargeting=function(){return[]};pa.getTargetingKeys=function(){return[]};"
			+ "var gt={apiReady:true,pubadsReady:true,cmd:{push:function(f){try{f()}catch(e){}return 1}},pubads:function(){return pa},"
			+ "defineSlot:function(){return slot},defineOutOfPageSlot:function(){return slot},destroySlots:function(){return true},enableServices:noop,display:noop,"
			+ "sizeMapping:function(){var m={addSize:function(){return m},build:function(){return[]}};return m},companionAds:function(){return pa},content:function(){return pa},setAdIframeTitle:noop,getVersion:function(){return ''}};"
			+ "var q=W.googletag&&W.googletag.cmd;W.googletag=gt;if(q&&q.forEach)q.forEach(function(f){try{f()}catch(e){}})}}catch(e){}"

			+ "try{var FAB=function(){};FAB.prototype={check:function(){this._nd&&this._nd.forEach(function(f){try{f()}catch(e){}});return false},"
			+ "emitEvent:function(){return this},clearEvent:noop,on:function(d,f){if(!d)(this._nd=this._nd||[]).push(f),setTimeout(f,1);return this},"
			+ "onDetected:function(){return this},onNotDetected:function(f){(this._nd=this._nd||[]).push(f);setTimeout(f,1);return this},setOption:function(){return this}};"
			+ "['FuckAdBlock','BlockAdBlock','SniffAdBlock','KillAdBlock'].forEach(function(n){W[n]=FAB});"
			+ "['fuckAdBlock','blockAdBlock','sniffAdBlock','killAdBlock'].forEach(function(n){var i=new FAB();try{Object.defineProperty(W,n,{get:function(){return i},set:function(){},configurable:true})}catch(e){W[n]=i}});"
			+ "W.canRunAds=true;W.canShowAds=true;W.isAdBlockActive=false;W.adblock=false;W.adBlockEnabled=false;W.adsBlocked=false;W.google_ad_status=1}catch(e){}"

			+ "var CSS='ins.adsbygoogle[data-ad-slot],[id^=\"google_ads_iframe\"],[id^=\"div-gpt-ad\"],iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication\"],"
			+ "iframe[id^=\"google_ads\"],iframe[src*=\"amazon-adsystem\"],.trc_rbox_container,#taboola-below-article-thumbnails,[id^=\"taboola-\"],.OUTBRAIN,[data-widget-id^=\"AR_\"],"
			+ "[data-google-query-id],div[aria-label=\"Advertisement\"],div[aria-label=\"Ad\"],.adthrive-ad,.mv-ad-box,[id^=\"ezoic-pub-ad\"],.ezoic-ad"
			+ "{max-height:1px!important;min-height:0!important;height:1px!important;opacity:0!important;pointer-events:none!important;overflow:hidden!important;margin:0!important;padding:0!important}';"
			+ "function css(){try{if(document.getElementById('__btab'))return;var s=document.createElement('style');s.id='__btab';s.textContent=CSS;(document.head||document.documentElement).appendChild(s)}catch(e){}}"

			+ "var RX=/ad ?-?block(er|ing)?|adblock|ad blocker|disable (your )?(ad|content) ?block|whitelist (us|this site)|allow ?list (us|this site)|turn off (your )?ad/i;"
			+ "function big(el){var r=el.getBoundingClientRect();return r.width*r.height>W.innerWidth*W.innerHeight*0.15}"
			+ "function zap(){try{var b=document.body;if(!b)return;var hit=false;var all=b.querySelectorAll('div,section,aside,dialog');"
			+ "for(var i=0;i<all.length;i++){var el=all[i];var st=getComputedStyle(el);if(st.position!=='fixed'&&st.position!=='absolute'&&el.tagName!=='DIALOG')continue;"
			+ "if((parseInt(st.zIndex)||0)<10&&el.tagName!=='DIALOG')continue;var t=el.innerText||'';if(t.length>3000||!RX.test(t)||!big(el))continue;el.remove();hit=true}"
			+ "if(hit){var bk=b.querySelectorAll('div');for(var j=0;j<bk.length;j++){var s2=getComputedStyle(bk[j]);"
			+ "if(s2.position==='fixed'&&(parseInt(s2.zIndex)||0)>=100&&!(bk[j].innerText||'').trim()&&big(bk[j]))bk[j].remove()}"
			+ "[document.documentElement,b].forEach(function(n){if(getComputedStyle(n).overflow==='hidden')n.style.setProperty('overflow','auto','important');"
			+ "n.style.removeProperty('position');n.classList.remove('modal-open','no-scroll','noscroll','overflow-hidden')});"
			+ "b.style.removeProperty('filter');var m=document.querySelector('main');if(m)m.style.removeProperty('filter')}}catch(e){}}"
			+ "css();document.addEventListener('DOMContentLoaded',function(){css();zap()});"
			+ "var n=0,iv=setInterval(function(){css();zap();if(++n>40)clearInterval(iv)},750);"
			+ "try{new MutationObserver(function(){if(zap._t)return;zap._t=setTimeout(function(){zap._t=0;zap()},300)}).observe(document.documentElement,{childList:true,subtree:true})}catch(e){}"
			+ "})();";
}
