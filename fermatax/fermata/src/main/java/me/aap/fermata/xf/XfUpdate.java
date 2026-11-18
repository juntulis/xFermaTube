package me.aap.fermata.xf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.log.Log;

/**
 * xFermaTube: update check. When the app is opened on the phone (at most once a day) it asks GitHub
 * for the latest release. If it is newer than this one, it offers to download it and hands the APK to
 * the phone's own installer with Play Store named as the installer, the same way "Fix now" does, so
 * Android Auto keeps showing the video app after the update. Only the release marked Latest is
 * offered, never a pre-release. Nothing is sent to GitHub except the request itself.
 */
public final class XfUpdate {
	private static final String API = "https://api.github.com/repos/juntulis/xFermaTube/releases/latest";
	private static final String MIME = "application/vnd.android.package-archive";
	private static final String PREFS = "xf_update";
	private static final String LAST_CHECK = "lastCheck";
	private static final String SKIP_TAG = "skipTag";
	private static final String FILE_TAG = "fileTag";
	private static final long INTERVAL_MS = 20 * 60 * 60 * 1000L;
	private static final long MAX_SIZE = 200L * 1024 * 1024;
	private static volatile boolean running;

	private XfUpdate() {
	}

	/** The latest release on GitHub. */
	record Release(String tag, String apkUrl, long size, String sha256) {
	}

	/** The downloaded update, served to the installer by XfApkProvider as "update.apk". */
	public static File file(Context ctx) {
		return new File(new File(ctx.getCacheDir(), "xf-update"), "update.apk");
	}

	private static SharedPreferences prefs(Context ctx) {
		return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	/** Called when the app is opened on the phone. Checks in the background; shows an offer if needed. */
	public static void checkAtStartup(MainActivityDelegate a) {
		if (!XfConfig.YOUTUBE_ONLY || a.isCarActivity() || running) return;
		Context ctx = a.getContext().getApplicationContext();
		dropOldFile(ctx);
		if (!a.getPrefs().getBooleanPref(MainActivityPrefs.XF_UPDATE_CHECK)) return;
		SharedPreferences sp = prefs(ctx);
		long now = System.currentTimeMillis();
		long last = sp.getLong(LAST_CHECK, 0);
		if ((last != 0) && (now - last >= 0) && (now - last < INTERVAL_MS)) return;
		run(a, false);
	}

	/** Settings → Check for updates: ignores the daily limit and "Skip this version". */
	public static void checkNow(MainActivityDelegate a) {
		if (running) return;
		run(a, true);
	}

	private static void run(MainActivityDelegate a, boolean manual) {
		running = true;
		Context ctx = a.getContext().getApplicationContext();
		if (manual) Toast.makeText(ctx, R.string.xf_update_checking, Toast.LENGTH_SHORT).show();
		new Thread(() -> {
			Release r = null;
			String error = null;
			try {
				r = fetch();
			} catch (Exception ex) {
				Log.e(ex, "Update check failed");
				error = ex.getClass().getSimpleName();
			}
			Release rel = r;
			String err = error;
			a.getHandler().post(() -> {
				running = false;
				if (gone(a) || a.isCarActivity()) return;
				// Counted as done only when the result could be shown: a closed screen tries again next start
				if (rel != null) prefs(ctx).edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply();
				if (rel == null) {
					if (manual) toast(ctx, ctx.getString(R.string.xf_update_failed, err));
					return;
				}
				boolean newer = compare(rel.tag(), XfConfig.RELEASE) > 0;
				if (!newer) {
					if (manual) toast(ctx, ctx.getString(R.string.xf_update_none, XfConfig.RELEASE));
					return;
				}
				if (!manual && rel.tag().equals(prefs(ctx).getString(SKIP_TAG, null))) return;
				if (rel.apkUrl() == null) {
					if (manual) toast(ctx, ctx.getString(R.string.xf_update_failed, "no APK"));
					return;
				}
				offer(a, rel);
			});
		}, "XfUpdateCheck").start();
	}

	private static boolean gone(MainActivityDelegate a) {
		var act = a.getAppActivity();
		return (act == null) || act.isFinishing() || act.isDestroyed();
	}

	private static void toast(Context ctx, String text) {
		Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
	}

	private static void offer(MainActivityDelegate a, Release rel) {
		Context ctx = a.getContext();
		try {
			a.createDialogBuilder()
					.setTitle(ctx.getString(R.string.xf_update_title, version(rel.tag())))
					.setMessage(ctx.getString(R.string.xf_update_message, XfConfig.RELEASE))
					.setPositiveButton(R.string.xf_update_now, (d, w) -> download(a, rel))
					.setNegativeButton(R.string.xf_mic_later, null)
					.setNeutralButton(R.string.xf_update_skip, (d, w) ->
							prefs(ctx).edit().putString(SKIP_TAG, rel.tag()).apply())
					.show();
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to show the update dialog");
		}
	}

	private static void download(MainActivityDelegate a, Release rel) {
		Context ctx = a.getContext().getApplicationContext();
		File f = file(ctx);
		if (running) return;
		running = true;
		new Thread(() -> {
			String error = null;
			try {
				boolean have = rel.tag().equals(prefs(ctx).getString(FILE_TAG, null)) && f.isFile() &&
						matches(f, rel);
				if (!have) {
					a.getHandler().post(() -> toast(ctx, ctx.getString(R.string.xf_update_downloading)));
					fetchApk(ctx, rel, f);
					prefs(ctx).edit().putString(FILE_TAG, rel.tag()).apply();
				}
			} catch (Exception ex) {
				Log.e(ex, "Update download failed");
				error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
			}
			String err = error;
			a.getHandler().post(() -> {
				running = false;
				if (err != null) toast(ctx, ctx.getString(R.string.xf_update_failed, err));
				else if (!gone(a)) install(a);
			});
		}, "XfUpdateDownload").start();
	}

	/**
	 * Hands the downloaded APK to the phone's own installer with Play Store named as the installer,
	 * like "Fix now". The installer asks the user to confirm; on success the app is replaced.
	 */
	private static void install(MainActivityDelegate a) {
		Context ctx = a.getContext();
		PackageManager pm = ctx.getPackageManager();
		if (!pm.canRequestPackageInstalls()) {
			try {
				a.createDialogBuilder().setTitle(R.string.xf_update_allow_title)
						.setMessage(R.string.xf_update_allow)
						.setPositiveButton(R.string.xf_update_open_setting, (d, w) -> {
							try {
								a.getAppActivity().startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
										Uri.parse("package:" + ctx.getPackageName())));
							} catch (RuntimeException ex) {
								Log.e(ex, "Failed to open the install setting");
							}
						})
						.setNegativeButton(R.string.xf_close, null).show();
			} catch (RuntimeException ex) {
				Log.e(ex, "Failed to show the install permission dialog");
			}
			return;
		}
		try {
			Uri uri = new Uri.Builder().scheme("content").authority(ctx.getPackageName() + ".xfapk")
					.path("update.apk").build();
			Intent in = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, MIME)
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
					.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, XfInstallFix.PLAY_STORE)
					.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
			// Only the phone's own installer gets the file: another installer app would lose the Play Store
			// mark, and an unknown app must not be handed the APK
			ComponentName system = systemInstaller(pm, in);
			if (system == null) throw new IllegalStateException("No system package installer");
			in.setComponent(system);
			a.getAppActivity().startActivity(in);
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to start the update install");
			toast(ctx, ctx.getString(R.string.xf_update_failed, ex.getClass().getSimpleName()));
		}
	}

	private static ComponentName systemInstaller(PackageManager pm, Intent in) {
		try {
			List<ResolveInfo> list = pm.queryIntentActivities(in, PackageManager.MATCH_DEFAULT_ONLY);
			for (ResolveInfo ri : list) {
				if ((ri.activityInfo != null) && (ri.activityInfo.applicationInfo != null) &&
						((ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0)) {
					return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
				}
			}
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to look for package installers");
		}
		return null;
	}

	/** Removes a downloaded update that is already installed (or older). */
	private static void dropOldFile(Context ctx) {
		try {
			SharedPreferences sp = prefs(ctx);
			String tag = sp.getString(FILE_TAG, null);
			File f = file(ctx);
			if ((tag != null) && (compare(tag, XfConfig.RELEASE) <= 0)) {
				//noinspection ResultOfMethodCallIgnored
				f.delete();
				sp.edit().remove(FILE_TAG).apply();
			}
		} catch (RuntimeException ex) {
			Log.e(ex, "Failed to clean up the update file");
		}
	}

	static Release fetch() throws IOException, org.json.JSONException {
		HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
		try {
			c.setConnectTimeout(10000);
			c.setReadTimeout(15000);
			c.setRequestProperty("Accept", "application/vnd.github+json");
			c.setRequestProperty("User-Agent", "xFermaTube-update-check");
			int code = c.getResponseCode();
			if (code != 200) throw new IOException("HTTP " + code);
			String body;
			try (InputStream in = c.getInputStream()) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				for (int n; (n = in.read(buf)) > 0; ) {
					out.write(buf, 0, n);
					if (out.size() > 2 * 1024 * 1024) throw new IOException("Response too large");
				}
				body = out.toString("UTF-8");
			}
			return parse(body);
		} finally {
			c.disconnect();
		}
	}

	static Release parse(String body) throws org.json.JSONException {
		JSONObject o = new JSONObject(body);
		String tag = o.getString("tag_name");
		String url = null;
		long size = 0;
		String sha = null;
		JSONArray assets = o.optJSONArray("assets");
		if (assets != null) {
			for (int i = 0; i < assets.length(); i++) {
				JSONObject as = assets.getJSONObject(i);
				String name = as.optString("name", "");
				if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
				url = as.optString("browser_download_url", null);
				size = as.optLong("size", 0);
				String digest = as.optString("digest", "");
				if (digest.startsWith("sha256:")) sha = digest.substring(7).toLowerCase(Locale.ROOT);
				break;
			}
		}
		if ((url != null) && !url.startsWith("https://github.com/juntulis/xFermaTube/releases/download/")) url = null;
		return new Release(tag, url, size, sha);
	}

	private static void fetchApk(Context ctx, Release rel, File f) throws Exception {
		File dir = f.getParentFile();
		if ((dir != null) && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("No cache dir");
		File tmp = new File(dir, "update.tmp");
		try {
			fetchApk(ctx, rel, f, tmp);
		} finally {
			// A failed download leaves nothing behind; a good earlier update.apk is kept
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
		}
	}

	private static void fetchApk(Context ctx, Release rel, File f, File tmp) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(rel.apkUrl()).openConnection();
		try {
			c.setConnectTimeout(15000);
			c.setReadTimeout(30000);
			c.setInstanceFollowRedirects(true);
			c.setRequestProperty("User-Agent", "xFermaTube-update-check");
			int code = c.getResponseCode();
			if (code != 200) throw new IOException("HTTP " + code);
			long total = 0;
			try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
				byte[] buf = new byte[65536];
				for (int n; (n = in.read(buf)) > 0; ) {
					total += n;
					if (total > MAX_SIZE) throw new IOException("File too large");
					out.write(buf, 0, n);
				}
			}
		} finally {
			c.disconnect();
		}
		if (!matches(tmp, rel)) {
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
			throw new IOException("Checksum or size mismatch");
		}
		PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(tmp.getPath(), 0);
		if ((pi == null) || !ctx.getPackageName().equals(pi.packageName)) {
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
			throw new IOException("Not an xFermaTube APK");
		}
		//noinspection ResultOfMethodCallIgnored
		f.delete();
		if (!tmp.renameTo(f)) throw new IOException("Rename failed");
	}

	private static boolean matches(File f, Release rel) {
		if ((rel.size() > 0) && (f.length() != rel.size())) return false;
		if (rel.sha256() == null) return true;
		try (InputStream in = new java.io.FileInputStream(f)) {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[65536];
			for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
			return sb.toString().equals(rel.sha256());
		} catch (Exception ex) {
			Log.e(ex, "Failed to check the update file");
			return false;
		}
	}

	private static String version(String tag) {
		String v = tag.startsWith("v") ? tag.substring(1) : tag;
		int plus = v.indexOf('+');
		return (plus < 0) ? v : v.substring(0, plus);
	}

	/**
	 * Compares release names like "v1.0.4", "1.0.3" or "1.0.3-beta.4". Numbers are compared one by one;
	 * with the same numbers a release is newer than its beta.
	 */
	static int compare(String a, String b) {
		String[] pa = version(a).split("-", 2);
		String[] pb = version(b).split("-", 2);
		String[] na = pa[0].split("\\.");
		String[] nb = pb[0].split("\\.");
		for (int i = 0; i < Math.max(na.length, nb.length); i++) {
			int x = (i < na.length) ? num(na[i]) : 0;
			int y = (i < nb.length) ? num(nb[i]) : 0;
			if (x != y) return Integer.compare(x, y);
		}
		boolean preA = pa.length > 1;
		boolean preB = pb.length > 1;
		if (preA != preB) return preA ? -1 : 1;
		if (!preA) return 0;
		return compare(pa[1].replaceAll("[^0-9.]", "").replaceAll("^\\.+", ""),
				pb[1].replaceAll("[^0-9.]", "").replaceAll("^\\.+", ""));
	}

	private static int num(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException ex) {
			return 0;
		}
	}
}
