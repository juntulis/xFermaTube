package me.app.fermatax.auto.xf;

import static me.aap.utils.async.Completed.completedVoid;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.android.AdbMdns;
import me.aap.fermata.R;
import me.aap.fermata.xf.XfInstallFix;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.log.Log;
import me.app.fermatax.auto.carapps.adb.AdbConnectPort;
import me.app.fermatax.auto.carapps.adb.LocalAdb;

/**
 * xFermaTube: the second way to reinstall the app "from Google Play", for phones whose installer
 * drops the Play Store mark when the app itself opens it (the first way, in XfInstallFixActivity).
 * Here the ADB shell opens the phone's package installer on the app's own APK: the shell is allowed
 * to name Play Store as the installer, and the install is still started by the system installer,
 * which is what Android Auto checks. A plain "pm install -i com.android.vending" is not enough: the
 * install is then recorded as started by the shell, and the car hides the video app.
 * <p>
 * The shell is reached through the phone's own Wireless debugging over loopback: the user pairs once
 * with the 6-digit code typed into a notification. The user then confirms the installer's Update
 * window. Settings and data are kept. The pairing code is never logged or stored.
 * <p>
 * Works on Android 15+ (older versions refuse an installer window opened by the shell), and not on
 * Samsung, whose installer ignores the mark when the shell asks.
 */
public final class XfInstallFixer {
	public enum State {
		IDLE, CHECKING, NO_WIRELESS, NOTIFICATIONS_OFF, WAITING_CODE, PAIRING, WRONG_CODE, PAIR_FAILED,
		/** The installer window was opened: the rest is the user's tap on Update. */
		INSTALLING, FAILED
	}

	private static final String GOOGLE_INSTALLER = "com.google.android.packageinstaller";
	private static final String AOSP_INSTALLER = "com.android.packageinstaller";
	private static final String CHANNEL = "xf_install_fix";
	private static final int NOTIFICATION_ID = 0x5f1c;
	private static final String KEY_CODE = "code";
	private static final long PAIRING_PORT_TIMEOUT_MS = 8000;
	private static final long CONNECT_PORT_TIMEOUT_MS = 5000;
	/** A receiver may hold the broadcast for about 10 s; the work continues after it is released. */
	private static final long RECEIVER_HOLD_MS = 9000;
	private static final long REPLY_TIMEOUT_MS = 10 * 60 * 1000L;

	private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private static volatile State state = State.IDLE;
	private static volatile String detail = "";
	/** A lookup for the system pairing window is running. */
	private static boolean pairingWatch;

	private XfInstallFixer() {
	}

	public static State state() {
		return state;
	}

	/** Why the installer could not be opened: the shell's answer or a short reason. */
	public static String detail() {
		return detail;
	}

	/** Android 15+ and not Samsung: see the class comment. */
	public static boolean supported() {
		return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) &&
				!"samsung".equalsIgnoreCase(Build.MANUFACTURER);
	}

	/**
	 * The phone's own package installer window for an APK: Google's, else AOSP's, else another app
	 * that is part of the system. Null when no system app takes APK installs.
	 */
	@Nullable
	static ComponentName systemInstaller(Context ctx) {
		ComponentName best = null;
		int bestRank = Integer.MAX_VALUE;
		for (ResolveInfo ri : installers(ctx)) {
			if (!isSystem(ri)) continue;
			String p = ri.activityInfo.packageName;
			int rank = GOOGLE_INSTALLER.equals(p) ? 0 : AOSP_INSTALLER.equals(p) ? 1 : 2;
			if (rank < bestRank) {
				best = new ComponentName(p, ri.activityInfo.name);
				bestRank = rank;
			}
		}
		return best;
	}

	/**
	 * True when an app that is not part of the system also takes APK installs (an installer app the
	 * user added). Its installs never carry the Play Store mark, so the request must name the system one.
	 */
	static boolean hasOtherInstaller(Context ctx) {
		for (ResolveInfo ri : installers(ctx)) {
			if (!isSystem(ri)) return true;
		}
		return false;
	}

	static Intent installIntent(Context ctx) {
		return new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(XfApkProvider.uri(ctx), XfApkProvider.MIME);
	}

	private static List<ResolveInfo> installers(Context ctx) {
		try {
			return ctx.getPackageManager().queryIntentActivities(installIntent(ctx), PackageManager.MATCH_DEFAULT_ONLY);
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to look for package installers");
			return List.of();
		}
	}

	private static boolean isSystem(ResolveInfo ri) {
		return (ri.activityInfo != null) && (ri.activityInfo.applicationInfo != null) &&
				((ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
	}

	public static void addListener(Runnable listener) {
		listeners.add(listener);
	}

	public static void removeListener(Runnable listener) {
		listeners.remove(listener);
	}

	/** Back to the start after a failure or a cancelled installer window, so that start() runs again. */
	public static void reset(Context c) {
		Context ctx = c.getApplicationContext();
		boolean opened;
		synchronized (XfInstallFixer.class) {
			if (busy()) return;
			opened = state == State.INSTALLING;
			state = State.IDLE;
		}
		// The installer window was closed without an install: that attempt is over
		if (opened) XfInstallFix.clearStarted(ctx);
		try {
			NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID);
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to cancel the install fix notification");
		}
		notifyListeners();
	}

	/** Looking for Wireless debugging or pairing: the way must not be changed under it. */
	static boolean busy() {
		State s = state;
		return (s == State.CHECKING) || (s == State.PAIRING);
	}

	/**
	 * Looks for Wireless debugging. If the app is already paired the installer is opened right away,
	 * otherwise the notification that takes the pairing code is posted.
	 */
	public static void start(Context c) {
		Context ctx = c.getApplicationContext();
		synchronized (XfInstallFixer.class) {
			if (busy()) return;
			state = State.CHECKING;
		}
		notifyListeners();
		LocalAdb adb;
		try {
			adb = new LocalAdb(ctx);
		} catch (Throwable t) {
			Log.e(t, "Cannot prepare ADB");
			fail(ctx, "ADB: " + t.getClass().getSimpleName());
			return;
		}
		port(ctx, null, CONNECT_PORT_TIMEOUT_MS).onCompletion((port, err) -> {
			if ((err != null) || (port == null)) {
				adb.close();
				setState(State.NO_WIRELESS);
			} else if (!adb.hasKey()) { // never paired: a connection attempt cannot succeed
				adb.close();
				askCode(ctx, State.WAITING_CODE);
			} else {
				launch(ctx, adb, port, true);
			}
		});
	}

	/** The reply notification times out and can be swiped away: post it again. */
	public static void askAgain(Context c) {
		State s = state;
		if ((s == State.NOTIFICATIONS_OFF) || (s == State.WAITING_CODE)) askCode(c, State.WAITING_CODE);
		else if ((s == State.WRONG_CODE) || (s == State.PAIR_FAILED)) askCode(c, s);
	}

	private static void askCode(Context c, State s) {
		Context ctx = c.getApplicationContext();
		if (!notificationsOn(ctx)) {
			setState(State.NOTIFICATIONS_OFF);
			return;
		}
		setState(s);
		post(ctx, codeText(s, false), true, false);
		watchPairingWindow(ctx);
	}

	private static int codeText(State s, boolean windowFound) {
		return (s == State.WRONG_CODE) ? R.string.xf_fix_wrong_code :
				(s == State.PAIR_FAILED) ? R.string.xf_fix_pair_failed :
						windowFound ? R.string.xf_fix_notif_found : R.string.xf_fix_notif_prompt;
	}

	/**
	 * The system pairing window closes as soon as the user leaves it, so the code has to be typed
	 * without coming back to the app. The window announces a pairing service: when it shows up, the
	 * notification is posted again so that it pops up over the window, with the reply field.
	 */
	private static void watchPairingWindow(Context ctx) {
		synchronized (XfInstallFixer.class) {
			if (pairingWatch) return;
			pairingWatch = true;
		}
		port(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING, REPLY_TIMEOUT_MS).onCompletion((port, err) -> {
			synchronized (XfInstallFixer.class) {
				pairingWatch = false;
			}
			if ((err != null) || (port == null)) return;
			State s = state;
			if ((s == State.WAITING_CODE) || (s == State.WRONG_CODE) || (s == State.PAIR_FAILED)) {
				post(ctx, codeText(s, true), true, true);
			}
		});
	}

	/** Pairs with the code the user typed, then starts the reinstall. */
	static FutureSupplier<Void> submit(Context c, @Nullable String input) {
		Context ctx = c.getApplicationContext();
		boolean valid = (input != null) && input.matches("[0-9]{6}");
		synchronized (XfInstallFixer.class) {
			// A stray reply must not disturb the running work or open a second installer window
			if (busy() || (state == State.INSTALLING)) return completedVoid();
			if (valid) state = State.PAIRING;
		}
		if (!valid) {
			askCode(ctx, State.WRONG_CODE);
			return completedVoid();
		}
		notifyListeners();
		post(ctx, R.string.xf_fix_working, false, false);
		LocalAdb adb;
		try {
			adb = new LocalAdb(ctx);
		} catch (Throwable t) {
			Log.e(t, "Cannot prepare the ADB pairing");
			askCode(ctx, State.PAIR_FAILED);
			return completedVoid();
		}
		Promise<Void> done = new Promise<>();
		port(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING, PAIRING_PORT_TIMEOUT_MS)
				.then(port -> adb.pair(port, input)).onCompletion((r, err) -> {
					if (err != null) {
						adb.close();
						Log.w("ADB pairing failed: ", err.getClass().getSimpleName());
						askCode(ctx, isPairingRequired(err) ? State.WRONG_CODE : State.PAIR_FAILED);
						done.complete(null);
						return;
					}
					port(ctx, null, CONNECT_PORT_TIMEOUT_MS).onCompletion((port, e) -> {
						if ((e != null) || (port == null)) {
							adb.close();
							fail(ctx, "Wireless debugging was not found after pairing");
						} else {
							launch(ctx, adb, port, false);
						}
						done.complete(null);
					});
				});
		return done;
	}

	private static void launch(Context ctx, LocalAdb adb, int port, boolean askCodeOnFailure) {
		String command;
		try {
			command = command(ctx);
		} catch (RuntimeException ex) {
			adb.close();
			fail(ctx, String.valueOf(ex.getMessage()));
			return;
		}
		XfInstallFix.markStarted(ctx, XfInstallFix.WAY_SHELL);
		adb.runForOutput(port, command).onCompletion((out, err) -> {
			adb.close();
			if (err == null) {
				// "am start" answers "Starting: Intent {…}", or an Error / exception line when it cannot
				String text = (out == null) ? "" : out.trim();
				if (text.contains("Error") || text.contains("Exception")) {
					fail(ctx, text);
					return;
				}
				XfInstallFix.handedOver();
				setState(State.INSTALLING);
				post(ctx, R.string.xf_fix_notif_installing, false, false);
			} else if (askCodeOnFailure) { // the old pairing is gone or was revoked: pair again
				XfInstallFix.clearStarted(ctx);
				Log.w("ADB connection failed: ", err.getClass().getSimpleName());
				askCode(ctx, State.WAITING_CODE);
			} else {
				Log.w("ADB command failed: ", err.getClass().getSimpleName());
				fail(ctx, "ADB: " + err.getClass().getSimpleName());
			}
		});
	}

	/**
	 * The shell command: open the phone's package installer on the app's own APK, with Play Store
	 * named as the installer. The shell cannot give the installer access to the APK, so the app grants
	 * it first. Only names made of safe characters go into the command.
	 */
	static String command(Context ctx) {
		try {
			XfApkProvider.apk(ctx);
		} catch (java.io.FileNotFoundException ex) {
			throw new IllegalStateException(ex.getMessage());
		}
		ComponentName installer = systemInstaller(ctx);
		if (installer == null) throw new IllegalStateException("No system package installer was found");
		String name = installer.flattenToString();
		Uri apk = XfApkProvider.uri(ctx);
		String uri = apk.toString();
		if (!name.matches("[A-Za-z0-9_./]+") || !uri.matches("content://[A-Za-z0-9_./]+")) {
			throw new IllegalStateException("Unexpected installer name");
		}
		ctx.grantUriPermission(installer.getPackageName(), apk, Intent.FLAG_GRANT_READ_URI_PERMISSION);
		return "am start -n " + name + " -a android.intent.action.INSTALL_PACKAGE -d " + uri +
				" -t " + XfApkProvider.MIME +
				" --es android.intent.extra.INSTALLER_PACKAGE_NAME " + XfInstallFix.PLAY_STORE +
				" --ez android.intent.extra.NOT_UNKNOWN_SOURCE true" +
				" --ei android.intent.extra.INSTALL_REASON 1" +
				" --es android.intent.extra.REFERRER_NAME android-app://" + XfInstallFix.PLAY_STORE + " 2>&1";
	}

	/** Off for the app, or only for this channel: either way the code cannot be typed. */
	private static boolean notificationsOn(Context ctx) {
		NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
		if (!nm.areNotificationsEnabled()) return false;
		NotificationChannel ch = nm.getNotificationChannel(CHANNEL);
		return (ch == null) || (ch.getImportance() != NotificationManager.IMPORTANCE_NONE);
	}

	private static void fail(Context ctx, String why) {
		XfInstallFix.clearStarted(ctx);
		detail = (why.length() > 600) ? why.substring(0, 600) : why;
		setState(State.FAILED);
		post(ctx, R.string.xf_fix_failed, false, false);
	}

	private static FutureSupplier<Integer> port(Context ctx, @Nullable String pairingType, long timeout) {
		try {
			return (pairingType == null) ? AdbConnectPort.find(ctx, timeout) :
					AdbConnectPort.find(ctx, pairingType, timeout);
		} catch (Throwable t) {
			return me.aap.utils.async.Completed.failed(t);
		}
	}

	private static boolean isPairingRequired(Throwable err) {
		for (int depth = 0; (err != null) && (depth < 8); depth++, err = err.getCause()) {
			if (err instanceof AdbPairingRequiredException) return true;
		}
		return false;
	}

	private static void setState(State s) {
		state = s;
		notifyListeners();
	}

	private static void notifyListeners() {
		for (Runnable l : listeners) {
			try {
				l.run();
			} catch (RuntimeException ex) {
				Log.e(ex, "Install fix listener failed");
			}
		}
	}

	/** Replaces the one notification: with a reply field while a code is expected, else a plain text. */
	@SuppressLint("MissingPermission") // checked with areNotificationsEnabled()
	private static void post(Context ctx, int textRes, boolean replyField, boolean popUp) {
		try {
			NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
			if (!nm.areNotificationsEnabled()) return;
			nm.createNotificationChannel(new NotificationChannel(CHANNEL, ctx.getString(R.string.xf_fix_title),
					NotificationManager.IMPORTANCE_HIGH));
			String text = ctx.getString(textRes);
			NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
					.setSmallIcon(R.drawable.notification)
					.setContentTitle(ctx.getString(R.string.xf_fix_notif_title))
					.setContentText(text)
					.setStyle(new NotificationCompat.BigTextStyle().bigText(text))
					.setOnlyAlertOnce(!popUp)
					.setAutoCancel(!replyField);
			if (replyField) {
				PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, Receiver.class),
						PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE); // the system fills in the reply
				String hint = ctx.getString(R.string.xf_fix_notif_hint);
				b.setOngoing(true).setTimeoutAfter(REPLY_TIMEOUT_MS).addAction(
						new NotificationCompat.Action.Builder(R.drawable.notification, hint, pi)
								.addRemoteInput(new RemoteInput.Builder(KEY_CODE).setLabel(hint).build()).build());
			} else {
				b.setTimeoutAfter(60000);
			}
			nm.notify(NOTIFICATION_ID, b.build());
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to post the install fix notification");
		}
	}

	/** Receives the code typed into the notification. Not exported: only our own PendingIntent reaches it. */
	public static final class Receiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context ctx, Intent intent) {
			Bundle reply = RemoteInput.getResultsFromIntent(intent);
			CharSequence text = (reply == null) ? null : reply.getCharSequence(KEY_CODE);
			PendingResult pending = goAsync();
			AtomicBoolean released = new AtomicBoolean();
			Runnable release = () -> {
				if (released.compareAndSet(false, true) && (pending != null)) pending.finish();
			};
			try {
				if (pending != null) {
					App.get().getScheduler().schedule(release, RECEIVER_HOLD_MS, TimeUnit.MILLISECONDS);
				}
				submit(ctx, (text == null) ? null : text.toString().replaceAll("[^0-9]", ""))
						.onCompletion((r, err) -> release.run());
			} catch (Throwable t) {
				Log.e(t, "Install fix reply failed");
				release.run();
			}
		}
	}
}
