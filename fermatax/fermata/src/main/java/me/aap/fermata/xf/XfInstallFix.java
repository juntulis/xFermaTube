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
 * xFermaTube: Android Auto shows a sideloaded video app only when it was installed "from Google
 * Play": the installer on record must be Google Play Store, and the install must have been started
 * by the phone's own package installer (or Play Store), not by the ADB shell. This class reads that
 * record, says whether it is good, and offers the fix. The fix itself (the app asks the phone's
 * installer to install it again with that mark) is in the auto flavor: XfInstallFixActivity.
 * The check runs when the app is opened on the phone, never on the car screen.
 */
public final class XfInstallFix {
	public static final String PLAY_STORE = "com.android.vending";
	public static final String SHELL = "com.android.shell";
	/** The app itself opened the phone's installer. */
	public static final int WAY_SIMPLE = 1;
	/** The shell opened the phone's installer, through Wireless debugging. */
	public static final int WAY_SHELL = 2;

	public enum Verdict {
		/** As on a phone where the video app shows up. */
		GOOD,
		/** Known not to be accepted: the installer is not Play Store, or the shell started the install. */
		BAD,
		/** Not readable, or a combination nobody has reported yet. */
		UNKNOWN
	}

	private static final String ACTIVITY = "me.app.fermatax.auto.xf.XfInstallFixActivity";
	private static final String PREFS = "xf_install_fix";
	/** When a reinstall was started: the app is killed by it and reads this on the next start. */
	private static final String STARTED = "started";
	/** lastUpdateTime when that reinstall was started: tells whether it has happened. */
	private static final String STARTED_UPDATED = "startedUpdated";
	private static final String STARTED_WAY = "startedWay";
	/** A reinstall was done that way and the record came out wrong: this phone does not keep the mark. */
	private static final String SIMPLE_FAILED = "simpleFailed";
	private static final String SHELL_FAILED = "shellFailed";
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

	/** The package that started the install (Android 11+), null when there is none; "?" when it cannot be read. */
	public static String initiator(Context ctx) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
		try {
			return ctx.getPackageManager().getInstallSourceInfo(ctx.getPackageName()).getInitiatingPackageName();
		} catch (Exception ex) {
			return "?";
		}
	}

	public static Verdict verdict(Context ctx) {
		return verdict(installer(ctx), initiator(ctx), Build.VERSION.SDK_INT);
	}

	/**
	 * Measured 2026-11: with Installer = Play Store and Initiated by = the system package installer
	 * (AAenabler on a Samsung) the car shows the video app; with Initiated by = shell ("pm install -i"
	 * or "adb install -i") it does not. The open tools KingInstaller and UnknownAuto use the same rule.
	 */
	static Verdict verdict(String installer, String initiator, int sdk) {
		if ("?".equals(installer)) return Verdict.UNKNOWN;
		if (!PLAY_STORE.equals(installer)) return Verdict.BAD;
		if (sdk < Build.VERSION_CODES.R) return Verdict.GOOD;
		if (SHELL.equals(initiator)) return Verdict.BAD;
		if (PLAY_STORE.equals(initiator) || ((initiator != null) && initiator.contains("packageinstaller"))) {
			return Verdict.GOOD;
		}
		return Verdict.UNKNOWN;
	}

	/** True when the install record is known not to be accepted by Android Auto. */
	public static boolean needed(Context ctx) {
		return verdict(ctx) == Verdict.BAD;
	}

	/** True unless the install record is known to be good: the fix is then offered in the settings. */
	public static boolean offered(Context ctx) {
		return verdict(ctx) != Verdict.GOOD;
	}

	/** The fix screen is part of the auto flavor. */
	public static boolean available(Context ctx) {
		if (!BuildConfig.AUTO) return false;
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

	private static long updated(Context ctx) {
		try {
			return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
		} catch (Exception ex) {
			return 0;
		}
	}

	/** Written synchronously: the reinstall kills the process. */
	public static void markStarted(Context ctx, int way) {
		running = true;
		prefs(ctx).edit().putLong(STARTED, System.currentTimeMillis()).putLong(STARTED_UPDATED, updated(ctx))
				.putInt(STARTED_WAY, way).commit();
	}

	/**
	 * The installer window is open and the rest is up to the user. If the app is opened again without
	 * having been replaced, the startup check may say that the reinstall did not finish.
	 */
	public static void handedOver() {
		running = false;
	}

	public static void clearStarted(Context ctx) {
		running = false;
		prefs(ctx).edit().remove(STARTED).remove(STARTED_UPDATED).remove(STARTED_WAY).apply();
	}

	/** The phone's installer did not keep the Play Store mark when the app itself opened it. */
	public static boolean simpleFailed(Context ctx) {
		return prefs(ctx).getBoolean(SIMPLE_FAILED, false);
	}

	/** The user picked the other way without a failed reinstall, e.g. the installer could not be opened. */
	public static void skipSimple(Context ctx) {
		prefs(ctx).edit().putBoolean(SIMPLE_FAILED, true).apply();
	}

	public static boolean shellFailed(Context ctx) {
		return prefs(ctx).getBoolean(SHELL_FAILED, false);
	}

	/** Start over with the simple way. */
	public static void resetFailed(Context ctx) {
		prefs(ctx).edit().remove(SIMPLE_FAILED).remove(SHELL_FAILED).apply();
	}

	/**
	 * Called when the app is opened on the phone. Shows the result of a reinstall that has just
	 * happened, or offers the fix when the install record is wrong. Returns true if a dialog was shown.
	 */
	public static boolean checkAtStartup(MainActivityDelegate a) {
		if (!XfConfig.YOUTUBE_ONLY || a.isCarActivity() || running) return false;
		try {
			Context ctx = a.getContext();
			SharedPreferences sp = prefs(ctx);
			Verdict verdict = verdict(ctx);
			boolean fixed = verdict == Verdict.GOOD;
			long updated = updated(ctx);
			long started = sp.getLong(STARTED, 0);
			// With a good install record the app needs no ADB access, whenever it is opened
			if (fixed) {
				forgetAdbKey(ctx);
				if (simpleFailed(ctx) || shellFailed(ctx)) resetFailed(ctx);
			}

			if (started != 0) {
				long startedUpdated = sp.getLong(STARTED_UPDATED, updated);
				int way = sp.getInt(STARTED_WAY, 0);
				sp.edit().remove(STARTED).remove(STARTED_UPDATED).remove(STARTED_WAY).apply();
				// Reinstalled, and the record is still wrong: that way does not work on this phone.
				// Remembered however late the app is opened again, so that the next try takes the other way.
				boolean reinstalled = (updated != 0) && (updated != startedUpdated);
				boolean ignored = reinstalled && (verdict == Verdict.BAD) && (way != 0);
				if (ignored) sp.edit().putBoolean((way == WAY_SHELL) ? SHELL_FAILED : SIMPLE_FAILED, true).apply();
				long age = System.currentTimeMillis() - started;
				if ((age >= 0) && (age < RESULT_WINDOW_MS)) {
					if (fixed) {
						String msg = ctx.getString(R.string.xf_fix_done) +
								((way == WAY_SHELL) ? "\n\n" + ctx.getString(R.string.xf_fix_done_wireless) : "");
						a.getHandler().post(() -> show(a, R.string.xf_fix_done_title, msg, 0, null, R.string.xf_close, null));
						return true;
					}
					if ((verdict == Verdict.BAD) && available(ctx)) {
						int msg = ignored ? R.string.xf_fix_ignored : R.string.xf_fix_unfinished;
						a.getHandler().post(() -> show(a, R.string.xf_fix_title, ctx.getString(msg),
								R.string.xf_fix_open, () -> open(ctx), R.string.xf_close, null));
						return true;
					}
				}
			}

			if ((verdict != Verdict.BAD) || !available(ctx)) return false;
			if (sp.getLong(SKIPPED, 0) == updated) return false;
			String from = installer(ctx);
			String by = initiator(ctx);
			a.getHandler().post(() -> show(a, R.string.xf_fix_ask_title,
					ctx.getString(R.string.xf_fix_ask, (from == null) ? "none" : from, (by == null) ? "none" : by),
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
