package me.aap.fermata.xf;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;

/**
 * xFermaTube: a plain-text report of how the app was installed, for the case when Android Auto
 * shows only the audio entry. Android Auto decides by the install source whether a sideloaded video
 * app is shown, and that decision is not visible to the user. The report contains no personal data:
 * app, phone model, Android and Android Auto versions, install source.
 */
public final class XfInstallReport {
	private static final String AA_PKG = "com.google.android.projection.gearhead";
	private static final String PLAY_STORE = XfInstallFix.PLAY_STORE;
	private static final String PROJECTION =
			"com.google.android.gms.car.category.CATEGORY_PROJECTION";

	private XfInstallReport() {
	}

	public static void show(MainActivityDelegate a) {
		Context ctx = a.getContext();
		String text = build(ctx);
		// Copy and Share are in the row on top of the text
		boolean fix = XfInstallFix.offered(ctx) && XfInstallFix.available(ctx);
		try {
			var b = a.createDialogBuilder()
					.setTitle(R.string.xf_aa_check)
					.setView(XfLog.textView(ctx, text, R.string.xf_aa_check, false))
					.setNegativeButton(R.string.xf_close, null);
			if (fix) b.setPositiveButton(R.string.xf_fix_now, (d, w) -> XfInstallFix.open(ctx));
			b.show();
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to show the install report");
		}
	}

	public static String build(Context ctx) {
		StringBuilder b = new StringBuilder(1024);
		PackageManager pm = ctx.getPackageManager();
		String pkg = ctx.getPackageName();

		b.append("xFermaTube ").append(XfConfig.RELEASE).append(" (").append(BuildConfig.VERSION_NAME)
				.append(" / ").append(BuildConfig.VERSION_CODE).append(")\n");
		b.append("Package: ").append(pkg).append('\n');
		try {
			PackageInfo pi = pm.getPackageInfo(pkg, 0);
			b.append("Installed: ").append(date(pi.firstInstallTime)).append(", updated: ")
					.append(date(pi.lastUpdateTime)).append('\n');
		} catch (Exception ex) {
			b.append("Installed: ? (").append(ex.getClass().getSimpleName()).append(")\n");
		}
		b.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
				.append(", Android ").append(Build.VERSION.RELEASE).append(" (API ")
				.append(Build.VERSION.SDK_INT).append(")\n");
		b.append("System build: ").append(Build.DISPLAY).append('\n');
		b.append("Keeping the car picture moving: ").append(XfKeepDrawing.status(ctx)).append('\n');

		b.append("\nInstall source\n");
		String installer = null;
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				InstallSourceInfo i = pm.getInstallSourceInfo(pkg);
				installer = i.getInstallingPackageName();
				b.append("  Installer: ").append(app(pm, installer)).append('\n');
				b.append("  Initiated by: ").append(app(pm, i.getInitiatingPackageName())).append('\n');
				b.append("  Originating: ").append(app(pm, i.getOriginatingPackageName())).append('\n');
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
					b.append("  Update owner: ").append(app(pm, i.getUpdateOwnerPackageName())).append('\n');
				}
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					b.append("  Package source: ").append(source(i.getPackageSource())).append('\n');
				}
			} else {
				installer = pm.getInstallerPackageName(pkg);
				b.append("  Installer: ").append(app(pm, installer)).append('\n');
			}
		} catch (Exception ex) {
			b.append("  ? (").append(ex.getClass().getSimpleName()).append(")\n");
		}

		b.append("\nAndroid Auto\n");
		try {
			PackageInfo aa = pm.getPackageInfo(AA_PKG, 0);
			b.append("  Version: ").append(aa.versionName).append('\n');
			b.append("  Enabled: ").append(aa.applicationInfo == null || aa.applicationInfo.enabled)
					.append('\n');
		} catch (PackageManager.NameNotFoundException ex) {
			b.append("  Not installed or not visible\n");
		} catch (Exception ex) {
			b.append("  ? (").append(ex.getClass().getSimpleName()).append(")\n");
		}

		b.append("\nCar screen components of this app\n");
		try {
			Intent q = new Intent(Intent.ACTION_MAIN).addCategory(PROJECTION).setPackage(pkg);
			List<ResolveInfo> all = pm.queryIntentServices(q, PackageManager.MATCH_DISABLED_COMPONENTS);
			if (all.isEmpty()) b.append("  none found\n");
			for (ResolveInfo ri : all) {
				if (ri.serviceInfo == null) continue;
				String name = ri.serviceInfo.name;
				int st = pm.getComponentEnabledSetting(new ComponentName(pkg, name));
				boolean on = (st == COMPONENT_ENABLED_STATE_ENABLED) ||
						((st == COMPONENT_ENABLED_STATE_DEFAULT) && ri.serviceInfo.enabled);
				b.append("  ").append(name.substring(name.lastIndexOf('.') + 1)).append(": ")
						.append(on ? "enabled" : "disabled").append('\n');
			}
		} catch (Exception ex) {
			b.append("  ? (").append(ex.getClass().getSimpleName()).append(")\n");
		}

		b.append("\nMicrophone permission: ").append(
				ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PERMISSION_GRANTED ?
						"granted" : "not granted").append('\n');

		// Patikrinta 2026-11-16: telefone, kuriame video programa rodoma (Samsung, diegta per
		// AAenabler), Installer = com.android.vending, Initiated by = sisteminis diegiklis. Po
		// "pm install -i" (Initiated by = com.android.shell) tas pats automobilis video programos nerodė.
		String fixHow = XfInstallFix.available(ctx) ?
				"1. Tap Fix now: the app asks the phone's installer to install it again, marked as " +
						"coming from Google Play Store. One tap on Update, settings are kept.\n" +
						"2. Or uninstall, then install through AAenabler: open AAenabler and pick the APK " +
						"from inside it. Run this check again.\n" :
				"1. Uninstall, then install through AAenabler: open AAenabler and pick the APK " +
						"from inside it. Run this check again.\n";
		switch (XfInstallFix.verdict(ctx)) {
			case GOOD -> b.append("\nRESULT: good. Google Play Store is the installer and the phone's " +
					"installer did the install, as on a phone where the video app shows up. Look for the " +
					"video app on the car screen: the Customize launcher list on the phone may not show " +
					"it while no car is connected.\n");
			case BAD -> {
				if (PLAY_STORE.equals(installer)) {
					b.append("\nRESULT: not accepted. Google Play Store is the installer, but the install " +
							"was started by the ADB shell (adb install -i, or Fix now of an older xFermaTube). " +
							"Android Auto in a car does not take that: on a phone where the video app shows " +
							"up, Initiated by is the phone's package installer.\n").append(fixHow);
				} else {
					b.append("\nRESULT: not accepted. The installer is not Google Play Store. On a phone " +
							"where the video app shows up it is com.android.vending, so this is the most " +
							"likely reason why Android Auto hides the video app.\n").append(fixHow);
				}
				b.append("Installing from a PC with adb install -i com.android.vending is not enough: " +
						"the install is then recorded as started by the shell.\n");
			}
			default -> b.append("\nRESULT: unclear. This combination of Installer and Initiated by has " +
					"not been reported yet. If the car screen shows only the audio entry, fix it:\n")
					.append(fixHow);
		}
		b.append("\nNot readable by the app, check by hand: Android Auto settings > Developer " +
				"settings > Unknown sources must be ticked.\n");
		lastCrash(ctx, b);
		String appLog = XfLog.read(ctx, 80);
		b.append("\nApp log (last lines; all of it: Settings > App log)\n")
				.append(appLog.isEmpty() ? "(empty)\n" : appLog);
		recentLog(b);
		return b.toString();
	}

	/**
	 * The app's own recent log lines (an app can read only its own): car and phone window
	 * lifecycle, WebView visibility, video delay stalls, warnings and errors.
	 */
	private static void recentLog(StringBuilder b) {
		try {
			Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "-t", "3000",
					"--pid=" + android.os.Process.myPid()});
			java.util.regex.Pattern keep = java.util.regex.Pattern.compile(
					"^\\S+ \\S+ ([EF]/|W/(?!chromium)|./xf)");
			StringBuilder log = new StringBuilder();
			try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
					p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
				for (String line; (line = r.readLine()) != null; ) {
					if (!keep.matcher(line).find()) continue;
					if (line.length() > 300) line = line.substring(0, 300) + "…";
					log.append(line).append('\n');
					if (log.length() > 40000) log.delete(0, 20000);
				}
			}
			p.destroy();
			if (log.length() > 10000) log.delete(0, log.length() - 10000);
			b.append("\nRecent log\n").append(log.length() == 0 ? "(empty)\n" : log);
		} catch (Exception ex) {
			b.append("\nRecent log: unreadable (").append(ex.getClass().getSimpleName()).append(")\n");
		}
	}

	private static java.io.File carStartupErrorFile(Context ctx) {
		return new java.io.File(new java.io.File(ctx.getNoBackupFilesDir(), "diagnostics"), "car-startup-error.txt");
	}

	/** A car screen startup failure is caught (not a crash), so it is kept here for the report. */
	public static void recordCarStartupError(Context ctx, Throwable error) {
		try {
			java.io.StringWriter sw = new java.io.StringWriter();
			error.printStackTrace(new java.io.PrintWriter(sw));
			String text = date(System.currentTimeMillis()) + '\n' + sw;
			if (text.length() > 6000) text = text.substring(0, 6000) + "…";
			java.io.File f = carStartupErrorFile(ctx);
			java.io.File dir = f.getParentFile();
			if ((dir != null) && !dir.isDirectory()) dir.mkdirs();
			// Append: a follow-up failure must not hide the first one; keep the file small
			if (f.isFile() && (f.length() > 20000)) f.delete();
			java.nio.file.Files.write(f.toPath(), (text + '\n').getBytes(java.nio.charset.StandardCharsets.UTF_8),
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (Throwable ignore) {
		}
	}

	/** The newest crash recorded by the diagnostics crash handler, so that it can be copied without a PC. */
	private static void lastCrash(Context ctx, StringBuilder b) {
		try {
			java.io.File se = carStartupErrorFile(ctx);
			if (se.isFile()) {
				b.append("\nCar screen startup error\n").append(new String(
						java.nio.file.Files.readAllBytes(se.toPath()), java.nio.charset.StandardCharsets.UTF_8))
						.append('\n');
			}
		} catch (Exception ignore) {
		}
		try {
			java.io.File dir = new java.io.File(new java.io.File(ctx.getNoBackupFilesDir(), "diagnostics"), "crash");
			java.io.File[] files = dir.listFiles((d, n) -> n.startsWith("crash-") && n.endsWith(".json"));
			if ((files == null) || (files.length == 0)) return;
			java.util.Arrays.sort(files, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
			java.io.File last = files[0];
			// Short list first: when, which exception, where
			b.append("\nRecent crashes (").append(files.length).append(")\n");
			java.util.regex.Pattern p = java.util.regex.Pattern.compile(
					"\"type\":\"([^\"]+)\",\"stack\":\\[\"([^\"]+)\"(?:,\"([^\"]+)\")?");
			for (int i = 0; i < Math.min(5, files.length); i++) {
				String t = new String(java.nio.file.Files.readAllBytes(files[i].toPath()),
						java.nio.charset.StandardCharsets.UTF_8);
				java.util.regex.Matcher m = p.matcher(t);
				b.append("- ").append(date(files[i].lastModified())).append(": ");
				if (m.find()) {
					b.append(m.group(1)).append(" at ").append(m.group(2));
					if (m.group(3) != null) b.append(" < ").append(m.group(3));
				} else {
					b.append("?");
				}
				b.append('\n');
			}
			String raw = new String(java.nio.file.Files.readAllBytes(last.toPath()),
					java.nio.charset.StandardCharsets.UTF_8);
			// What happened before the crash: breadcrumb events, compact
			java.util.regex.Matcher e = java.util.regex.Pattern.compile(
					"\"elapsed_ms\":(\\d+)[^{}]*?\"category\":\"([^\"]+)\",\"event\":\"([^\"]+)\"")
					.matcher(raw.replace("\\\"", "\""));
			StringBuilder ev = new StringBuilder();
			while (e.find()) {
				ev.append(e.group(1)).append("ms ").append(e.group(2)).append('/').append(e.group(3)).append('\n');
			}
			if (ev.length() > 3000) ev.delete(0, ev.length() - 3000);
			if (ev.length() > 0) b.append("\nEvents before the last crash\n").append(ev);
			String text = raw.replace("\\n", "\n").replace("\\t", "  ");
			if (text.length() > 4000) text = text.substring(0, 4000) + "…";
			b.append("\nLast crash (").append(date(last.lastModified())).append(")\n").append(text).append('\n');
		} catch (Exception ex) {
			b.append("\nLast crash: unreadable (").append(ex.getClass().getSimpleName()).append(")\n");
		}
	}

	private static String app(PackageManager pm, String pkg) {
		if (pkg == null) return "none";
		try {
			CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
			return pkg + " (" + label + ")";
		} catch (Exception ex) {
			return pkg;
		}
	}

	private static String source(int s) {
		return switch (s) {
			case 0 -> "unspecified";
			case 1 -> "other";
			case 2 -> "store";
			case 3 -> "local file";
			case 4 -> "downloaded file";
			default -> String.valueOf(s);
		};
	}

	private static String date(long ms) {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
	}
}
