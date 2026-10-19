// Runs on every old.reddit.com page the user views. Reports who the page says
// is signed in, so the app notices logins, logouts and account changes.
(function () {
  var body = document.body;
  var loggedIn = !!(body && body.classList.contains('loggedin'));
  var link = document.querySelector('#header-bottom-right span.user a');
  return JSON.stringify({
    host: location.host,
    username: loggedIn && link ? link.textContent.trim() : null
  });
})();
