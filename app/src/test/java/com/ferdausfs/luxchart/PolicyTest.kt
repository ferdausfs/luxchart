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
}
