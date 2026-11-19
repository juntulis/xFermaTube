package me.aap.fermata.addon.web;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.FermataApplication;

/**
 * xFermaTube: open pages ("tabs") of the Web browser, shown on its start page above the bookmarks.
 * <p>
 * Light tabs: the browser keeps one page at a time. A tab remembers the address and the title of
 * a page; switching to it opens that address again. Logins stay (cookies are kept), the place in
 * a video may not. Several live pages at once would each hold a whole web page with its video in
 * memory, too much for a phone that also drives the car screen.
 * <p>
 * A page opened from the start page becomes a new tab, a link followed in a page stays in its tab.
 */
public final class XfWebTabs {
	static final int MAX = 8;
	private static final String PREFS = "xf_web_tabs";
	private static final Object lock = new Object();
	private static List<Tab> tabs;
	private static int current = -1;   // -1: the start page is shown, the next page is a new tab

	private XfWebTabs() {
	}

	static final class Tab {
		final String url;
		final String title;

		Tab(String url, String title) {
			this.url = url;
			this.title = title;
		}
	}

	private static List<Tab> tabs() {
		if (tabs == null) {
			tabs = new ArrayList<>();
			try {
				JSONArray a = new JSONArray(prefs().getString("tabs", "[]"));
				for (int i = 0; (i < a.length()) && (tabs.size() < MAX); i++) {
					JSONObject o = a.getJSONObject(i);
					String url = o.optString("u", "");
					if (isWeb(url)) tabs.add(new Tab(url, o.optString("t", "")));
				}
			} catch (Exception ignore) {
			}
		}
		return tabs;
	}

	private static SharedPreferences prefs() {
		return FermataApplication.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	private static void save() {
		JSONArray a = new JSONArray();
		try {
			for (Tab t : tabs()) a.put(new JSONObject().put("u", t.url).put("t", t.title));
		} catch (Exception ignore) {
		}
		prefs().edit().putString("tabs", a.toString()).apply();
	}

	static boolean isWeb(String url) {
		return (url != null) && (url.startsWith("https://") || url.startsWith("http://")) &&
				!XfWebSync.isStartPage(url) && !url.startsWith("https://xf.invalid/");
	}

	/** A copy of the tabs and the index of the current one (-1 = none). */
	static List<Tab> list() {
		synchronized (lock) {
			return new ArrayList<>(tabs());
		}
	}

	static int current() {
		synchronized (lock) {
			return current;
		}
	}

	/** A page of the plain browser has loaded. */
	static void pageLoaded(String url, String title) {
		synchronized (lock) {
			if (XfWebSync.isStartPage(url)) {
				current = -1;
				return;
			}
			if (!isWeb(url)) return;
			String t = (title == null) ? "" : title.trim();
			if (t.length() > 80) t = t.substring(0, 80) + "…";
			List<Tab> l = tabs();
			if ((current < 0) || (current >= l.size())) {
				// From the start page (or Back past it): a page that is already open is that tab
				for (int i = 0; i < l.size(); i++) {
					if (l.get(i).url.equals(url)) {
						current = i;
						l.set(i, new Tab(url, t.isEmpty() ? l.get(i).title : t));
						save();
						return;
					}
				}
				if (l.size() >= MAX) l.remove(0);   // the oldest one goes
				l.add(new Tab(url, t));
				current = l.size() - 1;
			} else {
				Tab old = l.get(current);
				l.set(current, new Tab(url, (t.isEmpty() && old.url.equals(url)) ? old.title : t));
			}
			save();
		}
	}

	/** The page has a (new) title. */
	static void titleChanged(String url, String title) {
		if ((title == null) || title.trim().isEmpty()) return;
		synchronized (lock) {
			List<Tab> l = tabs();
			if ((current < 0) || (current >= l.size()) || !l.get(current).url.equals(url)) return;
			String t = title.trim();
			if (t.length() > 80) t = t.substring(0, 80) + "…";
			if (t.equals(l.get(current).title)) return;
			l.set(current, new Tab(url, t));
			save();
		}
	}

	/**
	 * A link of the start page: https://xf.invalid/tab?i=N opens tab N, .../close?i=N closes it.
	 * Taken only from the start page itself, so other sites cannot close tabs.
	 *
	 * @return true if this was such a link (handled, the navigation is cancelled)
	 */
	static boolean navigation(WebView view, WebResourceRequest request) {
		Uri u = request.getUrl();
		if (!"xf.invalid".equals(u.getHost())) return false;
		String path = u.getPath();
		if (!"/tab".equals(path) && !"/close".equals(path)) return false;
		if (!(view instanceof FermataWebView v) || (view.getClass() != FermataWebView.class) ||
				!XfWebSync.isStartPage(view.getUrl())) return true;
		int i;
		try {
			i = Integer.parseInt(u.getQueryParameter("i"));
		} catch (Exception ex) {
			return true;
		}
		String open = null;
		synchronized (lock) {
			List<Tab> l = tabs();
			if ((i < 0) || (i >= l.size())) return true;
			if ("/tab".equals(path)) {
				current = i;
				open = l.get(i).url;
			} else {
				l.remove(i);
				current = -1;
				save();
			}
		}
		String url = open;
		if (url != null) v.post(() -> v.loadUserUrl(url));
		else v.post(v::reload);
		return true;
	}
}
