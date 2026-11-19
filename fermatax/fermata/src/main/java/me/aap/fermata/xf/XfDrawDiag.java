package me.aap.fermata.xf;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * xFermaTube car screen diagnostics, for the install report and for adb.
 * <p>
 * While the car screen is on, it notices when the app's animation frames stop (the picture freezes
 * while the sound goes on) and when they come back, and writes that to the log with the tag
 * "xfDiag"; the install report shows those lines.
 * <p>
 * For adb: a file named xf-verbose.txt in the app's external files folder makes it log the frame
 * and draw counts every second; changing the file xf-dump.txt there logs the view tree with the
 * tag "xfDump".
 */
public final class XfDrawDiag {
	private static final Handler main = new Handler(Looper.getMainLooper());
	@Nullable
	private static View root;
	@Nullable
	private static Context app;
	private static boolean running;
	private static boolean verbose;
	private static boolean framePending;
	private static int frames, draws, ticks, stalled;
	private static long dumpSeen = -1;
	private static final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
		@Override
		public void doFrame(long t) {
			frames++;
			framePending = false;
			// Counting every frame keeps the display's frame clock running: only when asked to
			if (running && verbose) Choreographer.getInstance().postFrameCallback(this);
		}
	};
	private static final ViewTreeObserver.OnDrawListener drawCb = () -> draws++;
	private static final Runnable tick = new Runnable() {
		@Override
		public void run() {
			if (!running) return;
			check();
			checkDumpRequest();
			main.postDelayed(this, 1000);
		}
	};

	private XfDrawDiag() {
	}

	public static void start(Context ctx, @Nullable View decor) {
		stop();
		running = true;
		root = decor;
		frames = draws = ticks = stalled = 0;
		framePending = false;
		app = ctx.getApplicationContext();
		verbose = exists("xf-verbose.txt");
		try {
			if (decor != null) decor.getViewTreeObserver().addOnDrawListener(drawCb);
		} catch (RuntimeException ex) {
			XfLog.w("xfDiag", "draw listener: " + ex);
		}
		Choreographer.getInstance().postFrameCallback(frameCb);
		main.postDelayed(tick, 1000);
		XfLog.i("xfDiag", "car screen on" + (verbose ? " (verbose)" : "") + state());
	}

	public static void stop() {
		if (!running) return;
		running = false;
		main.removeCallbacks(tick);
		Choreographer.getInstance().removeFrameCallback(frameCb);
		View r = root;
		root = null;
		try {
			if (r != null) r.getViewTreeObserver().removeOnDrawListener(drawCb);
		} catch (RuntimeException ignore) {
		}
		XfLog.i("xfDiag", "car screen off");
	}

	/** One frame is asked for every second; a second without it means the frames are stopped. */
	private static void check() {
		ticks++;
		int f = frames, d = draws;
		frames = draws = 0;
		if (verbose) {
			android.util.Log.i("xfDiag", "t=" + ticks + " anim=" + f + " draw=" + d + state());
			return;
		}
		if (framePending) {
			stalled++;
			if ((stalled == 2) || ((stalled % 60) == 0)) {
				XfLog.w("xfDiag", "car picture: no frames for " + stalled + " s" + state());
			}
		} else {
			if (stalled >= 2) {
				XfLog.w("xfDiag", "car picture: frames are back after " + stalled + " s" + state());
			}
			stalled = 0;
			framePending = true;
			Choreographer.getInstance().postFrameCallback(frameCb);
		}
	}

	private static String state() {
		StringBuilder sb = new StringBuilder(96);
		Context ctx = app;
		if (ctx != null) {
			try {
				PowerManager pm = ctx.getSystemService(PowerManager.class);
				KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
				sb.append(" screen=").append((pm != null) && pm.isInteractive());
				sb.append(" locked=").append((km != null) && km.isKeyguardLocked());
			} catch (RuntimeException ignore) {
			}
		}
		View r = root;
		if (r != null) {
			try {
				android.view.Display d = r.getDisplay();
				sb.append(" disp=").append(d == null ? "null" : d.getDisplayId() + "/" + d.getState());
			} catch (RuntimeException ignore) {
			}
			sb.append(" attached=").append(r.isAttachedToWindow()).append(" shown=").append(r.isShown())
					.append(" size=").append(r.getWidth()).append('x').append(r.getHeight());
		}
		return sb.toString();
	}

	private static boolean exists(String name) {
		Context ctx = app;
		if (ctx == null) return false;
		try {
			return new File(ctx.getExternalFilesDir(null), name).isFile();
		} catch (Throwable ex) {
			return false;
		}
	}

	/** Dumps when adb touches files/xf-dump.txt. */
	private static void checkDumpRequest() {
		Context ctx = app;
		if (ctx == null) return;
		try {
			File f = new File(ctx.getExternalFilesDir(null), "xf-dump.txt");
			long m = f.isFile() ? f.lastModified() : 0;
			if (dumpSeen == -1) dumpSeen = m;
			if (m != dumpSeen) {
				dumpSeen = m;
				dump("request");
			}
		} catch (Throwable ignore) {
		}
	}

	/** Logs the car screen's view tree: what is shown, where, and in which colour. */
	public static void dump(String why) {
		View r = root;
		if (r == null) return;
		try {
			android.util.Log.i("xfDump", "=== " + why);
			View body = r.findViewById(me.aap.fermata.R.id.body_layout);
			if (body != null) chain("xfDump", "body", body.getContext());
			chain("xfDump", "root", r.getContext());
			int[] n = {0};
			dump(r, 0, n);
			android.util.Log.i("xfDump", "=== end, views=" + n[0]);
		} catch (Throwable ex) {
			android.util.Log.w("xfDump", "failed: " + ex);
		}
	}

	/** Logs a context and the contexts it wraps, with the screen each one describes. */
	public static void chain(String tag, String what, @Nullable Context c) {
		for (int i = 0; (c != null) && (i < 8); i++) {
			try {
				Resources r = c.getResources();
				Configuration cf = r.getConfiguration();
				android.util.Log.i(tag, "ctx " + what + "[" + i + "] " + c.getClass().getName() +
						" density=" + r.getDisplayMetrics().density + " dpi=" + cf.densityDpi + " orient=" +
						cf.orientation + " w=" + cf.screenWidthDp + " h=" + cf.screenHeightDp + " uiMode=" +
						cf.uiMode);
			} catch (Throwable ex) {
				android.util.Log.i(tag, "ctx " + what + "[" + i + "] " + c.getClass().getName() + ": " + ex);
			}
			c = (c instanceof android.content.ContextWrapper w) ? w.getBaseContext() : null;
		}
	}

	private static void dump(View v, int depth, int[] n) {
		if (n[0]++ > 600) return;
		StringBuilder sb = new StringBuilder(200);
		for (int i = 0; i < depth; i++) sb.append(' ');
		sb.append(v.getClass().getSimpleName());
		if (v.getId() != View.NO_ID) {
			try {
				sb.append('#').append(v.getResources().getResourceEntryName(v.getId()));
			} catch (RuntimeException ignore) {
				sb.append('#').append(Integer.toHexString(v.getId()));
			}
		}
		int vis = v.getVisibility();
		sb.append(vis == View.VISIBLE ? " V" : (vis == View.INVISIBLE ? " I" : " G"));
		sb.append(" a=").append(v.getAlpha());
		sb.append(" [").append(v.getLeft()).append(',').append(v.getTop()).append(' ')
				.append(v.getWidth()).append('x').append(v.getHeight()).append(']');
		if (v instanceof android.widget.TextView t) {
			sb.append(" text=").append(t.getTextSize()).append(" len=").append(t.length());
		}
		android.util.Log.i("xfDump", sb.toString());
		if ((vis != View.VISIBLE) && (depth > 12)) return;
		if ((v instanceof android.view.ViewGroup g) && !(v instanceof android.webkit.WebView)) {
			for (int i = 0, c = g.getChildCount(); i < c; i++) dump(g.getChildAt(i), depth + 1, n);
		}
	}
}
