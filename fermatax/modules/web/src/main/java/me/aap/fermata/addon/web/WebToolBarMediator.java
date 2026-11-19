package me.aap.fermata.addon.web;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_ENTER;
import static android.view.KeyEvent.KEYCODE_NUMPAD_ENTER;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.LEFT;
import static java.util.Objects.requireNonNull;
import static me.aap.fermata.util.Utils.dynCtx;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.view.TopBarController;
import me.aap.fermata.ui.view.TopBarMediatorSupport;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.ToolBarView;

/**
 * @author Andrey Pavlenko
 */
public class WebToolBarMediator implements ToolBarView.Mediator {
	private static final WebToolBarMediator instance = new WebToolBarMediator();

	public static WebToolBarMediator getInstance() {
		return instance;
	}

	@Override
	public void enable(ToolBarView tb, ActivityFragment f) {
		dynCtx(f.requireContext());
		WebBrowserFragment b = (WebBrowserFragment) f;
		EditText t = createAddress(tb, b);
		String url = b.getUrl();
		if (url != null) t.setText(xfShownAddress(url));
		if (b.xfCanHideBar()) t.setHint("Search or type a web address");
		addView(tb, t, R.id.browser_addr, LEFT);
		int backSide = TopBarMediatorSupport.getBackButtonSide(tb);
		addButton(tb, R.drawable.forward, v ->
				requireNonNull(b.getWebView()).goForward(), R.id.browser_forward, backSide);
		if (b.hasHomeButton()) {
			addButton(tb, me.aap.fermata.R.drawable.home, v -> b.goHome(), R.id.browser_home, backSide);
		}
		TopBarMediatorSupport.installBackButton(tb, this);
		// xFermaTube: car screen – the number of open pages (tabs); a tap shows them (the start page)
		if (b.xfCanHideBar()) xfAddTabCount(tb, b, t.getCurrentTextColor());
		addButton(tb, R.drawable.clear, v -> t.setText(""), R.id.browser_addr_clear);
		// xFermaTube: car screen – hide this bar for the page (a pull down from the top brings it back)
		if (b.xfCanHideBar()) addButton(tb, R.drawable.fullscreen, v -> b.xfHideBar(), R.id.fullscreen);
		addButton(tb, me.aap.fermata.R.drawable.bookmark_filled, v ->
				onBookmarksButtonClick(b), me.aap.fermata.R.id.bookmarks);
		FermataWebView wv = b.getWebView();
		setButtonsVisibility(tb, (wv != null) && wv.canGoBack(), (wv != null) && wv.canGoForward());
		ToolBarView.Mediator.super.enable(tb, f);
		TopBarController.refresh(MainActivityDelegate.get(tb.getContext()), f);
	}

	private void onBookmarksButtonClick(WebBrowserFragment f) {
		f.getActivityDelegate().getToolBarMenu().show(f::bookmarksMenu);
	}

	public void setAddress(ToolBarView tb, String addr) {
		EditText et = tb.findViewById(R.id.browser_addr);
		if (et != null) et.setText(xfShownAddress(addr));
		xfSetTabCount(tb.findViewById(R.id.browser_tabs));
	}

	/** xFermaTube: the start page shows an empty address line (with a hint), like Chrome. */
	private static String xfShownAddress(String addr) {
		return ((addr != null) && XfWebSync.isStartPage(addr)) ? "" : addr;
	}

	/** xFermaTube: a Chrome-like [N] button – the number of open pages, a tap shows them. */
	private void xfAddTabCount(ToolBarView tb, WebBrowserFragment b, int color) {
		Context ctx = tb.getContext();
		TextView c = new TextView(ctx);
		ConstraintLayout.LayoutParams lp = setLayoutParams(c, 0, MATCH_PARENT);
		lp.horizontalWeight = 1;
		lp.dimensionRatio = "1:1";
		c.setGravity(Gravity.CENTER);
		c.setTextColor(color);
		c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
		c.setTypeface(Typeface.DEFAULT_BOLD);
		c.setIncludeFontPadding(false);
		GradientDrawable box = new GradientDrawable();
		box.setCornerRadius(UiUtils.toPx(ctx, 4));
		box.setStroke(UiUtils.toIntPx(ctx, 2), color);
		int in = UiUtils.toIntPx(ctx, 12);
		c.setBackground(new InsetDrawable(box, in));
		c.setContentDescription("Open pages");
		c.setFocusable(true);
		c.setOnClickListener(v -> b.goHome());
		addView(tb, c, R.id.browser_tabs);
		xfSetTabCount(c);
	}

	private static void xfSetTabCount(View v) {
		if (v instanceof TextView c) c.setText(String.valueOf(XfWebTabs.list().size()));
	}

	/** xFermaTube: the open pages changed (a page loaded, a title came): the [N] button shows it. */
	static void xfTabsChanged(FermataWebView v) {
		v.getActivity().onSuccess(a -> {
			ActivityFragment f = a.getActiveFragment();
			if ((f != null) && (f.getToolBarMediator() instanceof WebToolBarMediator)) {
				xfSetTabCount(a.getToolBar().findViewById(R.id.browser_tabs));
			}
		});
	}

	/**
	 * Web history controls remain web-specific actions, while the canonical Back button is owned by
	 * the common top-bar controller. The legacy back argument is intentionally ignored so existing
	 * web-view callbacks can migrate without retaining a second Back visibility authority.
	 */
	public void setButtonsVisibility(ToolBarView tb, boolean back, boolean forward) {
		tb.findViewById(R.id.browser_forward).setVisibility(forward ? VISIBLE : GONE);
		TopBarController.refresh(MainActivityDelegate.get(tb.getContext()));
	}

	private EditText createAddress(ToolBarView tb, WebBrowserFragment f) {
		EditText t = createEditText(tb);
		ConstraintLayout.LayoutParams lp = setLayoutParams(t, 0, WRAP_CONTENT);
		lp.horizontalWeight = 2;
		MainActivityDelegate a = MainActivityDelegate.get(tb.getContext());
		if (a != null && a.isGlassTheme()) {
			boolean isNight = a.getPrefs().isGlassNightMode();
			t.setBackground(a.getGlassCardDrawable(tb.getContext(), 18f));
			t.setTextColor(isNight ? 0xFFE0F7FA : 0xFF15181A);
			t.setHintTextColor(isNight ? 0x8080D8FF : 0x6615181A);
			int padH = UiUtils.toIntPx(tb.getContext(), 12);
			int padV = UiUtils.toIntPx(tb.getContext(), 6);
			t.setPadding(padH, padV, padH, padV);
		} else {
			t.setBackgroundResource(me.aap.utils.R.color.tool_bar_edittext_bg);
		}
		t.setOnKeyListener((v, keyCode, event) -> onKey(f, t, keyCode, event));
		t.setMaxLines(1);
		t.setSingleLine(true);
		return t;
	}

	private boolean onKey(WebBrowserFragment f, EditText text, int keyCode, KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

		switch (keyCode) {
			case KEYCODE_DPAD_CENTER:
			case KEYCODE_ENTER:
			case KEYCODE_NUMPAD_ENTER:
				f.loadUrl(text.getText().toString());
				return true;
			default:
				return UiUtils.dpadFocusHelper(text, keyCode, event);
		}
	}
}
