package com.ferdausfs.luxchart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the hotfix 1.6.1 pure helpers: request-header
 * forwarding filter, Range skip rule, crash-report sanitizer and the
 * crash-loop kill switch. These run in CI before assembleRelease.
 */
class HotfixPolicyTest {

    // ---- item 3: header forwarding filter ------------------------------------

    @Test
    fun `only Origin Referer Accept Accept-Language are forwarded`() {
        val out = Policy.forwardableRequestHeaders(
            mapOf(
                "Origin" to "https://vela.luxalgo.com",
                "referer" to "https://vela.luxalgo.com/chart",
                "ACCEPT" to "application/json",
                "accept-language" to "en-US,en;q=0.9",
                "Authorization" to "Bearer secret",
                "User-Agent" to "spoof",
                "Range" to "bytes=0-100",
                "Cookie" to "a=b",
                "X-Custom" to "x"
            )
        )
        assertEquals(
            mapOf(
                "Origin" to "https://vela.luxalgo.com",
                "Referer" to "https://vela.luxalgo.com/chart",
                "Accept" to "application/json",
                "Accept-Language" to "en-US,en;q=0.9"
            ),
            out
        )
    }

    @Test
    fun `header forwarding drops blank values and handles null or empty`() {
        assertTrue(Policy.forwardableRequestHeaders(null).isEmpty())
        assertTrue(Policy.forwardableRequestHeaders(emptyMap()).isEmpty())
        assertTrue(
            Policy.forwardableRequestHeaders(mapOf("Origin" to " ", "Accept" to "*/*"))
                .containsKey("Accept")
        )
        assertFalse(
            Policy.forwardableRequestHeaders(mapOf("Origin" to "  ")).containsKey("Origin")
        )
    }

    @Test
    fun `case variants collapse to the canonical header name`() {
        val out = Policy.forwardableRequestHeaders(mapOf("oRiGiN" to "https://x.y"))
        assertEquals(listOf("Origin"), out.keys.toList())
    }

    // ---- item 3: Range requests are never intercepted -------------------------

    @Test
    fun `Range requests are skipped and plain GETs are not`() {
        assertTrue(
            Policy.shouldSkipRequest(
                "GET", "https://vela.luxalgo.com/data.json",
                mapOf("Range" to "bytes=0-1023")
            )
        )
        assertTrue(
            Policy.shouldSkipRequest(
                "GET", "https://vela.luxalgo.com/data.json",
                mapOf("range" to "bytes=100-")
            )
        )
        assertFalse(
            Policy.shouldSkipRequest(
                "GET", "https://vela.luxalgo.com/data.json", emptyMap()
            )
        )
    }

    // ---- item 4: crash-report sanitizer ----------------------------------------

    @Test
    fun `sanitizer strips URL query strings`() {
        val out = Policy.sanitizeCrashText(
            "url was: https://vela.luxalgo.com/chart?token=SECRET&sid=1 end"
        )
        assertFalse(out.contains("SECRET"))
        assertFalse(out.contains("sid=1"))
        assertTrue(out.contains("https://vela.luxalgo.com/chart"))
    }

    @Test
    fun `sanitizer redacts cookie values but keeps structure`() {
        val out = Policy.sanitizeCrashText(
            "Cookie: sessionid=abc123; csrftoken=xyz\nSet-Cookie: a=b\nframe ok"
        )
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("a=b"))
        assertFalse(out.contains("csrftoken=xyz"))
        assertTrue(out.contains("<redacted>"))
        assertTrue(out.contains("Set-Cookie:"))
        assertTrue(out.contains("frame ok"))
    }

    @Test
    fun `sanitizer keeps normal stack frames untouched`() {
        val line = "\tat android.webkit.WebView.shouldInterceptRequest(WebView.java:1)"
        assertEquals(line, Policy.sanitizeCrashText(line))
    }

    @Test
    fun `sanitizer caps output length`() {
        val out = Policy.sanitizeCrashText("x".repeat(50_000))
        assertTrue(out.length <= Policy.CRASH_TEXT_MAX_CHARS + 20)
        assertTrue(out.endsWith("(truncated)"))
    }

    // ---- item 5: crash-loop kill switch ----------------------------------------

    @Test
    fun `kill switch trips at 2 consecutive launch crashes`() {
        assertFalse(Policy.tier2KillSwitch(0))
        assertFalse(Policy.tier2KillSwitch(1))
        assertTrue(Policy.tier2KillSwitch(2))
        assertTrue(Policy.tier2KillSwitch(3))
    }
}
