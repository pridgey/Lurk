# Lurk

A personal Reddit client for Android that runs on the Reddit website instead of the API.

- **Native screens, fed by a hidden browser.** Feeds, posts and comment threads are native Compose screens. Their data comes from a hidden "engine" WebView that asks Reddit for the JSON version of each page (the same URL with `.json` on the end) using your own signed-in cookies. Votes and saves go through the engine the same way the old Reddit site sends them.
- **Old Reddit first, www when it has to.** The engine starts on old.reddit.com. Old Reddit now sends some accounts to its login page (`/login?reason=lor2`) even when they're signed in; when that happens the app retries on www.reddit.com and uses it from then on, for data and web pages alike.
- **Your subreddits, automatically.** The engine reads your username (`/api/me.json`) and subscriptions (`/subreddits/mine/subscriber.json`) and fills the subreddit bar along the bottom. It resyncs on launch, after you sign in, and whenever the app notices you signed in or out.
- **Multiple accounts.** Each account lives in its own WebView profile (separate cookies), so you can switch between them from the account menu without signing out.
- **Videos.** Reddit-hosted videos, and GIF/Imgur clips Reddit has converted, play in a full-screen player (Media3) with sound; GIF-style clips loop silently. Videos from other sites (YouTube, Vimeo, Twitch clips, ...) play full screen in that site's own embedded player; anything without an embed opens in a bottom sheet that shows just the page's video (or the whole page, if no video is found). Links to video pages on YouTube, Vimeo, Twitch and the like open in the same sheet; links straight to video files (.mp4, Imgur .gifv) play in the native player.
- **Web fallback.** Sign-in, the inbox, user pages and anything else without a native screen open in a visible WebView showing old Reddit with a mobile skin (`assets/skin/skin.css`). **⋮ → View on web** shows any screen there too, which is also how you reply for now. Links to other sites open in a Custom Tab.

## Build and install

1. Open this folder in Android Studio (Ladybug or newer) and let Gradle sync. If Studio offers to upgrade the Android Gradle Plugin or dependencies, accepting is fine.
2. Plug in your phone with USB debugging on and press **Run**.
   Or build an APK with **Build → Build App Bundle(s) / APK(s) → Build APK(s)** and sideload it.

Requires Android 8.0+. Multiple accounts need Android System WebView 120 or newer (anything updated through the Play Store in the last couple of years). On older WebViews the app works with a single account and says so in the account menu.

## Signing in

Use your Reddit username and password. Reddit may refuse to show anything until you sign in. "Continue with Google/Apple" opens outside the app and won't sign the app in, because those providers block sign-in inside embedded web views. If your account only has Google sign-in, set a Reddit password first at reddit.com/settings (from a normal browser).

## When something breaks

Reddit changes its pages from time to time. Open **⋮ → Engine debug** to see the hidden engine's log and the start of the last response. **Copy** puts all of it on the clipboard. Each sync logs which cookies the account has (names only) and whether Reddit's login cookie is there.

- **Feeds or threads show an error:** Engine debug lists each request the engine made, its HTTP status, and the start of the last response. Reddit's JSON format rarely changes; the parsing lives in `data/Models.kt` and is covered by `app/src/test/.../ParseTest.kt`.
- **Signed in but the app says you aren't:** check the sync lines in Engine debug. "Login cookie: missing" means the sign-in didn't stick; "present" with "Signed in as: nobody" means Reddit isn't accepting it on either old Reddit or www.
- **Pages look wrong:** adjust `assets/skin/skin.css`. It's plain CSS against old Reddit's markup; testing changes in a desktop browser with the Stylus extension first is fastest.

## Layout

| Path | What it does |
| --- | --- |
| `assets/skin/skin.css` | The mobile skin for old Reddit |
| `assets/skin/inject.js` | Injects the skin before each page paints |
| `assets/engine/isolate.js` | Finds a page's main video and shows only that (video sheet) |
| `assets/engine/whoami.js` | Reports who's signed in on each old Reddit page you view |
| `web/RedditEngine.kt` | The hidden WebView engine: makes signed-in requests and passes back the responses |
| `web/BrowserController.kt` | The visible fallback WebView, link routing, page-user detection |
| `web/Links.kt` | URL rules: native screen, rewrite to old Reddit, keep, or open externally |
| `data/RedditApi.kt` | Feeds, threads, more comments, votes, saves, username and subscriptions, via the engine; old-to-www fallback |
| `data/Models.kt` | Posts and comments, parsed from Reddit's JSON |
| `data/Screens.kt` | The back stack's screens and their loading state; vote/save state |
| `web/WebProfiles.kt` | Per-account WebView profiles (multi-account) |
| `data/AccountStore.kt` | Saved accounts and cached subscription lists |
| `data/AccountSession.kt` | One active account: its browser, engine and syncing |
| `ui/LurkApp.kt` | The app scaffold: top bar, menus, subreddit bar, screen switching |
| `ui/Screens.kt`, `ui/PostViews.kt` | Feed, thread, comments and image viewer |
| `ui/VideoPlayer.kt`, `ui/EmbedPlayer.kt`, `ui/VideoSheet.kt` | Native player, embedded players, and the video-page sheet |
| `ui/Theme.kt`, `ui/LurkIcons.kt` | Lurk's colors, type and shapes; its line icons |

To look at the screens without a phone, run `./gradlew testDebugUnitTest --tests '*ScreenshotTest'`. It renders the feed, a thread and the loading state with sample data, in light and dark, to `app/build/screenshots/`.

## Being a good citizen

The engine only requests what you're looking at (a page of 25 posts as you scroll, one thread when you open it), waits at least 0.8 s between requests, and uses your own signed-in session. Keep it that way: Reddit's terms restrict automated access, and heavy background scraping is the quickest way to get an account flagged.

## Getting the APK from GitHub

Every push to `main` builds the app with GitHub Actions (`.github/workflows/build-apk.yml`).
The newest APK is attached to the **latest** release: open the repo's **Releases** page and download `Lurk-<n>.apk`.
Each run's APK is also kept as an artifact on its **Actions** run page. You can trigger a build manually from **Actions → Build APK → Run workflow**.
