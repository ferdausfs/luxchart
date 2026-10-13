# LuxChart

Lightweight native Android app (Kotlin, single Activity + WebView) that loads
https://www.luxalgo.com/ and its web platform in a desktop-mode WebView, so the
charts feel closer to a native app than the mobile browser. Personal use only.

## Current scope (CI FIRST skeleton)
- WebView loading `https://www.luxalgo.com/`
- Desktop user-agent + wide viewport (chart features are not hidden)
- Allow-list kept inside the WebView: `luxalgo.com` (+ subdomains),
  `accounts.google.com`, `appleid.apple.com`, `facebook.com` (+ subdomains)
- Everything else opens in the external browser
- Signed release APK built by GitHub Actions on every push to `main`

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
