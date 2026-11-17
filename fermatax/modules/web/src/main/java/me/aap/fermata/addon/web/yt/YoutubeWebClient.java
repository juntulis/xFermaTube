package me.aap.fermata.addon.web.yt;

import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import me.aap.fermata.addon.web.FermataWebClient;
import me.aap.fermata.addon.web.WebBrowserFragment;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;

/**
 * @author Andrey Pavlenko
 */
public class YoutubeWebClient extends FermataWebClient {
	public YoutubeWebClient() {
		super();
	}

	private YoutubeWebClient(DiagnosticsObserver observer) {
		super(observer);
	}

	@Override
	protected FermataWebClient newReplacement() {
		return new YoutubeWebClient(getDiagnosticsObserver());
	}

	@Override
	public void onPageStarted(WebView view, String url, Bitmap favicon) {
		MainActivityDelegate.get(view.getContext()).clearVoiceSelection();
		if (view instanceof YoutubeWebView youtube) {
			youtube.onMainFramePageStarted();
			youtube.syncYoutubeTheme(youtube.isDarkTheme());
			youtube.evaluateJavascript(YoutubeScripts.PREFER_H264 + YoutubeScripts.ADBLOCK_CSS + YoutubeScripts.NETWORK_ADBLOCK, null);
		}
		super.onPageStarted(view, url, favicon);
	}

	@Override
	public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
		if (!request.isForMainFrame()) return false;
		if (request.hasGesture() && (view instanceof YoutubeWebView youtube)) {
			try {
				YoutubeItem.fromPageUrl(request.getUrl().toString(), "", 0L);
				if (youtube.getMediaEngine().routeExplicitSelection(request.getUrl().toString())) return true;
				youtube.armExplicitPlayback();
			} catch (IllegalArgumentException ignored) {
			}
		}
		// xFermata: Google prisijungimas lieka YouTube lange, kad po jo grįžtų tiesiai į YouTube
		// (slapukai bendri telefonui ir automobiliui: prisijungus telefone, prisijungta ir mašinoje).
		if (isGoogleSignIn(request.getUrl().getHost())) return false;
		if (!isYoutubeUri(request.getUrl())) {
			MainActivityDelegate a = MainActivityDelegate.get(view.getContext());

			try {
				if (!(a.showFragment(
						me.aap.fermata.R.id.web_browser_fragment) instanceof WebBrowserFragment f))
					return false;
				f.loadUrl(request.getUrl().toString());
				return true;
			} catch (IllegalArgumentException ex) {
				Log.d(ex);
			}
		}

		return false;
	}

	// xFermaTube: sinchronizacijos testo video (assets/xf-sync-test.webm) puslapiui adresu
	// https://xf.invalid/sync-test.webm. Atsakoma su CORS, kad puslapis galėtų jį ir „klausyti“ (🎤 matavimas),
	// ir su Range, nes medijos grotuvas skaito dalimis.
	static final String XF_TEST_URL = "https://xf.invalid/sync-test.webm";
	private static volatile byte[] xfTestVideo;

	@Override
	public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view,
			WebResourceRequest request) {
		if (XF_TEST_URL.equals(request.getUrl().toString())) {
			try {
				return xfTestResponse(view.getContext(), request);
			} catch (Exception ex) {
				Log.e(ex, "xF sync test video");
				return null;
			}
		}
		return super.shouldInterceptRequest(view, request);
	}

	private static android.webkit.WebResourceResponse xfTestResponse(android.content.Context ctx,
			WebResourceRequest request) throws java.io.IOException {
		byte[] data = xfTestVideo;
		if (data == null) {
			try (java.io.InputStream in = ctx.getAssets().open("xf-sync-test.webm")) {
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(600_000);
				byte[] buf = new byte[16384];
				for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
				xfTestVideo = data = out.toByteArray();
			}
		}
		java.util.Map<String, String> h = new java.util.HashMap<>();
		h.put("Access-Control-Allow-Origin", "*");
		h.put("Accept-Ranges", "bytes");
		h.put("Cache-Control", "no-store");
		int from = 0, to = data.length - 1, status = 200;
		String range = null;
		for (java.util.Map.Entry<String, String> e : request.getRequestHeaders().entrySet()) {
			if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();
		}
		if ((range != null) && range.startsWith("bytes=")) {
			try {
				String[] p = range.substring(6).split(",")[0].trim().split("-", -1);
				if (!p[0].isEmpty()) {
					from = Integer.parseInt(p[0]);
					if ((p.length > 1) && !p[1].isEmpty()) to = Math.min(to, Integer.parseInt(p[1]));
				} else if ((p.length > 1) && !p[1].isEmpty()) {
					from = Math.max(0, data.length - Integer.parseInt(p[1]));
				}
				if (from > to) from = to;
				status = 206;
				h.put("Content-Range", "bytes " + from + "-" + to + "/" + data.length);
			} catch (NumberFormatException ignored) {
				from = 0;
				to = data.length - 1;
			}
		}
		h.put("Content-Length", String.valueOf(to - from + 1));
		return new android.webkit.WebResourceResponse("video/webm", null, status,
				(status == 206) ? "Partial Content" : "OK", h,
				new java.io.ByteArrayInputStream(data, from, to - from + 1));
	}

	static boolean isGoogleSignIn(String host) {
		if (host == null) return false;
		host = host.toLowerCase(java.util.Locale.ROOT);
		return host.startsWith("accounts.google.") || host.equals("accounts.youtube.com") ||
				host.equals("myaccount.google.com") || host.equals("gds.google.com");
	}

	@Override
	public void onPageFinished(WebView view, String url) {
		super.onPageFinished(view, url);
		if (view instanceof YoutubeWebView youtube) {
			youtube.syncYoutubeTheme(youtube.isDarkTheme());
			if (youtube.acceptsPageCallback(url))
				youtube.collectVoiceSearchResults(url);
		}
	}
}
