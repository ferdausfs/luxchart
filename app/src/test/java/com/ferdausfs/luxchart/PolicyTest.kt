package com.ferdausfs.luxchart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure policy helpers (no Android framework needed).
 * These run in CI before assembleRelease.
 */
class PolicyTest {

    // ---- Desktop UA builder --------------------------------------------------

    private fun systemUa(major: String) =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$major.0.6899.1 Mobile Safari/537.36"

    @Test
    fun `desktop UA uses the real Chrome major from the system WebView UA`() {
        val out = Policy.desktopUa(systemUa("141"))
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
            out
        )
    }

    @Test
    fun `desktop UA keeps the exact Windows desktop template`() {
        val out = Policy.desktopUa(systemUa("133"))
        assertTrue(out.endsWith("Safari/537.36"))
        assertTrue(out.contains("Windows NT 10.0; Win64; x64"))
        assertFalse(out.contains("Android"))
        assertFalse(out.contains("Mobile"))
        assertFalse(out.contains("131"))
    }

    @Test
    fun `desktop UA falls back when system UA has no Chrome token`() {
        val out = Policy.desktopUa("Mozilla/5.0 (X11; Linux) AppleWebKit/537.36 Safari/537.36")
        assertTrue(out.contains("Chrome/"))
        assertTrue(out.endsWith("Safari/537.36"))
    }

    @Test
    fun `desktop UA falls back on null empty or absurd input`() {
        listOf(null, "", "Chrome/99999999.0.0.0 Safari/537.36", "Chrome/").forEach {
            val out = Policy.desktopUa(it)
            assertTrue(out.contains("Chrome/"))
            assertTrue(out.endsWith("Safari/537.36"))
        }
    }

    // ---- Navigation allow-list ----------------------------------------------

    @Test
    fun `luxalgo com and all subdomains are allowed`() {
        listOf(
            "luxalgo.com", "www.luxalgo.com", "vela.luxalgo.com",
            "api.luxalgo.com", "a.b.c.luxalgo.com"
        ).forEach { assertTrue(it, Policy.isAllowed(it)) }
    }

    @Test
    fun `lookalike hosts are rejected`() {
        listOf(
            "notluxalgo.com", "luxalgo.com.evil.io", "luxalgo.community",
            "luxalgo", "", "   ", "::1", "127.0.0.1:443"
        ).forEach { assertFalse(it, Policy.isAllowed(it)) }
    }

    @Test
    fun `allow-list is case and whitespace insensitive`() {
        assertTrue(Policy.isAllowed("WWW.LUXALGO.COM"))
        assertTrue(Policy.isAllowed(" Vela.LuxAlgo.com "))
    }

    @Test
    fun `oauth and payment hosts are allowed including subdomains`() {
        listOf(
            "accounts.google.com", "sub.accounts.google.com",
            "appleid.apple.com",
            "facebook.com", "www.facebook.com", "m.facebook.com",
            "accounts.youtube.com",
            "discord.com", "cdn.discord.com",
            "login.microsoftonline.com",
            "checkout.stripe.com", "js.stripe.com",
            "paypal.com", "www.paypal.com",
            "challenges.cloudflare.com"
        ).forEach { assertTrue(it, Policy.isAllowed(it)) }
    }

    @Test
    fun `sibling hosts outside the list are rejected`() {
        listOf(
            "google.com", "youtube.com", "microsoftonline.com", "stripe.com",
            "cloudflare.com", "apple.com", "evil-accounts.google.com.evil.io"
        ).forEach { assertFalse(it, Policy.isAllowed(it)) }
    }

    @Test
    fun `isLuxAlgo matches only luxalgo family`() {
        assertTrue(Policy.isLuxAlgo("luxalgo.com"))
        assertTrue(Policy.isLuxAlgo("vela.luxalgo.com"))
        assertTrue(Policy.isLuxAlgo("Vela.LuxAlgo.com"))
        assertFalse(Policy.isLuxAlgo("accounts.google.com"))
        assertFalse(Policy.isLuxAlgo("velaluxalgo.com"))
        assertFalse(Policy.isLuxAlgo("luxalgo.com.evil.io"))
        assertFalse(Policy.isLuxAlgo(null))
    }

    // ---- data: URL parsing (blob/data downloads) -----------------------------

    @Test
    fun `base64 data url is split correctly`() {
        val parts = Policy.dataUrlParts("data:application/pdf;base64,JVBERi0=")!!
        assertEquals("application/pdf", parts.mime)
        assertTrue(parts.isBase64)
        assertEquals("JVBERi0=", parts.payload)
    }

    @Test
    fun `urlencoded data url is detected`() {
        val parts = Policy.dataUrlParts("data:text/plain,hello%20world")!!
        assertEquals("text/plain", parts.mime)
        assertFalse(parts.isBase64)
        assertEquals("hello%20world", parts.payload)
    }

    @Test
    fun `data url without mime gets null mime`() {
        val parts = Policy.dataUrlParts("data:;base64,AAA=")!!
        assertEquals(null, parts.mime)
        assertTrue(parts.isBase64)
        assertEquals("AAA=", parts.payload)
    }

    @Test
    fun `garbage data urls return null`() {
        listOf(null, "http://x/y", "data:nocomma", "blob:https://x/abc").forEach {
            assertEquals(it, null, Policy.dataUrlParts(it))
        }
    }
}
