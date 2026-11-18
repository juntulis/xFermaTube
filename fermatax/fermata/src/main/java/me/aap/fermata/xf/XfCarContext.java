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
 * With the older Android Auto the two already agree and nothing is wrapped.
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
	 * Returns the host's inflater, or a copy of it that inflates with the car activity's resources
	 * when the host's context describes another screen.
	 */
	public static LayoutInflater inflater(LayoutInflater host, Context carActivity) {
		try {
			Context hc = host.getContext();
			if (hc instanceof XfCarContext) return host;
			Resources car = carActivity.getResources();
			Configuration h = hc.getResources().getConfiguration();
			Configuration c = car.getConfiguration();
			if (sameScreen(h, c)) return host;
			XfCarContext ctx = new XfCarContext(hc, car);
			LayoutInflater li = host.cloneInContext(ctx);
			ctx.inflater = li;
			android.util.Log.i("xfCar", "car layout: host says " + h.densityDpi + "dpi w" +
					h.screenWidthDp + " orient " + h.orientation + ", car is " + c.densityDpi + "dpi w" +
					c.screenWidthDp + " orient " + c.orientation + ": using the car's");
			return li;
		} catch (Throwable ex) {
			android.util.Log.w("xfCar", "car layout: wrapper failed: " + ex);
			return host;
		}
	}

	private static boolean sameScreen(Configuration a, Configuration b) {
		if ((a.densityDpi != b.densityDpi) || (a.orientation != b.orientation)) return false;
		int w = Math.max(a.screenWidthDp, b.screenWidthDp);
		return Math.abs(a.screenWidthDp - b.screenWidthDp) * 10 <= w;
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
