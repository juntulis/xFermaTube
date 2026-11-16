package me.aap.fermata.xf;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;

/**
 * xFermaTube: Android Auto shows a sideloaded video app only when its install source is Google Play
 * Store. This class knows whether the source is wrong and offers the fix: the app reinstalls itself
 * through the phone's own Wireless debugging (the work is done by XfInstallFixer in the auto flavor).
 * The check runs when the app is opened on the phone, never on the car screen.
 */
public final class XfInstallFix {
	public static final String PLAY_STORE = "com.android.vending";
	private static final String ACTIVITY = "me.app.fermatax.auto.xf.XfInstallFixActivity";
	private static final String PREFS = "xf_install_fix";
	/** When the reinstall command was started: the app is killed by it and reads this on the next start. */
	private static final String STARTED = "started";
	/** lastUpdateTime of the install for which the user answered "Not now". */
	private static final String SKIPPED = "skipped";
	private static final long RESULT_WINDOW_MS = 15 * 60 * 1000L;
	/** A reinstall started by this process is still running: the startup check must not judge it. */
	private static volatile boolean running;

	private XfInstallFix() {
	}

	/** The installer package, null when there is none; "?" when it cannot be read. */
	public static String installer(Context ctx) {
		try {
			PackageManager pm = ctx.getPackageManager();
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				return pm.getInstallSourceInfo(ctx.getPackageName()).getInstallingPackageName();
			}
			return pm.getInstallerPackageName(ctx.getPackageName());
		} catch (Exception ex) {
			return "?";
		}
	}

	/** True only when the installer was read and is not Google Play Store. */
	public static boolean needed(Context ctx) {
		String i = installer(ctx);
		return !PLAY_STORE.equals(i) && !"?".equals(i);
	}

	/** Wireless debugging exists from Android 11; the fix screen is part of the auto flavor. */
	public static boolean available(Context ctx) {
		if (!BuildConfig.AUTO || (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)) return false;
		try {
			return ctx.getPackageManager().resolveActivity(intent(ctx), 0) != null;
		} catch (Exception ex) {
			return false;
		}
	}

	public static void open(Context ctx) {
		try {
			ctx.startActivity(intent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to open the install fix");
		}
	}

	private static Intent intent(Context ctx) {
		return new Intent().setClassName(ctx.getPackageName(), ACTIVITY);
	}

	private static SharedPreferences prefs(Context ctx) {
		return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	/** Written synchronously: the reinstall kills the process a few seconds later. */
	public static void markStarted(Context ctx) {
		running = true;
		prefs(ctx).edit().putLong(STARTED, System.currentTimeMillis()).commit();
	}

	public static void clearStarted(Context ctx) {
		running = false;
		prefs(ctx).edit().remove(STARTED).apply();
	}

	/**
	 * Called when the app is opened on the phone. Shows the result of a reinstall that has just
	 * happened, or offers the fix when the install source is wrong. Returns true if a dialog was shown.
	 */
	public static boolean checkAtStartup(MainActivityDelegate a) {
		if (!XfConfig.YOUTUBE_ONLY || a.isCarActivity() || running) return false;
		try {
			Context ctx = a.getContext();
			SharedPreferences sp = prefs(ctx);
			boolean needed = needed(ctx);
			boolean fixed = PLAY_STORE.equals(installer(ctx));
			long started = sp.getLong(STARTED, 0);
			// With the right install source the app needs no ADB access, whenever it is opened
			if (fixed) forgetAdbKey(ctx);

			if (started != 0) {
				sp.edit().remove(STARTED).apply();
				long age = System.currentTimeMillis() - started;
				if ((age >= 0) && (age < RESULT_WINDOW_MS)) {
					if (fixed) {
						a.getHandler().post(() -> show(a, R.string.xf_fix_done_title, ctx.getString(R.string.xf_fix_done),
								0, null, R.string.xf_close, null));
						return true;
					}
					if (available(ctx)) {
						a.getHandler().post(() -> show(a, R.string.xf_fix_title, ctx.getString(R.string.xf_fix_unfinished),
								R.string.xf_fix_open, () -> open(ctx), R.string.xf_close, null));
						return true;
					}
				}
			}

			if (!needed || !available(ctx)) return false;
			long updated = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
			if (sp.getLong(SKIPPED, 0) == updated) return false;
			String from = installer(ctx);
			a.getHandler().post(() -> show(a, R.string.xf_fix_ask_title,
					ctx.getString(R.string.xf_fix_ask, (from == null) ? "none" : from),
					R.string.xf_fix_now, () -> open(ctx),
					R.string.xf_mic_later, () -> sp.edit().putLong(SKIPPED, updated).apply()));
			return true;
		} catch (Exception ex) {
			Log.e(ex, "Install source check failed");
			return false;
		}
	}

	private static void show(MainActivityDelegate a, int title, String message, int positive,
													 Runnable onPositive, int negative, Runnable onNegative) {
		try {
			var b = a.createDialogBuilder().setTitle(title).setMessage(message)
					.setNegativeButton(negative, (d, w) -> {
						if (onNegative != null) onNegative.run();
					});
			if (positive != 0) b.setPositiveButton(positive, (d, w) -> {
				if (onPositive != null) onPositive.run();
			});
			b.show();
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to show the install fix dialog");
		}
	}

	/** After the fix the app needs no ADB access: drop the key that Wireless debugging was paired with. */
	private static void forgetAdbKey(Context ctx) {
		try {
			File key = new File(new File(ctx.getFilesDir(), "adb"), "identity");
			if (key.isFile() && !key.delete()) Log.w("Failed to delete the ADB key");
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to delete the ADB key");
		}
	}
}
