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
  // Senos (bendros) reikšmės tinka tik AA ryšiams; telefone pradedam nuo 0.
  function stored(base) {
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
  var pending = 0, lastT = 0;
  var geom = null, geomKey = '', geomAt = 0, capW = MAX_W, opacity = '';
  // Diagnostika: per paskutinę sekundę
  var st = { cap: 0, drawn: 0, skip: 0, bmpMs: 0 }, stats = { capFps: 0, drawFps: 0, skip: 0, bmpMs: 0 }, statsAt = 0;

  function activeVideo() {
    return (window.__fermataActiveVideo && window.__fermataActiveVideo()) ||
      document.querySelector('video.html5-main-video') || document.querySelector('video');
  }

  function flush() {
    gen++;
    for (var i = 0; i < queue.length; i++) queue[i].bmp.close();
    queue = [];
    lastT = 0;
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
      '#xf-delay-ui.xf-open .xf-diag{display:inline-block;font:500 13px/1 monospace;opacity:.8;padding:0 6px}' +
      '#xf-delay-ui .xf-label{min-width:84px;text-align:center;padding:0 4px}' +
      '#xf-delay-ui .xf-meas{font:600 15px/1 sans-serif;padding:0 4px}#xf-delay-ui .xf-meas:empty{display:none}' +
      '#xf-delay-ui:not(.xf-open) .xf-meas.xf-quiet{display:none}' +
      '#xf-delay-ui button.xf-auto-on{background:rgba(60,170,90,.85)}';
    document.documentElement.appendChild(s);
  }

  function btn(text, step) {
    var b = document.createElement('button');
    b.className = 'xf-step';
    b.textContent = text;
    b.addEventListener('click', function (e) {
      e.stopPropagation(); e.preventDefault();
      manualSet(delayMs + step); openUi();
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
    autoCand = null; stableN = 0;
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
  function correlate(refE, r0, micE, m0) {
    var maxLag = Math.round(MAX_LAG_MS / BIN_MS), shift = (r0 - m0) / BIN_MS;
    var iStart = Math.max(0, Math.ceil(-shift)), iEnd = Math.min(refE.length - 1, Math.floor(micE.length - 2 - shift - maxLag));
    var N = iEnd - iStart + 1;
    if (N < 200) return { err: 'recording too short' };
    var loud = 0;
    for (var i = 0; i < refE.length; i++) loud = Math.max(loud, refE[i]);
    if (loud < 0.003) return { err: 'video too quiet' };
    var a = prep(refE), b = prep(micE), cs = [];
    for (var lag = 0; lag <= maxLag; lag++) {
      var sab = 0, saa = 0, sbb = 0;
      for (i = iStart; i <= iEnd; i++) {
        var j = i + shift + lag, j0 = Math.floor(j), f = j - j0;
        var x = a[i], y = b[j0] * (1 - f) + b[j0 + 1] * f;
        sab += x * y; saa += x * x; sbb += y * y;
      }
      cs.push(saa && sbb ? sab / Math.sqrt(saa * sbb) : 0);
    }
    var best = 0;
    for (lag = 1; lag <= maxLag; lag++) if (cs[lag] > cs[best]) best = lag;
    var second = 0;
    for (lag = 0; lag <= maxLag; lag++) if (Math.abs(lag - best) > 5 && cs[lag] > second) second = cs[lag];
    var off = 0;
    if (best > 0 && best < maxLag) {
      var y0 = cs[best - 1], y1 = cs[best], y2 = cs[best + 1], d = y0 - 2 * y1 + y2;
      if (d < 0) off = 0.5 * (y0 - y2) / d;
    }
    var conf = cs[best], ratio = second > 0 ? conf / second : 9;
    return { ms: Math.round((best + off) * BIN_MS), conf: Math.round(conf * 100) / 100,
      ratio: Math.round(ratio * 100) / 100, ok: conf >= 0.25 && ratio >= 1.25 };
  }

  // Mikrofoną įrašo programa (Java AudioRecord, be garso fokuso – video nesustoja), naršyklė tik
  // originalą. Abi kreivės turi pirmo taško laiką (epochos ms), pagal jį ir sulyginamos.
  // opts.fakeDelayMs – testui be programos: „mikrofonas“ = originalas, uždelstas, su triukšmu ir
  // prasidedantis vėliau (tikrina ir laikų sulyginimą).
  var JS_XF_MIC = 51, MIC_EXTRA_SEC = 1.5, micCb = null, measGen = 0;

  // opts.auto – auto režimo matavimas (tylus); opts.onDone(res|null, klaida) – kai baigta.
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
      measuring = false; micCb = null; clearTimeout(timer);
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
          if (!quiet) { recent.push({ ms: res.ms, at: Date.now() }); if (recent.length > RECENT_N) recent.shift(); }
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
      var r = correlate(refE, epochOff + r0perf, micRes.env, micRes.t0);
      var dev = micRes.dev || '?';
      if (r.err) { done('🎤 ' + r.err + ' · ' + dev, null, r.err); return; }
      r.mic = dev; r.ts = micRes.ts;
      done('🎤 ' + r.ms + ' ms ' + (r.ok ? '✓' : '?') + ' ' + r.conf + ' · ' + dev, r, null);
    }

    var startEpoch = Date.now(), myGen = ++measGen;
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
  // 🎤 matuoja pats: naujam video po ~10 s (greitas startas), po pauzės ~20 s, po užstrigimo
  // (waiting → playing) ir kas AUTO_EVERY_MS.
  // Vienas matavimas turi ±20–30 ms triukšmo, todėl:
  //  - didelis pokytis (> BIG_MS, pvz. po užstrigimo) taikomas, kai du matavimai iš eilės sutampa (±AGREE_MS);
  //  - smulkus derinimas: paskutinių RECENT_N patikimų matavimų mediana, taisoma jau nuo FINE_MS;
  //  - kol matavimų mažiau nei MIN_FOR_FINE, matuojama dažniau (kas AUTO_CONFIRM_MS).
  // Keičiama pamažu (RAMP_STEP kas 100 ms), kad vaizdas nešokinėtų. Taikoma „🎤 − korekcija“;
  // korekciją išmoksta, kai pataisai ranka.
  var AUTO_KEY = 'xf_auto' + SFX, OFF_KEY = 'xf_mic_offset_ms' + SFX;
  var NO_PERM = 'no microphone permission', NO_APP = 'works only in the app';
  var AUTO_START_MS = 20000, AUTO_STALL_MS = 8000, AUTO_EVERY_MS = 60000, AUTO_AFTER_CHANGE_MS = 30000;
  var AUTO_STABLE_MS = 180000, STABLE_N = 3;   // po STABLE_N „ok“ iš eilės matuojama rečiau
  var CAND_TTL_MS = 120000;                    // tiek galioja didelio pokyčio kandidatas
  // Greitas startas: pirmas naujo video matavimas prasideda po AUTO_FAST_MS, trunka FAST_SEC, o aiškus
  // rezultatas (FAST_CONF, FAST_RATIO) taikomas iškart. Toliau įprasti matavimai jį patikslina mediana.
  var AUTO_FAST_MS = 10000, FAST_SEC = 6, FAST_CONF = 0.45, FAST_RATIO = 1.8;
  var AUTO_CONFIRM_MS = 3000, AUTO_RETRY_MS = 30000, LEARN_MS = 10 * 60000;
  var FINE_MS = 12, BIG_MS = 60, AGREE_MS = 40, RAMP_STEP = 20, RECENT_N = 5, MIN_FOR_FINE = 3;
  var BIG_CONF = 0.35, BIG_RATIO = 1.5;
  var recent = [];       // paskutiniai patikimi 🎤 matavimai {ms, at} (žali, be korekcijos)
  var stableN = 0;       // kiek kartų iš eilės matavimas sutapo su nustatyta delsa
  var vidGen = 0;        // didėja keičiantis video: seno video matavimo rezultatas atmetamas
  var fastStart = true;  // kitas auto matavimas – greitas (naujo video pirmasis)
  function startDelay() { return fastStart ? AUTO_FAST_MS : AUTO_START_MS; }

  function median(a) {
    var s = a.slice().sort(function (x, y) { return x - y; }), n = s.length;
    return n ? (n % 2 ? s[(n - 1) >> 1] : Math.round((s[n / 2 - 1] + s[n / 2]) / 2)) : null;
  }

  // Paskutinių RECENT_N matavimų, ne senesnių nei maxAgeMs, mediana (arba null).
  function recentMedian(maxAgeMs) {
    var now = Date.now(), v = [];
    for (var i = recent.length - 1; i >= 0 && v.length < RECENT_N; i--) {
      if (!maxAgeMs || now - recent[i].at <= maxAgeMs) v.push(recent[i].ms);
    }
    return median(v);
  }
  // Telefone (be AA) auto numatytai išjungtas: ten garsas su vaizdu ir taip sutampa.
  var autoStored = localStorage.getItem(AUTO_KEY);
  var autoOn = autoStored != null ? autoStored !== '0' : CONN !== 'phone';
  var micOffset = parseInt(stored('xf_mic_offset_ms') || '0', 10) || 0;
  var autoTimer = 0, autoCand = null, autoBlocked = false, stalled = false, rampTimer = 0, autoBtn = null;
  var autoFake = null;   // testui naršyklėje: imituojama delsa (ms) vietoj mikrofono
  var autoLog = [];      // paskutiniai auto įvykiai (diagnostikai)

  // Žurnalas saugomas ir localStorage (paskutiniai 40 įrašų, su ryšiu), kad vėliau būtų galima perskaityti.
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
  }

  function setOffset(v) {
    micOffset = Math.max(-500, Math.min(500, Math.round(v)));
    localStorage.setItem(OFF_KEY, String(micOffset));
    logAuto('offset ' + micOffset);
  }

  function stopRamp() { clearInterval(rampTimer); rampTimer = 0; }

  function rampTo(target) {
    stopRamp();
    rampTimer = setInterval(function () {
      var d = target - delayMs;
      if (Math.abs(d) <= RAMP_STEP) { stopRamp(); api.set(target); return; }
      api.set(delayMs + (d > 0 ? RAMP_STEP : -RAMP_STEP));
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
    var fast = fastStart;
    fastStart = false;
    measure({ auto: true, sec: fast ? FAST_SEC : 0, fakeDelayMs: autoFake,
      onDone: function (res, err) { autoResult(res, err, fast); } });
  }

  function autoResult(res, err, fast) {
    if (!autoOn) return;   // išjungta, kol vyko matavimas
    if (err === NO_PERM || err === NO_APP) { autoBlocked = true; logAuto('stopped: ' + err); updateLabel(); return; }
    if (!res || !res.ok) {
      logAuto('unreliable: ' + (err || (res && res.ms + ' ms ' + res.conf)));
      autoSchedule(fast ? AUTO_CONFIRM_MS : AUTO_RETRY_MS);   // po greito starto iškart įprastas matavimas
      return;
    }
    var clamp = function (v) { return Math.max(0, Math.min(3000, Math.round(v))); };
    var target = clamp(res.ms - micOffset);
    var tag = '🎤 ' + res.ms + ' (' + res.conf + (res.ts === false ? ', no ts' : '') + ')';

    // Greitas startas: aiškus pirmas matavimas taikomas iškart, nelaukiant antro ar trečio.
    // Neaiškus keliauja įprastu keliu (kandidatas arba rinkimas medianai).
    if (fast && res.conf >= FAST_CONF && res.ratio >= FAST_RATIO) {
      autoCand = null; stableN = 0;
      recent.push({ ms: res.ms, at: Date.now() });
      if (recent.length > RECENT_N) recent.shift();
      if (Math.abs(target - delayMs) > FINE_MS) {
        logAuto(tag + ' → fast start ' + delayMs + '→' + target);
        showMeas('🎤 auto: ' + delayMs + ' → ' + target + ' ms', true);
        rampTo(target);
      } else {
        logAuto(tag + ' → fast start ok (' + delayMs + ')');
      }
      autoSchedule(AUTO_CONFIRM_MS);
      updateDiag();
      return;
    }

    // Didelis pokytis: ar ne vienkartinė klaida? Reikia dviejų sutampančių ir aiškių (BIG_CONF, BIG_RATIO)
    // matavimų iš eilės: ritmiškoje muzikoje koreliacija gali pagauti ne tą smailę.
    if (Math.abs(target - delayMs) > BIG_MS) {
      stableN = 0;
      if (res.conf < BIG_CONF || res.ratio < BIG_RATIO) {
        logAuto(tag + ' → too unclear for a big change (' + res.ratio + ')');
        autoSchedule(AUTO_CONFIRM_MS);
        return;
      }
      if (autoCand != null && Date.now() - autoCand.meas.at <= CAND_TTL_MS &&
          Math.abs(autoCand.target - target) <= AGREE_MS) {
        var t = clamp((autoCand.target + target) / 2);
        logAuto(tag + ' → change ' + delayMs + '→' + t);
        showMeas('🎤 auto: ' + delayMs + ' → ' + t + ' ms', true);
        recent = [autoCand.meas, { ms: res.ms, at: Date.now() }];   // seni matavimai nebetinka
        autoCand = null;
        rampTo(t);
        autoSchedule(AUTO_CONFIRM_MS);
        return;
      }
      logAuto(tag + ' → candidate ' + target + ', checking again');
      autoCand = { target: target, meas: { ms: res.ms, at: Date.now() } };
      autoSchedule(AUTO_CONFIRM_MS);
      return;
    }

    // Smulkus derinimas pagal medianą.
    autoCand = null;
    recent.push({ ms: res.ms, at: Date.now() });
    if (recent.length > RECENT_N) recent.shift();
    if (recent.length < MIN_FOR_FINE) {
      logAuto(tag + ' → collecting (' + recent.length + '/' + MIN_FOR_FINE + ')');
      autoSchedule(AUTO_CONFIRM_MS);
      updateDiag();
      return;
    }
    var med = clamp(recentMedian() - micOffset);
    if (Math.abs(med - delayMs) > FINE_MS) {
      logAuto(tag + ' → adjust ' + delayMs + '→' + med + ' (median)');
      showMeas('🎤 auto: ' + delayMs + ' → ' + med + ' ms', true);
      stableN = 0;
      rampTo(med);
      autoSchedule(AUTO_AFTER_CHANGE_MS);
    } else {
      stableN++;
      logAuto(tag + ' → ok (' + delayMs + ', median ' + med + ')');
      autoSchedule(stableN >= STABLE_N ? AUTO_STABLE_MS : AUTO_EVERY_MS);
    }
    updateDiag();
  }

  function setAuto(on) {
    autoOn = !!on;
    localStorage.setItem(AUTO_KEY, autoOn ? '1' : '0');
    autoBlocked = false; autoCand = null; stableN = 0;
    logAuto(autoOn ? 'auto on' : 'auto off');
    updateLabel();
    if (autoOn) autoSchedule(video && !video.paused ? 1000 : AUTO_START_MS); else { clearTimeout(autoTimer); autoTimer = 0; stopRamp(); }
  }

  function onWaiting() { stalled = true; }
  function onPlaying() {
    var wasStalled = stalled;
    stalled = false;
    autoCand = null;
    if (wasStalled) stableN = 0;
    if (wasStalled) logAuto('after stall');
    autoSchedule(wasStalled ? AUTO_STALL_MS : startDelay());
  }

  function buildUi() {
    ui = document.createElement('div');
    ui.id = 'xf-delay-ui';
    ui.appendChild(btn('−100', -100));
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
    ui.appendChild(btn('+100', 100));
    var mb = document.createElement('button');
    mb.className = 'xf-step';
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
    if (label) label.textContent = '⏱ ' + delayMs + ' ms' + (autoOn && !autoBlocked ? ' A' : '') + (measuring ? ' 🎤' : '');
    if (autoBtn) autoBtn.classList.toggle('xf-auto-on', autoOn);
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
    if (ui.parentElement !== parent) parent.appendChild(ui);
    geomAt = 0;                          // kitame draw() perskaičiuoti vietą
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
    if (ui) {
      ui.style.setProperty('position', g.pos, 'important');
      ui.style.left = (g.left + 8) + 'px';
      ui.style.top = (g.top + 8) + 'px';
    }
  }

  function setOpacity(v) {
    if (opacity !== v) { opacity = v; canvas.style.opacity = v; }
  }

  function onFrame(now, meta) {
    if (!capturing || !video) return;
    vfcId = video.requestVideoFrameCallback(onFrame);
    if (delayMs <= 0 || !video.videoWidth) return;
    if (pending >= MAX_PENDING) { st.skip++; return; }   // telefonas nespėja: nekaupiam eilės
    var w = Math.min(video.videoWidth, capW);
    var h = Math.round(video.videoHeight * w / video.videoWidth);
    // Laikas, kada kadras turėjo pasirodyti ekrane (tolygus tempas), o ne kada suveikė callback'as.
    var t = (meta && meta.expectedDisplayTime) || now || performance.now();
    var mt = meta ? meta.mediaTime : video.currentTime;
    var g0 = gen, t0 = performance.now();
    pending++;
    createImageBitmap(video, { resizeWidth: w, resizeHeight: h, resizeQuality: 'low' })
      .then(function (bmp) {
        pending--;
        st.bmpMs += performance.now() - t0;
        // Pasenęs (po seek) ar ne iš eilės grįžęs kadras: išmetam, kad vaizdas nešokinėtų atgal.
        if (!capturing || g0 !== gen || t <= lastT) { bmp.close(); return; }
        lastT = t;
        st.cap++;
        queue.push({ bmp: bmp, t: t, mt: mt });
        while (queue.length > MAX_FRAMES) queue.shift().bmp.close();
      }, function () { pending--; });
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
    tickStats(now);
    updateGeom(now);
    if (!geom) return;
    // Kol nėra nupiešto kadro, canvas permatomas (matosi originalus video / viršelis).
    if (!shown) setOpacity('0');

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

  function start() {
    if (capturing || !video) return;
    capturing = true;
    canvas.style.display = 'block';
    geomAt = 0; geomKey = '';
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

  // Naujas video: seni 🎤 matavimai nebetinka (delsa tarp video skiriasi), renkam iš naujo.
  // Nustatyta delsa lieka, kol nauji matavimai parodys ką kita.
  function newVideo() {
    if (recent.length) logAuto('new video');
    recent = []; autoCand = null; stableN = 0; lastOkMeas = null; vidGen++; fastStart = true;
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
      return { on: autoOn, blocked: autoBlocked, offset: micOffset, cand: autoCand,
        scheduled: !!autoTimer, ramping: !!rampTimer, log: autoLog.slice() };
    },
    // Testui: auto(true) su imituota delsa (ms) vietoj mikrofono; null – tikras mikrofonas.
    _autoFake: function (ms) { autoFake = ms; autoBlocked = false; },
    _autoTick: function () { autoTick(); },
    // Testui: paduoti auto režimui matavimo rezultatą {ms, ok, conf, ratio} be mikrofono.
    _autoResult: function (res, err, fast) { autoResult(res, err, fast); },
    setOffset: function (v) { setOffset(v); updateDiag(); return micOffset; },
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
      localStorage.setItem(KEY, String(delayMs));
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
          video.removeEventListener('emptied', onEmptied);
          video.removeEventListener('waiting', onWaiting);
          video.removeEventListener('playing', onPlaying);
        }
        video = v;
        video.addEventListener('seeking', onSeek);
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
      if (delayMs > 0) start();
    }
  };
  window.__xfDelay = api;

  // YouTube yra SPA: video elementas ir fullscreen konteineris keičiasi, todėl periodiškai tikrinam.
  api.attach();
  setInterval(api.attach, 1000);
  window.addEventListener('resize', function () { geomAt = 0; });
  document.addEventListener('fullscreenchange', function () { setTimeout(api.attach, 50); });
})();
