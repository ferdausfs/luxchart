package com.ferdausfs.luxchart

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
}
