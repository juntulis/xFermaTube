// xFermaTube: picture delay for protected (DRM) video in the Web browser on the car screen.
//
// The frames of a protected video cannot be copied, so xf-video-delay.js cannot show them later.
// Instead, the timestamps of the video track are moved later by the delay when the site's player
// adds them to the browser (Media Source Extensions: SourceBuffer.timestampOffset). The browser
// then itself shows every frame that much later than its sound. Nothing is decrypted or copied:
// the timestamps are not encrypted, only the picture and the sound are.
//
// The site's player sees the values it set (offset, buffered ranges, removals), without the shift.
// A new delay applies to the segments loaded after the change (in live TV about 20 s later).
// A larger delay leaves a small hole in the video where it starts: playback steps over it.
//
// Added at document start (FermataWebView), the top page only. Only after the page sets DRM keys
// on a media element (setMediaKeys); other video is delayed by xf-video-delay.js.
// window.__xfMse: on() – shifting this page's video, set(ms) – a new delay, info() – diagnostics.
(function () {
  'use strict';
  if (window.top !== window || window.__xfMse || !window.SourceBuffer || !window.MediaSource) return;
  var P = SourceBuffer.prototype;
  var dTso = Object.getOwnPropertyDescriptor(P, 'timestampOffset');
  var dAws = Object.getOwnPropertyDescriptor(P, 'appendWindowStart');
  var dAwe = Object.getOwnPropertyDescriptor(P, 'appendWindowEnd');
  var dBuf = Object.getOwnPropertyDescriptor(P, 'buffered');
  if (!dTso || !dTso.get || !dTso.set || !dBuf || !dBuf.get) return;
  var eme = false, L = 0, vsbs = [], holes = 0, timer = 0, maxHole = 0;

  function off(sb) { return sb.__xfV ? sb.__xfL : 0; }

  // As the browser does: a value given as text is a number first (and "10" + 0.4 must not be "100.4")
  function num(x) { return (typeof x === 'number') ? x : Number(x); }

  function wrapNum(name, d) {
    if (!d || !d.get || !d.set) return;
    Object.defineProperty(P, name, {
      configurable: true, enumerable: d.enumerable,
      get: function () { var v = d.get.call(this); return isFinite(v) ? v - off(this) : v; },
      set: function (x) {
        var o = off(this);
        if (!o) { d.set.call(this, x); return; }
        x = num(x);
        d.set.call(this, isFinite(x) ? x + o : x);
      }
    });
  }
  wrapNum('timestampOffset', dTso);
  wrapNum('appendWindowStart', dAws);
  wrapNum('appendWindowEnd', dAwe);
  Object.defineProperty(P, 'buffered', {
    configurable: true, enumerable: dBuf.enumerable,
    get: function () {
      var r = dBuf.get.call(this), o = off(this);
      if (!o) return r;
      var s = [], e = [];
      for (var i = 0; i < r.length; i++) { s.push(r.start(i) - o); e.push(r.end(i) - o); }
      return { length: s.length, start: function (i) { return s[i]; }, end: function (i) { return e[i]; } };
    }
  });
  var rm = P.remove;
  P.remove = function (a, b) {
    var o = off(this);
    if (!o) return rm.apply(this, arguments);
    // The ranges buffered before a change of the delay are reported and removed with the new
    // delay: off by the change (a few hundred ms), the player only fetches or drops a bit more.
    return rm.call(this, Math.max(0, num(a) + o), num(b) + o);
  };

  // Moves the offset (and the append window the player set) of the segments that come next
  // from the old delay to the new one.
  function apply(sb) {
    if (sb.__xfL === L) return;
    if (sb.updating) { sb.addEventListener('updateend', function () { apply(sb); }, { once: true }); return; }
    try {
      var d = L - sb.__xfL;
      dTso.set.call(sb, dTso.get.call(sb) + d);
      if (dAws && dAwe) {
        var s = dAws.get.call(sb), e = dAwe.get.call(sb);
        // Start below end at every step: a later window moves its end first, an earlier its start
        if (d > 0) {
          if (isFinite(e)) dAwe.set.call(sb, e + d);
          if (s > 0) dAws.set.call(sb, s + d);
        } else {
          if (s > 0) dAws.set.call(sb, Math.max(0, s + d));
          if (isFinite(e)) dAwe.set.call(sb, e + d);
        }
      }
      if (d > 0) maxHole = Math.max(maxHole, d);
      sb.__xfL = L;
    } catch (e) {
      // Not now (the source is closed or a segment is half read): with the next segment.
      sb.addEventListener('updateend', function () { apply(sb); }, { once: true });
    }
  }

  function applyAll() {
    for (var i = 0; i < vsbs.length; i++) apply(vsbs[i]);
    if (maxHole > 0 && !timer) timer = setInterval(skipHole, 250);
  }

  function seedMs() {
    try {
      var f = window.Fermata && window.Fermata.xfShiftMs;
      var ms = f ? +window.Fermata.xfShiftMs() : -1;
      return (ms >= 0 && ms <= 3000) ? ms : -1;
    } catch (e) { return -1; }
  }

  function drm() {
    if (eme) return;
    eme = true;
    var ms = seedMs();
    if (ms > 0) { L = ms / 1000; applyAll(); }
  }

  var asb = MediaSource.prototype.addSourceBuffer;
  MediaSource.prototype.addSourceBuffer = function (mime) {
    var sb = asb.apply(this, arguments);
    var m = String(mime);
    // Video only: a buffer with the sound in it too would move the sound as well
    if (/^\s*video\//i.test(m) && !/mp4a|opus|vorbis|ac-3|ec-3|flac|mp3|aac/i.test(m)) {
      sb.__xfV = true; sb.__xfL = 0;
      vsbs.push(sb);
      if (vsbs.length > 8) vsbs.shift();
      if (eme) applyAll();
    }
    return sb;
  };
  // DRM in use = keys set on a media element (only asking whether DRM is supported is not enough)
  var smk = HTMLMediaElement.prototype.setMediaKeys;
  if (smk) {
    HTMLMediaElement.prototype.setMediaKeys = function (k) {
      if (k) drm();
      return smk.apply(this, arguments);
    };
  }

  // Where a larger delay starts, the video has a hole of the difference: step over it.
  function skipHole() {
    var v = document.querySelector('video');
    if (!v || v.paused || v.readyState >= 3) return;
    var ct = v.currentTime;
    for (var k = 0; k < vsbs.length; k++) {
      var r;
      try { r = dBuf.get.call(vsbs[k]); } catch (e) { continue; }
      for (var i = 0; i < r.length; i++) {
        var s = r.start(i);
        // Only a hole of at most the largest delay increase (plus a frame or two of slack)
        if (s > ct && s - ct <= maxHole + 0.1) { holes++; v.currentTime = s + 0.02; return; }
      }
    }
  }

  window.__xfMse = {
    on: function () { return eme && vsbs.length > 0; },
    set: function (ms) {
      var nl = Math.max(0, Math.min(3000, +ms || 0)) / 1000;
      if (nl === L) return;
      L = nl;
      applyAll();
    },
    info: function () {
      return { eme: eme, ms: Math.round(L * 1000), sbs: vsbs.length, holes: holes };
    }
  };
})();
