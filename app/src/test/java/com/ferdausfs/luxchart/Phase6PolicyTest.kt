package com.ferdausfs.luxchart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Phase 6 (v1.7.0) pure helpers: shell eligibility,
 * shell key normalization (fragment-free, namespaced), challenge/login
 * markers, the 10-minute shadow-fetch throttle and the SPA deep-link
 * fallback rule. These run in CI before assembleRelease.
 */
class Phase6PolicyTest {

    private val START = "https://vela.luxalgo.com/chart"

    // ---- shell eligibility -----------------------------------------------------

    @Test
    fun `start URL is shell-eligible`() {
        assertTrue(Policy.shellFetchEligible(START))
        assertTrue(Policy.shellFetchEligible("https://api.luxalgo.com/page")) // subdomain
        assertTrue(Policy.shellFetchEligible("  $START  ")) // whitespace tolerated
    }

    @Test
    fun `non-https or non-allow-listed URLs are never eligible`() {
        assertFalse(Policy.shellFetchEligible("http://vela.luxalgo.com/chart")) // no https
        assertFalse(Policy.shellFetchEligible(null))
        assertFalse(Policy.shellFetchEligible(""))
        assertFalse(Policy.shellFetchEligible("   "))
        assertFalse(Policy.shellFetchEligible("file:///android_asset/error.html"))
        assertFalse(Policy.shellFetchEligible("https://evil.example.com/chart"))
        assertFalse(Policy.shellFetchEligible("https://notluxalgo.com/chart")) // lookalike
    }

    @Test
    fun `sensitive URLs are never eligible`() {
        assertFalse(Policy.shellFetchEligible("$START?token=SECRET"))
        assertFalse(Policy.shellFetchEligible("https://vela.luxalgo.com/auth/login"))
        assertFalse(Policy.shellFetchEligible("https://vela.luxalgo.com/login?next=/chart"))
        assertFalse(Policy.shellFetchEligible("https://vela.luxalgo.com/session/new"))
        assertFalse(Policy.shellFetchEligible("https://vela.luxalgo.com/payment/upgrade"))
    }

    // ---- shell key normalization -------------------------------------------------

    @Test
    fun `fragment never changes the shell key`() {
        assertEquals(
            Policy.shellKey(START),
            Policy.shellKey("$START#pane")
        )
        assertEquals(
            Policy.shellKey("$START#deeply/nested#twice"),
            Policy.shellKey(START)
        )
    }

    @Test
    fun `different queries produce different keys`() {
        assertNotEquals(
            Policy.shellKey(START),
            Policy.shellKey("$START?symbol=BTC")
        )
    }

    @Test
    fun `shell keys live in their own namespace apart from GET keys`() {
        val url = "$START?symbol=BTC"
        assertNotEquals(Policy.shellKey(url), Policy.cacheKey("GET", url))
        assertNotEquals(
            Policy.shellKey(url),
            Policy.cacheKey("GET", url.substringBefore('?'))
        )
    }

    @Test
    fun `unparseable URLs have no shell key`() {
        assertNull(Policy.shellKey(null))
        assertNull(Policy.shellKey(""))
        assertNull(Policy.shellKey("not a url \\ x"))
    }

    // ---- challenge / login body markers ------------------------------------------

    @Test
    fun `cloudflare challenge bodies are detected`() {
        assertTrue(Policy.looksLikeChallengeOrLogin("Just a moment..."))
        assertTrue(Policy.looksLikeChallengeOrLogin("<script src=\"/cdn-cgi/challenge-platform/h/g/orchestrate\">"))
        assertTrue(Policy.looksLikeChallengeOrLogin("window._cf_chl_opt = {}"))
        assertTrue(Policy.looksLikeChallengeOrLogin("Please stand by, we are checking your browser before allowing access"))
        // case-insensitive
        assertTrue(Policy.looksLikeChallengeOrLogin("JUST A MOMENT"))
    }

    @Test
    fun `normal page bodies pass the marker check`() {
        assertFalse(Policy.looksLikeChallengeOrLogin("<html><body><canvas id=\"chart\"></canvas></body></html>"))
        assertFalse(Policy.looksLikeChallengeOrLogin("{\"ok\":true,\"candles\":[]}"))
        assertFalse(Policy.looksLikeChallengeOrLogin(""))
        assertFalse(Policy.looksLikeChallengeOrLogin(null))
    }

    // ---- shadow-fetch throttle -----------------------------------------------------

    @Test
    fun `first attempt is always allowed`() {
        assertTrue(Policy.shellThrottleAllowed(0L, 1_000L, Policy.SHELL_THROTTLE_MS))
        assertTrue(Policy.shellThrottleAllowed(-5L, 1_000L, Policy.SHELL_THROTTLE_MS))
    }

    @Test
    fun `within the interval is throttled, at or after it is allowed`() {
        val interval = Policy.SHELL_THROTTLE_MS // 10 minutes
        val last = 1_000_000L
        assertFalse(Policy.shellThrottleAllowed(last, last + interval - 1, interval))
        assertTrue(Policy.shellThrottleAllowed(last, last + interval, interval))
        assertTrue(Policy.shellThrottleAllowed(last, last + interval + 1, interval))
    }

    // ---- SPA deep-link fallback ------------------------------------------------------

    @Test
    fun `start path and deeper paths fall back to the start shell`() {
        assertTrue(Policy.spaShellFallback(START, START)) // exact
        assertTrue(Policy.spaShellFallback("$START?symbol=BTC&tf=1D", START)) // query only
        assertTrue(Policy.spaShellFallback("https://vela.luxalgo.com/chart/layout/2", START)) // deeper
    }

    @Test
    fun `paths outside the start path never fall back`() {
        assertFalse(Policy.spaShellFallback("https://vela.luxalgo.com/settings", START))
        assertFalse(Policy.spaShellFallback("https://vela.luxalgo.com/charts", START)) // boundary
        assertFalse(Policy.spaShellFallback("https://vela.luxalgo.com/", START))
    }

    @Test
    fun `other hosts and broken input never fall back`() {
        assertFalse(Policy.spaShellFallback("https://www.luxalgo.com/chart", START)) // different subdomain
        assertFalse(Policy.spaShellFallback("https://evil.example.com/chart", START))
        assertFalse(Policy.spaShellFallback(null, START))
        assertFalse(Policy.spaShellFallback(START, null))
    }

    // ---- version (single source of truth) ----------------------------------------------

    @Test
    fun `versionName source of truth is 1_7_0`() {
        // Android JVM unit tests run with the module dir (app/) as working dir;
        // check both layouts so the test is independent of the runner's cwd.
        val gradle = listOf(
            java.io.File("build.gradle.kts"),
            java.io.File("app/build.gradle.kts")
        ).firstOrNull { it.exists() }
        assertTrue("app/build.gradle.kts not reachable from test working dir", gradle != null)
        assertTrue(gradle!!.readText().contains("APP_VERSION_NAME = \"1.7.0\""))
    }
}
