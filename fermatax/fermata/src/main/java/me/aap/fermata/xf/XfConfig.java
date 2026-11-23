package me.aap.fermata.xf;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.fragment.ActivityFragment;

/** xFermata: YouTube-only versijos jungikliai. */
public final class XfConfig {
	/** true = programa atsidaro tiesiai į YouTube, kiti meniu punktai paslėpti. */
	public static final boolean YOUTUBE_ONLY = true;
	/** xFermaTube leidimo numeris (GitHub release); FermataX versionName / versionCode nekeičiami. */
	public static final String RELEASE = "1.1.1-beta.1";
	public static final String YOUTUBE_ADDON_CLASS = "me.aap.fermata.addon.web.yt.YoutubeAddon";
	/**
	 * Šoninėje juostoje programa „Web“ (FermataX naršyklė kitoms video svetainėms) su savo
	 * „Bookmarks“ ir su A/V sinchronizacija jos puslapiuose (XfWebSync). Viešinama nuo 1.0.4-beta.3
	 * (Justo sprendimas); false – paslepia „Web“.
	 */
	public static final boolean WEB_PROTO = true;

	private XfConfig() {
	}

	/**
	 * Automobilyje YouTube puslapis rodomas be Fermata viršutinės juostos: navigacijai užtenka
	 * šoninio meniu (Home = YouTube, atgal gestas). Telefone juosta lieka.
	 */
	public static boolean hideYoutubeTopBar(MainActivityDelegate a, int fragmentId) {
		return YOUTUBE_ONLY && (fragmentId == me.aap.fermata.R.id.youtube_fragment) &&
				a.isCarActivity();
	}

	/**
	 * The top bar is hidden on the car screen for YouTube and while a Web page shows its video
	 * fullscreen (the page's own fullscreen button; the side rail stays for Back).
	 */
	public static boolean hideTopBar(MainActivityDelegate a, ActivityFragment f) {
		return hideYoutubeTopBar(a, f.getFragmentId()) ||
				(a.isCarActivity() && (f instanceof XfNavigable n) && n.xfFullScreen());
	}
}
