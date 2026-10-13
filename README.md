# LuxChart

Lightweight native Android app (Kotlin, single Activity + WebView) that loads
https://www.luxalgo.com/ and its web platform in a desktop-mode WebView, so the
charts feel closer to a native app than the mobile browser. Personal use only.

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
  DownloadManager for downloads (public Downloads dir; legacy
  WRITE_EXTERNAL_STORAGE runtime request only on API < 29, app-private
  fallback if denied)
- **Splash screen** — androidx core-splashscreen, dark, held until the WebView
  starts painting (no white flash; reduced-motion respected)
- **Adaptive icon** — foreground/background/monochrome layers (API 26+);
  legacy PNGs on API 24-25
- **UI polish** — thin animated top progress bar, dark theme consistent across
  window/splash/WebView/bars, edge-to-edge inset handling (incl. keyboard)

## Allow-list (stays inside the WebView)
`luxalgo.com` (+ subdomains), `accounts.google.com`, `appleid.apple.com`,
`facebook.com` (+ subdomains). Everything else opens in the external browser.

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
4. `./gradlew assembleRelease` (versionCode = run number)
5. `apksigner verify --verbose`
6. upload artifact `LuxChart-release-apk-<run_number>` (90-day retention)
7. delete temp keystore

Secret values are never printed. The keystore is never committed.

## Versioning
- `versionName` — defined once in `app/build.gradle.kts` (`APP_VERSION_NAME`)
- `versionCode` — `github.run_number` in CI (locally defaults to 1)

## Project rules
- `applicationId` `com.ferdausfs.luxchart` is permanent — never change
- one feature per session; session log in `AGENT_LOG.md` (newest first)
- pending features are listed in `AGENT_LOG.md` — do not build without approval
- same keystore always; the .jks and passwords live outside this repo
