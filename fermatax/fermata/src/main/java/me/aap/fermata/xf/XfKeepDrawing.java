package me.aap.fermata.xf;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;

import me.aap.utils.log.Log;

/**
 * Keeps the car picture moving while the app is in the background on the phone.
 * <p>
 * The newer Android Auto shows the car screen in a window that the phone does not count as
 * visible. Samsung phones stop the animation frames of an app that has no visible window
 * ("stop animation in background states"): a few seconds after the xFermaTube screen on the phone
 * goes to the background or the phone is locked, the car picture freezes while the sound goes on.
 * <p>
 * Samsung's own rule is that an app receiving input is not in the background: posting an input
 * callback to the Choreographer resets its background state and schedules the next frame. While
 * the car screen is on, the app posts an empty one a few times a second. No permission is needed
 * and it works with the phone locked.
 * <p>
 * The 1x1 transparent window of v1.0.4-beta.1 ("Display over other apps") stays as an optional
 * extra for those who allowed it; it cannot help with the phone locked.
 */
public final class XfKeepDrawing {
	private static final int TICK_MS = 250;
	private static final int CALLBACK_INPUT = 0; // Choreographer.CALLBACK_INPUT
	private static final Handler main = new Handler(Looper.getMainLooper());
	private static final Runnable noop = () -> {
	};
	@Nullable
	private static View view;
	@Nullable
	private static Method postCallback;
	private static boolean ticking;
	private static boolean postFailed;
	private static final Runnable tick = new Runnable() {
		@Override
		public void run() {
			if (!ticking) return;
			if (!postInputCallback()) {
				ticking = false;
				return;
			}
			main.postDelayed(this, TICK_MS);
		}
	};

	private XfKeepDrawing() {
	}

	/** The phones where the car picture is known to freeze without it. */
	public static boolean needed() {
		return "samsung".equalsIgnoreCase(Build.MANUFACTURER);
	}

	public static boolean allowed(Context ctx) {
		try {
			return Settings.canDrawOverlays(ctx);
		} catch (RuntimeException ex) {
			return false;
		}
	}

	/** For the install report: how the picture is kept moving on this phone. */
	public static String status(Context ctx) {
		if (!needed()) return "not needed";
		return (postFailed ? "frame keeper unavailable" : "frame keeper ok") + ", overlay " +
				(allowed(ctx) ? "allowed" : "not allowed");
	}

	/** Opens the "Display over other apps" setting of this app. */
	public static void open(Context ctx) {
		Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
				Uri.parse("package:" + ctx.getPackageName()));
		i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		try {
			ctx.startActivity(i);
		} catch (RuntimeException ex) {
			try {
				ctx.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
						.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
			} catch (RuntimeException ex2) {
				Log.e(ex2, "Failed to open the overlay setting");
			}
		}
	}

	/** Main thread only: the Choreographer belongs to the thread that asks for it. */
	private static boolean postInputCallback() {
		if (postFailed) return false;
		try {
			Method m = postCallback;
			if (m == null) {
				m = Choreographer.class.getDeclaredMethod("postCallback", int.class, Runnable.class,
						Object.class);
				m.setAccessible(true);
				postCallback = m;
			}
			m.invoke(Choreographer.getInstance(), CALLBACK_INPUT, noop, null);
			return true;
		} catch (Throwable ex) {
			postFailed = true;
			android.util.Log.w("xfCar", "keep drawing: input callback unavailable: " + ex);
			return false;
		}
	}

	/** Called when the car screen starts. */
	public static void start(Context ctx) {
		if (needed() && !ticking && !postFailed) {
			ticking = true;
			main.removeCallbacks(tick);
			main.post(tick);
			android.util.Log.i("xfCar", "keep drawing: frame keeper on");
		}
		if (view != null) return;
		Context app = ctx.getApplicationContext();
		if (!needed() || !allowed(app)) return;
		try {
			WindowManager wm = app.getSystemService(WindowManager.class);
			if (wm == null) return;
			WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
					WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
					WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
							WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
							WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
					PixelFormat.TRANSLUCENT);
			lp.gravity = Gravity.TOP | Gravity.START;
			lp.setTitle("xFermaTube keep drawing");
			View v = new View(app);
			// Fully transparent windows may be ignored by the system: one step above transparent
			v.setBackgroundColor(0x01000000);
			wm.addView(v, lp);
			view = v;
			android.util.Log.i("xfCar", "keep drawing: overlay on");
		} catch (RuntimeException ex) {
			android.util.Log.w("xfCar", "keep drawing: overlay failed: " + ex);
		}
	}

	/** Called when the car screen is closed. */
	public static void stop(Context ctx) {
		ticking = false;
		main.removeCallbacks(tick);
		View v = view;
		if (v == null) return;
		view = null;
		try {
			WindowManager wm = ctx.getApplicationContext().getSystemService(WindowManager.class);
			if (wm != null) wm.removeViewImmediate(v);
		} catch (RuntimeException ex) {
			android.util.Log.w("xfCar", "keep drawing: overlay remove failed: " + ex);
		}
	}
}
