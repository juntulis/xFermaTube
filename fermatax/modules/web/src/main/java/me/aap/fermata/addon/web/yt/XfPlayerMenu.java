package me.aap.fermata.addon.web.yt;

import android.content.Context;

import me.aap.fermata.addon.web.R;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;

/**
 * xFermaTube: YouTube grotuvo nustatymai programos ⋮ meniu (subtitrai, garso takelis).
 *
 * <p>Viso ekrano režime paties YouTube nustatymų meniu nepasiekiamas, todėl sąrašai imami per
 * grotuvo API (assets/xf-video-delay.js: ccList / ccSet / auList / auSet). Sąrašo eilutės pirmas
 * simbolis '*' reiškia pasirinktą, eilutė '>' – siūlomą subtitrų vertimą.</p>
 */
final class XfPlayerMenu {
	private static final int CC_OFF = -1;
	private static final int CC_TRANSLATE = -2;

	/** Meniu punkto duomenys: kuris sąrašas ir kuri jo eilutė. */
	record Choice(boolean captions, int index) {
	}

	static FutureSupplier<Void> populateCaptions(YoutubeWebView web, OverlayMenu.Builder menu,
			OverlayMenu.SelectionHandler handler, Context ctx) {
		menu.setSelectionHandler(handler);
		return web.xfCaptionTracks().timeout(2500).main()
				.onFailure(err -> Log.e(err, "Failed to load caption tracks"))
				.map(list -> {
					String[] lines = split(list);
					if (lines.length == 0) {
						menu.addItem(UiUtils.getArrayItemId(0), null,
										ctx.getString(R.string.xf_subtitles_none))
								.setData(new Choice(true, CC_OFF));
						return null;
					}

					boolean any = false;
					for (String line : lines) any |= selected(line);
					menu.addItem(UiUtils.getArrayItemId(0), null, ctx.getString(R.string.xf_subtitles_off))
							.setChecked(!any, true).setData(new Choice(true, CC_OFF));

					// Vertimai (jų būna 1–2) iškart po „Off“, tada takeliai: sąrašas gali būti ilgas.
					int id = 0, translation = 0, track = 0;
					for (String line : lines) {
						if (!line.startsWith(">")) continue;
						menu.addItem(UiUtils.getArrayItemId(++id), null,
										ctx.getString(R.string.xf_subtitles_translate, label(line)))
								.setChecked(selected(line), true)
								.setData(new Choice(true, CC_TRANSLATE - translation++));
					}
					for (String line : lines) {
						if (line.startsWith(">")) continue;
						menu.addItem(UiUtils.getArrayItemId(++id), null, label(line))
								.setChecked(selected(line), true).setData(new Choice(true, track++));
					}
					return null;
				});
	}

	static FutureSupplier<Void> populateAudio(YoutubeWebView web, OverlayMenu.Builder menu,
			OverlayMenu.SelectionHandler handler) {
		menu.setSelectionHandler(handler);
		return web.xfAudioTracks().timeout(2500).main()
				.onFailure(err -> Log.e(err, "Failed to load audio tracks"))
				.map(list -> {
					String[] lines = split(list);
					if (lines.length == 0) {
						menu.addItem(me.aap.fermata.R.id.auto, null, me.aap.fermata.R.string.auto)
								.setChecked(true, true);
						return null;
					}
					for (int i = 0; i < lines.length; i++) {
						menu.addItem(UiUtils.getArrayItemId(i), null, label(lines[i]))
								.setChecked(selected(lines[i]), true).setData(new Choice(false, i));
					}
					return null;
				});
	}

	static boolean select(YoutubeWebView web, OverlayMenuItem item) {
		if (!(item.getData() instanceof Choice choice)) return false;
		if (choice.captions()) web.xfSetCaptionTrack(choice.index());
		else web.xfSetAudioTrack(choice.index());
		return true;
	}

	private static String[] split(String list) {
		return ((list == null) || list.isEmpty()) ? new String[0] : list.split("\n");
	}

	/** '*' yra pirmas simbolis, o vertimo eilutėje ('>') – antras. */
	private static boolean selected(String line) {
		int at = line.startsWith(">") ? 1 : 0;
		return (line.length() > at) && (line.charAt(at) == '*');
	}

	private static String label(String line) {
		int from = line.startsWith(">") ? 2 : 1;
		return (line.length() > from) ? line.substring(from) : "?";
	}

	private XfPlayerMenu() {
	}
}
