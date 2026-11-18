package me.aap.fermata.xf;

import android.content.Context;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.pref.PreferenceSet;

/** xFermaTube: the YouTube addon puts its sound and picture sync rows at the top of Settings. */
public interface XfSyncSettings {
	void xfContributeSyncSettings(Context ctx, PreferenceSet set, MainActivityDelegate a);
}
