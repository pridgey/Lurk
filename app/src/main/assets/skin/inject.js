// Injects skin.css into old.reddit.com pages. Runs at document start where the
// WebView supports it, and again on page finish as a fallback (it's idempotent).
// __LURK_CSS__ is replaced with the contents of skin.css by Scripts.kt.
(function () {
  if (window.__lurkSkin) return;
  // Old Reddit's login page is new-style Reddit; the skin would only get in its way.
  if (/^\/(login|register|account)/.test(location.pathname)) return;
  var css = __LURK_CSS__;

  function apply() {
    var root = document.head || document.documentElement;
    if (!root) return false;
    if (!document.getElementById('lurk-skin')) {
      var style = document.createElement('style');
      style.id = 'lurk-skin';
      style.textContent = css;
      root.appendChild(style);
    }
    window.__lurkSkin = true;
    return true;
  }

  if (!apply()) {
    document.addEventListener('readystatechange', apply);
  }

  // Move our stylesheet to the end of <head> once it exists, so it comes after Reddit's own.
  document.addEventListener('DOMContentLoaded', function () {
    var s = document.getElementById('lurk-skin');
    if (s && document.head) document.head.appendChild(s);
  });
})();
