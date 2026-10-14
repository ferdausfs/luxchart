# AGENT_LOG — LuxChart

Append-only session log. **Newest entry first. Never delete or rewrite past entries.**

Fixed entry template: session date, agent/tool, feature, what changed, touched
files, commit hash, pending items, notes for next agent.

SECURITY: never record tokens, passwords, keystore contents, base64 blobs, or
secret values in this file. Secret *names* and file *names* are allowed.

## Phase 7 — 2026-10-14 — SAVED DATA STAYS UNTIL I CLEAR IT: cache-first offline/online data cache (versionName 1.8.0)

- **Agent/Tool:** GLM (Super Z CLI) acting as senior Android engineer
- **Feature:** CACHE-FIRST data cache for luxalgo — anything loaded once
  (chart candles, indicators, assets) is served instantly from phone storage
  NEXT TIME, ONLINE TOO, and is NEVER deleted automatically; only
  "Clear saved data" deletes. Signing/CI/applicationId, main-frame policy and
  every hotfix-1.6.1 protection (no WebView access off-main,
  try/catch(Throwable)->null, crash-loop kill switch) untouched.
- **What changed (one commit per item, CI green on every push):**
  1. CACHE-FIRST ONLINE: intercept() serves a stored entry immediately even
     while online (hits counter); static assets never revalidated; API-like
     types (json/ndjson/msgpack/protobuf/text-plain, pure
     Policy.isApiLikeType) get ONE background refresh (2-thread executor,
     one in-flight per key) overwriting the stored copy for next time;
     no entry -> fetch+store+return. readFromDisk() shared by online and
     offline serving.
  2. NO AUTO DELETE: LRU eviction deleted outright. Policy.storeAllowedWithinCap
     = hard stop for NEW stores at 4 GB (Tier2Cache.DEFAULT_MAX_BYTES);
     existing entries never touched; one-time "Saved-data limit reached" toast
     (onLimitReached callback -> main thread, AtomicBoolean once-guard reset by
     Clear-saved-data); diagnostics keep the real saved-data size.
  3. AUTHORIZATION ALLOWED: Authorization GETs are cached; the header is
     forwarded to origin calls in MEMORY ONLY (never in disk meta — only
     SAFE_RESPONSE_HEADERS persist, never logged, never in the key which is
     METHOD+URL by construction); a failed authed refresh keeps the old entry.
     README documents the single-user/app-private trade-off.
  4. URL SKIP RULE: substring match REPLACED by PATH-ONLY SEGMENT equality
     (Policy.SENSITIVE_PATH_SEGMENTS + urlHasSensitiveSegment): auth, oauth,
     oauth2, login, logout, signin, signup, token, tokens, session, sessions,
     payment, payments, checkout — never host, never query; unparseable fails
     closed. /tokenizer, /blog/authors, ?token=... are NOT skipped.
  5. STORE RULES: storable types gain text/plain, application/octet-stream,
     application/wasm, application/x-protobuf, application/msgpack,
     application/x-ndjson (svg already via image/). Set-Cookie responses ARE
     stored (cookie header stripped from persisted meta automatically; fetched
     cookies still applied to CookieManager). Never stored: non-GET, 3xx,
     non-200, text/event-stream, grpc (containment also catches grpc-web).
  6. KEY NORMALIZATION: Policy.NOISY_QUERY_PARAMS (_, t, ts, _t, timestamp,
     cb, cachebust, nocache, rnd, random) dropped when building keys;
     symbol/resolution/from/to/type kept; applies to GET and SHELL keys.
  7. SPEED + NO DOUBLE DOWNLOAD: Accept-Encoding: identity removed (fetch +
     shadow fetch; HttpURLConnection gunzips transparently, hiding
     Content-Length). Store limit 25 MB -> 100 MB (largeHeap added so the
     mandated buffering is memory-safe). Streaming types pass the LIVE stream
     through (never buffered/stored); known length > 100 MB streams through;
     UNKNOWN length probe-reads a 4 MB window (ends inside -> buffered+stored;
     past it -> already-read prefix + remaining stream via SequenceInputStream,
     connection closed when the WebView closes the stream). 4xx/5xx return the
     REAL response (status, body, reasonPhrase) instead of null so the WebView
     never re-requests. Content-Length lies guarded; mid-body IO errors still
     degrade to null.
  8. INTEGRITY: writeEntryAtomically = tmp + rename, body first, meta (with
     body-length) LAST. Read side validates the length (readFromDisk AND
     shellFor); corrupt/mismatched pair is DELETED and treated as a plain
     MISS — never an exception, never toward the 3-failure disable. Legacy
     v1.7.0 entries (no body-length) accepted so saved data survives upgrade.
  9. DIAGNOSTICS: "stored" counts only entries really written to disk; new
     counters cacheFirstHits (online), refreshUpdates, bytesServed from cache;
     MainActivity.isOnline verified @Volatile (since Phase 6).
  10. REQUEST LOG: 300-entry ring buffer of the last luxalgo requests seen by
     shouldInterceptRequest (ALL methods incl. POST): time, method, sanitized
     host+path (pure Policy.sanitizedRequestUrl — query KEY NAMES only, never
     values/headers/cookies/tokens/bodies), status, content-type, size,
     decision (HIT / STORED / REFRESHED / SKIP:<reason> / NETWORK —
     Policy.skipReason explains skips). Online main frames log NETWORK (still
     never intercepted). Reachable ONLINE: Volume-Down 3x within 2 s opens the
     diagnostics + request-log dialog (Copy/Share/Close); banner long-press
     kept.
  11. JVM tests (Phase7PolicyTest): segment skip, skipReason table, store
     rules, streaming types, noisy-param normalization + cacheKey equivalence,
     4 GB cap decision, log sanitizer, API-like classification,
     Authorization-not-skipped guard.
  12. README "Offline & cache-first" rewritten + versionName 1.8.0.
- **Touched files:** `Policy.kt`, `Tier2Cache.kt`, `MainActivity.kt`,
  `AndroidManifest.xml` (largeHeap only), `strings.xml` (limit toast),
  `README.md`, `app/build.gradle.kts`,
  `app/src/test/.../Phase7PolicyTest.kt` (new),
  `PolicyTest.kt`/`Phase6PolicyTest.kt` (updated for mandated rule changes)
- **Commit hashes:** `1312f78` item 1 (#30) -> `2b547b7` item 2 (#31) ->
  `e01b43e` item 3 (#32) -> `d28f4ed` item 4 (#33) -> `cb4176e` item 5 (#34)
  -> `7337346` item 6 (#35) -> `e75b4f3` item 7 (#36) -> `aaa5c3f` item 8
  (#37) -> `006ce2b` item 9 (#38) -> `ed05c68` item 10 (#39) -> `de7b41e`
  item 11 (#41) -> `3a8da1d` item 12 (#42, versionName 1.8.0)
  - this log commit: recorded as repository HEAD at push time
- **KNOWN LIMITS (spec item 12):** POST bodies and WebSocket traffic are not
  cacheable via shouldInterceptRequest (bodies never reach the interceptor;
  ws/wss skipped) — live features need internet; page-side recomputation
  (indicators) is the site's own JS — the app caches code + input data but
  cannot force offline recomputation; cache-first static assets stay until
  cleared (after a site redesign use Clear-saved-data once); sensitive PATH
  segments never cached (never host/query); streaming types pass through
  uncached; known >100 MB and unknown-length >4 MB probe bodies stream
  through (no double download) uncached; Set-Cookie stored with the header
  stripped from disk; noisy query params ignored in keys; corrupt entries =
  delete + MISS; 3 consecutive failures disable Tier 2 for the session.
- **Pending items:** none from the Phase 7 scope.
- **Notes for next agent:** online main frames are STILL never intercepted
  (only logged); cache-first serving and background refresh are the new
  intercept contract — do not reintroduce revalidation of static assets; the
  4 MB probe window is what keeps gzipped JSON cacheable (the platform hides
  Content-Length when it gunzips) — do not "simplify" it away; Authorization
  forwarding is in-memory only by design; keep new rules PURE in Policy.kt
  with JVM tests. Do not touch applicationId `com.ferdausfs.luxchart`;
  signing/CI unchanged.

---


## Phase 6 — 2026-10-14 — OFFLINE main-page shell caching: airplane mode opens the saved chart shell (versionName 1.7.0)

- **Agent/Tool:** GLM (Super Z CLI) acting as senior Android engineer
- **Feature:** OFFLINE "main page shell" caching — with Airplane mode the app
  no longer lands on "Connection lost / ERR_INTERNET_DISCONNECTED": the main
  HTML document of vela.luxalgo.com/chart (never saved before: no-store +
  main frames not intercepted since 1.6.1) is now captured in the background
  while online and served from disk while offline. SAFE BY CONSTRUCTION:
  online navigation is NEVER touched.
- **What changed (one commit per item):**
  1. Shadow fetch (online only): after a main-frame onPageFinished on an
     allow-listed https URL (never auth/login/token/session/payment), ONE
     background request per URL per 10 min on a private executor —
     CookieManager cookies + the runtime desktop UA (captured on the main
     thread, no WebView access off-main), Accept: text/html,
     instanceFollowRedirects=false, 10 s timeouts. Stores ONLY HTTP 200 +
     text/html + <= 2 MB; never 3xx, never Set-Cookie (ignored on purpose),
     never Cloudflare/login bodies (pure Policy.SHELL_CHALLENGE_MARKERS:
     "just a moment", "cf-chl", "_cf_chl", "challenge-platform", …).
  2. Offline serving: shouldInterceptRequest now serves main frames ONLY
     when the device is offline + GET + allow-listed host + shell stored —
     exact match first, then SPA deep-link fallback to the start-URL shell
     (same host, path inside /chart; boundary-safe, pure
     Policy.spaShellFallback). Response = 200 text/html utf-8 + the safe
     stored headers. Online main frames still return null immediately;
     everything stays behind try/catch(Throwable)->null and the crash-loop
     kill switch.
  3. Storage: shells reuse the Tier-2 disk cache (same app-private versioned
     dir, LRU eviction, 500 MB cap) with a separate "SHELL|" key namespace
     (SHA-256 of the normalized URL — fragment dropped, sorted query).
     Set-Cookie/Cookie/Authorization can never reach the persisted meta;
     Clear-saved-data wipes shells too and resets the shadow-fetch throttle.
  4. Banner honesty: "Offline: showing saved data" only while a saved
     page/resources are actually served (marker reset per navigation in
     onPageStarted, set by the interceptor, refreshed via runOnUiThread —
     TextView only). Offline + nothing saved => banner AND error page say
     "Offline: nothing saved for this page yet. Open it once while online."
  5. Diagnostics: banner long-press now includes a shell section — offline
     shell-serve count, last shadow-fetch result (stored / skipped:reason /
     failed:code), stored shells as host+path (no query) with size + age.
  6. JVM tests (Phase6PolicyTest): eligibility, key normalization (fragment
     behavior incl. RFC-strict URI rejection), challenge markers, throttle
     boundaries, SPA fallback, version source-of-truth; versionName
     1.6.1 -> 1.7.0. Signing/CI/applicationId untouched.
- **Touched files:** `Policy.kt` (pure shell rules), `Tier2Cache.kt`
  (shadow fetch + store/serve shell + diagnostics + clear), `MainActivity.kt`
  (onPageFinished hook, main-frame offline branch, honest banner/error
  wording), `strings.xml`, `app/build.gradle.kts`,
  `app/src/test/.../Phase6PolicyTest.kt` (new)
- **Commit hashes:** `3160564` item 1 (run #22 green) -> `7b67dbc` item 2
  (run #23) -> `3dcb7b2` item 3 (run #24) -> `5875757` item 4 (run #25) ->
  `605564f` item 5 (run #26) -> `8e9d441` item 6 (run #27 FAILURE — two test
  bugs: marker spelled "cf-chl" missed Cloudflare's underscore variant
  "_cf_chl_opt", and the fragment test used a second '#', which
  java.net.URI refuses by RFC) -> fix `98ddfc1` (run #28 SUCCESS, JVM tests
  green, artifact `LuxChart-release-apk-28`, versionName 1.7.0)
  - this log commit: recorded as repository HEAD at push time
- **Pending items:** none from the Phase 6 scope. Watchlist: shells have no
  TTL by design (stale shell is still better than no shell; "Clear saved
  data" is the reset); subresource offline coverage is unchanged Tier-2
  behavior (shell renders, data APIs come from Tier-2/WebView cache).
- **Notes for next agent:** The interceptor's main-frame branch runs ONLY
  offline — never add online main-frame interception (Cloudflare/logins).
  Shell storage is luxalgo-safe via Policy.shellFetchEligible (https +
  allow-list + sensitive-token rejection); keep new shell rules PURE in
  Policy.kt with JVM tests. Do not touch applicationId
  `com.ferdausfs.luxchart`; signing/CI unchanged.

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
