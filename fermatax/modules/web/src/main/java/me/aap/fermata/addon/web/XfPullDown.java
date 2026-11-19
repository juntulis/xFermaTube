package me.aap.fermata.addon.web;

import android.view.MotionEvent;
import android.view.View;

import me.aap.utils.function.BooleanSupplier;

/**
 * xFermaTube: on the car screen a pull down from the top edge of a Web page brings the hidden
 * address bar back (the bar is hidden by its own button or while the page shows a video
 * fullscreen). The rest of a gesture that did it is not given to the page.
 */
final class XfPullDown {
	private static final float EDGE_DP = 40f;
	private static final float PULL_DP = 56f;
	private float downX;
	private float downY = -1f;
	private boolean done;

	/**
	 * @param action brings the bar back; false = the bar is already shown (the page keeps the touch)
	 * @return true when this touch belongs to a pull that brought the bar back
	 */
	boolean onTouch(View v, MotionEvent e, BooleanSupplier action) {
		float dp = v.getResources().getDisplayMetrics().density;

		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN -> {
				done = false;
				downX = e.getX();
				downY = (e.getY() <= EDGE_DP * dp) ? e.getY() : -1f;
				return false;
			}
			case MotionEvent.ACTION_MOVE -> {
				if (done) return true;
				if (downY < 0f) return false;
				float dy = e.getY() - downY;
				if ((dy < PULL_DP * dp) || (dy < 2f * Math.abs(e.getX() - downX))) return false;
				downY = -1f;
				done = action.getAsBoolean();
				me.aap.utils.log.Log.i("xfTabs: pull down, bar shown ", done);
				return done;
			}
			case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				boolean d = done;
				done = false;
				downY = -1f;
				return d;
			}
			default -> {
				return done;
			}
		}
	}
}
