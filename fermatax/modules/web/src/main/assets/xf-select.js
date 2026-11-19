// xFermaTube: drop-down lists (<select>) on the car screen.
// Android shows the list of a <select> in a window of its own, and on the car screen the app
// cannot open such windows: a tap on a list did nothing. Here the list is shown inside the page.
// Injected by the app into pages of the Web browser on the car screen only. Idempotent.
(function () {
  if (window.__xfSelect) return;
  window.__xfSelect = true;

  var open = null;   // {select, root}

  function style() {
    if (document.getElementById('xf-select-style')) return;
    var s = document.createElement('style');
    s.id = 'xf-select-style';
    s.textContent =
      '#xf-select,#xf-select *{visibility:visible!important;box-sizing:border-box}' +
      '#xf-select{position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;background:rgba(0,0,0,.55);' +
      'display:flex;align-items:center;justify-content:center;font:500 20px/1.3 sans-serif}' +
      '#xf-select .xf-panel{background:#202124;color:#fff;border-radius:16px;min-width:280px;max-width:80vw;' +
      'max-height:86vh;display:flex;flex-direction:column;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.5)}' +
      '#xf-select .xf-list{overflow-y:auto;-webkit-overflow-scrolling:touch;padding:6px 0}' +
      '#xf-select .xf-opt{padding:14px 22px;min-height:56px;cursor:pointer;white-space:nowrap;' +
      'overflow:hidden;text-overflow:ellipsis}' +
      '#xf-select .xf-opt.xf-sel{background:rgba(138,180,248,.28)}' +
      '#xf-select .xf-opt.xf-dis{opacity:.4;pointer-events:none}' +
      '#xf-select .xf-group{padding:10px 22px 4px;font-size:15px;opacity:.7}' +
      '#xf-select .xf-bar{display:flex;justify-content:flex-end;gap:8px;padding:8px 12px;border-top:1px solid rgba(255,255,255,.15)}' +
      '#xf-select .xf-btn{padding:12px 22px;border-radius:24px;background:rgba(255,255,255,.14);cursor:pointer}';
    document.documentElement.appendChild(s);
  }

  function fire(sel) {
    sel.dispatchEvent(new Event('input', { bubbles: true }));
    sel.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function close() {
    if (!open) return;
    var o = open;
    open = null;
    o.root.remove();
  }

  function show(sel) {
    close();
    style();
    var multi = sel.multiple;
    var root = document.createElement('div');
    root.id = 'xf-select';
    var panel = document.createElement('div');
    panel.className = 'xf-panel';
    var list = document.createElement('div');
    list.className = 'xf-list';
    var first = null;

    function addOpt(opt) {
      var d = document.createElement('div');
      d.className = 'xf-opt' + (opt.selected ? ' xf-sel' : '') +
        ((opt.disabled || (opt.parentElement && opt.parentElement.disabled)) ? ' xf-dis' : '');
      d.textContent = (opt.label || opt.text || '').trim() || ' ';
      d.addEventListener('click', function (e) {
        e.stopPropagation(); e.preventDefault();
        if (multi) {
          opt.selected = !opt.selected;
          d.classList.toggle('xf-sel', opt.selected);
          fire(sel);
          return;
        }
        var changed = !opt.selected;
        opt.selected = true;
        close();
        if (changed) fire(sel);
      });
      if (opt.selected && !first) first = d;
      list.appendChild(d);
    }

    for (var c = sel.firstElementChild; c; c = c.nextElementSibling) {
      if (c.tagName === 'OPTION') {
        if (!c.hidden) addOpt(c);
      } else if (c.tagName === 'OPTGROUP') {
        var g = document.createElement('div');
        g.className = 'xf-group';
        g.textContent = c.label || '';
        list.appendChild(g);
        for (var o = c.firstElementChild; o; o = o.nextElementSibling) {
          if (o.tagName === 'OPTION' && !o.hidden) addOpt(o);
        }
      }
    }
    panel.appendChild(list);
    if (multi) {
      var bar = document.createElement('div');
      bar.className = 'xf-bar';
      var done = document.createElement('div');
      done.className = 'xf-btn';
      done.textContent = 'OK';
      done.addEventListener('click', function (e) { e.stopPropagation(); e.preventDefault(); close(); });
      bar.appendChild(done);
      panel.appendChild(bar);
    }
    root.appendChild(panel);
    // A tap next to the list closes it without a change
    root.addEventListener('click', function (e) {
      if (e.target === root) { e.stopPropagation(); e.preventDefault(); close(); }
    });
    (document.fullscreenElement || document.body || document.documentElement).appendChild(root);
    open = { select: sel, root: root };
    if (first) first.scrollIntoView({ block: 'center' });
  }

  function target(e) {
    var t = e.target;
    var sel = t && t.closest ? t.closest('select') : null;
    if (!sel || sel.disabled) return null;
    // A list shown as a box (size > 1) works by itself
    if (!sel.multiple && sel.size > 1) return null;
    return sel;
  }

  // The system list is not opened (it would not show anyway); the page's own list instead.
  document.addEventListener('mousedown', function (e) {
    if (target(e)) e.preventDefault();
  }, true);
  document.addEventListener('click', function (e) {
    var sel = target(e);
    if (!sel) return;
    e.preventDefault(); e.stopPropagation();
    try { sel.focus({ preventScroll: true }); } catch (x) {}
    show(sel);
  }, true);
  // Keyboard / rotary controller: Enter or Space on a focused list
  document.addEventListener('keydown', function (e) {
    if (open && e.key === 'Escape') { e.preventDefault(); close(); return; }
    if ((e.key !== 'Enter' && e.key !== ' ') || open) return;
    var sel = target(e);
    if (!sel) return;
    e.preventDefault(); e.stopPropagation();
    show(sel);
  }, true);
  window.addEventListener('pagehide', close);
})();
