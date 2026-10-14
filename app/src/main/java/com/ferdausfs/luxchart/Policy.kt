package com.ferdausfs.luxchart

/**
 * Pure, JVM-testable policy helpers for LuxChart.
 *
 * RULES for this file:
 *  - NO Android imports (must run in plain JVM unit tests).
 *  - Navigation allow-list lives ONLY here (single source of truth).
 */
object Policy {

    /**
     * Hosts (exact match or any subdomain) that stay inside the WebView.
     * Everything else is handed to the external browser.
     * SINGLE SOURCE OF TRUTH for navigation — MainActivity must not keep its
     * own copy of this list.
     */
    val ALLOWED_HOSTS = listOf(
        "luxalgo.com",               // chart platform: vela./www./api. …
        "accounts.google.com",       // Google sign-in
        "appleid.apple.com",         // Apple sign-in
        "facebook.com",              // Facebook sign-in
        "accounts.youtube.com",      // Google/YouTube OAuth flow
        "discord.com",               // Discord sign-in
        "login.microsoftonline.com", // Microsoft sign-in
        "checkout.stripe.com",       // Stripe checkout
        "js.stripe.com",             // Stripe JS
        "paypal.com",                // PayPal
        "challenges.cloudflare.com"  // bot-protection challenge page
    )

    /**
     * True when [host] is one of [ALLOWED_HOSTS] or a subdomain of one.
     * Pure function: null/blank/host-with-port/IPv6 => false; case- and
     * whitespace-insensitive; lookalikes (notluxalgo.com, luxalgo.com.evil.io)
     * are rejected.
     */
    fun isAllowed(host: String?): Boolean {
        val h = normalizeHost(host) ?: return false
        return ALLOWED_HOSTS.any { d -> h == d || h.endsWith(".$d") }
    }

    /**
     * True only for luxalgo.com and its subdomains — used by the offline
     * Tier-2 cache, which must never touch third-party hosts.
     */
    fun isLuxAlgo(host: String?): Boolean {
        val h = normalizeHost(host) ?: return false
        return h == "luxalgo.com" || h.endsWith(".luxalgo.com")
    }

    private fun normalizeHost(host: String?): String? {
        if (host.isNullOrBlank()) return null
        val h = host.trim().lowercase()
        if (h.contains(":")) return null // host:port or bare IPv6 — not a plain DNS host
        return h
    }

    /**
     * Splits a `data:` URL into its parts WITHOUT decoding (decoding uses
     * android.util.Base64 in the app, which is not JVM-testable here).
     * Returns null for anything that is not a well-formed data URL.
     */
    fun dataUrlParts(url: String?): DataUrlParts? {
        if (url == null || !url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val header = url.substring(5, comma)
        val mime = header.substringBefore(';').trim().takeIf { it.isNotEmpty() }
        val isBase64 = header.split(';').any { it.trim().equals("base64", ignoreCase = true) }
        return DataUrlParts(mime, isBase64, url.substring(comma + 1))
    }

    /** Parts of a data: URL (see [dataUrlParts]). */
    class DataUrlParts(
        val mime: String?,
        val isBase64: Boolean,
        val payload: String
    )

    /**
     * Desktop user-agent built at runtime from the system WebView UA.
     *
     * Format mandated by the site: Windows Chrome desktop UA whose MAJOR
     * version tracks the device's actual WebView (so luxalgo always sees a
     * current browser — no hardcoded Chrome/131 anymore). When the system UA
     * carries no parseable Chrome major (practically impossible on devices
     * with WebView installed), a conservative static fallback is used.
     */
    fun desktopUa(systemUa: String?): String {
        val major = systemUa?.let { CHROME_MAJOR.find(it)?.groupValues?.get(1) }
            ?.takeIf { it.length in 1..3 && it.all { c -> c.isDigit() } }
        return UA_TEMPLATE.format(major ?: FALLBACK_CHROME_MAJOR)
    }

    private const val UA_TEMPLATE =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/%s.0.0.0 Safari/537.36"

    private val CHROME_MAJOR = Regex("""Chrome/(\d+)""")

    private const val FALLBACK_CHROME_MAJOR = "124"
}
