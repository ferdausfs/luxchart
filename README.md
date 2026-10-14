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

## Offline & cache-first — how it works, and known limitations

Two layers cooperate:

- **Tier 1** — when offline the WebView switches to
  `LOAD_CACHE_ELSE_NETWORK`, so anything the WebView itself cached while
  online (plus DOM storage / IndexedDB / service-worker data) is served.
- **Tier 2** — the app intercepts GET requests to `luxalgo.com` hosts
  (private versioned dir `files/swr-cache/v1`, key =
  `METHOD|normalized-URL` SHA-256) and is **CACHE-FIRST ALSO WHILE ONLINE**
  (Phase 7):
  - a stored entry is served instantly (counted as a hit), online or offline;
  - static assets (js/css/fonts/images/wasm/svg/html) are never revalidated;
  - API-like data (json/ndjson/msgpack/protobuf/text-plain) is served from
    the store and refreshed ONCE in the background (private executor, one
    in-flight per key) so the stored copy is fresh for NEXT time;
  - no entry → fetched, stored atomically (body tmp-file + rename first,
    meta with the exact body length last), then returned.

**SAVED DATA STAYS UNTIL I CLEAR IT.** Nothing is deleted automatically —
the old LRU eviction is gone. When saved data reaches the **4 GB** cap, NEW
stores are refused and a one-time "Saved-data limit reached" toast appears;
every existing entry stays untouched. "Clear saved data" (offline/error
screen) is the ONLY deletion path. Diagnostics always show the exact
saved-data size.

**Diagnostics + request log** — long-press the offline banner, or press
**Volume-Down 3× within 2 s** (works online too): copy/share a report with
the counters (hits, cache-first hits, background refresh updates, bytes
served from cache, stored, skipped), the saved-shell section and the last
**300 luxalgo requests**: time, method, host+path, query KEY NAMES only
(never values), status, content-type, size and the decision
(`HIT` / `STORED` / `REFRESHED` / `SKIP:<reason>` / `NETWORK`). Headers,
cookies, tokens and bodies are never logged.

**Authorization trade-off (single-user personal app):** GETs that carry an
`Authorization` header ARE cached. The header value is forwarded to the
origin in memory only — never stored on disk, never in cache meta, never
part of the cache key (key = method + normalized URL by construction) and
never logged — but the authorized RESPONSE BODY is saved in app-private
storage and served again until you clear saved data. If you hand the device
to someone, clear saved data first.

**Known limitations (by design, documented for AGENT_LOG):**
- **POST bodies and WebSocket traffic are not cacheable via
  shouldInterceptRequest** — request bodies never reach the interceptor and
  `ws://`/`wss://` upgrades are skipped; live-streaming features need
  internet.
- **Page-side recomputation (indicators) is the site's own JS** — the app
  can cache indicator code and input data, but values the page's JS
  recomputes appear only when that JS runs; the app cannot force it offline.
- Cache-first means static assets stay until you clear: after a site
  redesign use "Clear saved data" once (API data refreshes itself in the
  background; static assets intentionally never do).
- Sensitive **PATH segments** (auth, oauth, oauth2, login, logout, signin,
  signup, token, tokens, session, sessions, payment, payments, checkout) are
  never cached — path-segment equality only, NEVER the host, NEVER the query.
- Streaming responses (`text/event-stream`, gRPC) pass through live and are
  never buffered or stored.
- Responses with a known length > **100 MB**, and unknown-length (gzip /
  chunked) bodies that exceed the 4 MB probe window, stream through
  (already-read prefix + remaining stream via SequenceInputStream — no
  double download, connection closed with the stream) and are not stored.
- Responses WITH `Set-Cookie` ARE stored: the cookie header is stripped from
  the persisted meta automatically, while fetched cookies are still applied
  to the CookieManager.
- Cache keys ignore noisy query params (`_`, `t`, `ts`, `_t`, `timestamp`,
  `cb`, `cachebust`, `nocache`, `rnd`, `random`) so cache-buster URLs share
  one entry; all other params (symbol/resolution/from/to/…) are kept.
- Corrupt entries (stored length mismatch on read) are deleted and treated
  as a plain MISS — never an exception, never counted toward the
  3-failure disable.
- Third-party hosts are never touched or cached — only `luxalgo.com` and
  subdomains.
- Fragile-network guard: 3 consecutive interceptor failures disable Tier 2
  until the next app start (the cache must never break the app).
- Bot protections / logins generally cannot complete offline; the
  implementation is best-effort generic, not a LuxAlgo-specific product.

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
