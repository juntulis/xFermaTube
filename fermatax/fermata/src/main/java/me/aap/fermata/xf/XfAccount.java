package me.aap.fermata.xf;

import android.net.Uri;
import android.webkit.CookieManager;
import android.widget.Toast;

import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;

/**
 * xFermaTube: videos play only on the car screen. On the phone the YouTube page is opened only to
 * sign in; the cookies are shared with the car screen.
 */
public final class XfAccount {
	/** Google sign-in for YouTube; when already signed in, Google goes straight back to YouTube. */
	public static final String SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?service=youtube" +
			"&uilel=3&passive=true&continue=https%3A%2F%2Fm.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue" +
			"%26app%3Dm%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F";

	private XfAccount() {
	}

	public static boolean signedIn() {
		try {
			String c = CookieManager.getInstance().getCookie("https://www.youtube.com");
			return (c != null) && (c.contains("LOGIN_INFO=") || c.contains("SAPISID="));
		} catch (RuntimeException ex) {
			return false;
		}
	}

	public static void signIn(MainActivityDelegate a) {
		a.showFragment(R.id.youtube_fragment);
	}

	public static void signOut(MainActivityDelegate a) {
		a.createDialogBuilder()
				.setTitle(R.string.xf_account_sign_out)
				.setMessage(R.string.xf_account_sign_out_sub)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.xf_account_sign_out, (d, w) -> {
					try {
						CookieManager cm = CookieManager.getInstance();
						cm.removeAllCookies(ok -> {
							cm.flush();
							Toast.makeText(a.getContext(), R.string.xf_account_signed_out_done,
									Toast.LENGTH_SHORT).show();
							a.showPrimaryRoot();
						});
					} catch (RuntimeException ex) {
						Log.e(ex, "Failed to sign out");
					}
				}).show();
	}

	/**
	 * The phone YouTube page may show only the sign-in pages. Anything else on YouTube means the
	 * sign-in is over (or was not needed): the page is left and the settings are shown again.
	 */
	public static boolean isSignInPage(@Nullable String url) {
		if (url == null) return true;
		Uri u = Uri.parse(url);
		String host = u.getHost();
		if (host == null) return true;
		host = host.toLowerCase(java.util.Locale.ROOT);
		if (!host.equals("youtube.com") && !host.endsWith(".youtube.com")) return true;
		if (host.equals("accounts.youtube.com")) return true; // part of the Google sign-in
		String path = u.getPath();
		return (path != null) && path.startsWith("/signin");
	}

	public static void signInFinished(MainActivityDelegate a) {
		Toast.makeText(a.getContext(), signedIn() ? R.string.xf_account_signed_in_done :
				R.string.xf_account_signed_out, Toast.LENGTH_SHORT).show();
		a.showPrimaryRoot();
	}
}
