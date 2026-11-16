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
 * xFermaTube: reinstalls the app with Google Play Store as its install source, which is what Android
 * Auto wants before it shows a sideloaded video app. The app cannot do that with its own rights, so
 * it uses the phone's own Wireless debugging (Android 11+) over loopback: the user pairs once with
 * the 6-digit code typed into a notification, then the shell runs "pm install -r -i
 * com.android.vending" on a copy of the installed APK. Settings and data are kept.
 * <p>
 * The reinstall kills this process, so the command runs detached and starts the app again.
 * If the process is still alive later, the command's log is read to tell the user why it failed.
 * The pairing code is never logged or stored.
 */
public final class XfInstallFixer {
	public enum State {
		IDLE, CHECKING, NO_WIRELESS, NOTIFICATIONS_OFF, WAITING_CODE, PAIRING, WRONG_CODE, PAIR_FAILED,
		INSTALLING, FAILED
	}

	/** detail() of a reinstall that neither killed the app nor wrote a result. */
	public static final String NO_RESULT = "no result";
	private static final String CHANNEL = "xf_install_fix";
	private static final int NOTIFICATION_ID = 0x5f1c;
	private static final String KEY_CODE = "code";
	private static final String TMP = "/data/local/tmp/xf-fix";
	private static final long PAIRING_PORT_TIMEOUT_MS = 8000;
	private static final long CONNECT_PORT_TIMEOUT_MS = 5000;
	/** A receiver may hold the broadcast for about 10 s; the work continues after it is released. */
	private static final long RECEIVER_HOLD_MS = 9000;
	private static final long REPLY_TIMEOUT_MS = 10 * 60 * 1000L;
	/** The log is read 8, 30 and 90 seconds after the command was started. */
	private static final long[] WATCH_MS = {8000, 22000, 60000};

	private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private static volatile State state = State.IDLE;
	private static volatile String detail = "";
	private static int run;
	/** A lookup for the system pairing window is running. */
	private static boolean pairingWatch;

	private XfInstallFixer() {
	}

	public static State state() {
		return state;
	}

	/** Why the reinstall failed: the shell's answer, NO_RESULT, or a short reason. */
	public static String detail() {
		return detail;
	}

	public static void addListener(Runnable listener) {
		listeners.add(listener);
	}

	public static void removeListener(Runnable listener) {
		listeners.remove(listener);
	}

	/** Back to the start after a failure, so that start() runs again. */
	public static void reset() {
		synchronized (XfInstallFixer.class) {
			if (busy()) return;
			state = State.IDLE;
		}
		notifyListeners();
	}

	private static boolean busy() {
		State s = state;
		return (s == State.CHECKING) || (s == State.PAIRING) || (s == State.INSTALLING);
	}

	/**
	 * Looks for Wireless debugging. If the app is already paired the reinstall starts right away,
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
			if (busy()) return completedVoid(); // a stray reply must not disturb the running work
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
		XfInstallFix.markStarted(ctx);
		adb.runAndClose(port, command).onCompletion((r, err) -> {
			adb.close();
			if (err == null) {
				int id;
				synchronized (XfInstallFixer.class) {
					state = State.INSTALLING;
					id = ++run;
				}
				notifyListeners();
				post(ctx, R.string.xf_fix_notif_installing, false, false);
				watchLater(ctx, id, 0);
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
	 * The shell job: copy the installed APK, install the copy with Play Store as the installer and
	 * start the app again. It runs detached with SIGHUP ignored, because this process (the ADB
	 * client) is killed by the install. Only paths made of safe characters go into the command.
	 */
	static String command(Context ctx) {
		ApplicationInfo ai = ctx.getApplicationInfo();
		if ((ai.splitSourceDirs != null) && (ai.splitSourceDirs.length > 0)) {
			throw new IllegalStateException("The app is installed as split APKs");
		}
		String src = ai.sourceDir;
		String pkg = ctx.getPackageName();
		if ((src == null) || !src.matches("[A-Za-z0-9_./~=+-]+") || !pkg.matches("[A-Za-z0-9_.]+")) {
			throw new IllegalStateException("Unexpected APK path");
		}
		String apk = TMP + ".apk";
		String log = TMP + ".log";
		String pid = TMP + ".pid";
		String start = "";
		Intent li = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
		ComponentName cn = (li == null) ? null : li.getComponent();
		if (cn != null) {
			String name = cn.flattenToShortString();
			if (name.matches("[A-Za-z0-9_./]+")) start = "sleep 1; am start -n " + name + " >/dev/null 2>&1; ";
		}
		String job = "echo $$ >" + pid + "; sleep 2; cp " + src + " " + apk + " >" + log +
				" 2>&1 && pm install -r -i " + XfInstallFix.PLAY_STORE + " " + apk + " >>" + log +
				" 2>&1 && { rm -f " + log + "; " + start + "}; rm -f " + apk + " " + pid;
		// One job at a time: an earlier one may still wait for the user's answer to an install question
		return "trap '' HUP; P=$(cat " + pid + " 2>/dev/null); " +
				"if [ -n \"$P\" ] && grep -q xf-fix /proc/$P/cmdline 2>/dev/null; then exit 0; fi; " +
				"rm -f " + apk + " " + log + "; nohup $(command -v setsid) sh -c '" + job +
				"' >/dev/null 2>&1 </dev/null &";
	}

	/** Off for the app, or only for this channel: either way the code cannot be typed. */
	private static boolean notificationsOn(Context ctx) {
		NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
		if (!nm.areNotificationsEnabled()) return false;
		NotificationChannel ch = nm.getNotificationChannel(CHANNEL);
		return (ch == null) || (ch.getImportance() != NotificationManager.IMPORTANCE_NONE);
	}

	private static void watchLater(Context ctx, int id, int step) {
		App.get().getScheduler().schedule(() -> watch(ctx, id, step), WATCH_MS[step], TimeUnit.MILLISECONDS);
	}

	/** Still alive: the install has not replaced the app (yet). A non-empty log says why. */
	private static void watch(Context ctx, int id, int step) {
		if (!watching(id)) return;
		boolean last = step == WATCH_MS.length - 1;
		LocalAdb adb;
		try {
			adb = new LocalAdb(ctx);
		} catch (Throwable t) {
			if (last) fail(ctx, NO_RESULT);
			else watchLater(ctx, id, step + 1);
			return;
		}
		port(ctx, null, CONNECT_PORT_TIMEOUT_MS)
				.then(port -> adb.runForOutput(port, "cat " + TMP + ".log 2>/dev/null"))
				.onCompletion((out, err) -> {
					adb.close();
					if (!watching(id)) return;
					String text = ((err != null) || (out == null)) ? "" : out.trim();
					if (!text.isEmpty() && !text.contains("Success")) fail(ctx, text);
					else if (last) fail(ctx, NO_RESULT);
					else watchLater(ctx, id, step + 1);
				});
	}

	private static boolean watching(int id) {
		synchronized (XfInstallFixer.class) {
			return (state == State.INSTALLING) && (id == run);
		}
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
