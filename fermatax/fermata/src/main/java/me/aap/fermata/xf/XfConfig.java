package me.aap.fermata.xf;

import me.aap.fermata.ui.activity.MainActivityDelegate;

/** xFermata: YouTube-only versijos jungikliai. */
public final class XfConfig {
	/** true = programa atsidaro tiesiai į YouTube, kiti meniu punktai paslėpti. */
	public static final boolean YOUTUBE_ONLY = true;
	/** xFermaTube leidimo numeris (GitHub release); FermataX versionName / versionCode nekeičiami. */
	public static final String RELEASE = "1.0.3-beta.2";
	public static final String YOUTUBE_ADDON_CLASS = "me.aap.fermata.addon.web.yt.YoutubeAddon";

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
}
