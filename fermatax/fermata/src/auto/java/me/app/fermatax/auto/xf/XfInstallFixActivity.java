package me.app.fermatax.auto.xf;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import me.aap.fermata.R;
import me.aap.fermata.xf.XfInstallFix;
import me.app.fermatax.auto.xf.XfInstallFixer.State;

/**
 * xFermaTube: the "Fix install source" screen on the phone. Android Auto shows a sideloaded video
 * app only when Play Store is the installer on record and the phone's own package installer did the
 * install. Three ways, tried in this order:
 * <ol>
 * <li>Simple: the app opens the phone's installer on its own APK and names Play Store as the
 * installer; the user taps Update. This is what installer tools such as AAenabler do.</li>
 * <li>Wireless debugging: for phones whose installer drops the name when an app asks. The shell opens
 * the installer instead (XfInstallFixer). Android 15+, not Samsung.</li>
 * <li>Neither works: the screen says so and names the installer tools.</li>
 * </ol>
 * Which way has failed is known from the install record after a reinstall (XfInstallFix).
 */
public final class XfInstallFixActivity extends Activity {
	private enum Way {SIMPLE, WIRELESS, OTHER}

	private static final String STATE_ASKED = "askedNotifications";
	private static final String STATE_ERROR = "simpleError";
	private static final int REQ_INSTALL = 2;
	private final Runnable stateListener = () -> runOnUiThread(this::render);
	private TextView intro;
	private LinearLayout steps;
	private TextView body;
	private Button primary;
	/** Switches between the ways; hidden when there is nothing to switch to. */
	private Button secondary;
	/** Why the phone's installer could not be opened, or refused. */
	private String simpleError;
	/** The pairing code can also be typed here when Settings is open next to the app (split screen). */
	private LinearLayout codeRow;
	private EditText code;
	/** The system permission question is shown once; after that the button opens the app's settings. */
	private boolean askedNotifications;

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);
		if (b != null) {
			askedNotifications = b.getBoolean(STATE_ASKED);
			simpleError = b.getString(STATE_ERROR);
		}
		float dp = getResources().getDisplayMetrics().density;
		int pad = Math.round(24 * dp);
		LinearLayout column = new LinearLayout(this);
		column.setOrientation(LinearLayout.VERTICAL);
		column.setPadding(pad, pad, pad, pad);

		TextView title = new TextView(this);
		title.setTextSize(22f);
		title.setAccessibilityHeading(true);
		title.setText(R.string.xf_fix_title);
		column.addView(title);

		intro = new TextView(this);
		intro.setTextSize(16f);
		intro.setPadding(0, pad / 2, 0, 0);
		column.addView(intro);

		steps = new LinearLayout(this);
		steps.setOrientation(LinearLayout.VERTICAL);
		steps.setPadding(0, pad / 2, 0, 0);
		column.addView(steps);

		body = new TextView(this);
		body.setTextSize(16f);
		body.setPadding(0, pad / 2, 0, pad / 2);
		body.setTextIsSelectable(true);
		body.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
		column.addView(body);

		primary = new Button(this);
		primary.setMinHeight(Math.round(48 * dp));
		styleButton(primary);
		column.addView(primary);

		codeRow = new LinearLayout(this);
		codeRow.setOrientation(LinearLayout.HORIZONTAL);
		codeRow.setPadding(0, pad / 2, 0, 0);
		code = new EditText(this);
		code.setHint(R.string.xf_fix_code_hint);
		code.setInputType(InputType.TYPE_CLASS_NUMBER);
		code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
		code.setMinHeight(Math.round(48 * dp));
		codeRow.addView(code, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		Button pairNow = new Button(this);
		pairNow.setMinHeight(Math.round(48 * dp));
		pairNow.setText(R.string.xf_fix_pair_btn);
		styleButton(pairNow);
		pairNow.setOnClickListener(v -> {
			String typed = code.getText().toString().trim();
			code.setText("");
			XfInstallFixer.submit(this, typed);
		});
		codeRow.addView(pairNow);
		codeRow.setVisibility(View.GONE);
		column.addView(codeRow);

		secondary = new Button(this);
		secondary.setMinHeight(Math.round(48 * dp));
		secondary.setVisibility(View.GONE);
		LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		gap.topMargin = pad;
		column.addView(secondary, gap);

		ScrollView scroll = new ScrollView(this);
		scroll.addView(column);
		// Edge-to-edge (forced from Android 15): keep the content clear of the system bars and cutout
		ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
			Insets bars = insets.getInsets(
					WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
			v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
			return WindowInsetsCompat.CONSUMED;
		});
		setContentView(scroll);
	}

	/** The default grey button with the theme's light text is unreadable: use the theme accent. */
	private void styleButton(Button button) {
		android.util.TypedValue accent = new android.util.TypedValue();
		if (!getTheme().resolveAttribute(androidx.appcompat.R.attr.colorAccent, accent, true)) return;
		int fill = accent.data | 0xFF000000;
		button.setBackgroundTintList(ColorStateList.valueOf(fill));
		button.setTextColor((ColorUtils.calculateContrast(Color.BLACK, fill) >=
				ColorUtils.calculateContrast(Color.WHITE, fill)) ? Color.BLACK : Color.WHITE);
	}

	@Override
	protected void onSaveInstanceState(Bundle out) {
		super.onSaveInstanceState(out);
		out.putBoolean(STATE_ASKED, askedNotifications);
		out.putString(STATE_ERROR, simpleError);
	}

	@Override
	protected void onStart() {
		super.onStart();
		XfInstallFixer.addListener(stateListener);
	}

	@Override
	protected void onResume() {
		super.onResume();
		advance();
		render();
	}

	@Override
	protected void onStop() {
		XfInstallFixer.removeListener(stateListener);
		super.onStop();
	}

	private boolean developerOptionsOn() {
		return Settings.Global.getInt(getContentResolver(),
				Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
	}

	/** The simple way until a reinstall has shown that this phone's installer drops the Play Store name. */
	private Way way() {
		if (!XfInstallFix.simpleFailed(this)) return Way.SIMPLE;
		if (XfInstallFixer.supported() && !XfInstallFix.shellFailed(this)) return Way.WIRELESS;
		return Way.OTHER;
	}

	/** Each return to this screen moves on: look for Wireless debugging, or post the code notification again. */
	private void advance() {
		if ((way() != Way.WIRELESS) || !XfInstallFix.offered(this) || !developerOptionsOn()) return;
		switch (XfInstallFixer.state()) {
			case IDLE, NO_WIRELESS -> XfInstallFixer.start(this);
			case NOTIFICATIONS_OFF, WAITING_CODE, WRONG_CODE, PAIR_FAILED -> XfInstallFixer.askAgain(this);
			default -> {
			}
		}
	}

	private void render() {
		if (isFinishing()) return;
		Way way = way();
		codeRow.setVisibility(View.GONE);
		secondary.setVisibility(View.GONE);
		if (!XfInstallFix.offered(this)) {
			intro.setVisibility(View.GONE);
			steps.setVisibility(View.GONE);
			show(getString(R.string.xf_fix_ok), R.string.xf_close, this::finish);
			return;
		}
		intro.setVisibility(View.VISIBLE);
		switch (way) {
			case SIMPLE -> renderSimple();
			case WIRELESS -> renderWireless();
			default -> {
				intro.setText(R.string.xf_fix_intro_other);
				steps.setVisibility(View.GONE);
				show(getString(R.string.xf_fix_other), R.string.xf_close, this::finish);
				showSecondary(R.string.xf_fix_use_simple, this::useSimple);
			}
		}
	}

	private void renderSimple() {
		intro.setText(R.string.xf_fix_intro);
		boolean allowed = getPackageManager().canRequestPackageInstalls();
		showSteps(new int[]{R.string.xf_fix_step_allow, R.string.xf_fix_step_update, R.string.xf_fix_step_reopen},
				new boolean[]{allowed, false, false});
		if (simpleError != null) {
			show(getString(R.string.xf_fix_simple_failed) + "\n\n" + simpleError, R.string.xf_fix_try_again,
					this::reinstall);
			// A phone on which the installer cannot be used at all must not be left with "Try again" only
			showSecondary(XfInstallFixer.supported() ? R.string.xf_fix_use_wireless : R.string.xf_fix_other_ways, () -> {
				simpleError = null;
				XfInstallFix.skipSimple(this);
				advance();
				render();
			});
		} else if (!allowed) {
			show(getString(R.string.xf_fix_allow_off), R.string.xf_fix_open_allow, this::openInstallSetting);
		} else {
			show(getString(R.string.xf_fix_simple_ready), R.string.xf_fix_reinstall, this::reinstall);
		}
	}

	private void useSimple() {
		simpleError = null;
		XfInstallFix.resetFailed(this);
		XfInstallFixer.reset(this);
		render();
	}

	/**
	 * Asks the phone's installer to install the app's own APK again, with Play Store named as the
	 * installer. The installer becomes the one that started the install, which is the record Android
	 * Auto accepts. On success the app is replaced and this process ends; the result is read when the
	 * app is opened again (XfInstallFix.checkAtStartup).
	 */
	private void reinstall() {
		simpleError = null;
		try {
			XfApkProvider.apk(this);
			Intent in = XfInstallFixer.installIntent(this)
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
					.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, XfInstallFix.PLAY_STORE)
					.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
					.putExtra(Intent.EXTRA_RETURN_RESULT, true);
			// An installer app the user added would take the request and lose the mark: name the system one
			if (XfInstallFixer.hasOtherInstaller(this)) {
				ComponentName system = XfInstallFixer.systemInstaller(this);
				if (system == null) throw new IllegalStateException("No system package installer was found");
				in.setPackage(system.getPackageName());
			}
			XfInstallFix.markStarted(this, XfInstallFix.WAY_SIMPLE);
			startActivityForResult(in, REQ_INSTALL);
			XfInstallFix.handedOver();
		} catch (Exception ex) {
			XfInstallFix.clearStarted(this);
			simpleError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
		}
		render();
	}

	/** A result reaches the app only when it was not replaced: the user cancelled, or the install failed. */
	@Override
	protected void onActivityResult(int request, int result, Intent data) {
		super.onActivityResult(request, result, data);
		if (request != REQ_INSTALL) return;
		XfInstallFix.clearStarted(this);
		if (result == RESULT_FIRST_USER) {
			int code = (data == null) ? 0 : data.getIntExtra("android.intent.extra.INSTALL_RESULT", 0);
			simpleError = "Install failed (" + code + ")";
		} else if ((result == RESULT_OK) && XfInstallFix.needed(this)) {
			// Installed without replacing this process, and the record is still wrong
			XfInstallFix.skipSimple(this);
		}
		render();
	}

	private void openInstallSetting() {
		try {
			startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
					Uri.parse("package:" + getPackageName())));
		} catch (ActivityNotFoundException ex) {
			Toast.makeText(this, R.string.xf_fix_no_settings, Toast.LENGTH_LONG).show();
		}
	}

	private void renderWireless() {
		State s = XfInstallFixer.state();
		intro.setText(R.string.xf_fix_intro_wireless);
		// Not while a lookup or a pairing runs: its answer would open a second installer window
		if (!XfInstallFixer.busy()) showSecondary(R.string.xf_fix_use_simple, this::useSimple);
		boolean dev = developerOptionsOn();
		codeRow.setVisibility((dev && ((s == State.WAITING_CODE) || (s == State.WRONG_CODE) ||
				(s == State.PAIR_FAILED))) ? View.VISIBLE : View.GONE);
		boolean wireless = dev && (s != State.IDLE) && (s != State.CHECKING) && (s != State.NO_WIRELESS);
		boolean installing = s == State.INSTALLING;
		// A failure is shown at the last step: it is the installer that could not be opened
		boolean paired = wireless && (installing || (s == State.FAILED));
		showSteps(new int[]{R.string.xf_fix_step_dev, R.string.xf_fix_step_wifi, R.string.xf_fix_step_pair,
				R.string.xf_fix_step_install}, new boolean[]{dev, wireless, paired, false});

		String pair = getString(R.string.xf_fix_pair);
		Runnable again = () -> {
			XfInstallFixer.reset(this);
			advance();
			render();
		};
		if (s == State.FAILED) {
			show(failure(XfInstallFixer.detail()), R.string.xf_fix_try_again, again);
		} else if (installing) {
			show(getString(R.string.xf_fix_installing), R.string.xf_fix_try_again, again);
		} else if (!dev) {
			show(getString(R.string.xf_fix_dev_off), R.string.xf_fix_open_about,
					() -> open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)));
		} else if (s == State.NO_WIRELESS) {
			show(getString(R.string.xf_fix_wifi_off), R.string.xf_fix_open_dev, this::openDeveloperOptions);
		} else if (s == State.NOTIFICATIONS_OFF) {
			show(getString(R.string.xf_fix_notif_off), R.string.xf_fix_allow_notif, this::allowNotifications);
		} else if (s == State.WAITING_CODE) {
			show(pair, R.string.xf_fix_open_dev, this::openDeveloperOptions);
		} else if (s == State.WRONG_CODE) {
			show(getString(R.string.xf_fix_wrong_code) + "\n\n" + pair, R.string.xf_fix_open_dev,
					this::openDeveloperOptions);
		} else if (s == State.PAIR_FAILED) {
			show(getString(R.string.xf_fix_pair_failed) + "\n\n" + pair, R.string.xf_fix_open_dev,
					this::openDeveloperOptions);
		} else { // IDLE, CHECKING, PAIRING
			show(getString(R.string.xf_fix_working), 0, null);
		}
	}

	private String failure(String detail) {
		boolean blocked = detail.contains("USER_RESTRICTED") || detail.contains("SecurityException");
		return getString(blocked ? R.string.xf_fix_failed_restricted : R.string.xf_fix_failed) + "\n\n" + detail;
	}

	private void showSecondary(int label, Runnable action) {
		secondary.setVisibility(View.VISIBLE);
		secondary.setText(label);
		secondary.setOnClickListener(v -> action.run());
	}

	/** The extra asks Settings to scroll to Wireless debugging; phones that do not know it ignore it. */
	private void openDeveloperOptions() {
		open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
				.putExtra(":settings:fragment_args_key", "toggle_adb_wireless"));
	}

	private void allowNotifications() {
		if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) && !askedNotifications &&
				(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
			askedNotifications = true;
			requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
		} else {
			open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
					.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
		}
	}

	@Override
	public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
		super.onRequestPermissionsResult(code, permissions, results);
		advance();
		render();
	}

	/** The first step that is not done is the one to do now. */
	private void showSteps(int[] labels, boolean[] done) {
		steps.setVisibility(View.VISIBLE);
		steps.removeAllViews();
		float dp = getResources().getDisplayMetrics().density;
		boolean currentShown = false;
		for (int i = 0; i < labels.length; i++) {
			TextView row = new TextView(this);
			row.setTextSize(16f);
			row.setPadding(0, Math.round(6 * dp), 0, Math.round(6 * dp));
			String label = getString(labels[i]);
			if (done[i] && !currentShown) {
				row.setText("✓  " + label);
			} else if (!currentShown) {
				currentShown = true;
				row.setText("▶  " + label);
				row.setTypeface(Typeface.DEFAULT_BOLD);
			} else {
				row.setText("○  " + label);
			}
			steps.addView(row);
		}
	}

	/** No label hides the button while the work runs. */
	private void show(String text, int label, Runnable action) {
		body.setText(text);
		primary.setVisibility((label == 0) ? View.GONE : View.VISIBLE);
		if (label != 0) primary.setText(label);
		primary.setOnClickListener((action == null) ? null : v -> action.run());
	}

	/**
	 * Settings opens as its own window, not on top of this screen: the user must not have to come
	 * back through it, because leaving the pairing window closes it. On a large screen (unfolded
	 * phone, tablet) it opens next to the app.
	 */
	private void open(Intent intent) {
		try {
			startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK |
					Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT));
		} catch (ActivityNotFoundException ex) {
			Toast.makeText(this, R.string.xf_fix_no_settings, Toast.LENGTH_LONG).show();
		}
	}
}
