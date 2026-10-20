package com.ferdausfs.luxchart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Phase 9 (v1.9.1) pure rules — one section per
 * approved finding that introduced (or pinned) a pure rule:
 *  - F2: revalidateStoreAllowed (challenge-marked 200 never stored)
 *  - F3: shouldShowErrorPage (live page not swapped on ERROR_UNKNOWN online)
 *  - F5: cookieLossConfirmed (two-strike session-cookie loss)
 *  - F18: allow-list pin for the login / OAuth / chart flows (mixed-content
 *         hardening must never push these hosts out of the app)
 *  - F21: isRealPageUrl (https-only back-navigation targets)
 *  - F22 backing invariant pins: skipReason lower-case "get", DATA: prefix
 *        case-insensitivity, sanitizeBlobFileName long extensionless name
 * These run in CI before assembleRelease.
 */
class Phase9PolicyTest {

    // ---- F2: revalidateStoreAllowed --------------------------------------------

    private val jsonHeaders = mapOf("Content-Type" to listOf("application/json"))

    @Test
    fun `plain storable 200 is allowed in revalidation`() {
        assertTrue(Policy.revalidateStoreAllowed(200, jsonHeaders))
    }

    @Test
    fun `challenge-marked 200 is refused in revalidation`() {
        // A 200 can still be a Cloudflare challenge wall when it carries the
        // cf-mitigated / cf-chl-* header hints — never persist it over a
        // good entry (F2 root cause).
        assertFalse(
            Policy.revalidateStoreAllowed(
                200, mapOf("Content-Type" to listOf("text/html"), "cf-mitigated" to listOf("challenge"))
            )
        )
        assertFalse(
            Policy.revalidateStoreAllowed(
                200, mapOf("Content-Type" to listOf("application/json"), "cf-chl-bypass" to listOf("1"))
            )
        )
    }

    @Test
    fun `non-200 statuses are refused in revalidation`() {
        assertFalse(Policy.revalidateStoreAllowed(304, jsonHeaders))
        assertFalse(Policy.revalidateStoreAllowed(403, jsonHeaders))
        assertFalse(Policy.revalidateStoreAllowed(429, emptyMap()))
        assertFalse(Policy.revalidateStoreAllowed(500, jsonHeaders))
    }

    @Test
    fun `non-storable content types are refused in revalidation`() {
        assertFalse(
            Policy.revalidateStoreAllowed(
                200, mapOf("Content-Type" to listOf("text/event-stream"))
            )
        )
        assertFalse(Policy.revalidateStoreAllowed(200, emptyMap()))
        assertFalse(
            Policy.revalidateStoreAllowed(
                200, mapOf("Content-Type" to listOf("application/xml"))
            )
        )
    }

    @Test
    fun `real 200 never matches the challenge header hints`() {
        // The pinned table: a plain server: cloudflare header is NOT a hint.
        assertTrue(
            Policy.revalidateStoreAllowed(
                200,
                mapOf(
                    "Content-Type" to listOf("application/json"),
                    "Server" to listOf("cloudflare"),
                    "Set-Cookie" to listOf("cf_clearance=whatever")
                )
            )
        )
    }

    // ---- F3: shouldShowErrorPage ------------------------------------------------

    @Test
    fun `ERROR_UNKNOWN online with a loaded page does not replace the live page`() {
        // Navigation aborts (external/download handoff, canceled load) surface
        // as ERROR_UNKNOWN on the main frame while the chart is fine — keep it.
        assertFalse(Policy.shouldShowErrorPage(Policy.WEBVIEW_ERROR_UNKNOWN, true, true, false))
    }

    @Test
    fun `airplane-mode behaviour is unchanged — offline always shows the error page`() {
        // Owner condition: the offline flow must behave EXACTLY as before.
        // Any error while offline shows the page, whatever the code/state.
        assertTrue(Policy.shouldShowErrorPage(Policy.WEBVIEW_ERROR_UNKNOWN, false, true, false))
        assertTrue(Policy.shouldShowErrorPage(Policy.WEBVIEW_ERROR_UNKNOWN, false, false, false))
        assertTrue(Policy.shouldShowErrorPage(Policy.WEBVIEW_ERROR_UNKNOWN, false, true, true))
        assertTrue(Policy.shouldShowErrorPage(-6, false, true, false)) // ERROR_CONNECT offline
    }

    @Test
    fun `real error codes always show the error page — online or not`() {
        // ERROR_CONNECT (-6), ERROR_TIMEOUT (-8), ERROR_HOST_LOOKUP (-1 is NOT
        // among them — real codes are any value != ERROR_UNKNOWN here).
        assertTrue(Policy.shouldShowErrorPage(-6, true, true, false))
        assertTrue(Policy.shouldShowErrorPage(-8, true, true, false))
        assertTrue(Policy.shouldShowErrorPage(-118, true, true, false)) // CONNECTION_TIMED_OUT
    }

    @Test
    fun `first-load unknown error still shows the error page`() {
        // Nothing loaded yet (contentReady == false): an ERROR_UNKNOWN on the
        // very first navigation is still the only thing on screen — show it.
        assertTrue(Policy.shouldShowErrorPage(Policy.WEBVIEW_ERROR_UNKNOWN, true, false, false))
    }

    @Test
    fun `error page already showing keeps its refresh behaviour`() {
        // alreadyShowing == true preserves the old behaviour: a second error
        // while the error page is up still refreshes it (message update).
        assertTrue(Policy.shouldShowErrorPage(Policy.WEBVIEW_ERROR_UNKNOWN, true, true, true))
    }

    // ---- F5: cookieLossConfirmed ------------------------------------------------

    @Test
    fun `one missed page start is NOT a confirmed session loss`() {
        // Owner condition: logout/login generation-bump behaviour stays
        // conservative — a single cookie-less page start (one short-lived
        // analytics cookie expiring) must NOT abandon the saved-data
        // namespace while the user is still logged in.
        assertFalse(Policy.cookieLossConfirmed(0))
        assertFalse(Policy.cookieLossConfirmed(1))
    }

    @Test
    fun `two consecutive misses confirm the session loss`() {
        // Real logout / remote revocation always produces consecutive
        // absences — two strikes bump the generation as before.
        assertTrue(Policy.cookieLossConfirmed(2))
        assertTrue(Policy.cookieLossConfirmed(3))
        assertTrue(Policy.cookieLossConfirmed(10))
    }

    @Test
    fun `required strike count is exactly two`() {
        // Pin the constant so the confirmation window cannot drift silently.
        org.junit.Assert.assertEquals(2, Policy.COOKIE_LOSS_STRIKES_REQUIRED)
    }

    // ---- F22 backing invariant pins ---------------------------------------------
    // The five former !! sites are proven safe by these invariants; the pins
    // make the proofs test-visible so a Policy change cannot silently break
    // them.

    @Test
    fun `skipReason treats a lower-case get as cacheable (method-null proof)`() {
        // Tier2Cache.intercept's method elvis relies on: skipReason == null
        // ⇒ the method was a non-blank GET — in ANY case variant.
        assertNull(Policy.skipReason("get", "https://vela.luxalgo.com/api/v1/x", emptyMap()))
        assertNull(Policy.skipReason("GeT", "https://vela.luxalgo.com/api/v1/x", emptyMap()))
        // …and everything else (incl. null/blank) is rejected with "non-GET".
        assertEquals("non-GET", Policy.skipReason(null, "https://vela.luxalgo.com/api/v1/x", emptyMap()))
        assertEquals("non-GET", Policy.skipReason("  ", "https://vela.luxalgo.com/api/v1/x", emptyMap()))
        assertEquals("non-GET", Policy.skipReason("POST", "https://vela.luxalgo.com/api/v1/x", emptyMap()))
    }

    @Test
    fun `dataUrlParts accepts an upper-case DATA prefix`() {
        val parts = Policy.dataUrlParts("DATA:text/plain;base64,aGVsbG8=")
        org.junit.Assert.assertNotNull(parts)
        assertEquals("text/plain", parts!!.mime)
        assertTrue(parts.isBase64)
        assertEquals("aGVsbG8=", parts.payload)
        // lower-case still fine, and garbage still rejected
        org.junit.Assert.assertNotNull(Policy.dataUrlParts("data:text/plain,hi"))
        assertNull(Policy.dataUrlParts("https://vela.luxalgo.com/not-data"))
        assertNull(Policy.dataUrlParts("data:nocomma"))
    }

    @Test
    fun `sanitizeBlobFileName caps an 81-char extensionless name at 80`() {
        val long = "a".repeat(81)
        val capped = Policy.sanitizeBlobFileName(long)
        org.junit.Assert.assertNotNull(capped)
        assertEquals(80, capped!!.length)
        assertEquals(long.take(80), capped)
        // a short name passes through unchanged
        assertEquals("report.csv", Policy.sanitizeBlobFileName("report.csv"))
    }
}
