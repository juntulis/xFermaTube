package me.aap.fermata.addon.web;

import static android.os.Build.*;
import static me.aap.fermata.addon.web.FermataWebClient.isYoutubeUri;
import static me.aap.fermata.util.Utils.dynCtx;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CHANGED;
import static me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED;

import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.auto.AutomotiveNavigationController;
import me.aap.fermata.auto.OpenOnCarKind;
import me.aap.fermata.auto.OpenOnCarMode;
import me.aap.fermata.auto.OpenOnCarRequest;
import me.aap.fermata.auto.OpenOnCarToken;
import me.aap.fermata.auto.OpenOnCarRequestHandler;
import me.aap.fermata.auto.AutomotiveNavigationController.OpenResult;
import me.aap.fermata.addon.web.yt.YoutubeFragment;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityListener;
import me.aap.fermata.ui.activity.VoiceCommand;
import me.aap.fermata.ui.fragment.MainActivityFragment;
import me.aap.fermata.ui.view.TopBarController;
import me.aap.utils.function.BooleanConsumer;
import me.aap.utils.function.Supplier;
import me.aap.utils.log.Log;
import me.aap.utils.pref.BasicPreferenceStore;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;
import me.aap.utils.ui.view.ToolBarView;

/**
 * @author Andrey Pavlenko
 */
@Keep
@SuppressWarnings("unused")
public class WebBrowserFragment extends MainActivityFragment
		implements OverlayMenu.SelectionHandler, MainActivityListener, OpenOnCarRequestHandler,
		me.aap.fermata.xf.XfNavigable {
	private static final long FULLSCREEN_BACK_DUPLICATE_WINDOW_MS = 250L;

	private boolean fullScreenOnResume;
	private long suppressHistoryBackUntil;
	private WebUrlSyncObserver urlObserver;
	private long urlSourceGeneration;
	private FermataWebView urlSourceView;
	private WebUrlSourceBinding urlBinding;
	/** xFermaTube: the address bar hidden by its own button (car screen). */
	private boolean xfBarHidden;

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.web_browser_fragment;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		dynCtx(requireContext());
		return inflater.inflate(R.layout.browser, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		WebBrowserAddon addon = getAddon();
		if (addon == null) return;

		Context ctx = view.getContext();
		FermataWebView webView = view.findViewById(R.id.browserWebView);
		ViewGroup fullScreenView = view.findViewById(R.id.browserFullScreenView);
		FermataWebClient webClient = createWebClient();
		FermataChromeClient chromeClient = createChromeClient(webView, fullScreenView);
		webView.init(addon, webClient, chromeClient);
		webView.setSourceFragment(this);
		addon.attachRuntimeFragment(this);
		if (!addon.attachExternalFragment(this)) webView.loadBaselineUrl(getInitialUrl(addon));
		MainActivityDelegate.getActivityDelegate(ctx).onSuccess(this::registerListeners);
	}

	boolean openExternalPlayback(WebExternalMediaEngine engine) {
		FermataWebView web = getWebView();
		return (web != null) && web.openExternalPlayback(engine.getRequest());
	}

	void closeExternalPlayback(WebExternalMediaEngine engine) {
		FermataWebView web = getWebView();
		if (web != null) web.closeExternalPlayback(engine.getRequest());
	}

	protected void onAutomotiveShutdown() {
		FermataWebView web = getWebView();
		if (web != null) web.onAutomotiveShutdown();
	}

	protected void onAutomotiveSessionStarted() {
		FermataWebView web = getWebView();
		if (web != null) web.onAutomotiveSessionStarted();
	}

	@Override
	public void onDestroyView() {
		detachUrlObserver();
		WebBrowserAddon addon = getAddon();
		if (addon != null) {
			addon.detachExternalFragment(this);
			addon.detachRuntimeFragment(this);
		}
		MainActivityDelegate.getActivityDelegate(requireContext()).onSuccess(this::unregisterListeners);
		super.onDestroyView();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (hidden) {
			detachUrlObserver();
		} else {
			FermataWebView web = getWebView();
			WebBrowserAddon addon = getAddon();
			if ((web != null) && (addon != null)) attachUrlObserver(web, addon);
		}
	}

	private boolean isUrlSourceForeground(FermataWebView web, WebBrowserAddon addon) {
		MainActivityDelegate activity = MainActivityDelegate.getActivityDelegate(
				web.getContext()).peek();
		boolean active = !isHidden() && (activity != null) && (activity.getActiveFragment() == this);
		return shouldBindUrlObserver(addon.getAddonId(), getFragmentId(), web.getRuntimeHostMode(),
				active, isResumed(), web.isAttachedToWindow(), web, getWebView(),
				urlSourceGeneration, urlSourceGeneration);
	}

	static boolean isCurrentUrlSource(boolean active, boolean resumed, boolean attached,
			Object expectedView, Object currentView, long generation, long currentGeneration) {
		return active && resumed && attached && (expectedView != null) &&
				(expectedView == currentView) && (generation == currentGeneration);
	}

	static boolean shouldBindUrlObserver(int addonId, int fragmentId,
			me.aap.fermata.ui.policy.RuntimeHostMode hostMode, boolean active, boolean resumed,
			boolean attached, Object expectedView, Object currentView, long generation,
			long currentGeneration) {
		return (addonId == me.aap.fermata.R.id.web_browser_fragment) &&
				(fragmentId == me.aap.fermata.R.id.web_browser_fragment) &&
				(hostMode == me.aap.fermata.ui.policy.RuntimeHostMode.PHONE) &&
				isCurrentUrlSource(active, resumed, attached, expectedView, currentView,
						generation, currentGeneration);
	}

	private void attachUrlObserver(FermataWebView web, WebBrowserAddon addon) {
		if ((urlObserver != null) || !isUrlSourceForeground(web, addon)) return;
		final AutomotiveNavigationController controller = AutomotiveNavigationController.get();
		final OpenOnCarMode mode = controller.getOpenOnCarMode();
		final long sourceGeneration = ++urlSourceGeneration;
		urlSourceView = web;
		WebUrlSyncObserver.Scheduler scheduler = (runnable, delayMillis) -> {
			AtomicBoolean cancelled = new AtomicBoolean();
			Runnable task = () -> {
				if (!cancelled.get()) runnable.run();
			};
			web.postDelayed(task, delayMillis);
			return () -> {
				cancelled.set(true);
				web.removeCallbacks(task);
			};
		};
		WebUrlSyncObserver observer = new WebUrlSyncObserver(() -> {
			boolean admitted = isCurrentUrlSource(isUrlSourceForeground(web, addon), isResumed(),
					web.isAttachedToWindow(), web, urlSourceView, sourceGeneration, urlSourceGeneration);
			OpenOnCarToken token = controller.captureSourceToken(urlSourceGeneration);
			return new WebUrlSyncObserver.DispatchState(admitted && mode.isEnabled(), token.requestId(),
					urlSourceGeneration, token.registrationGeneration(), token.modeRevision(), token.connectionEpoch());
		}, scheduler, candidate -> dispatchWebUrl(controller, mode, web, addon, candidate));
		urlObserver = observer;
		urlBinding = new WebUrlSourceBinding(web.getWebViewClient(), observer, mode,
				sourceGeneration, web.getUrl());
	}

	private void dispatchWebUrl(AutomotiveNavigationController controller, OpenOnCarMode mode,
			FermataWebView web, WebBrowserAddon addon, WebUrlSyncObserver.Candidate candidate) {
		if (!isUrlSourceForeground(web, addon) || !mode.isEnabled() ||
				(urlSourceView != web) || (urlSourceGeneration != candidate.sourceGeneration())) return;
		WebUrlSyncObserver.DispatchState state = candidate.state();
		OpenOnCarToken token = new OpenOnCarToken(state.connectionEpoch(), state.hostGeneration(),
				state.modeRevision(), candidate.sourceGeneration(), state.token());
		if (!token.equals(controller.captureSourceToken(urlSourceGeneration))) return;
		controller.open(new OpenOnCarRequest(OpenOnCarKind.WEB_URL, addon.getAddonId(),
				candidate.url(), token), () -> (urlSourceView == web) &&
				(urlSourceGeneration == candidate.sourceGeneration()) && isUrlSourceForeground(web, addon));
	}

	private void detachUrlObserver() {
		urlSourceGeneration++;
		urlSourceView = null;
		if (urlBinding != null) {
			urlBinding.close();
			urlBinding = null;
		}
		urlObserver = null;
	}

	void onSourceViewReplaced(FermataWebView previous, FermataWebView replacement) {
		if (urlSourceView == previous) detachUrlObserver();
		replacement.setSourceFragment(this);
		replacement.post(this::tryAttachUrlObserver);
	}

	protected void tryAttachUrlObserver() {
		FermataWebView web = getWebView();
		WebBrowserAddon addon = getAddon();
		if ((web != null) && (addon != null)) attachUrlObserver(web, addon);
	}

	@Override public OpenResult openOnCar(OpenOnCarRequest request,
			java.util.function.BooleanSupplier stillCurrent) {
		FermataWebView web = getWebView();
		WebBrowserAddon addon = getAddon();
		if (!stillCurrent.getAsBoolean()) return OpenResult.CANCELLED;
		if ((web == null) || (addon == null) ||
				(getFragmentId() != me.aap.fermata.R.id.web_browser_fragment) ||
				(addon.getAddonId() != me.aap.fermata.R.id.web_browser_fragment) ||
				(web.getRuntimeHostMode() != me.aap.fermata.ui.policy.RuntimeHostMode.AA_PROJECTION))
			return OpenResult.NOT_READY;
		return web.openMirroredUrl(request, () -> stillCurrent.getAsBoolean() &&
				(getWebView() == web) && !isHidden() && (getActivityDelegate().getActiveFragment() == this));
	}

	@Override
	public void onRefresh(BooleanConsumer refreshing) {
		FermataWebView v = getWebView();
		if (v != null) {
			FermataWebClient c = v.getWebViewClient();
			if (c != null) {
				c.loading = refreshing;
				v.reloadUserPage();
			}
		}
	}

	@Override
	public void onPause() {
		detachUrlObserver();
		super.onPause();
		FermataWebView v = getWebView();
		if ((v == null) || !v.usesAutomotivePresentation()) return;
		FermataChromeClient chrome = v.getWebChromeClient();
		if (chrome != null) {
			if (chrome.isFullScreen()) {
				chrome.exitFullScreen();
				fullScreenOnResume = true;
			} else {
				fullScreenOnResume = false;
			}
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		tryAttachUrlObserver();
		FermataWebView v = getWebView();
		if ((v == null) || !v.usesAutomotivePresentation() || !fullScreenOnResume) return;
		// Calling here onResume makes the video to not get freezed
		// when you switch to another app and go back to Fermata
		v.onResume();
		if (!shouldRestoreFullScreenOnResume()) {
			fullScreenOnResume = false;
			return;
		}
		MainActivityDelegate.getActivityDelegate(getContext()).onSuccess(a -> a.post(() -> {
			FermataChromeClient chrome = v.getWebChromeClient();
			if (chrome != null) chrome.enterFullScreen();
		}));
	}

	protected boolean shouldRestoreFullScreenOnResume() {
		return true;
	}

	@NonNull
	protected String getInitialUrl(@NonNull WebBrowserAddon addon) {
		// xFermaTube: the browser opens with the bookmarks, one tap away
		if (xfStartPage()) return XfWebSync.START_URL;
		return addon.getLastUrl();
	}

	protected boolean hasHomeButton() {
		return xfStartPage();
	}

	protected void goHome() {
		if (xfStartPage()) loadUrl(XfWebSync.START_URL);
	}

	/** The plain browser (not YouTube, Stremio or TikTok) with the Web program on. */
	private boolean xfStartPage() {
		return XfWebSync.startPageEnabled() && (getClass() == WebBrowserFragment.class);
	}

	protected void registerListeners(MainActivityDelegate a) {
		a.addBroadcastListener(this, MainActivityListener.ACTIVITY_DESTROY | FRAGMENT_CHANGED | FRAGMENT_CONTENT_CHANGED);
	}

	protected void unregisterListeners(MainActivityDelegate a) {
		FermataWebView v = getWebView();
		WebBrowserAddon addon = getAddon();
		a.removeBroadcastListener(this);
		if ((addon != null) && (v != null)) addon.getPreferenceStore().removeBroadcastListener(v);
	}

	@Override
	public void onActivityEvent(MainActivityDelegate a, long e) {
		if (e == ACTIVITY_DESTROY) unregisterListeners(a);
		else if (e == FRAGMENT_CHANGED) {
			if (a.getActiveFragment() == this) tryAttachUrlObserver();
			else detachUrlObserver();
		} else if (e == FRAGMENT_CONTENT_CHANGED) {
			FermataWebView v = getWebView();
			if (v != null) v.checkThemeChanged();
		}
	}

	@Override
	public void setInput(Object input) {
		loadUrl(input.toString());
	}

	public void loadUrl(String url) {
		url = url.trim();
		if (Uri.parse(url).getScheme() == null) {
			// xFermaTube: "web.stremio.com" is an address, "car charging map" a search
			if (url.matches("[\\w-]+(\\.[\\w-]+)+(:\\d+)?([/?#].*)?")) url = "https://" + url;
			else url = getSearchUrl() + url;
		}

		FermataWebView v = getWebView();

		if (v != null) {
			if (!(this instanceof YoutubeFragment) && isYoutubeUri(Uri.parse(url)) &&
					AddonManager.get().hasAddon(me.aap.fermata.R.id.youtube_fragment)) {
				String u = url;
				MainActivityDelegate.getActivityDelegate(requireContext()).onSuccess(a -> {
					if (a.showFragment(me.aap.fermata.R.id.youtube_fragment) instanceof YoutubeFragment f)
						f.loadUrl(u);
				});
			} else {
				v.loadUserUrl(url);
			}
		} else {
			WebBrowserAddon addon = getAddon();
			if (addon != null) addon.setLastUrl(url);
		}
	}

	@Nullable
	public String getUrl() {
		WebView v = getWebView();
		return (v == null) ? null : v.getUrl();
	}

	@Override
	public boolean isRootPage() {
		FermataWebView v = getWebView();
		if ((v == null) || (v.getWebChromeClient() == null)) return true;
		return !v.getWebChromeClient().isFullScreen() && !v.canGoBack();
	}

	@Override
	public boolean onBackPressed() {
		FermataWebView v = getWebView();
		if (v == null) return false;
		FermataChromeClient chrome = v.getWebChromeClient();
		boolean fullScreen = (chrome != null) && chrome.isFullScreen();
		long now = SystemClock.elapsedRealtime();
		if (!fullScreen && (now < suppressHistoryBackUntil)) {
			suppressHistoryBackUntil = 0L;
			FermataWebClient.diagnosticsObserver().onPage(
					FermataWebClient.PageEvent.HISTORY_BACK_SUPPRESSED,
					FermataWebClient.DiagnosticsSnapshot.builder().web(v.isShown(), v.isAttachedToWindow(),
							v.getWidth(), v.getHeight()).result(true, v.canGoBack()).build());
			return true;
		}
		suppressHistoryBackUntil = 0L;

		return switch (WebBackNavigationPolicy.resolve(fullScreen, v.canGoBack())) {
			case EXIT_FULLSCREEN -> {
				boolean exited = v.exitFullScreenForBack();
				if (exited) suppressHistoryBackUntil = now + FULLSCREEN_BACK_DUPLICATE_WINDOW_MS;
				yield exited;
			}
			case WEB_HISTORY -> {
				v.goBack();
				yield true;
			}
			case PARENT -> false;
		};
	}

	// xFermaTube: the side rail of the car screen (the top bar's buttons are small there)

	@Override
	public void xfHome() {
		// The start page always has the address bar (a sure way back if the pull down is missed)
		xfShowBar();
		goHome();
	}

	@Override
	public boolean xfBack() {
		return onBackPressed();
	}

	@Override
	public void xfRefresh() {
		FermataWebView v = getWebView();
		if (v != null) v.reloadUserPage();
	}

	@Override
	public void xfHistory() {
	}

	@Override
	public void xfBookmarks() {
		getActivityDelegate().getToolBarMenu().show(this::bookmarksMenu);
	}

	/** The address bar can be hidden: the plain browser on the car screen. */
	boolean xfCanHideBar() {
		return xfStartPage() && getActivityDelegate().isCarActivity();
	}

	/** Hides the address bar (its own button); a pull down from the top brings it back. */
	void xfHideBar() {
		if (!xfCanHideBar()) return;
		xfBarHidden = true;
		TopBarController.refresh(getActivityDelegate(), this);
	}

	/**
	 * Brings the hidden address bar back (a pull down from the top), leaving the page's fullscreen
	 * video too. false = the bar is shown already.
	 */
	boolean xfShowBar() {
		if (!xfCanHideBar()) return false;
		FermataWebView v = getWebView();
		FermataChromeClient chrome = (v == null) ? null : v.getWebChromeClient();
		boolean full = (chrome != null) && chrome.isFullScreen();
		if (!xfBarHidden && !full) return false;
		xfBarHidden = false;
		if (full) chrome.exitFullScreen();
		TopBarController.refresh(getActivityDelegate(), this);
		return true;
	}

	@Override
	public boolean xfFullScreen() {
		if (!xfCanHideBar()) return false;
		if (xfBarHidden) return true;
		FermataWebView v = getWebView();
		FermataChromeClient chrome = (v == null) ? null : v.getWebChromeClient();
		return (chrome != null) && chrome.isFullScreen();
	}

	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return WebToolBarMediator.getInstance();
	}

	@Nullable
	protected WebBrowserAddon getAddon() {
		return AddonManager.get().getAddon(WebBrowserAddon.class);
	}

	@Nullable
	protected FermataWebView getWebView() {
		View v = getView();
		return (v != null) ? v.findViewById(R.id.browserWebView) : null;
	}

	@Override
	public void contributeToNavBarMenu(OverlayMenu.Builder b) {
		WebBrowserAddon a = getAddon();
		FermataWebView v = getWebView();
		if ((a == null) || (v == null)) return;

		Context ctx = dynCtx(requireContext());
		Resources res = ctx.getResources();
		Resources.Theme theme = ctx.getTheme();
		b.addItem(me.aap.fermata.R.id.refresh,
				ResourcesCompat.getDrawable(res, me.aap.fermata.R.drawable.refresh, theme),
				res.getString(me.aap.fermata.R.string.refresh)).setHandler(this);

		if (isDesktopVersionSupported()) {
			b.addItem(R.id.desktop_version,
							ResourcesCompat.getDrawable(res, R.drawable.desktop, theme),
							res.getString(R.string.desktop_version)).setChecked(a.isDesktopVersion())
					.setHandler(this);
		}

		FermataChromeClient chrome = v.getWebChromeClient();
		if (chrome == null) return;

		if (!chrome.isFullScreen()) {
			if (supportsManualFullscreen(v, chrome)) {
				b.addItem(R.id.fullscreen,
						ResourcesCompat.getDrawable(res, R.drawable.fullscreen, theme),
						res.getString(R.string.full_screen)).setHandler(this);
			}
		} else {
			b.addItem(R.id.fullscreen_exit,
					ResourcesCompat.getDrawable(res, R.drawable.fullscreen_exit, theme),
					res.getString(R.string.full_screen_exit)).setHandler(this);
		}

		b.addItem(me.aap.fermata.R.id.bookmarks,
				ResourcesCompat.getDrawable(res, me.aap.fermata.R.drawable.bookmark_filled, theme),
				res.getText(me.aap.fermata.R.string.bookmarks)).setSubmenu(this::bookmarksMenu);
	}

	protected boolean isDesktopVersionSupported() {
		return true;
	}

	protected boolean supportsManualFullscreen(FermataWebView web, FermataChromeClient chrome) {
		return chrome.canEnterFullScreen();
	}

	@NonNull
	protected FermataWebClient createWebClient() {
		return new FermataWebClient();
	}

	@NonNull
	protected FermataChromeClient createChromeClient(FermataWebView webView, ViewGroup fullScreenView) {
		return new FermataChromeClient(webView, fullScreenView);
	}

	@Override
	public boolean menuItemSelected(OverlayMenuItem item) {
		FermataWebView v = getWebView();
		if (v == null) return false;

		int id = item.getItemId();

		if (id == me.aap.fermata.R.id.refresh) {
			v.reloadUserPage();
			return true;
		} else if (id == R.id.desktop_version) {
			WebBrowserAddon addon = getAddon();
			if (addon != null) addon.setDesktopVersion(!addon.isDesktopVersion());
			return true;
		} else if (id == R.id.fullscreen || id == R.id.fullscreen_exit) {
			FermataChromeClient chrome = v.getWebChromeClient();
			if (chrome == null) return false;
			if (id == R.id.fullscreen) chrome.enterFullScreen();
			else chrome.exitFullScreen();
			return true;
		}

		return false;
	}

	public void bookmarksMenu(OverlayMenu.Builder b) {
		WebBrowserAddon a = getAddon();
		if (a == null) return;

		Map<String, String> all = a.getBookmarks();
		FermataWebView v = getWebView();
		String url = (v == null) ? null : v.getUrl();

		// xFermaTube: one tap bookmarks the open page under its title, no form to fill in on the car
		// screen. The list is shown again, so that the new bookmark is seen (toasts don't reach the car).
		if ((url != null) && (url.startsWith("https://") || url.startsWith("http://"))) {
			if (all.containsKey(url)) {
				b.addItem(me.aap.fermata.R.id.bookmark_remove, me.aap.fermata.R.string.xf_bookmark_remove)
						.setHandler(item -> {
							a.removeBookmark(url);
							item.getMenu().show(this::bookmarksMenu);
							return true;
						});
			} else {
				String title = v.getTitle();
				String name = ((title == null) || title.trim().isEmpty()) ? url : title.trim();
				String shortName = (name.length() > 60) ? name.substring(0, 60) + "…" : name;
				b.addItem(me.aap.fermata.R.id.xf_bookmark_add, me.aap.fermata.R.string.xf_bookmark_add)
						.setHandler(item -> {
							a.addBookmark(shortName, url);
							item.getMenu().show(this::bookmarksMenu);
							return true;
						});
			}
		}

		b.addItem(me.aap.fermata.R.id.bookmark_create, me.aap.fermata.R.string.create_bookmark)
				.setSubmenu(this::createBookmark);
		int i = 0;

		for (Map.Entry<String, String> e : all.entrySet()) {
			b.addItem(UiUtils.getArrayItemId(i++), e.getValue()).setData(e.getKey())
					.setHandler(this::bookmarkSelected);
		}
	}

	private void createBookmark(OverlayMenu.Builder b) {
		FermataWebView v = getWebView();
		if (v == null) return;
		PreferenceStore store = new BasicPreferenceStore();
		PreferenceStore.Pref<Supplier<String>> name = PreferenceStore.Pref.s("name", v.getTitle());
		PreferenceStore.Pref<Supplier<String>> url = PreferenceStore.Pref.s("url", v.getUrl());

		PreferenceSet set = new PreferenceSet();
		set.addStringPref(o -> {
			o.store = store;
			o.pref = name;
			o.title = me.aap.fermata.R.string.bookmark_name;
		});
		set.addStringPref(o -> {
			o.store = store;
			o.pref = url;
			o.title = R.string.url;
		});

		set.addToMenu(b, true);
		b.setCloseHandlerHandler(m -> {
			WebBrowserAddon a = getAddon();
			if (a != null) a.addBookmark(store.getStringPref(name), store.getStringPref(url));
		});
	}

	private boolean bookmarkSelected(OverlayMenuItem item) {
		if (item.isLongClick()) {
			String url = item.getData();
			item.getMenu().show(b ->
					b.addItem(me.aap.fermata.R.id.bookmark_remove, me.aap.fermata.R.string.remove_bookmark)
							.setHandler(i -> {
								WebBrowserAddon a = getAddon();
								if (a != null) a.removeBookmark(url);
								return true;
							})
			);
		} else {
			loadUrl(item.getData());
		}

		return true;
	}

	@Override
	public boolean isVoiceCommandsSupported() {
		return true;
	}

	@Override
	public void voiceCommand(VoiceCommand cmd) {
		String q = cmd.getQuery();

		if (cmd.isOpen()) {
			WebBrowserAddon a = getAddon();
			if (a != null) {
				for (Map.Entry<String, String> e : a.getBookmarks().entrySet()) {
					if (q.equalsIgnoreCase(e.getValue())) {
						loadUrl(e.getKey());
						return;
					}
				}
			}
		}

		try {
			var encoded =
					(VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) ? URLEncoder.encode(q,
							StandardCharsets.UTF_8) :
							URLEncoder.encode(q, "UTF-8");
			var u = getSearchUrl() + encoded;
			loadUrl(u);
		} catch (UnsupportedEncodingException ex) {
			Log.e(ex, "Failed to encode query ", q);
		}
	}

	protected String getSearchUrl() {
		return "https://www.google.com/search?q=";
	}
}
