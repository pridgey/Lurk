// Runs in the video sheet's web view. Finds the page's main video (the largest
// visible <video>, or an iframe that looks like a video player) and marks it so
// the "lurk-iso" class on <html> shows only that, filling the view.
// Returns "found" or "none". Safe to run repeatedly; the app polls while the page loads.
// Toggle afterwards with: window.__lurkIso.set(true | false)
(function () {
  var iso = window.__lurkIso;
  if (!iso) {
    var style = document.createElement('style');
    style.textContent =
      'html.lurk-iso, html.lurk-iso body { background: #000 !important; overflow: hidden !important; }' +
      'html.lurk-iso body * { visibility: hidden !important; }' +
      'html.lurk-iso .lurk-video, html.lurk-iso .lurk-video * { visibility: visible !important; }' +
      // A transform/filter/contain on any parent would trap the "fixed" video inside it.
      'html.lurk-iso .lurk-path { transform: none !important; filter: none !important; perspective: none !important;' +
      '  contain: none !important; will-change: auto !important; backdrop-filter: none !important; }' +
      'html.lurk-iso .lurk-video { position: fixed !important; inset: 0 !important; width: 100vw !important;' +
      '  height: 100vh !important; max-width: none !important; max-height: none !important; margin: 0 !important;' +
      '  z-index: 2147483647 !important; background: #000 !important; object-fit: contain !important; transform: none !important; }' +
      // Player frames keep their shape, centered; <video> letterboxes itself.
      'html.lurk-iso .lurk-video.lurk-frame { height: min(var(--lurk-h), 100vh) !important; margin: auto !important; }';
    (document.head || document.documentElement).appendChild(style);

    iso = window.__lurkIso = {
      el: null,
      playerFrame: /youtube|vimeo|player|embed|video|stream|twitch|tiktok|dailymotion|rumble/i,
      find: function () {
        var best = null, bestArea = 0;
        var els = document.querySelectorAll('video, iframe');
        for (var i = 0; i < els.length; i++) {
          var el = els[i];
          if (el.tagName === 'IFRAME' && !iso.playerFrame.test(el.src || '')) continue;
          var r = el.getBoundingClientRect();
          if (r.width < 120 || r.height < 80) continue; // hidden, tiny, or not laid out yet
          var area = r.width * r.height;
          if (area > bestArea) { best = el; bestArea = area; }
        }
        return best;
      },
      set: function (on) { document.documentElement.classList.toggle('lurk-iso', !!on); }
    };
  }

  if (iso.el && document.contains(iso.el)) return 'found';
  var el = iso.find();
  if (!el) return 'none';
  var marked = document.querySelectorAll('.lurk-video, .lurk-path');
  for (var i = 0; i < marked.length; i++) marked[i].classList.remove('lurk-video', 'lurk-path', 'lurk-frame');
  iso.el = el;
  el.classList.add('lurk-video');
  for (var p = el.parentElement; p; p = p.parentElement) p.classList.add('lurk-path');
  if (el.tagName === 'IFRAME') {
    var r = el.getBoundingClientRect();
    el.classList.add('lurk-frame');
    el.style.setProperty('--lurk-h', (r.height / r.width * 100).toFixed(2) + 'vw');
  }
  if (el.tagName === 'VIDEO') {
    // The page's own controls are hidden along with everything else.
    el.controls = true;
    el.setAttribute('playsinline', '');
    try { var p = el.play(); if (p && p.catch) p.catch(function () {}); } catch (e) {}
  }
  return 'found';
})();
