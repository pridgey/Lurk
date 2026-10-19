// Runs in the hidden engine on old.reddit.com/subreddits/mine.
// Returns the signed-in username and the subreddits this account subscribes to.
//
// It tries several places old Reddit lists subscriptions and merges them, and
// reports which selectors matched ("matched") so breakage shows up in Engine debug.
(function () {
  var body = document.body;
  var loggedIn = !!(body && body.classList.contains('loggedin'));
  var userLink = document.querySelector('#header-bottom-right span.user a');
  var username = loggedIn && userLink ? userLink.textContent.trim() : null;

  var skip = { all: 1, popular: 1, friends: 1, mod: 1, random: 1, randnsfw: 1 };
  var seen = {};
  var subs = [];

  function add(href) {
    var m = /\/r\/([A-Za-z0-9_]+)\/?/.exec(href || '');
    if (!m) return;
    var key = m[1].toLowerCase();
    if (skip[key] || seen[key]) return;
    seen[key] = 1;
    subs.push(m[1]);
  }

  var sources = [
    // Sidebar on /subreddits/mine: every subscription, alphabetical, on one page.
    { sel: '.subscription-box a.title', complete: true },
    // The main listing on /subreddits/mine (paged).
    { sel: '#siteTable .thing.subreddit a.title', complete: false },
    // The "my subreddits" dropdown in the old header.
    { sel: '.drop-choices.srdrop a.choice', complete: false }
  ];

  var matched = [];
  var complete = false;
  sources.forEach(function (src) {
    var els = document.querySelectorAll(src.sel);
    if (!els.length) return;
    matched.push(src.sel + ' (' + els.length + ')');
    if (src.complete) complete = true;
    els.forEach(function (a) { add(a.getAttribute('href')); });
  });

  var next = document.querySelector('.nav-buttons .next-button a');

  return JSON.stringify({
    url: location.href,
    username: username,
    subreddits: subs,
    matched: matched,
    complete: complete,
    next: next ? next.href : null
  });
})();
