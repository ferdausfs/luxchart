# AGENT_LOG — LuxChart

Append-only session log. **Newest entry first. Never delete or rewrite past entries.**

Fixed entry template: session date, agent/tool, feature, what changed, touched
files, commit hash, pending items, notes for next agent.

SECURITY: never record tokens, passwords, keystore contents, base64 blobs, or
secret values in this file. Secret *names* and file *names* are allowed.

---

## Hotfix 1.6.1 — 2026-10-14 — launch-crash fix: IO-thread WebView access + Tier-2 hardening + crash visibility (versionName 1.6.1)

- **Agent/Tool:** GLM (Super Z CLI) acting as senior Android engineer
- **Feature:** HOTFIX 1.6.1 — app crashed at launch ("WebView caused LuxChart to crash", release 1.6.0 run #18)
- **What changed:**
  1. ROOT CAUSE: `shouldInterceptRequest` read `view.settings.userAgentString`
     on the WebView IO thread (WebView methods are main-thread only →
     RuntimeException, outside the engine's try/catch). The desktop UA is now
     captured ONCE on the main thread in `configureWebView` into a
     `@Volatile requestUserAgent` and passed to the engine. Full callback
     audit: `shouldInterceptRequest` was the ONLY non-main-thread callback
     touching WebView/WebSettings/View (onRenderProcessGone is main-thread;
     JS-bridge methods already funnel UI work through runOnUiThread /
     executors) — thread-rule comments added at each site.
  2. Entire `shouldInterceptRequest` body wrapped in try/catch(Throwable) →
     null (Log.w with reason). Nothing from the interceptor can crash the app.
  3. Tier-2 hardening: main-frame navigations never intercepted
     (`isForMainFrame` → null); `Range` requests added to the skip rules
     (pure, tested); `instanceFollowRedirects=false` and any 3xx → null
     (redirects belong to the WebView); Set-Cookie of an intercepted 200 is
     applied via `CookieManager.setCookie` + flush before returning (stored
     responses still NEVER keep Set-Cookie); `Origin`/`Referer`/`Accept`/
     `Accept-Language` forwarded from the original request via pure
     `Policy.forwardableRequestHeaders` (never Authorization; all previous
     skip rules unchanged).
  4. Crash visibility (no logcat needed): default uncaught-exception handler
     writes a sanitized trace (query strings stripped from URLs, cookie
     values redacted, 20k cap — pure `Policy.sanitizeCrashText`) to
     `filesDir/last-crash.txt`, then delegates to the previous handler. On
     the next launch the file is shown in a Copy / Share / Close dialog and
     deleted on dismiss (4 s fallback if the page never paints).
  5. Crash-loop kill switch: launch counter in SharedPreferences, incremented
     in onCreate, reset after the page is visible 10 s. At >= 2 (pure
     `Policy.tier2KillSwitch`) the interceptor returns null for the whole run
     (Tier 2 off until the next successful run) + short Toast.
  6. JVM tests added for every pure addition (header filter, Range skip,
     sanitizer, kill switch). versionName 1.6.1. Signing/CI/applicationId
     untouched.
- **Touched files:** `MainActivity.kt`, `Tier2Cache.kt`, `Policy.kt`,
  `HotfixPolicyTest.kt` (new), `app/build.gradle.kts`, `strings.xml`
- **Build:** CI run #20 — GREEN, artifact
  `LuxChart-release-apk-20`: https://github.com/ferdausfs/luxchart/actions/runs/38528773541
- **Pending items:** none for the hotfix; Tier-2 disk format unchanged
  (swr-cache/v1).
- **Notes for next agent:** the interceptor is now forbidden from touching
  the WebView/WebSettings/Views — if a future change needs the UA or any
  WebView value there, capture it on the main thread like `requestUserAgent`.
  Keep every new pure rule in `Policy.kt` with a JVM test.
---

## Phase 5 — 2026-10-14 — Offline Tier 2 (SWR disk cache) + diagnostics (versionName 1.6.0)

- **Agent/Tool:** GLM (Super Z CLI) acting as senior Android engineer
- **Feature:** Phase 5 — offline Tier 2 + diagnostics (items 12–17, owner mandate)
- **What changed:**
  12/13. `shouldInterceptRequest` → `Tier2Cache.intercept`: GET requests to
     luxalgo hosts ONLY are fetched by the app (HttpURLConnection, cookies
     attached from CookieManager, real WebView UA, `Accept-Encoding: identity`,
     8s/15s timeouts), the response is returned to the WebView and 200s passing
     the pure rules are stored in app-private `files/swr-cache/v1` (key =
     SHA-256 of `METHOD|normalized URL`; normalization: lowercase scheme/host,
     drop fragment/default-port/empty query, sort query params — all JVM
     tested in `PolicyTest`). Stored: body, status, content-type, safe headers
     (CORS set incl.). NEVER cached: non-GET, Authorization header, WebSocket
     (`ws://`/`wss://`, `Upgrade: websocket`), URLs with
     auth/login/token/session/payment, Set-Cookie responses, non-200,
     streaming (event-stream/gRPC), non-luxalgo hosts.
  14. Any interceptor exception returns null (WebView default). 3 consecutive
     fetch failures auto-disable Tier 2 for the session. Network fetch outside
     the lock; disk writes serialized; eviction async.
  15. 500 MB LRU cap (evict to ~80%, lastModified-based, off-thread),
     versioned directory, "Clear saved data" button on the offline/error page
     via `LuxBridge.clearSavedData()`.
  16. Diagnostics: hits/misses/stored/skipped counters + last 50 skipped
     luxalgo host+path (NO query strings/cookies/tokens/bodies) exposed by
     long-press on the offline banner → dark dialog with Copy (clipboard) /
     Share (chooser) / Close.
  17. README "Offline" section with the full known-limitations list (see
     README; also: HTML shell of luxalgo pages IS stored — documented
     deviation needed to make offline work end to end; stale-while-revalidate
     has no TTL; 25 MB per-response cap; best-effort, logins/bot-challenges
     cannot complete offline).
  Lint: `./gradlew lintRelease` run once → 2 real NewApi errors fixed
  (@RequiresApi(Q) on saveBlobMediaStore; readNBytes replaced by chunked loop
  for minSdk 24) → 0 errors, 5 stylistic warnings left by design.
  versionName 1.6.0.
- **Touched files:** `Tier2Cache.kt` (new), `Policy.kt`, `PolicyTest.kt`,
  `MainActivity.kt`, `assets/error.html`, `strings.xml`, `README.md`,
  `app/build.gradle.kts`
- **Commit hashes:** `a582641` (policy), `3b5ed6f` (engine+wiring),
  `0e64ac9` (diagnostics), `a377958` (README), `771168e` (lint fixes)
- **Build:** CI run #18 — GREEN, artifact
  `LuxChart-release-apk-18`: https://github.com/ferdausfs/luxchart/actions/runs/38516342167
- **Pending items:** none — all 6 phases delivered; see README limitations.
- **Notes for next agent:** Tier-2 cache version lives in the directory name
  (`swr-cache/v1`) — bump it when the on-disk format changes. The engine must
  ALWAYS return null on any doubt (never break the app). Diagnostics strings
  must stay secret-free.
---

## Phase 4 — 2026-10-14 — Offline Tier 1 (versionName 1.5.0)

- **Agent/Tool:** GLM (Super Z CLI) acting as senior Android engineer
- **Feature:** Phase 4 — offline Tier 1 (items 9–11, owner mandate)
- **What changed:** WebView `cacheMode` now switches dynamically via the
  ConnectivityManager callback: online `LOAD_DEFAULT`, offline
  `LOAD_CACHE_ELSE_NETWORK`; evaluated at app start BEFORE the first load
  (`reevaluateOnlineState()` checks INTERNET+VALIDATED on the active network)
  and applied to every WebView instance (incl. recreation). NetworkCallback
  extended with `onLost`/`onCapabilitiesChanged`. DOM storage + IndexedDB
  stay on; Service Workers deliberately NOT blocked. Non-blocking banner
  "Offline: showing saved data" (amber on slate, full-width, not clickable —
  touches pass through to the charts) shown/hidden on transitions. The error
  page appears only when nothing is saved for the page (emerges naturally:
  no cache → main-frame error). versionName 1.5.0.
- **Touched files:** `MainActivity.kt`, `strings.xml`, `app/build.gradle.kts`
- **Commit hashes:** `ceb9f7f` (items 9+10), `9f45ccb` (item 11)
- **Build:** CI run #16 — GREEN, artifact
  `LuxChart-release-apk-16`: https://github.com/ferdausfs/luxchart/actions/runs/38516036914
- **Pending items:** Tier 2 (next phase) covers what the WebView cache cannot.
- **Notes for next agent:** `setOnlineState()` is the single place where
  online/offline transitions are applied — hook anything new there.
---

## Phase 3 — 2026-10-14 — blob:/data: downloads (versionName 1.4.0)

- **Agent/Tool:** GLM (Super Z CLI) acting as senior Android engineer
- **Feature:** Phase 3 — in-app downloads for blob:/data: (item 8, owner mandate)
- **What changed:** DownloadManager kept for http(s). `blob:` URLs are now
  fetched in-page: injected JS does `fetch(blobUrl) -> FileReader.readAsDataURL
  -> window.LuxBlob.save(name, mime, base64)`; the new `BlobBridge`
  (@JavascriptInterface, ProGuard keep added) decodes (android.util.Base64)
  on a single background executor and saves via MediaStore Downloads on
  API 29+ / legacy public Downloads below (permission flow reused; app-private
  fallback when denied). `data:` URLs are parsed by the new pure
  `Policy.dataUrlParts` (tested) and saved directly. Filenames:
  `luxchart-<ts>.<ext>` from a mime→ext map, `name (2).ext` de-dup on legacy
  paths. 80 MB cap (OOM guard). Toasts: `blob_saved` / `blob_failed`.
  versionName 1.4.0.
- **Touched files:** `MainActivity.kt`, `Policy.kt`, `PolicyTest.kt`,
  `proguard-rules.pro`, `strings.xml`, `README.md`, `app/build.gradle.kts`
- **Commit hashes:** `0493888`
- **Build:** CI run #14 — GREEN, artifact
  `LuxChart-release-apk-14`: https://github.com/ferdausfs/luxchart/actions/runs/38515720630
- **Pending items:** none for this phase
- **Notes for next agent:** every NEW JS bridge needs (a) registration inside
  `setupWebView()` and (b) an explicit `-keep class ...$BridgeName { *; }`
  rule. Bridge methods must never touch the UI directly — use
  `toastInternal()` / `runOnUiThread`.
---

## Phase 2 — 2026-10-14 — Login + compatibility (versionName 1.3.0)

- **Agent/Tool:** GLM (Super Z CLI) acting as senior Android engineer
- **Feature:** Phase 2 — login + compatibility (3 items, owner mandate)
- **What changed:**
  5. Blanket `setOnLongClickListener { true }` REMOVED (paste/select works in
     login fields again); `onPageFinished` injects an idempotent style tag
     (`#luxchart-canvas-lock`: `canvas{-webkit-user-select:none!important;
     -webkit-touch-callout:none!important}`) so canvases — including
     SPA-created ones — still don't open the selection popup.
  6. Desktop UA built at runtime: `Policy.desktopUa(
     WebSettings.getDefaultUserAgent(this))` extracts the REAL WebView Chrome
     major into the exact Windows template; hardcoded `Chrome/131` deleted
     (conservative 124 fallback only when no parseable major exists).
  7. Allow-list in ONE place: `Policy.kt` `ALLOWED_HOSTS` — luxalgo.com,
     accounts.google.com, appleid.apple.com, facebook.com,
     accounts.youtube.com, discord.com, login.microsoftonline.com,
     checkout.stripe.com, js.stripe.com, paypal.com,
     challenges.cloudflare.com (+ subdomains each). External-browser handoff
     now Toasts the host (`opening_external`). NEW pure `Policy.kt`
     (no Android imports) + `PolicyTest` (19 JVM tests: UA builder,
     allow-list matcher incl. lookalike rejection, data-URL parsing) and a CI
     step `./gradlew testDebugUnitTest` BEFORE `assembleRelease` (rest of the
     workflow unchanged). versionName 1.3.0.
- **Touched files:** `Policy.kt` (new), `app/src/test/.../PolicyTest.kt` (new),
  `MainActivity.kt`, `app/build.gradle.kts` (+junit dep),
  `.github/workflows/release.yml`, `strings.xml`, `README.md`
- **Commit hashes:** `e14e4a8` (item 5), `179a14f` (item 6),
  `31d779a` (item 7)
- **Build:** CI run #12 — GREEN, artifact
  `LuxChart-release-apk-12`: https://github.com/ferdausfs/luxchart/actions/runs/38515391880
- **Pending items:** none for this phase
- **Notes for next agent:** add future pure-logic helpers to `Policy.kt` and
  keep it Android-import-free so tests stay JVM-only.
---

## Phase 1 — 2026-10-14 — Stability hardening (versionName 1.2.0)

- **Agent/Tool:** GLM (Super Z CLI) acting as senior Android engineer
- **Feature:** Phase 1 — stability (4 items, owner mandate)
- **What changed:**
  1. `onReceivedHttpError` no longer replaced by the offline screen: HTTP >=
     400 (Cloudflare challenge, 403, 429) renders normally; the offline page
     is shown only for real main-frame network errors in `onReceivedError`.
  2. `onRenderProcessGone` returns true, destroys the dead WebView and
     rebuilds an identical one (same settings/clients/bridges/cookies via the
     new `createAndAttachWebView`/`setupWebView` factory) and reloads
     `currentUrl` (tracked in `onPageStarted`, http(s) only). Guard: max 3
     rebuilds, then the error page — the app never crashes.
  3. `onSaveInstanceState` no longer calls `webView.saveState`
     (TransactionTooLarge risk) — saves `currentUrl` + error flag only;
     restore re-loads the URL.
  4. Manifest: `allowBackup="false"`, `fullBackupContent="false"` (session
     cookies never leave the device via OS backup). versionName 1.2.0.
- **Touched files:** `MainActivity.kt`, `AndroidManifest.xml`,
  `app/build.gradle.kts`
- **Commit hashes:** `34b7ce8` (item 1), `de215d6` (item 2),
  `3592b78` (item 3), `7bbc311` (item 4)
- **Build:** CI run #10 — GREEN, artifact
  `LuxChart-release-apk-10`: https://github.com/ferdausfs/luxchart/actions/runs/38515118447
- **Pending items:** none for this phase
- **Notes for next agent:** WebView now always exists before
  `applyEdgeToEdgeAndInsets()`; recreation path re-registers JS interfaces —
  any NEW bridge must be added inside `setupWebView()` (recreation keeps it).
---

## Phase 0 — 2026-10-14 — Start URL → vela.luxalgo.com/chart (versionName 1.1.1)

- **Agent/Tool:** GLM (Super Z CLI) acting as senior Android engineer
- **Feature:** Phase 0 — locked start URL change (owner mandate)
- **What changed:** `START_URL` in `MainActivity.kt` is now the single
  constant `https://vela.luxalgo.com/chart`, used by first launch,
  error-retry (`retryLoading`) and WebView re-creation. `const val` (was
  `private const val`) with doc comment; allow-list untouched
  (`vela.luxalgo.com` is a luxalgo.com subdomain). README start-URL section
  added; all live `www.luxalgo.com` references dropped (AGENT_LOG history
  entries intentionally untouched — append-only). versionName 1.1.0 → 1.1.1.
- **Touched files:** `app/src/main/java/com/ferdausfs/luxchart/MainActivity.kt`,
  `app/build.gradle.kts`, `README.md`
- **Commit hashes:** `41a78dd` (code+version), `a8d0c5a` (README)
- **Build:** CI run #7 — GREEN, artifact
  `LuxChart-release-apk-7`: https://github.com/ferdausfs/luxchart/actions/runs/38514030464
- **Pending items:** none for this phase
- **Notes for next agent:** tag `phase-0-green` marks the last green commit of
  this phase (roll-back point).
---

## Session 2 — 2026-10-14 — All 9 pending features + UI polish (BUILD: GREEN)

- **Agent/Tool:** GLM (Super Z CLI) acting as senior Android engineer
- **Feature:** implemented ALL 9 pending features in one session (owner
  pre-approved everything, no approval gates) + premium UI polish
- **What changed:**
  1. Persistent login: `setAcceptCookie(true)` + `setAcceptThirdPartyCookies`
     (OAuth) + `CookieManager.flush()` on pause; DOM storage kept on.
  2. Immersive fullscreen + landscape: edge-to-edge via
     `WindowCompat.setDecorFitsSystemWindows(false)`, bars hidden with
     `WindowInsetsControllerCompat` (sticky, transient-by-swipe = smooth
     enter/exit), `configChanges` in manifest so rotation never reloads;
     insets listener pads content for bars + keyboard (IME).
  3. Back button: `OnBackPressedCallback` — `goBack()` when possible, else
     finish; exits directly from the error page.
  4. Offline/error page: `assets/error.html` (dark, animated, reduced-motion
     aware) with Retry (haptic via tiny JS bridge `LuxBridge`), plus
     ConnectivityManager auto-retry when the network returns; main-frame HTTP
     >= 400 also handled.
  5. Pull-to-refresh: none exists (no SwipeRefreshLayout) + overscroll off.
  6. Chart gesture fix: WebView pinch-zoom fully disabled (zoom controls
     hidden), long-press text selection suppressed so the chart canvas owns
     pan/pinch/draw; desktop UA + wide viewport kept; `textZoom = 100`.
  7. File upload: `onShowFileChooser` via `ActivityResultContracts`; downloads
     via DownloadManager (public Downloads dir; WRITE_EXTERNAL_STORAGE
     runtime request only on API < 29, app-private fallback if denied;
     Cookie + UA headers attached; blob:/data: rejected with toast).
  8. Splash: androidx core-splashscreen (dark, adaptive icon as art), held
     until first paint, seamless dark-to-dark handoff (no white flash).
  9. Adaptive icon: `mipmap-anydpi-v26` with background/foreground/monochrome
     candlestick vector; legacy PNGs stay for API 24-25.
  Polish: thin animated teal progress bar (WebChromeClient.onProgressChanged),
  content fade-in, consistent dark surfaces (window/splash/WebView/bars).
- **Touched files:** `app/build.gradle.kts` (deps core-ktx 1.15.0,
  activity-ktx 1.9.3, core-splashscreen 1.0.1; versionName 1.0.0 -> 1.1.0),
  `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml` (configChanges,
  usesCleartextTraffic=false, ACCESS_NETWORK_STATE, WRITE_EXTERNAL_STORAGE
  maxSdk 28), `app/src/main/java/com/ferdausfs/luxchart/MainActivity.kt`
  (rewritten), `app/src/main/assets/error.html` (new),
  `app/src/main/res/values/{colors.xml (new),strings.xml,themes.xml}`,
  `app/src/main/res/drawable/ic_launcher_foreground.xml` (new),
  `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (new), `README.md`
- **Commit hashes:** feature commit `a5017711360587a82c88d3228e162aafdd569921`
  (run #4 FAILURE — `overScrollMode`/scrollbar flags are View properties, not
  WebSettings; fixed by moving them out of `settings.apply`) -> fix commit
  `a77a1bcf47017d43056db5d530d918c6cc11e79d` -> run #5 SUCCESS
  (`BUILD SUCCESSFUL in 47s`, `apksigner verify` => Verifies, artifact
  `LuxChart-release-apk-5`, app-release.apk 129,821 bytes, versionName 1.1.0,
  versionCode 5). Download:
  https://github.com/ferdausfs/luxchart/actions/runs/38439291612
  - this log commit: recorded as repository HEAD at push time
- **Pending items:** none from the original 9. Watchlist for future sessions:
  POST_NOTIFICATIONS permission for download progress on Android 13+ (default
  omitted — downloads still work silently), desktop UA string rotation.
- **Notes for next agent:** Keystore reused (NOT recreated) — fingerprint
  unchanged, old APKs update in place. One feature per session still applies.
  `textZoom=100`, long-press suppression, and blob:/data: download rejection
  are deliberate defaults. Do not change applicationId
  `com.ferdausfs.luxchart`.

---

## Session 1 (addendum) — 2026-10-14 — BUILD RESULT: GREEN

- **Agent/Tool:** GLM (Super Z CLI) acting as senior Android engineer
- **Feature:** CI FIRST build result (no code changes in this commit — log only)
- **What changed:** AGENT_LOG.md only. Records commit hashes, CI runs, artifact.
- **Touched files:** `AGENT_LOG.md`
- **Commit hashes:**
  - skeleton commit: `28a841f81ff8ad22f7dcdf0b1dc519fdc980496e`
  - signing fix commit: `c15a5e7ad26c34b757f3a0eed5c198d120820d6f`
  - this log commit: recorded as the repository HEAD at push time
- **Build results (GitHub Actions):**
  - Run #1 (skeleton): FAILURE — `SigningConfig "release" is missing required
    property "keyPassword"` (Kotlin name shadowing: `keyPassword = keyPassword`
    self-assigned the property). Fixed in commit `c15a5e7` by renaming the env
    val to `keyPasswordValue`.
  - Run #2 (fix): SUCCESS — `BUILD SUCCESSFUL in 48s`,
    `apksigner verify --verbose` => Verifies (build-tools 37.0.0),
    artifact `LuxChart-release-apk-2` (app-release.apk, 24,641 bytes,
    versionName 1.0.0, versionCode 2 = run number).
    Download: https://github.com/ferdausfs/luxchart/actions/runs/38431517449
- **Pending items:** unchanged (see Session 1 entry below)
- **Notes for next agent:** keystore fingerprint was delivered to the owner
  separately (outside this repo). Same keystore must be used for every future
  build. Log-only commits still trigger the release workflow — harmless.

---

## Session 1 — 2026-10-14 — Initial skeleton + signed release CI (bootstrap)

- **Agent/Tool:** GLM (Super Z CLI) acting as senior Android engineer
- **Feature:** CI FIRST — minimal compiling app skeleton + signed release pipeline
- **What changed:**
  - Created private repo `luxchart`, branch `main`.
  - Android app: Kotlin, single Activity (`MainActivity`) + WebView loading
    `https://www.luxalgo.com/`, desktop user-agent (Chrome/131 Win64) + wide
    viewport, domain allow-list (luxalgo.com + subdomains,
    accounts.google.com, appleid.apple.com, facebook.com + subdomains),
    non-allow-list URLs open in the external browser.
  - Gradle Kotlin DSL: AGP 8.7.3, Kotlin 2.0.21, Gradle wrapper 8.10.2, JDK 17,
    minSdk 24 / targetSdk 35 / compileSdk 35.
  - `versionCode = github.run_number` (local fallback 1); `versionName` defined
    once (`APP_VERSION_NAME = "1.0.0"` in `app/build.gradle.kts`).
  - Release build: minify + shrinkResources + WebView keep rules
    (`app/proguard-rules.pro`). Missing signing env vars => fail fast with a
    clear message (no debug-signing fallback).
  - GitHub Actions `.github/workflows/release.yml`: verify 4 secrets -> decode
    KEYSTORE_BASE64 to temp file -> `./gradlew assembleRelease` -> apksigner
    verify --verbose -> upload artifact `LuxChart-release-apk-<run_number>`
    (90-day retention) -> delete temp keystore.
  - Repo Actions secrets set via API (names only): KEYSTORE_BASE64,
    KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
  - Keystore generated OUTSIDE the repo and delivered separately
    (`luxchart-release.jks` + backup .md, never committed).
- **Touched files:** initial commit — full skeleton (22 files):
  `.github/workflows/release.yml`, `.gitignore`, `AGENT_LOG.md`, `README.md`,
  `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
  `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
  `gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle.kts`,
  `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`,
  `app/src/main/java/com/ferdausfs/luxchart/MainActivity.kt`,
  `app/src/main/res/values/strings.xml`,
  `app/src/main/res/values/themes.xml`,
  `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`
- **Commit hash:** pending (recorded in the next entry after first push)
- **Pending items (do NOT build without approval):**
  - persistent login via CookieManager
  - immersive fullscreen + landscape
  - back-button history handling
  - offline/error page with retry
  - pull-to-refresh disabled
  - pinch/pan chart conflict fix
  - file upload/download
  - splash screen
  - adaptive icon
- **Notes for next agent:** One feature per session, never mixed scope. Same
  keystore always (delivered backup files; never commit them). versionCode must
  keep increasing (CI run number). Never change applicationId
  `com.ferdausfs.luxchart`. Keep release signing env-var only.
