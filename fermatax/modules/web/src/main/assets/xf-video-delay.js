// xFermata: YouTube vaizdo vėlinimas (A/V sync Android Auto).
// Originalus <video> groja kaip įprasta (garsas nesikeičia), o jo kadrai kaupiami eilėje
// ir piešiami ant <canvas> virš video po delayMs. Reikšmė saugoma localStorage.
// Idempotentiškas: galima įterpti kelis kartus. API: window.__xfDelay.set(ms) / .get() / .stats().
(function () {
  if (window.__xfDelay) { window.__xfDelay.attach(); return; }

  // Ryšys (nustato programa): 'usb' / 'wifi' – Android Auto, 'phone' – telefone; '' – naršyklėje.
  // Delsa, korekcija ir auto jungiklis saugomi atskirai kiekvienam ryšiui, kad auto susireguliuotų greičiau.
  var CONN = window.__xfConn || '';
  var SFX = CONN ? '_' + CONN : '';
  // Web naršyklė (ne YouTube): programa nustato __xfWeb ir __xfSeed {d, o} – paskutinę žinomą šio ryšio
  // delsą ir korekciją. Delsa priklauso nuo ryšio (automobilis, USB ar belaidis), ne nuo svetainės, o
  // localStorage kiekvienai svetainei atskiras, todėl naršyklėje pradinė reikšmė ateina iš programos.
  var WEB = !!window.__xfWeb;
  var SEED = (WEB && window.__xfSeed) || null;
  // Senos (bendros) reikšmės tinka tik AA ryšiams; telefone pradedam nuo 0.
  function stored(base) {
    if (SEED) {
      if (base === 'xf_video_delay_ms') return String(SEED.d || 0);
      if (base === 'xf_mic_offset_ms') return String(SEED.o || 0);
    }
    var v = localStorage.getItem(base + SFX);
    if (v == null && CONN !== 'phone') v = localStorage.getItem(base);
    return v;
  }
  var KEY = 'xf_video_delay_ms' + SFX;
  var MAX_FRAMES = 200;      // apsauga nuo atminties perpildymo (3 s esant 60 fps = 180)
  var MAX_W = 1280;          // kadrai ne platesni nei 1280 px
  var MAX_PENDING = 3;       // kiek createImageBitmap gali vykti vienu metu; daugiau = kadras praleidžiamas
  var GEOM_MS = 250;         // video vietos/dydžio tikrinimo intervalas (ne kiekviename kadre)
  var delayMs = parseInt(stored('xf_video_delay_ms') || '0', 10) || 0;

  var video = null, canvas = null, ctx = null, ui = null, label = null, diag = null;
  var queue = [];            // {bmp, t, mt}, t didėjantis
  var shown = null;          // paskutinis nupieštas kadras
  var capturing = false, vfcId = 0, rafId = 0, hideTimer = 0, redraw = false;
  var gen = 0;               // didinamas po flush: vėluojantys createImageBitmap rezultatai atmetami
  var pending = 0, lastT = 0, pendingAt = 0, pgen = 0, lastVfcAt = 0;
  var geom = null, geomKey = '', geomAt = 0, capW = MAX_W, opacity = '';
  // Diagnostika: per paskutinę sekundę
  var st = { cap: 0, drawn: 0, skip: 0, bmpMs: 0 }, stats = { capFps: 0, drawFps: 0, skip: 0, bmpMs: 0 }, statsAt = 0;

  function activeVideo() {
    if (testVid) return testVid;   // sinchronizacijos testo metu vėlinamas testo video
    if (WEB) return webVideo();
    return (window.__fermataActiveVideo && window.__fermataActiveVideo()) ||
      document.querySelector('video.html5-main-video') || document.querySelector('video');
  }

  // Svetainėje gali būti keli <video> (reklamos, peržiūros sąraše): imamas grojantis, su garsu ir didžiausias.
  // Dabartinis turi pirmenybę, kad pasirinkimas nešokinėtų.
  function webVideo() {
    var all = document.querySelectorAll('video'), best = null, bestScore = 0;
    for (var i = 0; i < all.length; i++) {
      var v = all[i], r = v.getBoundingClientRect(), score = 0.5;   // nematomas: tik jei kito nėra
      if (r.width >= 2 && r.height >= 2) {
        score = r.width * r.height * (v.paused ? 1 : 8) * (v.muted || !v.volume ? 1 : 4) * (v === video ? 2 : 1);
      }
      if (score > bestScore) { bestScore = score; best = v; }
    }
    return best;
  }

  function flush() {
    gen++;
    for (var i = 0; i < queue.length; i++) queue[i].bmp.close();
    queue = [];
    lastT = 0;
    ccReset();
  }

  // ---- Subtitrai ----
  // Viso ekrano (immersive) režime YouTube subtitrų langas paslėptas kartu su visu puslapiu ir
  // nevėlinamas. Tekstą kopijuojam į savo eilutę virš vaizdo ir rodom po delayMs, kaip kadrus.
  var cc = null, ccRoot = null, ccObs = null, ccLast = '', ccSeq = 0, ccShownSeq = 0;

  function ccRead() {
    if (!ccRoot) return '';
    var lines = ccRoot.querySelectorAll('.caption-visual-line'), out = [];
    if (!lines.length) lines = ccRoot.querySelectorAll('.ytp-caption-segment');
    for (var i = 0; i < lines.length; i++) {
      // „caption-window-_N“ yra YouTube pranešimas („… >> Lithuanian, Click for settings“), ne subtitrai.
      var w = lines[i].closest ? lines[i].closest('.caption-window') : null;
      if (w && /^caption-window-_/.test(w.id || '')) continue;
      var s = (lines[i].textContent || '').replace(/\s+/g, ' ').trim();
      if (s) out.push(s);
    }
    return out.join('\n');
  }

  function ccShow(txt) {
    if (!cc) return;
    while (cc.firstChild) cc.removeChild(cc.firstChild);
    var parts = txt ? txt.split('\n') : [];
    for (var i = 0; i < parts.length; i++) {
      if (i) cc.appendChild(document.createElement('br'));
      var sp = document.createElement('span');
      sp.textContent = parts[i];
      cc.appendChild(sp);
    }
    cc.classList.toggle('xf-cc-on', parts.length > 0);
  }

  function ccChanged() {
    var txt = ccRead();
    if (txt === ccLast) return;
    ccLast = txt;
    var seq = ++ccSeq, d = capturing ? delayMs : 0;
    function apply() { if (seq > ccShownSeq) { ccShownSeq = seq; ccShow(txt); } }
    if (d > 0) setTimeout(apply, d); else apply();
  }

  // Po persukimo ar naujo video: laukiantys seni tekstai nebetinka.
  function ccReset() {
    ccShownSeq = ccSeq;
    ccLast = null;
    if (ccRoot) ccChanged();
  }

  function ccAttach(parent) {
    if (typeof MutationObserver === 'undefined') return;
    if (!cc) { cc = document.createElement('div'); cc.id = 'xf-cc'; }
    if (cc.parentElement !== parent) parent.appendChild(cc);
    var root = document.querySelector('.ytp-caption-window-container');
    if (root === ccRoot) return;
    if (ccObs) ccObs.disconnect();
    ccRoot = root; ccObs = null;
    if (root) {
      ccObs = new MutationObserver(ccChanged);
      ccObs.observe(root, { childList: true, subtree: true, characterData: true });
    }
    ccLast = null;
    ccChanged();
  }

  // ---- YouTube grotuvo nustatymai programos ⋮ meniu ----
  // Viso ekrano režime paties YouTube meniu nepasiekiamas, todėl subtitrus, garso takelį,
  // kartojimą ir „Stable volume“ programa valdo per grotuvo API. Sąrašai grąžinami eilutėmis:
  // pirmas simbolis '*' = pasirinktas, ' ' = ne; eilutė '>' = siūlomas vertimas.
  var ccTracks = [], ccTrs = [], auTracks = [];

  function ytp() {
    return document.getElementById('movie_player') || document.querySelector('.html5-video-player');
  }

  function ccCurrent(p) {
    try { return p.getOption('captions', 'track') || {}; } catch (e) { return {}; }
  }

  // langs – telefono kalbų kodai (pvz. ['en','lt']): kiekvienai, kurios takelio nėra, siūlomas vertimas.
  function ccList(langs, done) {
    var p = ytp(), n = 0;
    langs = langs || [];
    if (!p || !p.getOption) { done(''); return; }
    try { if (p.loadModule) p.loadModule('captions'); } catch (e) {}
    (function read() {
      var l = [];
      try { l = p.getOption('captions', 'tracklist', { includeAsr: true }) || []; } catch (e) {}
      if (!l.length && n++ < 8) { setTimeout(read, 150); return; }
      // Telefono kalbų takeliai sąrašo viršuje (sąrašas gali būti ilgas).
      var rank = function (t) {
        var c = String(t.languageCode || ''), r = langs.indexOf(c);
        if (r < 0) r = langs.indexOf(c.split('-')[0]);
        return r < 0 ? langs.length : r;
      };
      l = l.map(function (t, i) { return { t: t, i: i }; })
        .sort(function (a, b) { return (rank(a.t) - rank(b.t)) || (a.i - b.i); })
        .map(function (x) { return x.t; });
      ccTracks = l; ccTrs = [];
      var cur = ccCurrent(p), on = !!cur.languageCode;
      var tr = (on && cur.translationLanguage && cur.translationLanguage.languageCode) || '';
      var out = [], have = {}, canTr = false;
      for (var i = 0; i < l.length; i++) {
        var t = l[i];
        have[t.languageCode] = 1;
        have[String(t.languageCode || '').split('-')[0]] = 1;
        if (t.is_translateable) canTr = true;
        var sel = on && !tr && t.languageCode === cur.languageCode &&
          (t.kind || '') === (cur.kind || '') && (t.name || '') === (cur.name || '');
        out.push((sel ? '*' : ' ') + (t.displayName || t.languageName || t.languageCode));
      }
      // Vertimas į telefono kalbas, kurių takelio nėra.
      if (canTr && langs.length) {
        var tl = [];
        try { tl = p.getOption('captions', 'translationLanguages') || []; } catch (e) {}
        for (var k = 0; k < langs.length; k++) {
          if (have[langs[k]]) continue;
          have[langs[k]] = 1;
          for (var j = 0; j < tl.length; j++) {
            if (tl[j].languageCode !== langs[k]) continue;
            ccTrs.push(tl[j]);
            out.push('>' + (tr === langs[k] ? '*' : ' ') + tl[j].languageName);
            break;
          }
        }
      }
      done(out.join('\n'));
    })();
  }

  // i: -1 – išjungti; -2, -3… – vertimas (pirmas, antras… iš ccList); kitaip takelio numeris.
  function ccSet(i) {
    var p = ytp();
    if (!p || !p.setOption) return false;
    try {
      if (i === -1) { p.setOption('captions', 'track', {}); return true; }
      var t = ccTracks[i];
      if (i <= -2) {
        var ccTr = ccTrs[-2 - i];
        if (!ccTr) return false;
        var cur = ccCurrent(p), base = null;
        for (var k = 0; k < ccTracks.length && !base; k++) {
          if (ccTracks[k].languageCode === cur.languageCode && ccTracks[k].is_translateable) base = ccTracks[k];
        }
        for (var m = 0; m < ccTracks.length && !base; m++) if (ccTracks[m].is_translateable) base = ccTracks[m];
        if (!base) return false;
        t = {};
        for (var key in base) t[key] = base[key];
        t.translationLanguage = ccTr;
      }
      if (!t) return false;
      p.setOption('captions', 'track', t);
      return true;
    } catch (e) { return false; }
  }

  function auInfo(t) {
    try { return t.getLanguageInfo() || {}; } catch (e) { return {}; }
  }

  function auList(done) {
    var p = ytp(), l = [], cur = null;
    try { l = (p.getAvailableAudioTracks() || []).slice(); cur = p.getAudioTrack(); } catch (e) {}
    // Originalas pirmas: automatiškai įgarsintų takelių gali būti dvidešimt.
    l.sort(function (a, b) { return (auInfo(b).isDefault ? 1 : 0) - (auInfo(a).isDefault ? 1 : 0); });
    auTracks = l;
    var ci = cur ? auInfo(cur).id : null, out = [];
    for (var i = 0; i < l.length; i++) {
      var x = auInfo(l[i]);
      out.push((ci != null && x.id === ci ? '*' : ' ') + (x.name || x.id || ('Track ' + (i + 1))));
    }
    done(out.join('\n'));
  }

  function auSet(i) {
    var p = ytp();
    try {
      if (!p || !auTracks[i]) return false;
      p.setAudioTrack(auTracks[i]);
      return true;
    } catch (e) { return false; }
  }

  // Bitai: 1 – kartojimas įjungtas, 2 – video turi „Stable volume“ takelį, 4 – „Stable volume“ įjungtas.
  function playerState() {
    var p = ytp(), s = 0;
    try { if (p.getLoopVideo()) s |= 1; } catch (e) {}
    try {
      if (p.hasDrcAudioTrack()) {
        s |= 2;
        if (Number(p.getDrcUserPreference()) === 1) s |= 4;
      }
    } catch (e) {}
    return s;
  }

  function ensureStyle() {
    if (document.getElementById('xf-delay-style')) return;
    var s = document.createElement('style');
    s.id = 'xf-delay-style';
    // id selektorius nugali Fermata immersive režimo „body *:not(video) {visibility:hidden}“
    s.textContent =
      '#xf-delay-canvas{pointer-events:none!important;visibility:visible!important;' +
      'background:#000;z-index:2147483647!important}' +
      '#xf-delay-ui,#xf-delay-ui *{visibility:visible!important}' +
      '#xf-delay-ui{position:absolute;top:8px;left:8px;z-index:2147483647;display:flex;gap:6px;' +
      'align-items:center;font:600 18px/1 sans-serif;color:#fff;background:rgba(0,0,0,.55);' +
      'border-radius:22px;padding:4px 6px;opacity:.35;transition:opacity .3s;user-select:none}' +
      '#xf-delay-ui.xf-open{opacity:1}#xf-delay-ui.xf-off{display:none!important}' +
      '#xf-delay-ui button{all:unset;min-width:44px;height:40px;text-align:center;border-radius:20px;' +
      'background:rgba(255,255,255,.18);color:#fff;font:600 16px/40px sans-serif;cursor:pointer}' +
      '#xf-delay-ui .xf-step,#xf-delay-ui .xf-diag{display:none}' +
      '#xf-delay-ui.xf-open .xf-step{display:inline-block}' +
      '#xf-delay-ui .xf-gone{display:none!important}' +
      '#xf-delay-ui.xf-busy .xf-adj{opacity:.3;pointer-events:none}' +
      '#xf-delay-ui.xf-open .xf-diag{display:inline-block;font:500 13px/1 monospace;opacity:.8;padding:0 6px}' +
      '#xf-delay-ui .xf-label{min-width:84px;text-align:center;padding:0 4px}' +
      '#xf-delay-ui .xf-meas{font:600 15px/1 sans-serif;padding:0 4px}#xf-delay-ui .xf-meas:empty{display:none}' +
      '#xf-delay-ui:not(.xf-open) .xf-meas.xf-quiet{display:none}' +
      '#xf-delay-ui button.xf-auto-on{background:rgba(60,170,90,.85)}' +
      // Subtitrų eilutė viso ekrano (immersive) režime; kituose režimuose rodo pats YouTube.
      '#xf-cc{display:none;position:fixed;left:6%;right:6%;bottom:9%;z-index:2147483647;text-align:center;' +
      'pointer-events:none;font:500 4.6vh/1.4 "YouTube Noto",Roboto,Arial,sans-serif;color:#fff}' +
      'html.fermata-yt-immersive #xf-cc.xf-cc-on{display:block}' +
      '#xf-cc,#xf-cc *{visibility:visible!important}' +
      '#xf-test-video{position:fixed!important;left:0;top:0;width:100vw;height:100vh;background:#000;' +
      'object-fit:contain;z-index:2147483646!important;visibility:visible!important;pointer-events:none}' +
      '#xf-cc span{background:rgba(8,8,8,.75);padding:.1em .4em;' +
      '-webkit-box-decoration-break:clone;box-decoration-break:clone}';
    document.documentElement.appendChild(s);
  }

  function gone(b) { b.classList.add('xf-gone'); return b; }
  function btn(text, step) {
    var b = document.createElement('button');
    b.className = 'xf-step xf-adj';
    b.textContent = text;
    b.addEventListener('click', function (e) {
      e.stopPropagation(); e.preventDefault();
      // Ranka paleistas 🎤 – laukiam jo; tylų auto matavimą nutraukiam, kad ±10 veiktų visada.
      if (measuring) { if (!measQuiet) return; if (measCancel) measCancel(); }
      if (testVid) shiftCorr(step); else manualSet(delayMs + step);
      openUi();
    }, true);
    return b;
  }

  // Ranka pakeista delsa = tiesa. Jei neseniai buvo patikimas 🎤 matavimas, įsimenam skirtumą
  // (mikrofono kelio ir AA vaizdo delsa), kad auto režimas toliau taikytų „🎤 − korekcija“.
  // Korekcija skaičiuojama iš paskutinių matavimų medianos, ne iš vieno (vienas turi ±20–30 ms triukšmo).
  function manualSet(ms) {
    stopRamp();
    api.set(ms);
    var med = recentMedian(LEARN_MS);
    if (med == null && lastOkMeas && Date.now() - lastOkMeas.at < LEARN_MS) med = lastOkMeas.ms;
    if (med != null) setOffset(med - delayMs);
    stableN = 0;
  }

  // Korekcija tiesiogiai (testo video ar kameros matavimas): delsa + ms, ir tiek pat įsimenama korekcijoje,
  // kad auto režimas toliau taikytų tą patį poslinkį („🎤 − korekcija“). Nepriklauso nuo to, ar buvo matavimas.
  // Korekcija skaičiuojama nuo 🎤 medianos (ne nuo dabartinės korekcijos): jei auto dar nebuvo nusistovėjęs,
  // „micOffset − ms“ būtų klaidinga ir auto vėliau nuvestų delsą į šoną. Medianos nėra – tik tada senasis būdas.
  function shiftCorr(ms) {
    ms = Math.round(ms) || 0;
    if (!ms) return;
    stopRamp();
    var prevOff = micOffset;
    api.set(delayMs + ms);
    var med = recentMedian(LEARN_MS);
    if (med == null && lastOkMeas && Date.now() - lastOkMeas.at < LEARN_MS) med = lastOkMeas.ms;
    setOffset(med != null ? med - delayMs : prevOff - ms);
    stableN = 0;
  }

  // ---- Sinchronizacijos testas (⋮ → Sync test arba telefono vedlys) ----
  // Programos testo video: kas sekundę baltas blyksnis ir pyptelėjimas tuo pačiu metu (faile sutampa ~2 ms).
  // Groja vietoj YouTube video tuo pačiu keliu (<video> → vėlinimas → Android Auto), todėl automobilyje
  // matosi tikras likęs poslinkis: akimis (±10) arba telefono kamera (vienkartinis vedlys telefone).
  var TEST_URL = 'https://xf.invalid/sync-test.webm';
  var testVid = null, ytResume = false;
  function syncTest(on) {
    if (!!on === !!testVid) return !!testVid;
    if (on) {
      var yv = activeVideo();
      ytResume = !!yv && !yv.paused;
      if (yv) {
        try { window.__fermataPauseOk = true; yv.pause(); } catch (e) {} finally { window.__fermataPauseOk = false; }
      }
      ensureStyle();
      var t = document.createElement('video');
      t.id = 'xf-test-video';
      t.crossOrigin = 'anonymous';   // programa atsako su CORS: tada veikia ir 🎤 matavimas (captureStream)
      t.loop = true; t.playsInline = true; t.autoplay = true;
      t.src = TEST_URL;
      (document.fullscreenElement || document.body).appendChild(t);
      testVid = t;
      var p = t.play();
      if (p && p.catch) p.catch(function (e) { logAuto('test play: ' + (e && e.name)); });
      logAuto('sync test on');
    } else {
      var old = testVid;
      testVid = null;
      try { old.pause(); old.removeAttribute('src'); old.load(); } catch (e) {}
      old.remove();
      logAuto('sync test off');
    }
    api.attach();
    if (!on && ytResume && video && video.paused) { var r = video.play(); if (r && r.catch) r.catch(function () {}); }
    updateLabel();
    openUi(6000);
    return !!testVid;
  }

  function openUi(ms) {
    if (!ui) return;
    ui.classList.add('xf-open');
    updateDiag();
    clearTimeout(hideTimer);
    hideTimer = setTimeout(function () {
      // Neuždarom tik kol vyksta ranka paleistas matavimas; auto (tylūs) matavimai valdiklio nelaiko.
      if (measuring && !measQuiet) openUi(); else closeUi();
    }, ms || 4000);
  }

  function closeUi() {
    clearTimeout(hideTimer);
    if (ui) ui.classList.remove('xf-open');
  }

  function isOpen() { return !!ui && ui.classList.contains('xf-open'); }

  // ---- 🎤 Delsos matavimas mikrofonu ----
  // Originalus garsas imamas iš <video> (captureStream, grojimo nekeičia), kolonėlių garsas – mikrofonu.
  // Abiejų garsumo kreivės (kas 10 ms) lyginamos stumiant viena kitos atžvilgiu: geriausio sutapimo
  // poslinkis = kiek kolonėlės vėluoja nuo originalo.
  // Ieškoma 0–1000 ms: testiniai video pypsi kas 1 s, todėl didesnis langas duotų dviprasmišką rezultatą.
  var MEAS_SEC = 10, BIN_MS = 10, MAX_LAG_MS = 1000;
  var measuring = false, measQuiet = false, measOut = null, lastMeas = null, lastOkMeas = null;   // lastOkMeas {ms, at}
  var measCancel = null;   // nutraukia vykstantį matavimą (ranka paspaudus ±10 auto matavimo metu)

  // quiet = auto režimo tekstas: matosi tik atidarius valdiklį.
  function showMeas(text, quiet) {
    if (measOut) { measOut.textContent = text || ''; measOut.classList.toggle('xf-quiet', !!quiet); }
    if (!quiet) openUi(8000);
  }

  // Garsumo pokyčiai: log(RMS) minus slankusis vidurkis (~0,5 s), kad tolygus ūžesys nedarytų įtakos.
  function prep(e) {
    var L = e.length, lg = new Float64Array(L), ps = new Float64Array(L + 1), out = new Float64Array(L), W = 25;
    for (var i = 0; i < L; i++) { lg[i] = Math.log(e[i] + 1e-4); ps[i + 1] = ps[i] + lg[i]; }
    for (i = 0; i < L; i++) {
      var a = Math.max(0, i - W), b = Math.min(L, i + W + 1);
      out[i] = lg[i] - (ps[b] - ps[a]) / (b - a);
    }
    return out;
  }

  // refE prasideda laiku r0, micE – m0 (epochos ms); abi kas BIN_MS. Mikrofono reikšmės imamos
  // tuo pačiu realiu laiku + lag (tiesinė interpoliacija tarp gretimų taškų).
  // parts > 1: papildomai tas pats skaičiuojama kiekvienai lygiai įrašo daliai (res.parts). Jei dalys
  // rodo tą pačią vietą kaip visas įrašas, matavimas patikimas ir be antro matavimo.
  // hint (ms) – dabartinė delsa: jei šalia jos (±HINT_MS) yra smailė, beveik lygi aukščiausiai (≥ HINT_KEEP),
  // grąžinama ji (res.kept). Ritmiškoje muzikoje smailės kartojasi kas ritmo periodą ir būna beveik vienodos:
  // tada matavimas nieko naujo nesako, ir šokinėti tarp jų neverta.
  var HINT_MS = 80, HINT_KEEP = 0.95;
  function correlate(refE, r0, micE, m0, parts, hint) {
    var maxLag = Math.round(MAX_LAG_MS / BIN_MS), shift = (r0 - m0) / BIN_MS;
    var iStart = Math.max(0, Math.ceil(-shift)), iEnd = Math.min(refE.length - 1, Math.floor(micE.length - 2 - shift - maxLag));
    var N = iEnd - iStart + 1;
    if (N < 200) return { err: 'recording too short' };
    var loud = 0;
    for (var i = 0; i < refE.length; i++) loud = Math.max(loud, refE[i]);
    if (loud < 0.003) return { err: 'video too quiet' };
    var a = prep(refE), b = prep(micE);
    var hb = hint != null && hint >= 0 ? hint / BIN_MS : null;
    var res = peakAt(a, b, shift, iStart, iEnd, maxLag, hb);
    if (parts > 1) {
      var len = Math.floor(N / parts);
      res.parts = [];
      for (var k = 0; k < parts; k++) res.parts.push(peakAt(a, b, shift, iStart + k * len, iStart + (k + 1) * len - 1, maxLag, hb));
    }
    return res;
  }

  // Koreliacijos smailė ruože iStart..iEnd (paruoštos kreivės a ir b). hb – hint, išreikštas žingsniais.
  function peakAt(a, b, shift, iStart, iEnd, maxLag, hb) {
    var cs = [], i;
    for (var lag = 0; lag <= maxLag; lag++) {
      var sab = 0, saa = 0, sbb = 0;
      for (i = iStart; i <= iEnd; i++) {
        var j = i + shift + lag, j0 = Math.floor(j), f = j - j0;
        var x = a[i], y = b[j0] * (1 - f) + b[j0 + 1] * f;
        sab += x * y; saa += x * x; sbb += y * y;
      }
      cs.push(saa && sbb ? sab / Math.sqrt(saa * sbb) : 0);
    }
    var top = 0;
    for (lag = 1; lag <= maxLag; lag++) if (cs[lag] > cs[top]) top = lag;
    var best = top;
    if (hb != null) {
      var lo = Math.max(0, Math.round(hb - HINT_MS / BIN_MS)), hi = Math.min(maxLag, Math.round(hb + HINT_MS / BIN_MS)), loc = lo;
      for (lag = lo + 1; lag <= hi; lag++) if (cs[lag] > cs[loc]) loc = lag;
      var isPeak = (loc === 0 || cs[loc] >= cs[loc - 1]) && (loc === maxLag || cs[loc] >= cs[loc + 1]);
      if (lo <= hi && isPeak && Math.abs(loc - top) > 5 && cs[loc] > 0 && cs[loc] >= HINT_KEEP * cs[top]) best = loc;
    }
    var second = 0;
    for (lag = 0; lag <= maxLag; lag++) if (Math.abs(lag - best) > 5 && cs[lag] > second) second = cs[lag];
    var off = 0;
    if (best > 0 && best < maxLag) {
      var y0 = cs[best - 1], y1 = cs[best], y2 = cs[best + 1], d = y0 - 2 * y1 + y2;
      if (d < 0) off = 0.5 * (y0 - y2) / d;
    }
    var conf = cs[best], ratio = second > 0 ? conf / second : 9;
    var res = { ms: Math.round((best + off) * BIN_MS), conf: Math.round(conf * 100) / 100,
      ratio: Math.round(ratio * 100) / 100, ok: conf >= 0.25 && ratio >= 1.25 };
    if (best !== top) res.kept = true;
    return res;
  }

  // Mikrofoną įrašo programa (Java AudioRecord, be garso fokuso – video nesustoja), naršyklė tik
  // originalą. Abi kreivės turi pirmo taško laiką (epochos ms), pagal jį ir sulyginamos.
  // opts.fakeDelayMs – testui be programos: „mikrofonas“ = originalas, uždelstas, su triukšmu ir
  // prasidedantis vėliau (tikrina ir laikų sulyginimą).
  var JS_XF_MIC = 51, JS_XF_LOG = 52, MIC_EXTRA_SEC = 1.5, micCb = null, measGen = 0;

  // opts.auto – auto režimo matavimas (tylus); opts.onDone(res|null, klaida) – kai baigta.
  function level(env) {
    if (!env || !env.length) return '?';
    var s = 0;
    for (var i = 0; i < env.length; i++) s += env[i];
    return String(Math.round(s / env.length * 10000) / 10000);
  }

  function measure(opts) {
    opts = opts || {};
    var fake = opts.fakeDelayMs != null, quiet = !!opts.auto;
    if (measuring) { if (!quiet) showMeas('🎤 already measuring…'); return; }
    function fail(err) {
      showMeas('🎤 ' + err, quiet && err !== NO_PERM);   // be leidimo – rodom visada
      if (opts.onDone) opts.onDone(null, err);
    }
    if (!video) { fail('no video'); return; }
    if (video.paused) { fail('play a video first'); return; }
    if (!fake && !(window.Fermata && window.Fermata.event)) { fail(NO_APP); return; }
    var ref;
    try {
      var at = (video.captureStream ? video.captureStream() : null);
      at = at && at.getAudioTracks();
      if (!at || !at.length) { fail('no audio track'); return; }
      ref = new MediaStream([at[0]]);
    } catch (e) { fail('cannot capture audio'); return; }
    // AudioContext kuriamas iškart paspaudus (vartotojo gestas). Programoje WebView gesto nereikalauja
    // (setMediaPlaybackRequiresUserGesture(false)), todėl auto režimas gali matuoti ir pats.
    var ac;
    try { ac = new AudioContext(); } catch (e) {
      ref.getTracks().forEach(function (t) { t.stop(); });
      fail('AudioContext error'); return;
    }
    measuring = true; measQuiet = quiet;
    var myVid = vidGen;
    var secs = opts.sec > 0 ? opts.sec : MEAS_SEC;   // greito starto matavimas trumpesnis
    updateLabel();
    showMeas('🎤 listening… ' + secs + ' s', quiet);
    var proc = null, timer = 0, refDone = false, micRes = null;
    var sr = ac.sampleRate, binN = Math.round(sr * BIN_MS / 1000), epochOff = Date.now() - performance.now();
    var refE = [], fakeE = [], accR = 0, accF = 0, n = 0, samples = 0, r0perf = Infinity;

    function done(text, res, err) {
      measuring = false; micCb = null; measCancel = null; clearTimeout(timer);
      // Matuojant pasikeitė video: rezultatas sumaišytas iš dviejų video, netinka.
      if (res && myVid !== vidGen) { res = null; err = 'video changed'; text = '🎤 video changed'; }
      try { if (proc) proc.disconnect(); } catch (e) {}
      ref.getTracks().forEach(function (t) { t.stop(); });
      ac.close().catch(function () {});
      if (res) {
        lastMeas = res;
        if (res.ok) {
          lastOkMeas = { ms: res.ms, at: Date.now() };
          // Rankinis 🎤 irgi tinka medianai (auto rezultatus prideda autoResult).
          if (!quiet) { recent.push({ ms: res.ms, at: Date.now(), w: 1, n: tryN }); if (recent.length > POOL_N) recent.shift(); }
        }
      }
      updateLabel();
      showMeas(text, quiet && err !== NO_PERM);
      // Mikrofonas atsakė (pvz. leidimas duotas vėliau): auto režimas, sustojęs dėl leidimo, tęsia.
      if (res && autoBlocked && !fake) { autoBlocked = false; logAuto('resumed'); autoSchedule(AUTO_EVERY_MS); }
      if (opts.onDone) opts.onDone(res, err);
    }

    function finish() {
      if (!refDone || !micRes) return;
      if (micRes.err) { done('🎤 ' + micRes.err, null, micRes.err); return; }
      var r = correlate(refE, epochOff + r0perf, micRes.env, micRes.t0, opts.parts, opts.hint);
      var dev = micRes.dev || '?';
      if (r.err) { done('🎤 ' + r.err + ' · ' + dev, null, r.err); return; }
      r.mic = dev; r.ts = micRes.ts;
      // Vidutinis garso lygis: video takelio ir mikrofono (žurnalui: ar mikrofonas išvis girdėjo garsą).
      r.lv = level(refE) + '/' + level(micRes.env);
      done('🎤 ' + r.ms + ' ms ' + (r.ok ? '✓' : '?') + ' ' + r.conf + ' · ' + dev, r, null);
    }

    var startEpoch = Date.now(), myGen = ++measGen;
    measCancel = function () {
      if (measuring && measGen === myGen) done('🎤 cancelled', null, 'cancelled');
    };
    // Saugiklis: jei AudioContext niekada nepasileidžia (ac.resume nekviečia then), matavimas neturi kabėti amžinai.
    setTimeout(function () {
      if (measuring && measGen === myGen) done('🎤 timeout', null, 'timeout');
    }, secs * 1000 + 20000);
    micCb = function (res) {
      res = res || { err: 'empty reply' };
      if (!res.err && res.t0 < startEpoch - 1000) return;   // senesnio matavimo pavėlavęs atsakymas
      micRes = res; finish();
    };

    var src = ac.createMediaStreamSource(ref), merger = ac.createChannelMerger(2);
    src.connect(merger, 0, 0);
    if (fake) {
      var dl = ac.createDelay(3); dl.delayTime.value = opts.fakeDelayMs / 1000;
      var noise = ac.createBufferSource(), nb = ac.createBuffer(1, sr, sr), nd = nb.getChannelData(0);
      for (var i = 0; i < nd.length; i++) nd[i] = (Math.random() * 2 - 1) * 0.05;
      noise.buffer = nb; noise.loop = true; noise.start();
      src.connect(dl); dl.connect(merger, 0, 1); noise.connect(merger, 0, 1);
    }
    proc = ac.createScriptProcessor(4096, 2, 1);
    proc.onaudioprocess = function (ev) {
      var r = ev.inputBuffer.getChannelData(0), q = ev.inputBuffer.getChannelData(1);
      samples += r.length;
      // Bloko pradžios laikas; callback'ai gali vėluoti, todėl imamas mažiausias (mažiausiai vėlavęs).
      r0perf = Math.min(r0perf, performance.now() - samples / sr * 1000);
      if (refDone) return;
      for (var i = 0; i < r.length; i++) {
        accR += r[i] * r[i]; accF += q[i] * q[i];
        if (++n === binN) { refE.push(Math.sqrt(accR / n)); fakeE.push(Math.sqrt(accF / n)); accR = accF = n = 0; }
      }
    };
    var mute = ac.createGain(); mute.gain.value = 0;   // procesorius turi būti prijungtas, bet tylus
    merger.connect(proc); proc.connect(mute); mute.connect(ac.destination);
    ac.resume().then(function () {
      // „;ask“ = ranka paleistas matavimas: telefone be leidimo programa parodo leidimo užklausą.
      if (!fake) window.Fermata.event(JS_XF_MIC, String(secs + MIC_EXTRA_SEC) + (quiet ? '' : ';ask'));
      timer = setTimeout(function () {
        refDone = true;
        if (fake) {
          // Imituojamas atskiras įrašas, prasidėjęs 370 ms vėliau.
          var skip = 37;
          micCb({ t0: epochOff + r0perf + skip * BIN_MS, env: fakeE.slice(skip), dev: 'test', ts: true });
          return;
        }
        finish();
        timer = setTimeout(function () {
          if (measuring && measGen === myGen) done('🎤 app did not reply', null, 'app did not reply');
        }, 8000);
      }, secs * 1000);
    }).catch(function (e) { done('🎤 error: ' + (e && e.name), null, 'error'); });
  }

  // ---- Auto režimas ----
  // 🎤 matuoja pats: naujam video po ~2,5 s, po pauzės ir po užstrigimo (waiting → playing), toliau kas
  // AUTO_EVERY_MS (nusistovėjus – kas AUTO_STABLE_MS).
  //
  // Sprendžia ne vieno matavimo „aiškumas“, o matavimų sutapimas. Ritmiškoje muzikoje koreliacija turi
  // kelias beveik lygias smailes (kas ritmo periodą), todėl „ratio“ ten mažas, nors aukščiausia smailė
  // dažniausiai teisinga. Simuliacijose (2026-11): ratio ≥ 1,25 – klaidų praktiškai nėra; mažesnis ratio
  // patikimas tik tada, kai abi įrašo pusės atskirai rodo tą pačią vietą; tyloje pusės niekada nesutampa.
  //  - tinkamas matavimas (balsas): ok (ratio ≥ 1,25) arba stipri smailė (VOTE_CONF) ir pusės sutampa;
  //  - balsas dvigubas, kai matavimas ok ir dar pusės sutampa (ir conf ≥ VOTE_CONF) arba jis labai aiškus (CLEAR_*);
  //  - delsa keičiama, kai paskutinių FRESH_N bandymų balsų grupė (±AGREE_MS) surenka 2 balsus (jei
  //    dabartinė delsa nepatvirtinta) arba du atskirus matavimus (jei patvirtinta, t. y. turi LOCK_W balsus)
  //    ir nuo grupės pradžios dabartinė delsa nesurinko daugiau;
  //  - arti dabartinės delsos (±NEAR_MS) tik smulkus derinimas pagal grupės medianą, nuo LOCK_W balsų;
  //  - kai smailė prie dabartinės delsos beveik lygi aukščiausiai, matavimas ją patvirtina (correlate hint).
  // Keičiama pamažu (rampTo), kad vaizdas nešokinėtų. Taikoma „🎤 − korekcija“; korekciją išmoksta,
  // kai pataisai ranka.
  var AUTO_KEY = 'xf_auto' + SFX, OFF_KEY = 'xf_mic_offset_ms' + SFX;
  var NO_PERM = 'no microphone permission', NO_APP = 'works only in the app';
  var AUTO_FIRST_MS = 2500, AUTO_START_MS = 4000, AUTO_STALL_MS = 3000;
  var AUTO_SEC = 6, AUTO_PARTS = 2;            // auto matavimo trukmė ir į kiek dalių jis dalijamas patikrai
  var AUTO_GAP_MS = 500;                       // tarpas tarp matavimų, kol delsa gaudoma
  var AUTO_EVERY_MS = 20000, AUTO_STABLE_MS = 30000, STABLE_N = 3;   // po STABLE_N „ok“ iš eilės matuojama rečiau
  // Baterijai: kuo ilgiau delsa patvirtinama, tuo rečiau matuojama (30 s → 1 → 2 → 4 → 5 min).
  // Po prasukimo, užstrigimo, naujo video ar pakeitimo stableN = 0, ir vėl matuojama dažnai.
  var AUTO_MAX_MS = 5 * 60000;
  function stableInterval() {
    if (stableN < STABLE_N) return AUTO_EVERY_MS;
    return Math.min(AUTO_MAX_MS, AUTO_STABLE_MS * Math.pow(2, Math.min(5, stableN - STABLE_N)));
  }
  var AUTO_RETRY_MS = 10000, CATCH_MISS = 4;   // po CATCH_MISS netinkamų matavimų iš eilės (tyla) – retai
  // Kai pagauti nepavyksta ilgai (tylus video, triukšmas, uždengtas mikrofonas), bandoma vis rečiau:
  // 10, 20, 40, 80 s, toliau kas 2 min. Automobilyje 2026-11-18 buvo matuojama kas 10 s daugiau nei 4 min.
  var AUTO_RETRY_MAX_MS = 2 * 60000;
  function retryDelay() {
    if (missN <= CATCH_MISS) return AUTO_GAP_MS;
    return Math.min(AUTO_RETRY_MAX_MS, AUTO_RETRY_MS * Math.pow(2, Math.min(6, missN - CATCH_MISS - 1)));
  }
  var VOTE_CONF = 0.4;                         // stipri smailė tinka ir be „ratio“, jei įrašo pusės sutampa
  var FRESH_N = 3;                             // pokyčio kandidatai – tik iš tiek paskutinių bandymų (+ dabartinis)
  var CLEAR_CONF = 0.45, CLEAR_RATIO = 1.8;    // labai aiškus matavimas: dvigubas balsas
  var SPLIT_MS = 35;                           // tiek gali skirtis įrašo dalių ir viso įrašo smailės
  var AGREE_MS = 50, NEAR_MS = 80, FINE_MS = 12, LOCK_W = 3;
  var POOL_N = 6, POOL_TTL_MS = 5 * 60000, LEARN_MS = 10 * 60000, RAMP_STEP = 20;
  var recent = [];       // šio video balsai: paskutiniai tinkami 🎤 matavimai {ms, at, w} (žali, be korekcijos)
  var stableN = 0;       // kiek kartų iš eilės matavimas sutapo su nustatyta delsa
  var vidGen = 0;        // didėja keičiantis video: seno video matavimo rezultatas atmetamas
  var fastStart = true;  // kitas auto matavimas – naujo video pirmasis
  var missN = 0;         // kiek netinkamų matavimų iš eilės gaudymo metu
  var tryN = 0;          // šio video auto matavimų skaitiklis (ir netinkamų)
  var loose = false;     // po užstrigimo: delsa galėjo pasikeisti, todėl pakeitimui užtenka 2 balsų
  function startDelay() { return fastStart ? AUTO_FIRST_MS : AUTO_START_MS; }
  function weight(a) { var w = 0; a.forEach(function (v) { w += v.w || 1; }); return w; }
  function near(ms, tol) { return recent.filter(function (v) { return Math.abs(v.ms - ms) <= tol; }); }
  // Mediana pagal balsų svorį.
  function voteMedian(a) {
    var all = [];
    a.forEach(function (v) { for (var i = 0; i < (v.w || 1); i++) all.push(v.ms); });
    return median(all);
  }

  function median(a) {
    var s = a.slice().sort(function (x, y) { return x - y; }), n = s.length;
    return n ? (n % 2 ? s[(n - 1) >> 1] : Math.round((s[n / 2 - 1] + s[n / 2]) / 2)) : null;
  }

  // Paskutinių POOL_N matavimų, ne senesnių nei maxAgeMs, mediana (arba null).
  function recentMedian(maxAgeMs) {
    var now = Date.now(), v = [];
    for (var i = recent.length - 1; i >= 0 && v.length < POOL_N; i--) {
      if (!maxAgeMs || now - recent[i].at <= maxAgeMs) v.push(recent[i].ms);
    }
    return median(v);
  }
  // Telefone (be AA) auto numatytai išjungtas: ten garsas su vaizdu ir taip sutampa.
  var autoStored = localStorage.getItem(AUTO_KEY);
  var autoOn = autoStored != null ? autoStored !== '0' : CONN !== 'phone';
  var micOffset = parseInt(stored('xf_mic_offset_ms') || '0', 10) || 0;
  var autoTimer = 0, autoBlocked = false, stalled = false, rampTimer = 0, autoBtn = null;
  var autoFake = null;   // testui naršyklėje: imituojama delsa (ms) vietoj mikrofono
  var autoLog = [];      // paskutiniai auto įvykiai (diagnostikai)

  // Žurnalas saugomas ir localStorage (paskutiniai 40 įrašų, su ryšiu), kad vėliau būtų galima perskaityti.
  // Video pavadinimų ir adresų į žurnalą nerašom: jį žmonės kopijuoja į viešus pranešimus.
  var LOG_KEY = 'xf_auto_log';
  function logAuto(s) {
    var line = new Date().toTimeString().slice(0, 8) + ' ' + s;
    autoLog.push(line);
    if (autoLog.length > 20) autoLog.shift();
    try {
      var all = JSON.parse(localStorage.getItem(LOG_KEY) || '[]');
      all.push(new Date().toISOString().slice(5, 10) + ' ' + (CONN || '-') + ' ' + line);
      localStorage.setItem(LOG_KEY, JSON.stringify(all.slice(-40)));
    } catch (e) {}
    // Ir į programos žurnalą (failas): Settings → App log, išlieka uždarius programą.
    try { if (window.Fermata && window.Fermata.event) window.Fermata.event(JS_XF_LOG, (CONN || '-') + ' ' + s); } catch (e) {}
  }

  // Programai pranešama dabartinė delsa ir korekcija: ji jas perduoda Web naršyklės puslapiams (__xfSeed).
  // Su pauze, nes sklandus perėjimas (ramp) delsą keičia kelis kartus per sekundę.
  var JS_XF_STATE = 53, stateTimer = 0;
  function reportState() {
    clearTimeout(stateTimer);
    stateTimer = setTimeout(function () {
      try {
        if (CONN && window.Fermata && window.Fermata.event) window.Fermata.event(JS_XF_STATE, CONN + ';' + delayMs + ';' + micOffset);
      } catch (e) {}
    }, 1500);
  }

  function setOffset(v) {
    micOffset = Math.max(-500, Math.min(500, Math.round(v)));
    localStorage.setItem(OFF_KEY, String(micOffset));
    reportState();
    logAuto('offset ' + micOffset);
    updateLabel();
  }

  function stopRamp() { clearInterval(rampTimer); rampTimer = 0; }

  function rampTo(target) {
    stopRamp();
    // Didelis pokytis pritaikomas ne ilgiau kaip per ~1 s (žingsnis didesnis), mažas – po RAMP_STEP.
    var step = Math.max(RAMP_STEP, Math.ceil(Math.abs(target - delayMs) / 10));
    rampTimer = setInterval(function () {
      var d = target - delayMs;
      if (Math.abs(d) <= step) { stopRamp(); api.set(target); return; }
      api.set(delayMs + (d > 0 ? step : -step));
    }, 100);
  }

  function autoSchedule(ms) {
    clearTimeout(autoTimer); autoTimer = 0;
    if (autoOn && !autoBlocked) autoTimer = setTimeout(autoTick, ms);
  }

  function autoTick() {
    autoTimer = 0;
    if (!autoOn || autoBlocked) return;
    if (!video || video.paused || stalled || measuring || document.hidden) { autoSchedule(5000); return; }
    fastStart = false;
    measure({ auto: true, sec: AUTO_SEC, parts: AUTO_PARTS, hint: delayMs + micOffset, fakeDelayMs: autoFake,
      onDone: function (res, err) { autoResult(res, err); } });
  }

  function autoResult(res, err) {
    if (!autoOn) return;   // išjungta, kol vyko matavimas
    if (err === NO_PERM || err === NO_APP) { autoBlocked = true; logAuto('stopped: ' + err); updateLabel(); return; }
    var now = Date.now(), cur = delayMs + micOffset;   // dabartinė delsa mikrofono skalėje
    var clamp = function (v) { return Math.max(0, Math.min(3000, Math.round(v))); };
    recent = recent.filter(function (v) { return now - v.at <= POOL_TTL_MS; });
    // Ar dabartinę delsą jau patvirtino ankstesni matavimai?
    var locked = !loose && weight(near(cur, AGREE_MS)) >= LOCK_W;
    tryN++;
    // Ar abi įrašo pusės rodo tą pačią vietą kaip visas įrašas?
    var split = !!res && !!res.parts && res.parts.length > 1 && res.parts.every(function (h) {
      return (h.ok || h.conf >= VOTE_CONF) && Math.abs(h.ms - res.ms) <= SPLIT_MS;
    });
    // Smailė pačiame paieškos krašte (0 arba MAX_LAG_MS) – ne smailė, o šlaitas: netinka.
    var edge = !!res && (res.ms <= 0 || res.ms >= MAX_LAG_MS);
    if (!res || edge || !(res.ok || (res.conf >= VOTE_CONF && split))) {
      // Patvirtinta delsa nuo vieno nepatikimo matavimo (tyli vieta) nesikeičia, tad ir retinimas nenumušamas.
      if (!locked) { stableN = 0; missN++; }
      // Gaudant kartojama iškart, bet ne be galo (tyla, nutildytas garsas): po CATCH_MISS – vis rečiau.
      var next = locked ? stableInterval() : retryDelay();
      logAuto('unreliable: ' + (err || (res ? res.ms + ' ms ' + res.conf + '/' + res.ratio + (edge ? ', edge' : (res.parts && !split ? ', halves differ' : '')) + ', level ' + res.lv : 'no result')) +
        (locked ? ', delay kept' : ', try ' + missN) + ', next in ' + Math.round(next / 100) / 10 + ' s');
      autoSchedule(next);
      return;
    }
    missN = 0;
    var w = res.ok && ((split && res.conf >= VOTE_CONF) || (res.conf >= CLEAR_CONF && res.ratio >= CLEAR_RATIO)) ? 2 : 1;
    recent.push({ ms: res.ms, at: now, w: w, n: tryN });
    if (recent.length > POOL_N) recent.shift();
    var grp = near(res.ms, AGREE_MS), W = weight(grp), med = voteMedian(grp), target = clamp(med - micOffset);
    var tag = '🎤 ' + res.ms + ' (' + res.conf + '/' + res.ratio + ', level ' + res.lv + (w > 1 ? ', x2' : '') + (res.kept ? ', kept' : '') + (res.ts === false ? ', no ts' : '') + ')';

    // Arti dabartinės delsos: ji patvirtinama, o nuo LOCK_W balsų patikslinama pagal medianą.
    if (Math.abs(med - cur) <= NEAR_MS) {
      loose = false;
      // Delsa patvirtinta: pavieniai kitur rodę matavimai (klaidingos smailės) pamirštami, kad per ilgą
      // video nesusikauptų į „grupę“.
      if (W >= LOCK_W) recent = recent.filter(function (v) { return grp.indexOf(v) >= 0 || Math.abs(v.ms - cur) <= AGREE_MS; });
      if (W < LOCK_W) {
        logAuto(tag + ' → collecting (' + W + '/' + LOCK_W + ')');
        autoSchedule(AUTO_GAP_MS);
      } else if (Math.abs(target - delayMs) > FINE_MS) {
        logAuto(tag + ' → adjust ' + delayMs + '→' + target + ' (median)');
        showMeas('🎤 auto: ' + delayMs + ' → ' + target + ' ms', true);
        stableN = 0;
        rampTo(target);
        autoSchedule(AUTO_EVERY_MS);
      } else {
        stableN++;
        logAuto(tag + ' → ok (' + delayMs + ', median ' + target + ')');
        autoSchedule(stableInterval());
      }
      updateDiag();
      return;
    }

    // Kita vieta nei dabartinė delsa. Keičiama, kai grupė surinko pakankamai balsų ir nuo jos pradžios
    // dabartinė delsa nesurinko daugiau (kitaip tai pavienė klaidinga smailė tarp teisingų matavimų).
    stableN = 0;
    // Kandidatai – tik iš paskutinių bandymų: tyloje pavieniai atsitiktiniai rezultatai nesikaupia.
    // Grupė – didžiausia sutampančių balsų krūva, į kurią patenka naujausias matavimas.
    var fresh = recent.filter(function (v) {
      return tryN - v.n <= FRESH_N && Math.abs(v.ms - cur) > AGREE_MS;
    });
    W = 0; grp = [];
    fresh.forEach(function (c) {
      if (Math.abs(c.ms - res.ms) > AGREE_MS) return;
      var g = fresh.filter(function (v) { return Math.abs(v.ms - c.ms) <= AGREE_MS; }), gw = weight(g);
      if (gw > W) { W = gw; grp = g; }
    });
    med = grp.length ? voteMedian(grp) : res.ms; target = clamp(med - micOffset);
    // Nepatvirtintai delsai pakeisti užtenka 2 balsų (vienas dvigubas matavimas); patvirtintai – dviejų
    // atskirų matavimų.
    var have = locked ? grp.length : W, first = now;
    grp.forEach(function (v) { first = Math.min(first, v.at); });
    var since = weight(near(cur, AGREE_MS).filter(function (v) { return v.at > first; }));
    if (have >= 2 && W > since && Math.abs(target - delayMs) <= FINE_MS) {
      // Delsa jau ties riba (0 arba 3000 ms, pvz. dėl didelės korekcijos): keisti nėra ko, tad ir neskubama.
      logAuto(tag + ' → at limit (' + delayMs + ')');
      recent = grp;
      autoSchedule(AUTO_EVERY_MS);
      updateDiag();
      return;
    }
    if (have >= 2 && W > since) {
      logAuto(tag + ' → change ' + delayMs + '→' + target + ' (' + W + ')');
      showMeas('🎤 auto: ' + delayMs + ' → ' + target + ' ms', true);
      recent = grp;   // kiti matavimai nebetinka
      loose = false;
      rampTo(target);
    } else {
      logAuto(tag + ' → candidate ' + target + ' (' + have + '/2' + (locked ? ' meas.' : '') + '), checking again');
    }
    autoSchedule(AUTO_GAP_MS);
    updateDiag();
  }

  function setAuto(on) {
    autoOn = !!on;
    localStorage.setItem(AUTO_KEY, autoOn ? '1' : '0');
    autoBlocked = false; stableN = 0;
    logAuto(autoOn ? 'auto on' : 'auto off');
    updateLabel();
    if (autoOn) autoSchedule(video && !video.paused ? 1000 : AUTO_START_MS); else { clearTimeout(autoTimer); autoTimer = 0; stopRamp(); }
  }

  function onWaiting() { stalled = true; }
  function onPlaying() {
    var wasStalled = stalled;
    stalled = false;
    if (wasStalled) { stableN = 0; missN = 0; loose = true; logAuto('after stall'); }
    autoSchedule(wasStalled ? AUTO_STALL_MS : startDelay());
  }

  function buildUi() {
    ui = document.createElement('div');
    ui.id = 'xf-delay-ui';
    ui.appendChild(gone(btn('−100', -100)));
    ui.appendChild(btn('−10', -10));
    label = document.createElement('span');
    label.className = 'xf-label';
    // Bakstelėjus ⏱: atidaro, dar kartą – uždaro.
    label.addEventListener('click', function (e) {
      e.stopPropagation(); e.preventDefault();
      if (isOpen()) closeUi(); else openUi();
    }, true);
    ui.appendChild(label);
    ui.appendChild(btn('+10', 10));
    ui.appendChild(gone(btn('+100', 100)));
    var mb = document.createElement('button');
    mb.className = 'xf-step xf-gone';
    mb.textContent = '🎤';
    mb.addEventListener('click', function (e) { e.stopPropagation(); e.preventDefault(); measure(); }, true);
    ui.appendChild(mb);
    autoBtn = document.createElement('button');
    autoBtn.className = 'xf-step';
    autoBtn.textContent = 'Auto';
    autoBtn.addEventListener('click', function (e) {
      e.stopPropagation(); e.preventDefault(); setAuto(!autoOn); openUi();
    }, true);
    ui.appendChild(autoBtn);
    measOut = document.createElement('span');
    measOut.className = 'xf-meas';
    ui.appendChild(measOut);
    diag = document.createElement('span');
    diag.className = 'xf-diag';
    ui.appendChild(diag);
    updateLabel();
  }

  function updateLabel() {
    // „A“ = auto režimas įjungtas, 🎤 = šiuo metu matuojama.
    var corr = -micOffset;
    if (label) label.textContent = '⏱ ' + delayMs + ' ms' + (corr ? ' (' + (corr > 0 ? '+' : '−') + Math.abs(corr) + ')' : '') +
      (autoOn && !autoBlocked ? ' A' : '') + (measuring ? ' 🎤' : '') + (testVid ? ' · TEST' : '') +
      (noDelay === 'drm' ? ' · DRM' : noDelay === 'copy' && delayMs > 0 ? ' · off' : '');
    if (autoBtn) autoBtn.classList.toggle('xf-auto-on', autoOn);
    // ±10 pilki tik ranka paleisto 🎤 metu; tylus auto matavimas jų neblokuoja (paspaudus nutraukiamas).
    if (ui) ui.classList.toggle('xf-busy', !!measuring && !measQuiet);
  }

  // YouTube garso formatas pagal itag (afmt): delsa Android Auto gali skirtis opus (48 kHz) ir aac (44,1 kHz).
  var OPUS = { 249: 1, 250: 1, 251: 1, 600: 1, 774: 1 };
  var AAC = { 139: 1, 140: 1, 141: 1, 256: 1, 258: 1, 325: 1, 327: 1, 328: 1, 380: 1, 599: 1 };
  function audioFmt() {
    try {
      var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
      if (!p) return '';
      var s = p.getVideoStats && p.getVideoStats(), a = s && parseInt(s.afmt, 10);
      if (a) return OPUS[a] ? 'opus' : AAC[a] ? 'aac' : 'itag ' + a;
      var n = p.getStatsForNerds && p.getStatsForNerds(), c = n && n.codecs;
      if (c && c.indexOf('/') >= 0) return c.split('/').pop().trim().replace(/\s*\(.*$/, '').replace('mp4a.40.2', 'aac');
    } catch (e) {}
    return '';
  }

  // Atidarytame valdiklyje: „kadrai užfiksuoti/nupiešti per s · kopijavimo ms · praleisti · garso formatas“
  function updateDiag() {
    if (!diag || !ui || !ui.classList.contains('xf-open')) return;
    var fmt = audioFmt();
    // Why the picture is not delayed, in words (the badge shows only "DRM" / "off")
    var why = noDelay === 'drm' ? 'Protected video (DRM): the picture cannot be delayed' :
      noDelay === 'copy' ? 'This site does not let the picture be copied: not delayed' :
      noDelay === 'mse' ? 'Protected video (DRM): picture delayed in the stream, a change shows in ~20 s' : '';
    if (why) { diag.textContent = why + (CONN ? ' · ' + CONN : ''); return; }
    diag.textContent = (delayMs > 0
      ? stats.capFps + '/' + stats.drawFps + ' fps · ' + stats.bmpMs + ' ms' + (stats.skip ? ' · −' + stats.skip : '')
      : '') + (fmt ? (delayMs > 0 ? ' · ' : '') + fmt : '') + (micOffset ? ' · offs. ' + micOffset : '') +
      (CONN ? ' · ' + CONN : '') +
      // Paskutiniai 🎤 matavimai (žali ms): matosi, kiek jie šokinėja.
      (recent.length ? ' · 🎤 ' + recent.map(function (r) { return r.ms; }).join('/') : '');
  }

  // Canvas ir UI dedami šalia <video> (tame pačiame tėve), kad matytųsi ir fullscreen režime.
  function placeElements() {
    var parent = video && video.parentElement;
    if (!parent) return;
    if (!canvas) {
      canvas = document.createElement('canvas');
      canvas.id = 'xf-delay-canvas';
      ctx = canvas.getContext('2d', { alpha: false });
      canvas.style.display = 'none';     // rodomas tik kai vėlinimas > 0 (start)
    }
    if (canvas.parentElement !== parent || canvas.previousElementSibling !== video) {
      video.insertAdjacentElement('afterend', canvas);
    }
    if (!ui) buildUi();
    // Viso ekrano režime ⏱ dedamas į fullscreen elementą: šalia video (html5-video-container, z=10) jį
    // uždengia YouTube valdiklių sluoksnis (player-controls-background), ir bakstelėjimą gauna YouTube.
    var fs = document.fullscreenElement;
    var uiParent = (fs && fs !== video && fs.contains(video)) ? fs : parent;
    if (ui.parentElement !== uiParent) { uiParent.appendChild(ui); geomKey = ''; }
    ccAttach(parent);
    geomAt = 0;                         // kitame draw() perskaičiuoti vietą
    updateGeom(performance.now());
  }

  // Video vieta: Fermata immersive režime video yra fixed visame ekrane,
  // įprastai canvas/UI dalijasi video offsetParent. Skaitoma kas GEOM_MS, ne kiekviename kadre.
  function updateGeom(now) {
    if (!video || !canvas || now - geomAt < GEOM_MS) return;
    geomAt = now;
    var cs = getComputedStyle(video), g;
    if (cs.position === 'fixed') {
      var b = video.getBoundingClientRect();
      g = { pos: 'fixed', left: b.left, top: b.top, width: b.width, height: b.height };
    } else {
      g = { pos: 'absolute', left: video.offsetLeft, top: video.offsetTop,
        width: video.offsetWidth, height: video.offsetHeight };
    }
    g.cover = cs.objectFit === 'cover';
    // Svetainėje sluoksnis lieka video lygyje (ne virš visko), kad svetainės grotuvo mygtukai būtų matomi.
    if (WEB) canvas.style.setProperty('z-index', cs.zIndex, 'important');
    // Canvas raiška = ekrano pikseliai (devicePixelRatio), bet ne daugiau kaip MAX_W pločio.
    var k = Math.min(window.devicePixelRatio || 1, MAX_W / Math.max(1, g.width));
    g.cw = Math.max(1, Math.round(g.width * k));
    g.ch = Math.max(1, Math.round(g.height * k));
    var key = [g.pos, g.left, g.top, g.width, g.height, g.cw, g.ch, g.cover].join(',');
    geom = g;
    if (key === geomKey) return;
    geomKey = key;
    var s = canvas.style;
    s.setProperty('position', g.pos, 'important');
    s.left = g.left + 'px'; s.top = g.top + 'px';
    s.width = g.width + 'px'; s.height = g.height + 'px';
    if (canvas.width !== g.cw || canvas.height !== g.ch) { canvas.width = g.cw; canvas.height = g.ch; }
    redraw = true;
    // Kadrus fiksuojam ne didesnius, nei jie bus nupiešti.
    capW = Math.max(160, Math.min(MAX_W, g.cw));
    placeUi(g);
  }

  function placeUi(g) {
    if (!ui) return;
    var pos = g.pos, left = g.left, top = g.top, p = ui.parentElement;
    // ⏱ kitame tėve (fullscreen elemente): vieta skaičiuojama nuo jo, ne nuo video offsetParent.
    if (pos !== 'fixed' && p && video && p !== video.parentElement) {
      var vb = video.getBoundingClientRect(), pb = p.getBoundingClientRect();
      left = vb.left - pb.left; top = vb.top - pb.top;
    }
    ui.style.setProperty('position', pos, 'important');
    ui.style.left = (left + 8) + 'px';
    ui.style.top = (top + 8) + 'px';
  }

  function setOpacity(v) {
    if (opacity !== v) { opacity = v; canvas.style.opacity = v; }
  }

  function onFrame(now, meta) {
    if (!capturing || !video) return;
    lastVfcAt = performance.now();
    vfcId = video.requestVideoFrameCallback(onFrame);
    if (delayMs <= 0 || !video.videoWidth) return;
    var nowMs = performance.now();
    // Užstrigęs createImageBitmap (negrįžta) neturi amžinai blokuoti gaudymo
    if (pending >= MAX_PENDING && nowMs - pendingAt > 2000) { pending = 0; pgen++; }
    if (pending >= MAX_PENDING) { st.skip++; return; }   // telefonas nespėja: nekaupiam eilės
    var w = Math.min(video.videoWidth, capW);
    var h = Math.round(video.videoHeight * w / video.videoWidth);
    // Laikas, kada kadras turėjo pasirodyti ekrane (tolygus tempas), o ne kada suveikė callback'as.
    var t = (meta && meta.expectedDisplayTime) || now || nowMs;
    // Naujesnio Android Auto lange laikas gali būti klaidingas: kadras „iš ateities“ užkimšdavo eilę
    // ir visi vėlesni būdavo atmetami (t <= lastT), vaizdas sustingdavo iki prasukimo.
    if (!(t > nowMs - 1000 && t < nowMs + 1000)) t = nowMs;
    var mt = meta ? meta.mediaTime : video.currentTime;
    var g0 = gen, t0 = nowMs, pg = pgen;
    if (pending === 0) pendingAt = nowMs;
    pending++;
    createImageBitmap(video, { resizeWidth: w, resizeHeight: h, resizeQuality: 'low' })
      .then(function (bmp) {
        bmpFail = 0;
        if (pg === pgen) { pending--; pendingAt = performance.now(); }
        st.bmpMs += performance.now() - t0;
        // Pasenęs (po seek) ar ne iš eilės grįžęs kadras: išmetam, kad vaizdas nešokinėtų atgal.
        if (!capturing || g0 !== gen || t <= lastT) { bmp.close(); return; }
        lastT = t;
        st.cap++;
        queue.push({ bmp: bmp, t: t, mt: mt });
        while (queue.length > MAX_FRAMES) queue.shift().bmp.close();
      }, function () {
        if (pg === pgen) { pending--; pendingAt = performance.now(); }
        // Svetainės video kadrų nukopijuoti nepavyksta: vėlinimas šiam video išjungiamas, kad ekranas neliktų juodas.
        if (WEB && !noDelay && ++bmpFail > 60) {
          noDelay = 'copy';
          logAuto('frames cannot be copied: picture not delayed');
          stop();
          updateLabel();
        }
      });
  }

  function tickStats(now) {
    if (now - statsAt < 1000) return;
    var sec = (now - statsAt) / 1000 || 1;
    stats = {
      capFps: Math.round(st.cap / sec), drawFps: Math.round(st.drawn / sec), skip: st.skip,
      bmpMs: st.cap ? Math.round(st.bmpMs / st.cap) : 0
    };
    st = { cap: 0, drawn: 0, skip: 0, bmpMs: 0 };
    statsAt = now;
    updateDiag();
  }

  function draw(now) {
    rafId = requestAnimationFrame(draw);
    if (!video || !canvas) return;
    now = now || performance.now();
    // Grojant kadrų callback'ai nutrūko (be jų vaizdas stovi): registruojam iš naujo
    var pnow = performance.now();
    if (capturing && !video.paused && (video.readyState >= 3) && pnow - lastVfcAt > 1500) {
      try { video.cancelVideoFrameCallback(vfcId); } catch (e) {}
      vfcId = video.requestVideoFrameCallback(onFrame);
      lastVfcAt = pnow;
    }
    tickStats(now);
    updateGeom(now);
    if (!geom) return;
    // Kol nėra nupiešto kadro, canvas permatomas (matosi originalus video / viršelis).
    if (!shown) setOpacity('0');

    // <video controls>: bakstelėjus sluoksnis trumpam paslėptas (žr. peekUntil).
    if (peekUntil) {
      if (performance.now() >= peekUntil) { peekUntil = 0; redraw = true; }
      else setOpacity('0');
    }

    // Paimam naujausią kadrą, kurio amžius >= delayMs; senesnius išmetam.
    var due = performance.now() - delayMs, pick = null;
    while (queue.length && queue[0].t <= due) {
      if (pick) pick.bmp.close();
      pick = queue.shift();
    }
    if (pick) {
      if (shown) shown.bmp.close();
      shown = pick;
    } else if (!(redraw && shown)) {
      return;                        // paliekam paskutinį nupieštą kadrą
    }
    if (peekUntil) return;
    redraw = false;
    pick = shown;
    setOpacity('1');
    st.drawn++;

    var cw = geom.cw, ch = geom.ch, bw = pick.bmp.width, bh = pick.bmp.height;
    var s = geom.cover ? Math.max(cw / bw, ch / bh) : Math.min(cw / bw, ch / bh);
    var dw = bw * s, dh = bh * s;
    if (dw < cw || dh < ch) { ctx.fillStyle = '#000'; ctx.fillRect(0, 0, cw, ch); }
    ctx.drawImage(pick.bmp, (cw - dw) / 2, (ch - dh) / 2, dw, dh);
  }

  // Svetainėse: noDelay – šio video vėlinti negalima (apsaugotas arba kadrų nepavyksta nukopijuoti).
  var noDelay = false, bmpFail = 0, drmLook = 0;
  // <video controls>: naršyklės valdikliai yra paties video viduje ir vėlinimo sluoksnis juos uždengia.
  // Bakstelėjus video sluoksnis kelioms sekundėms paslepiamas (tuo metu vaizdas nevėlinamas).
  var peekUntil = 0;
  if (WEB) {
    document.addEventListener('pointerdown', function (e) {
      if (capturing && video && e.target === video && video.controls) peekUntil = performance.now() + 4000;
    }, true);
  }

  function start() {
    if (capturing || !video || noDelay) return;
    // Apsaugoto (DRM) video kadrų nukopijuoti negalima: sluoksnis būtų juodas. Jei puslapis video
    // krauna per MSE, vaizdą vėlina xf-mse-shift.js, perstumdamas vaizdo takelio laiko žymes.
    // MSE video of a page with DRM is shifted there too (e.g. an advert in the same player): not twice.
    var mse = WEB && window.__xfMse;
    var shifted = mse && mse.on() && /^blob:/.test(video.currentSrc || video.src || '');
    if (WEB && (video.mediaKeys || shifted)) {
      if (shifted) {
        noDelay = 'mse';
        mse.set(delayMs);
        logAuto('protected video: picture delayed by moving the video timestamps');
      } else {
        noDelay = 'drm';
        if (!drmLook) logAuto('protected video: picture not delayed');
        // The player may add its buffers a moment later: look again a few times
        if (drmLook < 5) {
          drmLook++;
          setTimeout(function () { if (noDelay === 'drm' && delayMs > 0) { noDelay = false; start(); } }, 2000);
        }
      }
      updateLabel();
      return;
    }
    capturing = true;
    canvas.style.display = 'block';
    geomAt = 0; geomKey = '';
    lastVfcAt = performance.now();
    vfcId = video.requestVideoFrameCallback(onFrame);
    rafId = requestAnimationFrame(draw);
  }

  function stop() {
    capturing = false;
    if (video && vfcId) video.cancelVideoFrameCallback(vfcId);
    cancelAnimationFrame(rafId);
    flush();
    if (shown) { shown.bmp.close(); shown = null; }
    if (canvas) { canvas.style.display = 'none'; opacity = ''; }
  }

  function onSeek() { flush(); }
  // Po prasukimo delsa galėjo pasikeisti: matuojam iš naujo iškart (kaip po užstrigimo), nelaukdami eilinio matavimo.
  function onSeeked() {
    if (!video || video.paused) return;
    if (!loose) logAuto('after seek');
    stableN = 0; missN = 0; loose = true;
    autoSchedule(AUTO_STALL_MS);
  }

  // Naujas video: seni 🎤 matavimai nebetinka (delsa tarp video skiriasi), renkam iš naujo.
  // Nustatyta delsa lieka, kol nauji matavimai parodys ką kita.
  function newVideo() {
    if (recent.length) logAuto('new video');
    recent = []; stableN = 0; missN = 0; tryN = 0; loose = false; lastOkMeas = null; vidGen++; fastStart = true;
    noDelay = false; bmpFail = 0; drmLook = 0; peekUntil = 0;
    updateDiag();
  }

  // Naujas video tame pačiame elemente: išmetam ir paskutinį rodytą kadrą.
  function onEmptied() {
    flush();
    if (shown) { shown.bmp.close(); shown = null; }
    newVideo();
  }

  var api = {
    get: function () { return delayMs; },
    measure: function (opts) { measure(opts); },
    lastMeasure: function () { return lastMeas; },
    // Programos (Java) mikrofono įrašo rezultatas: {t0, env[], dev, ts} arba {err}
    _mic: function (res) { if (micCb) micCb(res); },
    // Auto režimas: auto(true|false), autoInfo() – būsena ir paskutiniai įvykiai.
    auto: function (on) { if (on != null) setAuto(on); return autoOn; },
    autoInfo: function () {
      return { on: autoOn, blocked: autoBlocked, offset: micOffset, votes: recent.slice(),
        scheduled: !!autoTimer, ramping: !!rampTimer, log: autoLog.slice() };
    },
    // Testui: auto(true) su imituota delsa (ms) vietoj mikrofono; null – tikras mikrofonas.
    _autoFake: function (ms) { autoFake = ms; autoBlocked = false; },
    _autoTick: function () { autoTick(); },
    // Testui: paduoti auto režimui matavimo rezultatą {ms, ok, conf, ratio, parts} be mikrofono.
    _autoResult: function (res, err) { autoResult(res, err); },
    // Testui: pati koreliacija (garsumo kreivės kas 10 ms ir jų pradžios laikai).
    _correlate: function (refE, r0, micE, m0, parts, hint) { return correlate(refE, r0, micE, m0, parts, hint); },
    setOffset: function (v) { setOffset(v); updateDiag(); return micOffset; },
    // Sinchronizacijos testo video (⋮ meniu): syncTest(true|false), grąžina, ar testas rodomas.
    syncTest: function (on) { return syncTest(on); },
    testOn: function () { return !!testVid; },
    // Telefono kameros matavimo rezultatas: garsas vėluoja ms (neigiamas – skuba) → vaizdas vėlinamas tiek pat.
    camCorr: function (ms) {
      ms = Math.max(-500, Math.min(500, Math.round(ms) || 0));
      shiftCorr(ms);
      logAuto('camera ' + ms);
      showMeas('📷 ' + (ms > 0 ? '+' : '') + ms + ' ms', false);
      openUi(8000);
      return delayMs;
    },
    // Pirmo paleidimo vedlio užuomina automobilio ekrane (veikia ir be video): hint(tekstas, ms).
    hint: function (text, ms) {
      var h = document.getElementById('xf-hint');
      if (!h) {
        h = document.createElement('div');
        h.id = 'xf-hint';
        h.style.cssText = 'position:fixed;left:50%;top:12px;transform:translateX(-50%);z-index:2147483647;' +
          'background:rgba(0,0,0,.82);color:#fff;font:600 18px/1.35 sans-serif;padding:10px 18px;' +
          'border-radius:12px;max-width:80vw;text-align:center;pointer-events:none;visibility:visible';
      }
      // Viso ekrano režime matosi tik fullscreen elementas; kitaip – body (⏱ tėvas gali būti paslėptas grotuvas).
      var hp = document.fullscreenElement || document.body;
      if (h.parentElement !== hp) hp.appendChild(h);
      h.textContent = text || '';
      clearTimeout(h._t);
      h._t = setTimeout(function () { h.remove(); }, ms || 15000);
      return true;
    },
    // Programos ⋮ meniu: subtitrai, garso takelis, kartojimas, „Stable volume“.
    ccList: function (lang, done) { ccList(lang, done); },
    ccSet: function (i) { return ccSet(i); },
    auList: function (done) { auList(done); },
    auSet: function (i) { return auSet(i); },
    playerState: function () { return playerState(); },
    setLoop: function (on) { try { ytp().setLoopVideo(!!on); return true; } catch (e) { return false; } },
    setDrc: function (on) { try { ytp().setDrcUserPreference(on ? 1 : 0); return true; } catch (e) { return false; } },
    // Diagnostika: kiek ms rodomas kadras atsilieka nuo grojamo video (turi būti ≈ delayMs)
    lag: function () {
      return shown && video ? Math.round((video.currentTime - shown.mt) * 1000) : null;
    },
    stats: function () {
      return { capFps: stats.capFps, drawFps: stats.drawFps, skip: stats.skip, bmpMs: stats.bmpMs,
        queue: queue.length, pending: pending, capW: capW, audio: audioFmt() };
    },
    set: function (ms) {
      delayMs = Math.max(0, Math.min(3000, Math.round(ms) || 0));
      if (noDelay === 'mse' && window.__xfMse) window.__xfMse.set(delayMs);
      localStorage.setItem(KEY, String(delayMs));
      reportState();
      updateLabel();
      if (delayMs > 0) start(); else stop();
      return delayMs;
    },
    attach: function () {
      var v = activeVideo();
      if (!v) return;
      if (v !== video) {
        stop();
        if (video) {
          video.removeEventListener('seeking', onSeek);
          video.removeEventListener('seeked', onSeeked);
          video.removeEventListener('emptied', onEmptied);
          video.removeEventListener('waiting', onWaiting);
          video.removeEventListener('playing', onPlaying);
        }
        video = v;
        video.addEventListener('seeking', onSeek);
        video.addEventListener('seeked', onSeeked);
        video.addEventListener('emptied', onEmptied);
        video.addEventListener('waiting', onWaiting);
        video.addEventListener('playing', onPlaying);
        stalled = false;
        newVideo();
        if (!video.paused) autoSchedule(startDelay());
      }
      ensureStyle();
      placeElements();
      // Programos nustatymas „Show sync badge“: išjungus ženkliukas nerodomas, vėlinimas ir auto veikia toliau.
      if (ui) ui.classList.toggle('xf-off', window.__xfBadge === false);
      if (WEB && capturing && video.mediaKeys) { stop(); start(); }
      if (delayMs > 0) start();
    }
  };
  window.__xfDelay = api;

  // Telefone YouTube sąraše paleidžia nutildytas peržiūras (inline preview): jos bereikalingai dekoduoja video
  // ir eikvoja bateriją, ypač kai tikras video groja automobilyje. Stabdomos tik nutildytos ir tik ne
  // žiūrėjimo puslapyje: įprastas žiūrėjimas ir mini grotuvas telefone nesikeičia.
  if (CONN === 'phone' && !WEB) {
    document.addEventListener('play', function (e) {
      var v = e.target;
      if (!v || v.tagName !== 'VIDEO' || v === testVid || !v.muted) return;
      var p = location.pathname || '';
      if (p.indexOf('/watch') === 0 || p.indexOf('/shorts') === 0) return;
      try { window.__fermataPauseOk = true; v.pause(); } catch (x) {} finally { window.__fermataPauseOk = false; }
    }, true);
  }

  // YouTube reikšmės jau yra localStorage: programa jas sužino iškart, ne tik po kito pakeitimo.
  if (!WEB && (delayMs || micOffset)) reportState();

  // YouTube yra SPA: video elementas ir fullscreen konteineris keičiasi, todėl periodiškai tikrinam.
  api.attach();
  setInterval(api.attach, 1000);
  window.addEventListener('resize', function () { geomAt = 0; });
  document.addEventListener('fullscreenchange', function () { setTimeout(api.attach, 50); });
})();
