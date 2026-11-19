package me.aap.fermata.xf;

/**
 * xFermata: a fragment the side rail of the car screen can drive (home / back / refresh and the
 * program's own action). Implemented by the Web browser fragment and, with its own behaviour, by
 * the YouTube fragment (both in the web module); the rail reaches them through this interface.
 */
public interface XfNavigable {

	/** Atidaro pradžios puslapį (YouTube pagrindinį). */
	void xfHome();

	/** Vienu žingsniu atgal. false – nėra kur grįžti (programa neuždaroma). */
	boolean xfBack();

	/** Perkrauna puslapį. */
	void xfRefresh();

	/** Atidaro žiūrėjimo istoriją (paskutiniai video). */
	void xfHistory();

	/** Opens the bookmarks menu (Web). */
	default void xfBookmarks() {
	}

	/** A page's video is shown fullscreen (Web): the car screen hides the address bar then. */
	default boolean xfFullScreen() {
		return false;
	}
}
