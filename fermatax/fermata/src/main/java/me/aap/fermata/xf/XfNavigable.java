package me.aap.fermata.xf;

/**
 * xFermata: fragmentas, kurį galima valdyti iš automobilio šoninės juostos (Home / Atgal / Perkrauti).
 * Įgyvendina YouTube fragmentas (web modulyje), juosta jį pasiekia per šią sąsają.
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
}
