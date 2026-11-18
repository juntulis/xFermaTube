package me.aap.fermata.xf;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.LayoutInflater;

import androidx.annotation.Nullable;

/**
 * Gives the car screen's views the car screen's configuration.
 * <p>
 * The newer Android Auto hands the app a layout inflater whose context reports the phone's screen
 * (its density, portrait, its width) instead of the car's, and switches to the car's values some
 * time later. Views inflated with it were 2.6 times too big (buttons, missing sync badge) and
 * picked the portrait layout, which then collapsed to an empty grey screen. This context wraps the
 * host's one and answers with the car activity's resources, which always describe the car screen.
 * <p>
 * It is used even when the two agree at the moment the car screen is created: when the car screen
 * is created again in a living process (the car reconnects), the host first reports the car's
 * screen and switches to the phone's later, and the content collapsed to zero width. With the older
 * Android Auto the two agree all the time, so the wrapper changes nothing there.
 */
public final class XfCarContext extends ContextWrapper {
	private final Resources res;
	@Nullable
	private Resources.Theme theme;
	@Nullable
	private LayoutInflater inflater;

	private XfCarContext(Context host, Resources car) {
		super(host);
		res = car;
	}

	/**
	 * Returns a copy of the host's inflater that inflates with the car activity's resources (the
	 * host's inflater itself only if its context already uses them, or if the copy can't be made).
	 */
	public static LayoutInflater inflater(LayoutInflater host, Context carActivity) {
		try {
			Context hc = host.getContext();
			if (hc instanceof XfCarContext) return host;
			Resources car = carActivity.getResources();
			Resources hr = hc.getResources();
			if (hr == car) return host;
			XfCarContext ctx = new XfCarContext(hc, car);
			ctx.getTheme(); // Now, so that a failure falls back to the host's inflater
			LayoutInflater li = host.cloneInContext(ctx);
			ctx.inflater = li;
			Configuration h = hr.getConfiguration();
			Configuration c = car.getConfiguration();
			android.util.Log.i("xfCar", "car layout: host says " + h.densityDpi + "dpi w" +
					h.screenWidthDp + " orient " + h.orientation + ", car is " + c.densityDpi + "dpi w" +
					c.screenWidthDp + " orient " + c.orientation + ": using the car's");
			return li;
		} catch (Throwable ex) {
			android.util.Log.w("xfCar", "car layout: wrapper failed: " + ex);
			return host;
		}
	}

	@Override
	public Resources getResources() {
		return res;
	}

	@Override
	public AssetManager getAssets() {
		return res.getAssets();
	}

	@Override
	public Resources.Theme getTheme() {
		Resources.Theme t = theme;
		if (t == null) {
			// A theme resolves sizes with the resources it was made from: copy the host's styles
			t = res.newTheme();
			Resources.Theme ht = getBaseContext().getTheme();
			if (ht != null) t.setTo(ht);
			theme = t;
		}
		return t;
	}

	@Override
	public Object getSystemService(String name) {
		if (LAYOUT_INFLATER_SERVICE.equals(name)) {
			LayoutInflater li = inflater;
			if (li != null) return li;
		}
		return super.getSystemService(name);
	}
}
