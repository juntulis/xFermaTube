package me.aap.fermata.xf;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
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
		// Three buttons at most: with a wrong install source "Fix now" takes the place of Close
		boolean fix = XfInstallFix.needed(ctx) && XfInstallFix.available(ctx);
		try {
			a.createDialogBuilder()
					.setTitle(R.string.xf_aa_check)
					.setMessage(text)
					.setNegativeButton(fix ? R.string.xf_fix_now : R.string.xf_close,
							fix ? (d, w) -> XfInstallFix.open(ctx) : null)
					.setNeutralButton(R.string.xf_share, (d, w) -> share(ctx, text))
					.setPositiveButton(android.R.string.copy, (d, w) -> copy(ctx, text))
					.show();
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to show the install report");
		}
	}

	private static void copy(Context ctx, String text) {
		try {
			ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
			if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("xFermaTube", text));
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to copy the install report");
		}
	}

	private static void share(Context ctx, String text) {
		try {
			Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
					.putExtra(Intent.EXTRA_TEXT, text);
			ctx.startActivity(Intent.createChooser(send, ctx.getText(R.string.xf_aa_check))
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to share the install report");
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
		// AAenabler), Installer = com.android.vending, Initiated by = sisteminis diegiklis.
		if (PLAY_STORE.equals(installer)) {
			b.append("\nRESULT: the install source is Google Play Store, as on a phone where the " +
					"video app shows up. Look for the video app on the car screen: the Customize " +
					"launcher list on the phone may not show it while no car is connected. If the car " +
					"screen still shows only the audio entry and you used Fix now, uninstall and install " +
					"through AAenabler: on some cars the fix is not enough.\n");
		} else {
			b.append("\nRESULT: the installer is not Google Play Store. On a phone where the video " +
					"app shows up it is com.android.vending, so this is the most likely reason why " +
					"Android Auto hides the video app.\n" +
					(XfInstallFix.available(ctx) ?
							"1. Tap Fix now (experimental, not enough on some cars): the app reinstalls itself " +
									"through the phone's Wireless debugging.\n" +
									"2. Reliable way: uninstall, then install through AAenabler: open AAenabler and pick " +
									"the APK from inside it. Run this check again.\n" :
							"1. Uninstall, then install through AAenabler: open AAenabler and pick the APK " +
									"from inside it. Run this check again.\n" +
									"2. Still not Google Play Store? Then the phone's own installer ignores " +
									"the request.\n") +
					"3. Or install from a PC:\n" +
					"   adb install -r -i com.android.vending xFermaTube.apk\n");
		}
		b.append("\nNot readable by the app, check by hand: Android Auto settings > Developer " +
				"settings > Unknown sources must be ticked.\n");
		return b.toString();
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
