# LuxChart

Lightweight native Android app (Kotlin, single Activity + WebView) that loads
https://vela.luxalgo.com/chart (the LuxAlgo chart page) in a desktop-mode
WebView, so the charts feel closer to a native app than the mobile browser.
Personal use only.

## Features
- **Persistent login** — cookies (incl. third-party for OAuth) accepted and
  flushed to disk on pause; DOM storage enabled
- **Immersive fullscreen + landscape** — system bars hide in immersive-sticky
  mode (swipe from edge for smooth transient reveal); `configChanges` set so
  rotation never reloads the WebView
- **Back button** — `goBack()` inside site history, otherwise exits
  (`OnBackPressedCallback`)
- **Offline/error page** — local dark animated HTML screen with Retry button,
  haptic feedback, and auto-retry when the network returns
- **Pull-to-refresh** — disabled (no SwipeRefreshLayout, overscroll off)
- **Chart gesture fix** — WebView-level pinch-zoom disabled (wide viewport +
  desktop UA kept) so the chart canvas owns pan/pinch; long-press text
  selection suppressed
- **File upload + downloads** — `onShowFileChooser` for uploads;
  DownloadManager for http(s) downloads (public Downloads dir; legacy
  WRITE_EXTERNAL_STORAGE runtime request only on API < 29, app-private
  fallback if denied); `blob:`/`data:` downloads fetched in-page
  (JS fetch -> base64 -> LuxBlob bridge) and saved to Downloads via
  MediaStore on API 29+ (legacy path below), 80 MB cap
- **Splash screen** — androidx core-splashscreen, dark, held until the WebView
  starts painting (no white flash; reduced-motion respected)
- **Adaptive icon** — foreground/background/monochrome layers (API 26+);
  legacy PNGs on API 24-25
- **Offline (Tier 1)** — dynamic cache mode via ConnectivityManager: online
  `LOAD_DEFAULT`, offline `LOAD_CACHE_ELSE_NETWORK` (set correctly at app
  start); DOM storage + IndexedDB on; Service Workers not blocked; non-blocking
  amber banner "Offline: showing saved data" while offline
- **Offline (Tier 2, stale-while-revalidate)** — GET requests to luxalgo hosts
  are fetched by the app (cookies + real UA attached), stored in a private,
  versioned, 500 MB LRU disk cache (key = METHOD + normalized URL), and
  replayed offline with their original safe headers (incl. CORS). Never
  cached: non-GET, Authorization, WebSocket, Set-Cookie responses, streaming,
  and auth/login/token/session/payment URLs. Any interceptor exception returns
  null; 3 consecutive failures disable Tier 2 for the session. "Clear saved
  data" button on the offline screen. Diagnostics (counters + last 50 skipped
  host/path, no query strings) via long-press on the offline banner → Copy/Share
- **JVM unit tests** — pure logic (desktop UA builder, navigation allow-list,
  data-URL parsing, cache skip rules, cache-key normalization) tested in
  `app/src/test/`; CI runs them before the release build
- **UI polish** — thin animated top progress bar, dark theme consistent across
  window/splash/WebView/bars, edge-to-edge inset handling (incl. keyboard)

## Allow-list (stays inside the WebView)
`luxalgo.com` (+ subdomains), `accounts.google.com`, `appleid.apple.com`,
`facebook.com`, `accounts.youtube.com`, `discord.com`,
`login.microsoftonline.com`, `checkout.stripe.com`, `js.stripe.com`,
`paypal.com`, `challenges.cloudflare.com` (+ subdomains for every entry).
Everything else opens in the external browser (with a short Toast naming the
host). The list lives in ONE place: `Policy.kt` (`ALLOWED_HOSTS`), covered by
JVM unit tests in `app/src/test/`.

## Offline — how it works, and known limitations

Two layers cooperate:

- **Tier 1** — when offline the WebView switches to
  `LOAD_CACHE_ELSE_NETWORK`, so anything the WebView itself cached while
  online (plus DOM storage / IndexedDB / service-worker data) is served.
- **Tier 2** — the app intercepts GET requests to `luxalgo.com` hosts, fetches
  them itself (cookies + the real WebView UA attached), stores 200 responses
  (JSON/JS/CSS/fonts/images and the luxalgo HTML shell) in a private
  versioned cache (`files/swr-cache/v1`, key = `METHOD|normalized-URL`
  SHA-256), and serves the stored copy — with original CORS headers — when
  you are offline. Online it behaves like stale-while-revalidate: always
  fresh, saving a copy.

**Known limitations (by design, documented for AGENT_LOG):**
- **WebSocket** traffic (`ws://`/`wss://`, `Upgrade: websocket`) is never
  intercepted or cached — live-streaming features need internet.
- **POST/PUT/DELETE** are never cached — only idempotent GETs.
- **Auth-gated data IS cached, with a documented trade-off (Phase 7)**:
  GETs that carry an `Authorization` header are cached like any other GET.
  The header value itself is forwarded to the origin in memory only — it is
  never stored on disk, never written into cache meta, never part of the
  cache key (key = `METHOD|normalized-URL`, headers cannot influence it) and
  never logged. The RESPONSE BODY of an authorized request, however, IS
  saved in app-private storage and served again (online cache-first and
  offline) until "Clear saved data" wipes it. For a single-user personal app
  this is safe by construction (private storage, no other app can read it);
  if you hand the device to someone, clear saved data first.
- **Streaming responses** (`text/event-stream`, gRPC) are never cached.
- **Stale-while-revalidate, no TTL**: offline you see the last stored copy —
  it may be outdated. Nothing expires by time; only the 500 MB LRU evicts.
- **Size caps**: 500 MB total (evicted to ~80% of the cap), 25 MB per
  response (larger responses stream normally but are not cached).
- **Third-party hosts are never touched or cached** — only `luxalgo.com` and
  subdomains.
- **Fragile-network guard**: 3 consecutive interceptor failures disable
  Tier 2 until the next app start (the cache must never break the app).
- **Bot protections / logins** generally cannot complete offline; the
  implementation is best-effort generic, not a LuxAlgo-specific product.
- Diagnostics are privacy-safe by construction: counters + `host/path` only
  (never query strings, cookies, tokens, bodies).

## Requirements
- JDK 17 (Temurin recommended)
- Android SDK (API 35) for local builds only — CI provides its own

## Build locally (Termux or desktop)
```bash
./gradlew assembleDebug            # quick smoke test, no secrets needed

# Release build requires the signing material (kept OUT of this repo):
export KEYSTORE_FILE=/absolute/path/luxchart-release.jks
export KEYSTORE_PASSWORD='...'
export KEY_ALIAS=luxchart
export KEY_PASSWORD='...'
./gradlew assembleRelease
```
The release build fails fast with a clear message if any variable is missing.
There is intentionally NO debug-signing fallback.

Note: `gradle.properties` sets `-Xmx3g`; on small devices override it in
`~/.gradle/gradle.properties`.

## CI (GitHub Actions)
`.github/workflows/release.yml` runs on push to `main` and on manual dispatch:
1. checkout -> JDK 17 -> Gradle caching
2. verify the 4 Actions secrets exist (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) — hard fail if missing
3. decode KEYSTORE_BASE64 to a temp file
4. `./gradlew testDebugUnitTest` — JVM unit tests for the pure logic (must pass before the release build)
5. `./gradlew assembleRelease` (versionCode = run number)
6. `apksigner verify --verbose`
7. upload artifact `LuxChart-release-apk-<run_number>` (90-day retention)
8. delete temp keystore

Secret values are never printed. The keystore is never committed.

## Versioning
- `versionName` — defined once in `app/build.gradle.kts` (`APP_VERSION_NAME`)
- `versionCode` — `github.run_number` in CI (locally defaults to 1)

## Start URL
`https://vela.luxalgo.com/chart` — defined once (`START_URL` in
`MainActivity.kt`); used for first launch, error-retry and WebView
re-creation. Navigation allow-list covers it (`vela.luxalgo.com` is a
`luxalgo.com` subdomain).

## Project rules
- `applicationId` `com.ferdausfs.luxchart` is permanent — never change
- one feature per session; session log in `AGENT_LOG.md` (newest first)
- pending features are listed in `AGENT_LOG.md` — do not build without approval
- same keystore always; the .jks and passwords live outside this repo
