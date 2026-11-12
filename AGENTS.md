# AGENTS.md — agent instructions for LuxChart

Native Android app (Kotlin, single Activity + WebView) wrapping
https://vela.luxalgo.com/chart in desktop mode. Personal, single-user app.

## Working agreements (enforced by the owner)
- ONE commit per finding; every push must leave CI green
  (.github/workflows/release.yml: testDebugUnitTest -> lintRelease ->
  signed assembleRelease -> apksigner verify).
- AGENT_LOG.md is append-only, newest entry first — every session appends
  an entry using the existing template. Never record secret VALUES, only
  names.
- Untouchable without explicit owner approval: applicationId
  (com.ferdausfs.luxchart), keystore/Actions secrets, the main-frame
  allow-list behaviour, "saved data stays until cleared" (NO auto-deletion
  of cache entries), versionCode = CI run number, webkit 1.12.1 pin
  (Phase 8 G4), the hotfix-1.6.1 protections (crash-loop kill switch,
  thread rule, redirect rule).

## Architecture in one breath
- `Policy.kt` — PURE decision logic (NO Android imports, JVM-tested):
  navigation allow-list (`ALLOWED_HOSTS` = single source of truth),
  cache skip/store rules, URL normalization, challenge detection,
  crash-text sanitizer, desktop UA builder.
- `Tier2Cache.kt` — stale-while-revalidate offline disk cache (4 GB cap,
  no eviction, generation-scoped keys) + main-page shell shadow fetch.
  Must ALWAYS degrade to null on any doubt — never throw through the
  interceptor, never break a page.
- `MainActivity.kt` — single Activity: WebView wiring, immersive UI,
  bridges (LuxBridge / LuxBlob), downloads, diagnostics.

## Hard rules learned the hard way (never regress)
- `shouldInterceptRequest` runs on a WebView IO thread: NEVER touch the
  WebView/WebSettings there; the UA comes from `@Volatile requestUserAgent`.
- `Policy.kt` must stay free of Android imports (pure JVM tests).
- JS bridge methods are the documented ones only and are kept by ProGuard
  rules; bridges re-check state/host before acting.
- Every user-visible string lives in `strings.xml`.
- Blob/data file writes and downloads never run on the main thread.

## Agents & skills installed here (ECC setup)
- `.claude/agents/` — kotlin-reviewer, kotlin-build-resolver,
  security-reviewer, silent-failure-hunter, performance-optimizer,
  code-simplifier, code-explorer, tdd-guide, bengali-reviewer
- `.claude/skills/` — android-clean-architecture, kotlin-patterns,
  kotlin-coroutines-flows, kotlin-testing, coding-standards,
  codebase-onboarding, tdd-workflow, verification-loop, change-impact,
  security-review
- `.claude/commands/` — `/kotlin-review`, `/kotlin-build`, `/kotlin-test`,
  `/gradle-build`, `/code-review`, `/build-fix`

Run `/kotlin-review` before opening a PR; `/kotlin-build` mirrors the CI
steps (`./gradlew testDebugUnitTest lintRelease assembleRelease`).
