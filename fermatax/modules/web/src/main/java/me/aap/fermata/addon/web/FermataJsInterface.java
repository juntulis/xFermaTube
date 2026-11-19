package me.aap.fermata.addon.web;

import android.webkit.JavascriptInterface;

import androidx.annotation.Keep;

import me.aap.utils.app.App;
import me.aap.utils.log.Log;

/**
 * @author Andrey Pavlenko
 */
public class FermataJsInterface {
	public static final String NAME = "Fermata";
	public static final String JS_EVENT = "window.Fermata.event";
	private static final int MAX_EVENT_DATA = 64 * 1024;
	public static final int JS_EDIT = 0;
	public static final int JS_ERR = 1;
	protected static final int JS_LAST = 1;
	/** xFermata: 🎤 delay measurement (data = seconds to record). */
	public static final int JS_XF_MIC = JS_LAST + 50;
	/** xFermata: A/V sync event line for the app log (Settings > App log). */
	public static final int JS_XF_LOG = JS_LAST + 51;
	/** xFermata: the delay the page now uses ("conn;delayMs;correctionMs"). */
	public static final int JS_XF_STATE = JS_LAST + 52;
	private final FermataWebView webView;

	public FermataJsInterface(FermataWebView webView) {
		this.webView = webView;
	}

	public FermataWebView getWebView() {
		return webView;
	}

	@Keep
	@SuppressWarnings("unused")
	@JavascriptInterface
	public void event(int event, String data) {
		// xFermaTube: any site can call this; huge strings are dropped before they reach the UI thread
		if ((data != null) && (data.length() > MAX_EVENT_DATA)) return;
		App.get().run(() -> handleEvent(event, data));
	}

	/**
	 * xFermaTube: the picture delay (ms) for protected video in the Web browser on the car screen,
	 * asked by xf-mse-shift.js when the page starts a DRM video; -1 = none.
	 */
	@Keep
	@SuppressWarnings("unused")
	@JavascriptInterface
	public int xfShiftMs() {
		return XfWebSync.shiftMs(getWebView());
	}

	protected void handleEvent(int event, String data) {
		switch (event) {
			case JS_EDIT:
				Log.d("Edit text event: ", data);
				getWebView().showKeyboard(data);
				break;
			case JS_ERR:
				Log.e("JavaScript error: ", data);
				break;
			// xFermaTube: A/V sync on the pages of the Web browser (YouTube handles these itself)
			case JS_XF_MIC:
				XfWebSync.mic(getWebView(), data);
				break;
			case JS_XF_LOG:
				XfWebSync.log(getWebView(), data);
				break;
			case JS_XF_STATE:
				XfWebSync.saveState(getWebView(), data);
				break;
		}
	}
}
