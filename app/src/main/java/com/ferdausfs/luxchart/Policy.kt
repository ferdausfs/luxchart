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

    // ---- Tier-2 offline cache policy (pure, tested) --------------------------

    /** URL substrings (lower-cased match on path+query) that must NEVER be cached. */
    private val NEVER_CACHE_URL_TOKENS = listOf(
        "auth", "login", "token", "session", "payment"
    )

    /** Content types that are always streaming — never buffered/cached. */
    private val STREAMING_TYPES = listOf(
        "text/event-stream", "application/grpc", "application/stream+json"
    )

    /**
     * Content-type prefixes eligible for the offline disk cache. This is the
     * mandated set (JSON/JS/CSS/fonts/images) PLUS text/html for luxalgo pages
     * so the app shell itself can be shown offline (documented deviation that
     * makes the whole offline feature actually work end to end).
     */
    private val STORABLE_TYPES = listOf(
        "application/json",
        "text/javascript", "application/javascript", "application/x-javascript",
        "text/css",
        "font/", "application/font-woff", "application/vnd.ms-fontobject",
        "application/x-font-ttf", "application/x-font-opentype",
        "image/",
        "text/html"
    )

    /**
     * Request-level cache-skip rules. TRUE means: never touch the cache for
     * this request (just let the WebView do its default thing).
     * Never: non-GET, Authorization header, Range (partial-content) request,
     * WebSocket upgrade, non-http(s), and URLs containing
     * auth/login/token/session/payment.
     */
    fun shouldSkipRequest(
        method: String?,
        url: String?,
        requestHeaders: Map<String, String>
    ): Boolean {
        if (method.isNullOrBlank() || !method.equals("GET", ignoreCase = true)) return true
        if (url.isNullOrBlank()) return true
        val u = url.trim().lowercase()
        if (!u.startsWith("https://") && !u.startsWith("http://")) return true // incl. ws/wss
        if (requestHeaders.keys.any { it.equals("Authorization", ignoreCase = true) }) return true
        // Hotfix 1.6.1 item 3: partial-content requests must never be
        // intercepted (the WebView negotiates ranges itself).
        if (requestHeaders.keys.any { it.equals("Range", ignoreCase = true) }) return true
        if (requestHeaders.entries.any {
                it.key.equals("Upgrade", ignoreCase = true) &&
                    it.value.equals("websocket", ignoreCase = true)
            }
        ) return true
        val pathAndQuery = u.substringAfter("://")
        return NEVER_CACHE_URL_TOKENS.any { pathAndQuery.contains(it) }
    }

    // ---- Request-header forwarding (hotfix 1.6.1 item 3, pure, tested) -------

    /**
     * Headers copied from the ORIGINAL request onto the Tier-2 re-fetch.
     * Exactly the CORS/context headers the page expects — NOTHING sensitive:
     * Authorization is kept out by the skip rules and is never forwarded.
     */
    val FORWARDED_REQUEST_HEADERS = listOf(
        "Origin", "Referer", "Accept", "Accept-Language"
    )

    /**
     * Pure filter: from the original request headers, keep ONLY
     * [FORWARDED_REQUEST_HEADERS] (case-insensitive), remapped to their
     * canonical spelling; blank values are dropped. Everything else
     * (Authorization, Range, Cookie, User-Agent, …) is never forwarded.
     */
    fun forwardableRequestHeaders(requestHeaders: Map<String, String>?): Map<String, String> {
        if (requestHeaders.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        requestHeaders.forEach { (name, value) ->
            if (value.isNullOrBlank()) return@forEach
            val canonical = FORWARDED_REQUEST_HEADERS
                .firstOrNull { it.equals(name, ignoreCase = true) } ?: return@forEach
            out[canonical] = value.trim()
        }
        return out
    }

    /**
     * Response-level store rules: only 200s without Set-Cookie, non-streaming,
     * with a storable content type. TRUE means eligible to save to disk.
     */
    fun shouldStoreResponse(
        statusCode: Int,
        responseHeaders: Map<String, List<String>>
    ): Boolean {
        if (statusCode != 200) return false
        if (responseHeaders.keys.any { it.equals("Set-Cookie", ignoreCase = true) }) return false
        val contentType = responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()?.lowercase() ?: return false
        if (STREAMING_TYPES.any { contentType.contains(it) }) return false
        return STORABLE_TYPES.any { contentType.startsWith(it) }
    }

    /**
     * Cache-key normalization: lower-case scheme+host, drop the fragment,
     * drop the default port (80/443), drop an empty query separator, and
     * sort query parameters for a stable key. Path case is preserved.
     * Returns null when the URL cannot be parsed (=> do not cache).
     */
    fun normalizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = java.net.URI(url.trim())
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val defaultPort = when (scheme) { "http" -> 80; "https" -> 443; else -> null }
            val port = if (uri.port != -1 && uri.port != defaultPort) ":${uri.port}" else ""
            val path = uri.rawPath ?: ""
            val query = uri.rawQuery
                ?.split('&')
                ?.filter { it.isNotEmpty() }
                ?.sorted()
                ?.joinToString("&")
                ?.takeIf { it.isNotEmpty() }
                ?.let { "?$it" } ?: ""
            "$scheme://$host$port$path$query"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Final cache key: SHA-256 of "METHOD|normalized-url" as a hex string
     * (filesystem-safe, no separators from the URL survive).
     */
    fun cacheKey(method: String, url: String): String? {
        val normalized = normalizeUrl(url) ?: return null
        return sha256Hex("$method|".uppercase() + normalized)
    }

    private fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ---- Crash-report sanitization (hotfix 1.6.1 item 4, pure, tested) -------

    /** Upper bound for the persisted crash text (keeps the dialog light). */
    const val CRASH_TEXT_MAX_CHARS = 20_000

    private val CRASH_URL_REGEX = Regex("https?://\\S+")
    private val CRASH_COOKIE_REGEX = Regex("(?i)((set-)?cookie\\s*[:=])[^\\n\\r]*")

    /**
     * Makes a crash stack trace safe to store and show (secret-free):
     * every http(s) URL loses its query string (tokens/session ids live
     * there) and any `Cookie:`/`Set-Cookie:`-looking assignment is redacted
     * for the rest of its line. Stack frames, thread names and messages pass
     * through untouched. Output is hard-capped at [maxChars].
     */
    fun sanitizeCrashText(text: String, maxChars: Int = CRASH_TEXT_MAX_CHARS): String {
        val noQuery = CRASH_URL_REGEX.replace(text) { m -> m.value.substringBefore('?') }
        val noCookies = CRASH_COOKIE_REGEX.replace(noQuery, "$1 <redacted>")
        return if (noCookies.length > maxChars) {
            noCookies.take(maxChars) + "\n… (truncated)"
        } else {
            noCookies
        }
    }

    // ---- Crash-loop kill switch (hotfix 1.6.1 item 5, pure, tested) ----------

    /**
     * Consecutive launch crashes after which Tier 2 (the request
     * interceptor) stays disabled for the run: if the app cannot reach a
     * stable page with the cache active, the cache is the suspect. The
     * counter resets once a page has been visible for 10 seconds, so the
     * next successful run re-enables Tier 2.
     */
    const val CRASH_LOOP_LIMIT = 2

    fun tier2KillSwitch(launchCrashCount: Int): Boolean =
        launchCrashCount >= CRASH_LOOP_LIMIT

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
