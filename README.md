# Lurk

A personal Reddit client for Android that runs on the Reddit website instead of the API.

- **Your subreddits, automatically.** After you sign in, a hidden "engine" WebView reads your subscriptions from `old.reddit.com/subreddits/mine` and fills the native subreddit bar along the bottom. It resyncs on launch and whenever the app notices you signed in or out.
- **Multiple accounts.** Each account lives in its own WebView profile (separate cookies), so you can switch between them from the account menu without signing out.
- **Readable old Reddit.** Posts and comments are shown in old Reddit with a mobile skin (`assets/skin/skin.css`) that follows your system's light/dark setting. Links to other sites open in a Custom Tab; Reddit links are kept in the app and redirected to old Reddit.

## Build and install

1. Open this folder in Android Studio (Ladybug or newer) and let Gradle sync. If Studio offers to upgrade the Android Gradle Plugin or dependencies, accepting is fine.
2. Plug in your phone with USB debugging on and press **Run**.
   Or build an APK with **Build → Build App Bundle(s) / APK(s) → Build APK(s)** and sideload it.

Requires Android 8.0+. Multiple accounts need Android System WebView 120 or newer (anything updated through the Play Store in the last couple of years). On older WebViews the app works with a single account and says so in the account menu.

## Signing in

Use your Reddit username and password. "Continue with Google/Apple" opens outside the app and won't sign the app in, because those providers block sign-in inside embedded web views. If your account only has Google sign-in, set a Reddit password first at reddit.com/settings (from a normal browser).

## When something breaks

Reddit changes its pages from time to time. Open **⋮ → Engine debug** to see what the hidden engine loaded, its log, and the raw data it extracted.

- **Subreddit bar is empty but you're signed in:** the subscription selectors in `assets/engine/session.js` no longer match. In a desktop browser, open `old.reddit.com/subreddits/mine`, save the page (Ctrl+S, "Web page, HTML only"), and share it along with the Engine debug output.
- **Pages look wrong:** adjust `assets/skin/skin.css`. It's plain CSS against old Reddit's markup; testing changes in a desktop browser with the Stylus extension first is fastest.
- **Signed-in state not detected:** check `assets/engine/whoami.js`, which reads the username from old Reddit's header.

## Layout

| Path | What it does |
| --- | --- |
| `assets/skin/skin.css` | The mobile skin for old Reddit |
| `assets/skin/inject.js` | Injects the skin before each page paints |
| `assets/engine/session.js` | Extracts username + subscriptions (hidden engine) |
| `assets/engine/whoami.js` | Reports who's signed in on each page you view |
| `web/RedditEngine.kt` | The hidden WebView engine: load page, run extractor, return data |
| `web/BrowserController.kt` | The visible WebView, link routing, page-user detection |
| `web/Links.kt` | URL rules: rewrite to old Reddit, keep, or open externally |
| `web/WebProfiles.kt` | Per-account WebView profiles (multi-account) |
| `data/AccountStore.kt` | Saved accounts and cached subscription lists |
| `data/AccountSession.kt` | One active account: its browser, engine and syncing |
| `ui/LurkApp.kt` | The Compose UI |

## Being a good citizen

The engine loads at most a handful of pages per sync, at human pace, using your own signed-in session. Keep it that way: Reddit's terms restrict automated access, and heavy background scraping is the quickest way to get an account flagged.

## Getting the APK from GitHub

Every push to `main` builds the app with GitHub Actions (`.github/workflows/build-apk.yml`).
The newest APK is attached to the **latest** release: open the repo's **Releases** page and download `Lurk-<n>.apk`.
Each run's APK is also kept as an artifact on its **Actions** run page. You can trigger a build manually from **Actions → Build APK → Run workflow**.
