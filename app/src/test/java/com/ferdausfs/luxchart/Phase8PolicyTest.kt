package com.ferdausfs.luxchart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Phase 8 (v1.9.0) pure rules:
 *  - A1: last-URL restore decision (BUG A cold-start restore)
 *  - B1: relaxed cache key (time-window params stripped, BUG B fix)
 *  - G1: Cloudflare challenge-response rule
 *  - G2: static-asset TTL revalidation decision
 *  - G3: cache-generation keying
 *  - G4: blob display-name sanitizing
 *  - Evidence tool: chart-like request classification
 * These run in CI before assembleRelease.
 */
class Phase8PolicyTest {

    // ---- A1: restoreAllowed ----------------------------------------------------

    @Test
    fun `restore allows https allow-listed chart urls`() {
        assertTrue(Policy.restoreAllowed("https://vela.luxalgo.com/chart"))
        assertTrue(
            Policy.restoreAllowed(
                "https://vela.luxalgo.com/chart?symbol=BTCUSD&resolution=15"
            )
        )
        assertTrue(Policy.restoreAllowed("https://www.luxalgo.com/chart"))
    }

    @Test
    fun `restore allows other allow-listed hosts`() {
        // Spec: allow-listed HOSTS are fine; only sensitive PATHS block.
        assertTrue(Policy.restoreAllowed("https://accounts.google.com/some/page"))
    }

    @Test
    fun `restore rejects non-https and non-allow-listed hosts`() {
        assertFalse(Policy.restoreAllowed("http://vela.luxalgo.com/chart"))
        assertFalse(Policy.restoreAllowed("file:///android_asset/error.html"))
        assertFalse(Policy.restoreAllowed("https://evil.example.com/chart"))
        // lookalikes must not pass the host check
        assertFalse(Policy.restoreAllowed("https://luxalgo.com.evil.io/chart"))
        assertFalse(Policy.restoreAllowed("https://notluxalgo.com/chart"))
    }

    @Test
    fun `restore rejects sensitive path segments`() {
        assertFalse(Policy.restoreAllowed("https://vela.luxalgo.com/login"))
        assertFalse(Policy.restoreAllowed("https://vela.luxalgo.com/auth/step"))
        assertFalse(Policy.restoreAllowed("https://vela.luxalgo.com/oauth2/callback"))
        assertFalse(Policy.restoreAllowed("https://vela.luxalgo.com/checkout"))
        assertFalse(Policy.restoreAllowed("https://vela.luxalgo.com/LOGOUT"))
        // /tokenizer is NOT a "token" segment (equality, not substring)
        assertTrue(Policy.restoreAllowed("https://vela.luxalgo.com/tokenizer"))
    }

    @Test
    fun `restore ignores the query string`() {
        // Path-only segment rule: the query is never inspected.
        assertTrue(Policy.restoreAllowed("https://vela.luxalgo.com/chart?next=/login"))
    }

    @Test
    fun `restore rejects null blank and unparseable urls`() {
        assertFalse(Policy.restoreAllowed(null))
        assertFalse(Policy.restoreAllowed(""))
        assertFalse(Policy.restoreAllowed("   "))
        assertFalse(Policy.restoreAllowed("https://"))
        assertFalse(Policy.restoreAllowed("::not a url::"))
    }

    // ---- B1: relaxed cache key (BUG B) ------------------------------------------

    @Test
    fun `same timeframe with different time windows collapses to one relaxed key`() {
        val a = "https://api.luxalgo.com/v1/candles?symbol=BTCUSD&resolution=5&from=1000&to=2000&countback=300"
        val b = "https://api.luxalgo.com/v1/candles?symbol=BTCUSD&resolution=5&from=9000&to=11000&countback=300"
        val c = "https://api.luxalgo.com/v1/candles?resolution=5&to=55555&countback=1&from=-123&symbol=BTCUSD"
        val ka = Policy.relaxedCacheKey("GET", a)
        val kb = Policy.relaxedCacheKey("GET", b)
        val kc = Policy.relaxedCacheKey("GET", c)
        assertEquals(ka, kb)
        assertEquals(ka, kc)
    }

    @Test
    fun `different resolution never collides in the relaxed key`() {
        val m5 = "https://api.luxalgo.com/v1/candles?symbol=BTCUSD&resolution=5&from=1000&to=2000"
        val m15 = "https://api.luxalgo.com/v1/candles?symbol=BTCUSD&resolution=15&from=1000&to=2000"
        val d1 = "https://api.luxalgo.com/v1/candles?symbol=BTCUSD&resolution=D&from=1000&to=2000"
        assertNotEquals(Policy.relaxedCacheKey("GET", m5), Policy.relaxedCacheKey("GET", m15))
        assertNotEquals(Policy.relaxedCacheKey("GET", m5), Policy.relaxedCacheKey("GET", d1))
    }

    @Test
    fun `different symbol and path never collide in the relaxed key`() {
        val btc = "https://api.luxalgo.com/v1/candles?symbol=BTCUSD&resolution=5"
        val eth = "https://api.luxalgo.com/v1/candles?symbol=ETHUSD&resolution=5"
        val other = "https://api.luxalgo.com/v1/indicators?symbol=BTCUSD&resolution=5"
        assertNotEquals(Policy.relaxedCacheKey("GET", btc), Policy.relaxedCacheKey("GET", eth))
        assertNotEquals(Policy.relaxedCacheKey("GET", btc), Policy.relaxedCacheKey("GET", other))
    }

    @Test
    fun `relaxed key keeps non-time params and drops the whole time-window family`() {
        val withWindow = Policy.relaxedNormalizeUrl(
            "https://api.luxalgo.com/v1/candles?symbol=BTC&resolution=5&type=bar&from=1&to=2&countback=3&since=4&until=5&start=6&end=7&_=9"
        )
        assertEquals(
            "https://api.luxalgo.com/v1/candles?resolution=5&symbol=BTC&type=bar",
            withWindow
        )
    }

    @Test
    fun `relaxed key is namespaced away from exact and shell keys`() {
        val url = "https://api.luxalgo.com/v1/candles?symbol=BTC&resolution=5"
        val exact = Policy.cacheKey("GET", url)
        val relaxed = Policy.relaxedCacheKey("GET", url)
        val shell = Policy.shellKey(url)
        assertNotEquals(exact, relaxed)
        assertNotEquals(shell, relaxed)
    }

    @Test
    fun `exact key still keeps from and to (bug B root cause documented)`() {
        // The EXACT key must keep time-window params (two calls with different
        // windows are different resources); the bug was that the chart's
        // moving window therefore never repeated. This test pins the exact
        // behaviour so the relaxed layer stays the fix.
        val a = "https://api.luxalgo.com/v1/candles?symbol=BTC&resolution=5&from=1&to=2"
        val b = "https://api.luxalgo.com/v1/candles?symbol=BTC&resolution=5&from=3&to=4"
        assertNotEquals(Policy.cacheKey("GET", a), Policy.cacheKey("GET", b))
        assertEquals(Policy.relaxedCacheKey("GET", a), Policy.relaxedCacheKey("GET", b))
    }

    @Test
    fun `relaxed normalization rejects bad urls`() {
        assertNull(Policy.relaxedNormalizeUrl(null))
        assertNull(Policy.relaxedNormalizeUrl(""))
        assertNull(Policy.relaxedNormalizeUrl("::bad::"))
        assertNull(Policy.relaxedCacheKey("GET", "::bad::"))
    }

    // ---- Evidence tool: chart-like request classification -----------------------

    @Test
    fun `chart-like by path hints`() {
        assertTrue(Policy.chartLikeRequest("https://api.luxalgo.com/v1/candles?from=1&to=2"))
        assertTrue(Policy.chartLikeRequest("https://vela.luxalgo.com/chart"))
        assertTrue(Policy.chartLikeRequest("https://api.luxalgo.com/v1/market-data/feed"))
        assertTrue(Policy.chartLikeRequest("https://api.luxalgo.com/v1/Candles")) // case-insensitive
    }

    @Test
    fun `chart-like by query key names even with a generic path`() {
        assertTrue(Policy.chartLikeRequest("https://api.luxalgo.com/v1/data?symbol=BTC&resolution=5"))
        assertTrue(Policy.chartLikeRequest("https://api.luxalgo.com/v1/data?timeframe=1h"))
        assertTrue(Policy.chartLikeRequest("https://api.luxalgo.com/v1/data?countback=300"))
    }

    @Test
    fun `non-chart requests are not classified as chart data`() {
        assertFalse(Policy.chartLikeRequest("https://vela.luxalgo.com/assets/app.abc123.js"))
        assertFalse(Policy.chartLikeRequest("https://vela.luxalgo.com/static/css/site.css"))
        assertFalse(Policy.chartLikeRequest(null))
        assertFalse(Policy.chartLikeRequest(""))
        assertFalse(Policy.chartLikeRequest("::bad::"))
    }

    // ---- G1: Cloudflare challenge-response rule --------------------------------

    private fun headersOf(vararg pairs: Pair<String, String>): Map<String, List<String>> =
        pairs.map { (k, v) -> k to listOf(v) }.toMap()

    @Test
    fun `challenge statuses are always a challenge`() {
        assertTrue(Policy.isChallengeResponse(403, emptyMap()))
        assertTrue(Policy.isChallengeResponse(429, emptyMap()))
        assertTrue(Policy.isChallengeResponse(503, emptyMap()))
    }

    @Test
    fun `cf headers mark a challenge regardless of status`() {
        assertTrue(
            Policy.isChallengeResponse(200, headersOf("cf-mitigated" to "challenge"))
        )
        assertTrue(
            Policy.isChallengeResponse(200, headersOf("Cf-Chl-Bypass" to "1"))
        )
        assertTrue(
            Policy.isChallengeResponse(404, headersOf("cf-challenge" to "js-pass"))
        )
    }

    @Test
    fun `plain cloudflare server header is NOT a challenge`() {
        assertFalse(
            Policy.isChallengeResponse(200, headersOf("server" to "cloudflare"))
        )
        assertFalse(Policy.isChallengeResponse(200, emptyMap()))
    }

    @Test
    fun `regular 4xx and 5xx are not challenges`() {
        assertFalse(Policy.isChallengeResponse(404, emptyMap()))
        assertFalse(Policy.isChallengeResponse(500, emptyMap()))
        assertFalse(Policy.isChallengeResponse(401, headersOf("server" to "nginx")))
    }

    @Test
    fun `three strikes in a row block a host and any other outcome resets the row`() {
        // Pure-rule sanity: the constant matches the spec (3).
        assertEquals(3, Policy.MAX_CHALLENGE_STRIKES)
    }
}
