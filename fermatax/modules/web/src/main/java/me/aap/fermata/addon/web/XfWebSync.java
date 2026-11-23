package me.aap.fermata.addon.web;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.SystemClock;

import java.util.Objects;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.addon.web.yt.XfMicRecorder;
import me.aap.fermata.addon.web.yt.YoutubeAddon;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.xf.XfConfig;
import me.aap.fermata.xf.XfLog;
import me.aap.utils.log.Log;

/**
 * xFermaTube: A/V sync (the delayed picture and the microphone measurement, assets/xf-video-delay.js)
 * on the pages of the Web browser, not only on YouTube.
 * <p>
 * Any site can be open in the browser and the scripts of all its frames reach the same JS bridge.
 * So everything here works on the car screen only and while the page is shown, the input is
 * short and limited in rate, and the microphone is used only while sound is playing. The page
 * gets a loudness curve, never the sound itself (see {@link XfMicRecorder}), and only if it is
 * still the page that asked.
 * <p>
 * The delay belongs to the connection (the car and USB or wireless), not to the site, and a
 * site's localStorage is its own. So the last delay set anywhere is kept here and handed to
 * every Web page as its starting value.
 */
public final class XfWebSync {
	private static final String PREFS = "xf_sync";
	private static final int MAX_ARG = 32;      // "wifi;3000;-500" and "11.5;ask" are much shorter
	private static final int MAX_LOG = 400;
	private static final long STATE_GAP_MS = 500;
	private static String script;
	private static String selectScript;
	private static String shiftScript;
	private static long stateAt;

	private XfWebSync() {
	}

	/**
	 * The start page of the Web browser: all bookmarks as big buttons, one tap opens one. Made by
	 * the app at this address (no such site exists: ".invalid" is never a real domain), so Back
	 * returns to it like to any page.
	 */
	public static final String START_URL = "https://xf.invalid/start";

	public static boolean isStartPage(String url) {
		return (url != null) && (url.equals(START_URL) || url.startsWith(START_URL + "?") ||
				url.startsWith(START_URL + "#"));
	}

	/** The start page is for the plain browser only (with the Web program on). */
	public static boolean startPageEnabled() {
		return XfConfig.WEB_PROTO;
	}

	/** Answers the browser's request for {@link #START_URL}; null for any other request. */
	static android.webkit.WebResourceResponse startPage(android.webkit.WebView view,
			android.webkit.WebResourceRequest request) {
		if (!startPageEnabled() || !request.isForMainFrame() ||
				!isStartPage(request.getUrl().toString()) || (view == null) ||
				(view.getClass() != FermataWebView.class)) return null;
		WebBrowserAddon addon = AddonManager.get().getAddon(WebBrowserAddon.class);
		java.util.Map<String, String> all = (addon == null) ? java.util.Collections.emptyMap() :
				addon.getBookmarks();
		byte[] html = startHtml(all).getBytes(java.nio.charset.StandardCharsets.UTF_8);
		java.util.Map<String, String> h = new java.util.HashMap<>();
		h.put("Cache-Control", "no-store");
		return new android.webkit.WebResourceResponse("text/html", "UTF-8", 200, "OK", h,
				new java.io.ByteArrayInputStream(html));
	}

	static String startHtml(java.util.Map<String, String> bookmarks) {
		return startHtml(bookmarks, XfWebTabs.list(), XfWebTabs.current());
	}

	static String startHtml(java.util.Map<String, String> bookmarks, java.util.List<XfWebTabs.Tab> tabs,
			int current) {
		StringBuilder b = new StringBuilder(4096 + (bookmarks.size() + tabs.size()) * 256);
		b.append("<!doctype html><html><head><meta charset=\"utf-8\">")
				.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
				.append("<title>Start</title><style>")
				.append("html,body{margin:0;background:#18191c;color:#e8eaed;font:500 20px/1.3 sans-serif}")
				.append("h2{margin:18px 22px 0;font-size:17px;font-weight:600;opacity:.65}")
				.append("main{padding:12px 20px 20px;display:grid;gap:14px;")
				.append("grid-template-columns:repeat(auto-fill,minmax(240px,1fr))}")
				.append(".t{position:relative;display:flex}")
				.append(".t>a.o{flex:1;padding-right:64px}")
				.append("a{display:flex;flex-direction:column;justify-content:center;min-height:96px;")
				.append("padding:14px 18px;border-radius:16px;background:#2a2c31;color:inherit;")
				.append("text-decoration:none;overflow:hidden;box-sizing:border-box}")
				.append("a.o{background:#26324a}a.o.c{outline:2px solid #8ab4f8}")
				.append("a.x{position:absolute;right:6px;top:50%;transform:translateY(-50%);min-height:0;")
				.append("width:52px;height:52px;padding:0;align-items:center;border-radius:26px;")
				.append("background:rgba(255,255,255,.12);font-size:26px}")
				.append("a:active,a:focus{background:#3c4049;outline:2px solid #8ab4f8}")
				.append(".n{overflow:hidden;text-overflow:ellipsis;display:-webkit-box;")
				.append("-webkit-line-clamp:2;-webkit-box-orient:vertical}")
				.append(".h{margin-top:6px;font-size:15px;opacity:.6;white-space:nowrap;overflow:hidden;")
				.append("text-overflow:ellipsis}")
				.append(".e{padding:12px 24px 24px;font-size:19px;opacity:.75;max-width:720px}")
				.append("</style></head><body>");
		if (!tabs.isEmpty()) {
			b.append("<h2>Open pages</h2><main>");
			for (int i = tabs.size() - 1; i >= 0; i--) {   // the newest first
				XfWebTabs.Tab t = tabs.get(i);
				String name = t.title.isEmpty() ? t.url : t.title;
				b.append("<div class=\"t\"><a class=\"o").append((i == current) ? " c" : "")
						.append("\" href=\"https://xf.invalid/tab?i=").append(i).append("\">")
						.append("<span class=\"n\">").append(html(name)).append("</span><span class=\"h\">")
						.append(html(host(t.url))).append("</span></a>")
						.append("<a class=\"x\" aria-label=\"Close\" href=\"https://xf.invalid/close?i=")
						.append(i).append("\">×</a></div>");
			}
			b.append("</main>");
		}
		b.append("<h2>Bookmarks</h2>");
		int n = 0;
		StringBuilder tiles = new StringBuilder();
		for (java.util.Map.Entry<String, String> e : bookmarks.entrySet()) {
			String url = e.getKey();
			if ((url == null) || !(url.startsWith("https://") || url.startsWith("http://"))) continue;
			String name = e.getValue();
			if ((name == null) || name.trim().isEmpty()) name = url;
			tiles.append("<a href=\"").append(html(url)).append("\"><span class=\"n\">")
					.append(html(name.trim())).append("</span><span class=\"h\">").append(html(host(url)))
					.append("</span></a>");
			n++;
		}
		if (n == 0) {
			b.append("<div class=\"e\">No bookmarks yet. Open a page, tap the bookmark button ")
					.append("in the top bar and choose “Bookmark this page”.</div>");
		} else {
			b.append("<main>").append(tiles).append("</main>");
		}
		return b.append("</body></html>").toString();
	}

	private static String host(String url) {
		try {
			String h = android.net.Uri.parse(url).getHost();
			if (h != null) return h.startsWith("www.") ? h.substring(4) : h;
		} catch (RuntimeException ignore) {
		}
		return url;
	}

	private static String html(String s) {
		StringBuilder b = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '<' -> b.append("&lt;");
				case '>' -> b.append("&gt;");
				case '&' -> b.append("&amp;");
				case '"' -> b.append("&quot;");
				case '\'' -> b.append("&#39;");
				default -> b.append(c);
			}
		}
		return b.toString();
	}

	/** "phone": not on the car screen; "usb": Android Auto over the cable; "wifi": wireless. */
	public static String connection(Context context) {
		try {
			if (!MainActivityDelegate.get(context).isCarActivity()) return "phone";
			Context ctx = context.getApplicationContext();
			android.hardware.usb.UsbManager usb =
					(android.hardware.usb.UsbManager) ctx.getSystemService(Context.USB_SERVICE);
			android.hardware.usb.UsbAccessory[] acc = (usb != null) ? usb.getAccessoryList() : null;
			if ((acc != null) && (acc.length > 0)) return "usb";
			// Charging over a cable in the car = Android Auto over the cable (wireless: not charging,
			// or on a wireless charger).
			android.content.Intent bat = ctx.registerReceiver(null,
					new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
			int plugged = (bat != null) ? bat.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) : 0;
			if ((plugged == android.os.BatteryManager.BATTERY_PLUGGED_USB) ||
					(plugged == android.os.BatteryManager.BATTERY_PLUGGED_AC)) return "usb";
			return "wifi";
		} catch (Throwable ex) {
			Log.d(ex, "xF connection type");
			return "wifi";
		}
	}

	/** The plain browser only: YouTube has its own setup, other addons do not use the sync. */
	private static boolean isBrowser(FermataWebView v) {
		return XfConfig.WEB_PROTO && (v != null) && (v.getClass() == FermataWebView.class);
	}

	/** Known to be the car screen. Not known (no activity, an error) counts as "no". */
	private static boolean onCar(FermataWebView v) {
		try {
			return MainActivityDelegate.get(v.getContext()).isCarActivity();
		} catch (RuntimeException ex) {
			return false;
		}
	}

	/** A page of the browser, on the car screen and shown now. */
	private static boolean active(FermataWebView v) {
		return isBrowser(v) && v.isShown() && onCar(v);
	}

	/** A page of the Web browser has loaded: on the car screen it gets the sync script. */
	static void pageLoaded(FermataWebView v) {
		if (isBrowser(v)) {
			XfWebTabs.pageLoaded(v.getUrl(), v.getTitle());
			WebToolBarMediator.xfTabsChanged(v);
		}
		if (!isBrowser(v) || !onCar(v)) return;
		// Drop-down lists: the system list window cannot be shown on the car screen
		if (selectScript == null) selectScript = asset(v.getContext(), "xf-select.js");
		if (selectScript != null) v.evaluateJavascript(selectScript, null);
		String conn = connection(v.getContext());
		if (!"usb".equals(conn) && !"wifi".equals(conn)) return;
		String js = script(v.getContext());
		if (js == null) return;
		v.evaluateJavascript("window.__xfWeb=true;window.__xfConn='" + conn + "';window.__xfBadge=" +
				badge() + ";window.__xfBadgePos='" + badgePos(true) + "';window.__xfSeed=" + seed(conn) +
				";" + js, null);
	}

	/**
	 * Protected (DRM) video: its frames cannot be copied, so the picture is delayed by moving the
	 * timestamps of the video track (assets/xf-mse-shift.js). That has to be in the page before its
	 * player starts, so it is added at document start, once for the plain browser on the car screen.
	 */
	static void installShift(FermataWebView v, boolean car) {
		if (!car || !isBrowser(v)) return;
		if (!androidx.webkit.WebViewFeature.isFeatureSupported(
				androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return;
		if (shiftScript == null) shiftScript = asset(v.getContext(), "xf-mse-shift.js");
		if (shiftScript == null) return;
		try {
			androidx.webkit.WebViewCompat.addDocumentStartJavaScript(v, shiftScript,
					java.util.Collections.singleton("*"));
		} catch (RuntimeException ex) {
			Log.w(ex, "xF shift script");
		}
	}

	/**
	 * The picture delay for a DRM video of this page: the last known delay of the connection, or -1
	 * (not the browser on the car screen, no sync on this connection, no delay known yet).
	 * Called from the page on a JavaScript bridge thread.
	 */
	static int shiftMs(FermataWebView v) {
		if (!isBrowser(v) || !onCar(v)) return -1;
		String conn = connection(v.getContext());
		if (!"usb".equals(conn) && !"wifi".equals(conn)) return -1;
		SharedPreferences p = prefs();
		if (!p.contains("d_" + conn)) return -1;
		return Math.max(0, Math.min(3000, p.getInt("d_" + conn, 0)));
	}

	private static String script(Context ctx) {
		if (script == null) script = asset(ctx, "xf-video-delay.js");
		return script;
	}

	private static String asset(Context ctx, String name) {
		try (java.io.InputStream in = ctx.getAssets().open(name)) {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
			return out.toString("UTF-8");
		} catch (java.io.IOException ex) {
			Log.e(ex, "Failed to load " + name);
			return null;
		}
	}

	/** The "Show sync badge" setting of the YouTube part applies to the browser too. */
	private static boolean badge() {
		try {
			YoutubeAddon yt = AddonManager.get().getAddon(YoutubeAddon.class);
			return (yt == null) || yt.xfSyncBadge();
		} catch (RuntimeException ex) {
			return true;
		}
	}

	/** Where ⏱ sits ("x,y"), for YouTube or for the Web browser. */
	public static String badgePos(boolean web) {
		try {
			YoutubeAddon yt = AddonManager.get().getAddon(YoutubeAddon.class);
			return (yt == null) ? "0,0" : yt.xfBadgePos(web);
		} catch (RuntimeException ex) {
			return "0,0";
		}
	}

	/** ⏱ was dragged on a page: YouTube's view saves the YouTube place, a browser page the Web one. */
	static void saveBadgePos(FermataWebView v, String data) {
		boolean web;
		if (isBrowser(v)) web = true;
		else if (v instanceof me.aap.fermata.addon.web.yt.YoutubeWebView) web = false;
		else return;
		try {
			YoutubeAddon yt = AddonManager.get().getAddon(YoutubeAddon.class);
			if (yt != null) yt.xfSetBadgePos(web, data);
		} catch (RuntimeException ex) {
			Log.w(ex, "xF badge position");
		}
	}

	private static SharedPreferences prefs() {
		return FermataApplication.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	/**
	 * The last known delay and microphone correction of this connection, and the sync signal's
	 * constant (k: the page's sound reaches the microphone this much later than the app's own sync
	 * signal; it belongs to the phone, so another connection's value is used until this one has its own).
	 */
	private static String seed(String conn) {
		SharedPreferences p = prefs();
		Integer k = p.contains("k_" + conn) ? Integer.valueOf(p.getInt("k_" + conn, 0)) : null;
		for (String c : new String[]{"usb", "wifi", "phone"}) {
			if ((k == null) && p.contains("k_" + c)) k = p.getInt("k_" + c, 0);
		}
		String ks = (k != null) ? "k:" + k : "";
		if (!p.contains("d_" + conn)) return ks.isEmpty() ? "null" : "{" + ks + "}";
		return "{d:" + p.getInt("d_" + conn, 0) + ",o:" + p.getInt("o_" + conn, 0) +
				(ks.isEmpty() ? "" : "," + ks) + "}";
	}

	/**
	 * The page reports the delay it now uses: "conn;delayMs;correctionMs[;signalK]" (the last: the
	 * sync signal's constant, see XfLinkProbe and seed()). Comes from page script:
	 * taken only for the connection the page really is on, as numbers within the script's own
	 * limits, and not more often than the script itself reports.
	 *
	 * @param conn the connection of the web view that sent it
	 */
	public static void saveState(String data, String conn) {
		if ((data == null) || (data.length() > MAX_ARG) || (conn == null)) return;
		if (!"usb".equals(conn) && !"wifi".equals(conn) && !"phone".equals(conn)) return;
		long now = SystemClock.elapsedRealtime();
		if (now - stateAt < STATE_GAP_MS) return;
		String[] s = data.split(";", 5);
		if (((s.length != 3) && (s.length != 4)) || !conn.equals(s[0])) return;
		try {
			int d = Integer.parseInt(s[1]);
			int o = Integer.parseInt(s[2]);
			if ((d < 0) || (d > 3000) || (o < -500) || (o > 500)) return;
			Integer c = (s.length == 4) ? Integer.valueOf(Integer.parseInt(s[3])) : null;
			if ((c != null) && ((c < -500) || (c > 500))) return;
			SharedPreferences p = prefs();
			if (p.contains("d_" + conn) && (p.getInt("d_" + conn, 0) == d) && (p.getInt("o_" + conn, 0) == o) &&
					((c == null) || (p.contains("k_" + conn) && (p.getInt("k_" + conn, 0) == c))))
				return;
			stateAt = now;
			SharedPreferences.Editor e = p.edit().putInt("d_" + conn, d).putInt("o_" + conn, o);
			if (c != null) e.putInt("k_" + conn, c);
			e.apply();
		} catch (NumberFormatException ignore) {
		}
	}

	/** {@link #saveState(String, String)} from a page of the browser. */
	static void saveState(FermataWebView v, String data) {
		if (active(v)) saveState(data, connection(v.getContext()));
	}

	static void log(FermataWebView v, String data) {
		if ((data != null) && (data.length() <= MAX_LOG) && active(v)) XfLog.fromPage("web " + data);
	}

	/**
	 * A microphone measurement asked by a page of the browser: on the car screen only, the page
	 * must be on screen and sound must be playing (the measurement compares the video's sound with
	 * what the microphone hears, so without sound there is nothing to measure). The result goes
	 * only to the page that asked: not after it was left or hidden.
	 */
	static void mic(FermataWebView v, String data) {
		if ((data == null) || (data.length() > MAX_ARG) || !active(v)) return;
		Context ctx = v.getContext();
		AudioManager am = (AudioManager) ctx.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
		if ((am == null) || !am.isMusicActive()) {
			XfLog.fromPage("web: microphone not used, no sound is playing");
			XfMicRecorder.refuse(v, "no sound playing");
			return;
		}
		// The permission cannot be asked on the car screen: drop the page's ";ask"
		int sep = data.indexOf(';');
		String url = v.getUrl();
		XfMicRecorder.record(v, (sep >= 0) ? data.substring(0, sep) : data,
				() -> active(v) && Objects.equals(url, v.getUrl()));
	}
}
