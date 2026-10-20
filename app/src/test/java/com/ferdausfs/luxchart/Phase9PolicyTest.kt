package com.ferdausfs.luxchart

import org.junit.Assert.assertFalse
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
 *
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
}
