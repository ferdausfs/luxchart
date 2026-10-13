# AGENT_LOG — LuxChart

Append-only session log. **Newest entry first. Never delete or rewrite past entries.**

Fixed entry template: session date, agent/tool, feature, what changed, touched
files, commit hash, pending items, notes for next agent.

SECURITY: never record tokens, passwords, keystore contents, base64 blobs, or
secret values in this file. Secret *names* and file *names* are allowed.

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
