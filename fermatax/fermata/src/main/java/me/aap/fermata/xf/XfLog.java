package me.aap.fermata.xf;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;

/**
 * xFermaTube: the app's own event log, kept in a file. The system log is gone by the time a drive
 * is over (it holds some 20 minutes and dies with the process), so what happened in the car could
 * not be looked up afterwards. Written here: car screen start and stop, the car picture losing
 * frames, A/V sync measurements and their outcome.
 * <p>
 * No personal data: no video titles, addresses or accounts. People paste this log into public
 * bug reports.
 */
public final class XfLog {
	private static final String AA_PKG = "com.google.android.projection.gearhead";
	private static final int MAX_BYTES = 60_000; // Cut to the newer half when reached; a whole report fits a GitHub comment
	private static final int MAX_LINE = 300;
	private static final int PAGE_LINES_PER_MIN = 120;
	private static final Object lock = new Object();
	private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "xf-log");
		t.setDaemon(true);
		return t;
	});
	private static boolean started;
	private static long pageWindow;
	private static int pageLines;

	private XfLog() {
	}

	/** To the system log and to the file. */
	public static void i(String tag, String msg) {
		android.util.Log.i(tag, msg);
		add(tag, msg);
	}

	/** To the system log and to the file. */
	public static void w(String tag, String msg) {
		android.util.Log.w(tag, msg);
		add(tag + " (!)", msg);
	}

	/**
	 * A line sent by the video page's script (A/V sync). Every frame of the page can reach the
	 * bridge, ad frames included, so the text is cleaned, cut and limited in rate.
	 */
	public static void fromPage(String msg) {
		if (msg == null) return;
		long now = System.currentTimeMillis();
		synchronized (lock) {
			if (now - pageWindow > 60000) {
				pageWindow = now;
				pageLines = 0;
			}
			if (++pageLines > PAGE_LINES_PER_MIN) return;
		}
		android.util.Log.i("xfSync", clean(msg));
		add("sync", msg);
	}

	public static void add(String tag, String msg) {
		try {
			String line = stamp(System.currentTimeMillis()) + ' ' + tag + ": " + clean(msg) + '\n';
			io.execute(() -> write(line));
		} catch (Throwable ignore) {
			// Logging must never break the app
		}
	}

	private static String clean(String s) {
		if (s == null) return "";
		StringBuilder b = null;
		int n = Math.min(s.length(), MAX_LINE);
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			// Control characters and the Unicode line breaks: one event is one line
			if ((c < ' ') || (c == 0x7f) || (c == 0x85) || (c == 0x2028) || (c == 0x2029)) {
				if (b == null) b = new StringBuilder(s.substring(0, n));
				b.setCharAt(i, ' ');
			}
		}
		String r = (b != null) ? b.toString() : ((n < s.length()) ? s.substring(0, n) : s);
		return (n < s.length()) ? r + "…" : r;
	}

	private static String stamp(long ms) {
		return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
	}

	private static File file(Context ctx) {
		return new File(new File(ctx.getNoBackupFilesDir(), "diagnostics"), "xf-log.txt");
	}

	private static void write(String line) {
		synchronized (lock) {
			try {
				File f = file(FermataApplication.get());
				File dir = f.getParentFile();
				if ((dir != null) && !dir.isDirectory()) dir.mkdirs();
				if (f.length() > MAX_BYTES) trim(f);
				String text = line;
				if (!started) {
					started = true;
					text = stamp(System.currentTimeMillis()) + " --- app started: xFermaTube " +
							XfConfig.RELEASE + '\n' + line;
				}
				Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
						StandardOpenOption.APPEND);
			} catch (Throwable ignore) {
			}
		}
	}

	/** Keeps the newer half, starting at a line start. */
	private static void trim(File f) throws java.io.IOException {
		byte[] keep;
		try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
			long len = r.length();
			keep = new byte[(int) Math.min(len, MAX_BYTES / 2)];
			r.seek(len - keep.length);
			r.readFully(keep);
		}
		int from = 0;
		while ((from < keep.length) && (keep[from] != '\n')) from++;
		from = (from < keep.length) ? from + 1 : 0;
		byte[] out = new byte[keep.length - from];
		System.arraycopy(keep, from, out, 0, out.length);
		Files.write(f.toPath(), out);
	}

	/** The last lines of the log (all of it if maxLines is 0). */
	public static String read(Context ctx, int maxLines) {
		try {
			// Lines still waiting to be written
			io.submit(() -> {
			}).get(2, TimeUnit.SECONDS);
		} catch (Throwable ignore) {
		}
		synchronized (lock) {
			try {
				File f = file(ctx);
				if (!f.isFile()) return "";
				String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
				if (maxLines <= 0) return s;
				int pos = s.length() - 1; // The last character is a line end
				for (int i = 0; (i < maxLines) && (pos > 0); i++) pos = s.lastIndexOf('\n', pos - 1);
				return (pos <= 0) ? s : s.substring(pos + 1);
			} catch (Throwable ex) {
				return "unreadable (" + ex.getClass().getSimpleName() + ")\n";
			}
		}
	}

	public static void clear(Context ctx) {
		io.execute(() -> {
			synchronized (lock) {
				try {
					file(ctx).delete();
					started = false;
				} catch (Throwable ignore) {
				}
			}
		});
	}

	/** What is copied and shared: a few lines about the app and the phone, then the log. */
	public static String report(Context ctx) {
		StringBuilder b = new StringBuilder(4096);
		b.append("xFermaTube ").append(XfConfig.RELEASE).append(" (").append(BuildConfig.VERSION_NAME)
				.append(" / ").append(BuildConfig.VERSION_CODE).append(")\n");
		b.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
				.append(", Android ").append(Build.VERSION.RELEASE).append(" (API ")
				.append(Build.VERSION.SDK_INT).append(")\n");
		b.append("Android Auto: ");
		try {
			b.append(ctx.getPackageManager().getPackageInfo(AA_PKG, 0).versionName);
		} catch (PackageManager.NameNotFoundException ex) {
			b.append("not installed or not visible");
		} catch (Exception ex) {
			b.append('?');
		}
		b.append("\nKeeping the car picture moving: ").append(XfKeepDrawing.status(ctx)).append('\n');
		String log = read(ctx, 0);
		b.append("\nApp log (newest at the end)\n").append(log.isEmpty() ? "(empty)\n" : log);
		return b.toString();
	}

	public static void show(MainActivityDelegate a) {
		Context ctx = a.getContext();
		String text = report(ctx);
		try {
			a.createDialogBuilder()
					.setTitle(R.string.xf_log)
					.setView(textView(ctx, text, R.string.xf_log, true))
					.setNegativeButton(R.string.xf_close, null)
					.setNeutralButton(R.string.xf_log_clear, (d, w) -> clear(ctx))
					.show();
		} catch (RuntimeException ex) {
			me.aap.utils.log.Log.e(ex, "Failed to show the app log");
		}
	}

	/**
	 * Copy and Share in a row that stays on top, the text below it in a box of a fixed height that
	 * scrolls on its own: the buttons are reachable without scrolling through a long text.
	 */
	static android.view.View textView(Context ctx, String text, int shareTitle, boolean showEnd) {
		android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
		float dp = dm.density;
		int pad = Math.round(20 * dp);
		LinearLayout root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(pad, Math.round(8 * dp), pad, 0);

		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		Button copyBtn = new Button(ctx);
		copyBtn.setText(android.R.string.copy);
		copyBtn.setOnClickListener(v -> {
			if (copy(ctx, text)) Toast.makeText(ctx, R.string.xf_copied, Toast.LENGTH_SHORT).show();
		});
		row.addView(copyBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		Button shareBtn = new Button(ctx);
		shareBtn.setText(R.string.xf_share);
		shareBtn.setOnClickListener(v -> share(ctx, text, shareTitle));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.setMarginStart(Math.round(8 * dp));
		row.addView(shareBtn, lp);
		root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		ScrollView scroll = new ScrollView(ctx);
		TextView tv = new TextView(ctx);
		tv.setText(text);
		tv.setTextIsSelectable(true);
		tv.setTextSize(12);
		tv.setTypeface(Typeface.MONOSPACE);
		scroll.addView(tv);
		// A fixed height, whatever the dialog does with its content: about half of the screen
		root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				Math.round(dm.heightPixels * 0.5f)));
		if (showEnd) scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
		return root;
	}

	static boolean copy(Context ctx, String text) {
		try {
			ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
			if (cm == null) return false;
			cm.setPrimaryClip(ClipData.newPlainText("xFermaTube", text));
			return true;
		} catch (RuntimeException ex) {
			me.aap.utils.log.Log.e(ex, "Failed to copy");
			return false;
		}
	}

	static void share(Context ctx, String text, int title) {
		try {
			String t = text;
			Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
					.putExtra(Intent.EXTRA_TEXT, t);
			ctx.startActivity(Intent.createChooser(send, ctx.getText(title))
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
		} catch (RuntimeException ex) {
			me.aap.utils.log.Log.e(ex, "Failed to share");
		}
	}
}
