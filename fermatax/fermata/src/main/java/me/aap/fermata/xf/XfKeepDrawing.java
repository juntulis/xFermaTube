package me.aap.fermata.xf;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.log.Log;

/**
 * Keeps the car picture moving while the app is in the background on the phone.
 * <p>
 * The newer Android Auto shows the car screen in a window that the phone does not count as
 * visible. Samsung phones stop the animation frames of an app that has no visible window
 * ("stop animation in background states"), so once the xFermaTube screen on the phone goes to the
 * background, the car picture freezes while the sound goes on. A 1x1 transparent window that
 * ignores touches keeps the app visible for the system. It needs the "Display over other apps"
 * permission, which the user gives in the phone settings; without it nothing changes.
 */
public final class XfKeepDrawing {
	@Nullable
	private static View view;

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

	/** Called when the car screen starts: adds the invisible window once, if allowed. */
	public static void start(Context ctx) {
		if (view != null) return;
		Context app = ctx.getApplicationContext();
		if (!allowed(app)) return;
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
			android.util.Log.i("xfCar", "keep drawing: on");
		} catch (RuntimeException ex) {
			android.util.Log.w("xfCar", "keep drawing failed: " + ex);
		}
	}

	/** Called when the car screen is closed. */
	public static void stop(Context ctx) {
		View v = view;
		if (v == null) return;
		view = null;
		try {
			WindowManager wm = ctx.getApplicationContext().getSystemService(WindowManager.class);
			if (wm != null) wm.removeViewImmediate(v);
		} catch (RuntimeException ex) {
			android.util.Log.w("xfCar", "keep drawing remove failed: " + ex);
		}
	}

	/** One question at start on the phone, only where the picture is known to freeze. */
	public static boolean checkAtStartup(MainActivityDelegate a) {
		if (!XfConfig.YOUTUBE_ONLY || a.isCarActivity() || !needed()) return false;
		Context ctx = a.getContext();
		if (allowed(ctx) || a.getPrefs().getBooleanPref(MainActivityPrefs.XF_OVERLAY_ASKED)) return false;
		a.getHandler().post(() -> {
			try {
				a.createDialogBuilder()
						.setTitle(R.string.xf_overlay_title)
						.setMessage(R.string.xf_overlay_message)
						.setNegativeButton(R.string.xf_overlay_never, (d, w) ->
								a.getPrefs().applyBooleanPref(MainActivityPrefs.XF_OVERLAY_ASKED, true))
						.setNeutralButton(R.string.xf_overlay_later, null)
						.setPositiveButton(R.string.xf_overlay_open, (d, w) -> open(ctx))
						.show();
			} catch (RuntimeException ex) {
				Log.e(ex, "Failed to show the overlay dialog");
			}
		});
		return true;
	}
}
