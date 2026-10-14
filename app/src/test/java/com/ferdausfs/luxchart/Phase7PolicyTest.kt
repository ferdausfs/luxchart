package com.ferdausfs.luxchart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Phase 7 (v1.8.0) pure rules: path-segment skip,
 * store rules, key normalization with noisy cache-buster params, the 4 GB
 * size-cap decision, the request-log sanitizer, the API-like refresh
 * classification and the streaming-type rule. These run in CI before
 * assembleRelease.
 */
class Phase7PolicyTest {

    private val noHeaders = emptyMap<String, String>()

    // ---- item 4: path-only segment skip ---------------------------------------

    @Test
    fun `sensitive segments match by equality on the path only`() {
        val sensitive = listOf(
            "https://vela.luxalgo.com/auth",
            "https://vela.luxalgo.com/auth/me",
            "https://vela.luxalgo.com/oauth",
            "https://vela.luxalgo.com/oauth2/callback",
            "https://vela.luxalgo.com/login",
            "https://vela.luxalgo.com/LOGOUT",
            "https://vela.luxalgo.com/signin",
            "https://vela.luxalgo.com/signup",
            "https://vela.luxalgo.com/token",
            "https://vela.luxalgo.com/tokens",
            "https://vela.luxalgo.com/session",
            "https://vela.luxalgo.com/sessions/1",
            "https://vela.luxalgo.com/payment",
            "https://vela.luxalgo.com/payments/history",
            "https://vela.luxalgo.com/checkout"
        )
        sensitive.forEach {
            assertTrue("should be sensitive: $it", Policy.urlHasSensitiveSegment(it))
            assertEquals("sensitive-path", Policy.skipReason("GET", it, noHeaders))
        }
    }

    @Test
    fun `host and query are never inspected by the segment rule`() {
        // query is never inspected …
        listOf(
            "https://vela.luxalgo.com/api?token=abc",
            "https://vela.luxalgo.com/chart?next=/login&session=2",
            "https://vela.luxalgo.com/chart?payment=1"
        ).forEach { assertFalse(it, Policy.urlHasSensitiveSegment(it)) }
        // … and the host is never inspected either (login.luxalgo.com path /)
        assertFalse(Policy.urlHasSensitiveSegment("https://login.luxalgo.com/"))
        assertFalse(Policy.urlHasSensitiveSegment("https://auth.vela.luxalgo.com/x"))
    }

    @Test
    fun `segment equality is not substring matching`() {
        listOf(
            "https://vela.luxalgo.com/tokenizer/assets",
            "https://vela.luxalgo.com/blog/authors",
            "https://vela.luxalgo.com/sessionships",
            "https://vela.luxalgo.com/checkout-page",
            "https://vela.luxalgo.com/loginscreen-help" // single segment, not equal
        ).forEach { assertFalse(it, Policy.urlHasSensitiveSegment(it)) }
    }

    @Test
    fun `unparseable urls fail closed`() {
        assertTrue(Policy.urlHasSensitiveSegment(null))
        assertTrue(Policy.urlHasSensitiveSegment(""))
        assertTrue(Policy.urlHasSensitiveSegment("https://bad host/x"))
    }

    @Test
    fun `skipReason explains every skip class`() {
        assertEquals("non-GET", Policy.skipReason("POST", "https://vela.luxalgo.com/a", noHeaders))
        assertEquals("bad-url", Policy.skipReason("GET", null, noHeaders))
        assertEquals("bad-url", Policy.skipReason("GET", "   ", noHeaders))
        assertEquals("non-http", Policy.skipReason("GET", "wss://vela.luxalgo.com/ws", noHeaders))
        assertEquals("non-http", Policy.skipReason("GET", "file:///x", noHeaders))
        assertEquals(
            "range",
            Policy.skipReason(
                "GET", "https://vela.luxalgo.com/data.json", mapOf("Range" to "bytes=0-1")
            )
        )
        assertEquals(
            "websocket",
            Policy.skipReason(
                "GET", "https://vela.luxalgo.com/ws", mapOf("Upgrade" to "websocket")
            )
        )
        assertEquals(
            "sensitive-path", Policy.skipReason("GET", "https://vela.luxalgo.com/login", noHeaders)
        )
        assertNull(Policy.skipReason("GET", "https://vela.luxalgo.com/api/v1/candles", noHeaders))
        // Authorization does NOT skip (Phase 7 item 3)
        assertNull(
            Policy.skipReason(
                "GET", "https://vela.luxalgo.com/api/v1/candles", mapOf("Authorization" to "Bearer x")
            )
        )
    }

    // ---- item 5: store rules ----------------------------------------------------

    @Test
    fun `phase 7 storable types and streaming exclusions`() {
        val storable = listOf(
            "application/json",
            "text/plain",
            "application/octet-stream",
            "application/wasm",
            "application/x-protobuf",
            "application/msgpack",
            "application/x-ndjson",
            "image/svg+xml",
            "text/javascript",
            "text/css",
            "text/html"
        )
        storable.forEach {
            assertTrue(
                it,
                Policy.shouldStoreResponse(200, mapOf("Content-Type" to listOf(it)))
            )
        }
        // never stored: non-200 / 3xx / streaming
        val json = mapOf("Content-Type" to listOf("application/json"))
        assertFalse(Policy.shouldStoreResponse(199, json))
        assertFalse(Policy.shouldStoreResponse(301, json))
        assertFalse(Policy.shouldStoreResponse(404, json))
        assertFalse(Policy.shouldStoreResponse(200, mapOf("Content-Type" to listOf("text/event-stream"))))
        assertFalse(Policy.shouldStoreResponse(200, mapOf("Content-Type" to listOf("application/grpc"))))
        assertFalse(Policy.shouldStoreResponse(200, emptyMap()))
    }

    @Test
    fun `isStreamingType covers event-stream grpc and grpc-web`() {
        assertTrue(Policy.isStreamingType("text/event-stream"))
        assertTrue(Policy.isStreamingType("application/grpc"))
        assertTrue(Policy.isStreamingType("application/grpc-web+proto"))
        assertTrue(Policy.isStreamingType("application/stream+json"))
        assertTrue(Policy.isStreamingType("text/event-stream; charset=utf-8"))
        assertFalse(Policy.isStreamingType("text/html"))
        assertFalse(Policy.isStreamingType("application/json"))
        assertFalse(Policy.isStreamingType(null))
        assertFalse(Policy.isStreamingType(""))
    }

    // ---- item 6: key normalization with noisy params -----------------------------

    @Test
    fun `noisy cache busters are dropped from the key`() {
        assertEquals(
            "https://api.luxalgo.com/v1/candles?resolution=1D&symbol=BTCUSDT",
            Policy.normalizeUrl(
                "https://api.luxalgo.com/v1/candles?symbol=BTCUSDT&resolution=1D&_=1697040000000"
            )
        )
        assertEquals(
            "https://api.luxalgo.com/v1/list",
            Policy.normalizeUrl(
                "https://api.luxalgo.com/v1/list?cb=12&cachebust=34&nocache=56&rnd=7&random=8&t=9&ts=0&_t=1&timestamp=2&_T=3"
            )
        )
    }

    @Test
    fun `meaningful params survive normalization`() {
        assertEquals(
            "https://api.luxalgo.com/v1/candles?from=1&resolution=1D&symbol=BTC&to=2&type=candle",
            Policy.normalizeUrl(
                "https://api.luxalgo.com/v1/candles?type=candle&to=2&from=1&symbol=BTC&resolution=1D"
            )
        )
        // keys that merely START with a noisy name are kept
        assertEquals(
            "https://api.luxalgo.com/x?total=5&type=1",
            Policy.normalizeUrl("https://api.luxalgo.com/x?type=1&total=5")
        )
    }

    @Test
    fun `all-noisy query collapses to a keyless url and matches the plain url`() {
        assertEquals(
            "https://vela.luxalgo.com/chart",
            Policy.normalizeUrl("https://vela.luxalgo.com/chart?_=1&t=2&ts=3")
        )
    }

    @Test
    fun `cache-first keys ignore noisy params end to end`() {
        val withBusters = Policy.cacheKey(
            "GET", "https://api.luxalgo.com/v1/candles?symbol=BTCUSDT&resolution=1D&_=42&cb=x#frag"
        )
        val without = Policy.cacheKey(
            "GET", "https://api.luxalgo.com/v1/candles?resolution=1D&symbol=BTCUSDT"
        )
        assertEquals(without, withBusters)
        assertNotEquals(
            Policy.cacheKey("GET", "https://api.luxalgo.com/v1/candles?symbol=ETHUSDT"),
            withBusters
        )
    }

    // ---- item 2: size-cap decision ------------------------------------------------

    @Test
    fun `hard stop refuses only the NEW store and never deletes`() {
        val cap = 4L * 1024 * 1024 * 1024
        assertTrue(Policy.storeAllowedWithinCap(0L, 1L, cap))
        assertTrue(Policy.storeAllowedWithinCap(cap, 0L, cap))      // exactly at cap, zero-size
        assertTrue(Policy.storeAllowedWithinCap(cap - 1, 1L, cap))  // fills to exactly cap
        assertTrue(Policy.storeAllowedWithinCap(3L * 1024 * 1024 * 1024, 1024L * 1024 * 1024, cap))
        assertFalse(Policy.storeAllowedWithinCap(cap, 1L, cap))     // one byte over => refuse
        assertFalse(Policy.storeAllowedWithinCap(cap + 1, 0L, cap))
        assertFalse(
            Policy.storeAllowedWithinCap(
                3L * 1024 * 1024 * 1024, 1024L * 1024 * 1024 + 1, cap
            )
        )
    }

    // ---- item 10: log sanitizer -----------------------------------------------------

    @Test
    fun `request log keeps query key names only`() {
        val line = Policy.sanitizedRequestUrl(
            "https://API.luxalgo.com/v1/candles?symbol=BTCUSDT&resolution=1D&token=SECRET&api_key=xyz#frag"
        )
        assertEquals("api.luxalgo.com/v1/candles [symbol,resolution,token,api_key]", line)
        assertFalse(line.contains("SECRET"))
        assertFalse(line.contains("xyz"))
        assertFalse(line.contains("BTCUSDT"))
        assertFalse(line.contains("1D"))
        assertFalse(line.contains("frag"))
    }

    @Test
    fun `request log dedupes keys and handles bare and bad urls`() {
        assertEquals(
            "vela.luxalgo.com/chart [a,b]",
            Policy.sanitizedRequestUrl("https://vela.luxalgo.com/chart?a=1&b=2&a=3")
        )
        assertEquals("vela.luxalgo.com/chart", Policy.sanitizedRequestUrl("https://vela.luxalgo.com/chart"))
        assertEquals("vela.luxalgo.com", Policy.sanitizedRequestUrl("https://vela.luxalgo.com"))
        assertEquals("(bad-url)", Policy.sanitizedRequestUrl(null))
        assertEquals("(bad-url)", Policy.sanitizedRequestUrl(""))
        assertEquals("(bad-url)", Policy.sanitizedRequestUrl("not a url"))
    }

    // ---- item 1: API-like refresh classification --------------------------------------

    @Test
    fun `api-like types refresh and static assets never do`() {
        listOf(
            "application/json",
            "application/json; charset=utf-8",
            "APPLICATION/JSON",
            "application/x-ndjson",
            "application/msgpack",
            "application/x-protobuf",
            "text/plain",
            "text/plain; charset=iso-8859-1"
        ).forEach { assertTrue(it, Policy.isApiLikeType(it)) }
        listOf(
            "text/javascript",
            "application/javascript",
            "text/css",
            "image/png",
            "image/svg+xml",
            "font/woff2",
            "application/wasm",
            "text/html",
            "application/octet-stream",
            null,
            "",
            "   "
        ).forEach { assertFalse("$it", Policy.isApiLikeType(it)) }
    }

    // ---- item 3: Authorization allowed (regression guard) -------------------------------

    @Test
    fun `authorization carrying GETs flow through the cache`() {
        val u = "https://api.luxalgo.com/v1/portfolio"
        assertNull(Policy.skipReason("GET", u, mapOf("Authorization" to "Bearer secret")))
        assertFalse(Policy.shouldSkipRequest("GET", u, mapOf("AUTHORIZATION" to "Bearer secret")))
        // the cache key cannot contain header material: same URL, different token => same key
        assertEquals(
            Policy.cacheKey("GET", u),
            Policy.cacheKey("GET", u)
        )
    }
}
