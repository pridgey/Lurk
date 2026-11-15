// Lurk's reader view. Parses an article's HTML (as text, so none of its scripts run),
// finds the article with Readability, and returns it as simple blocks for Lurk to draw:
// headings, paragraphs, quotes, list items, code, images and rules. Inline markup is
// cut down to links, bold, italics, code and line breaks.
function lurkRead(html, pageUrl) {
  var doc = new DOMParser().parseFromString(html, 'text/html');
  var base = doc.createElement('base');
  base.setAttribute('href', pageUrl);
  if (doc.head) doc.head.insertBefore(base, doc.head.firstChild);

  var lang = (doc.documentElement && doc.documentElement.getAttribute('lang')) || null;
  var article = new Readability(doc, { charThreshold: 400, keepClasses: false }).parse();
  if (!article || !article.content) return JSON.stringify({ ok: false });

  var root = new DOMParser().parseFromString(article.content, 'text/html').body;
  var blocks = [];
  var seenImages = {};

  function abs(url) {
    if (!url) return null;
    try { return new URL(url, pageUrl).href; } catch (e) { return null; }
  }

  function esc(text) {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  // The best source for an image: its srcset's widest, else src, else a lazy-load attribute.
  function imageSrc(img) {
    var set = img.getAttribute('srcset') || img.getAttribute('data-srcset');
    if (set) {
      var best = null, bestW = 0;
      set.split(',').forEach(function (part) {
        var bits = part.trim().split(/\s+/);
        var w = parseInt((bits[1] || '').replace(/[^0-9]/g, ''), 10) || 1;
        if (bits[0] && w >= bestW) { best = bits[0]; bestW = w; }
      });
      if (best) return abs(best);
    }
    return abs(img.getAttribute('src') || img.getAttribute('data-src') || img.getAttribute('data-original'));
  }

  function addImage(img, caption) {
    var src = imageSrc(img);
    if (!src || /^data:/.test(src) || seenImages[src]) return;
    var w = parseInt(img.getAttribute('width'), 10) || 0;
    var h = parseInt(img.getAttribute('height'), 10) || 0;
    // Tracking pixels and spacers.
    if ((w && w < 40) || (h && h < 40)) return;
    seenImages[src] = true;
    blocks.push({ t: 'img', src: src, cap: caption || img.getAttribute('alt') || null, w: w, h: h });
  }

  // Inline content as cut-down HTML; images found along the way are collected for after.
  function inline(node, images) {
    var out = '';
    node.childNodes.forEach(function (child) {
      if (child.nodeType === 3) { out += esc(child.textContent.replace(/\s+/g, ' ')); return; }
      if (child.nodeType !== 1) return;
      var tag = child.tagName;
      if (tag === 'IMG') { images.push(child); return; }
      if (tag === 'PICTURE') { var pi = child.querySelector('img'); if (pi) images.push(pi); return; }
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT' || tag === 'SVG' || tag === 'IFRAME' || tag === 'BUTTON') return;
      if (tag === 'BR') { out += '<br>'; return; }
      var inner = inline(child, images);
      if (!inner.trim()) return;
      if (tag === 'A') {
        var href = abs(child.getAttribute('href'));
        out += href && !/^javascript:/i.test(href) ? '<a href="' + href.replace(/"/g, '&quot;') + '">' + inner + '</a>' : inner;
      } else if (tag === 'B' || tag === 'STRONG') out += '<b>' + inner + '</b>';
      else if (tag === 'I' || tag === 'EM' || tag === 'CITE') out += '<i>' + inner + '</i>';
      else if (tag === 'CODE' || tag === 'KBD' || tag === 'SAMP') out += '<tt>' + inner + '</tt>';
      else if (tag === 'SUP') out += '<sup>' + inner + '</sup>';
      else if (tag === 'SUB') out += '<sub>' + inner + '</sub>';
      else if (tag === 'S' || tag === 'DEL' || tag === 'STRIKE') out += '<s>' + inner + '</s>';
      else out += inner;
    });
    return out;
  }

  function clean(htmlText) {
    return htmlText.replace(/^(\s|<br>)+|(\s|<br>)+$/g, '').replace(/\s{2,}/g, ' ');
  }

  function para(type, node, extra) {
    var images = [];
    var text = clean(inline(node, images));
    if (text.replace(/<[^>]*>/g, '').trim()) {
      var block = { t: type, html: text };
      for (var k in extra || {}) block[k] = extra[k];
      blocks.push(block);
    }
    images.forEach(function (img) { addImage(img); });
  }

  var BLOCK = /^(P|H[1-6]|UL|OL|BLOCKQUOTE|PRE|FIGURE|PICTURE|IMG|HR|TABLE|DIV|SECTION|ARTICLE|HEADER|FOOTER|MAIN|ASIDE|DL|DETAILS|SUMMARY|LI|DD|DT|ADDRESS|CENTER)$/;

  function list(el, ordered, depth) {
    var n = 0;
    el.childNodes.forEach(function (li) {
      if (li.nodeType !== 1 || li.tagName !== 'LI') return;
      n++;
      var holder = li.cloneNode(true);
      var nested = [];
      holder.querySelectorAll(':scope > ul, :scope > ol').forEach(function (sub) { nested.push(sub); sub.remove(); });
      para('li', holder, { o: ordered, n: n, d: depth });
      nested.forEach(function (sub) { list(sub, sub.tagName === 'OL', depth + 1); });
    });
  }

  function walk(el) {
    var run = el.ownerDocument.createElement('span');
    function flush() {
      if (run.childNodes.length) { para('p', run); run = el.ownerDocument.createElement('span'); }
    }
    Array.prototype.slice.call(el.childNodes).forEach(function (child) {
      if (child.nodeType === 3) { if (child.textContent.trim()) run.appendChild(child.cloneNode()); return; }
      if (child.nodeType !== 1) return;
      var tag = child.tagName;
      if (!BLOCK.test(tag)) { run.appendChild(child.cloneNode(true)); return; }
      flush();
      if (/^H[1-6]$/.test(tag)) para('h', child, { l: parseInt(tag.charAt(1), 10) });
      else if (tag === 'P' || tag === 'DD' || tag === 'DT' || tag === 'SUMMARY' || tag === 'ADDRESS') para('p', child);
      else if (tag === 'UL' || tag === 'OL') list(child, tag === 'OL', 0);
      else if (tag === 'BLOCKQUOTE') {
        var parts = [];
        var qImages = [];
        var paras = child.querySelectorAll('p');
        if (paras.length) paras.forEach(function (p) { var t = clean(inline(p, qImages)); if (t) parts.push(t); });
        else { var t = clean(inline(child, qImages)); if (t) parts.push(t); }
        if (parts.length) blocks.push({ t: 'q', html: parts.join('<br><br>') });
      } else if (tag === 'PRE') {
        var code = child.textContent.replace(/\s+$/, '');
        if (code.trim()) blocks.push({ t: 'pre', text: code });
      } else if (tag === 'FIGURE') {
        var cap = child.querySelector('figcaption');
        var capText = cap ? cap.textContent.replace(/\s+/g, ' ').trim() : null;
        var imgs = child.querySelectorAll('img');
        if (imgs.length) imgs.forEach(function (img, i) { addImage(img, i === imgs.length - 1 ? capText : null); });
        else if (child.querySelector('pre')) walk(child);
      } else if (tag === 'PICTURE') { var p2 = child.querySelector('img'); if (p2) addImage(p2); }
      else if (tag === 'IMG') addImage(child);
      else if (tag === 'HR') { if (blocks.length && blocks[blocks.length - 1].t !== 'hr') blocks.push({ t: 'hr' }); }
      else if (tag === 'TABLE') {
        child.querySelectorAll('tr').forEach(function (tr) {
          var cells = [];
          tr.querySelectorAll('th, td').forEach(function (td) { var t = clean(inline(td, [])); if (t) cells.push(t); });
          if (cells.length) blocks.push({ t: 'p', html: cells.join(' · ') });
        });
      } else walk(child);
    });
    flush();
  }

  walk(root);

  // The article's own title often repeats as its first heading.
  var title = (article.title || '').trim();
  if (blocks.length && blocks[0].t === 'h' && blocks[0].html.replace(/<[^>]*>/g, '').trim() === title) blocks.shift();

  var words = (article.textContent || '').trim().split(/\s+/).length;
  return JSON.stringify({
    ok: true,
    title: title || null,
    byline: (article.byline || '').trim() || null,
    site: (article.siteName || '').trim() || null,
    published: article.publishedTime || null,
    excerpt: (article.excerpt || '').trim() || null,
    lang: article.lang || lang,
    words: words,
    blocks: blocks,
  });
}
