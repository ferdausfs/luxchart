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

    /**
     * Phase 7 item 4 — sensitive PATH segments. This list REPLACES the old
     * substring rule: matching is done on PATH SEGMENTS ONLY — never the
     * host, never the query string. A segment matches when it equals one of
     * these values (case-insensitive, after trimming). Segment equality
     * (not substring): "/tokenizer" does NOT match "token", "/blog/authors"
     * does NOT match "auth", "?next=/login" (query) does NOT match.
     */
    val SENSITIVE_PATH_SEGMENTS = listOf(
        "auth", "oauth", "oauth2", "login", "logout", "signin", "signup",
        "token", "tokens", "session", "sessions", "payment", "payments",
        "checkout"
    )

    private val SENSITIVE_SEGMENT_SET = SENSITIVE_PATH_SEGMENTS.toSet()

    /**
     * TRUE when any PATH segment of [url] equals a [SENSITIVE_PATH_SEGMENTS]
     * entry (case-insensitive). The host and the query are NEVER inspected.
     * Unparseable URL => TRUE (fail closed: cannot prove the path safe).
     */
    fun urlHasSensitiveSegment(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        return try {
            val path = java.net.URI(url.trim()).rawPath ?: ""
            path.split('/')
                .map { it.trim().lowercase() }
                .any { it.isNotEmpty() && it in SENSITIVE_SEGMENT_SET }
        } catch (e: Exception) {
            true
        }
    }

    /** Content types that are always streaming — never buffered/cached. */
    private val STREAMING_TYPES = listOf(
        "text/event-stream", "application/grpc", "application/stream+json"
    )

    /**
     * Content-type prefixes eligible for the offline disk cache. The mandated
     * set (JSON/JS/CSS/fonts/images) PLUS text/html for luxalgo pages so the
     * app shell itself can be shown offline (Phase 6), PLUS the Phase 7 item
     * 5 additions: text/plain, application/octet-stream, application/wasm,
     * application/x-protobuf, application/msgpack, application/x-ndjson
     * (image/svg+xml is covered by the image/ prefix).
     */
    private val STORABLE_TYPES = listOf(
        "application/json",
        "application/x-ndjson",
        "application/msgpack",
        "application/x-protobuf",
        "application/octet-stream",
        "application/wasm",
        "text/javascript", "application/javascript", "application/x-javascript",
        "text/css",
        "text/plain",
        "font/", "application/font-woff", "application/vnd.ms-fontobject",
        "application/x-font-ttf", "application/x-font-opentype",
        "image/",
        "text/html"
    )

    /**
     * Request-level cache-skip rules. TRUE means: never touch the cache for
     * this request (just let the WebView do its default thing).
     * Never: non-GET, Range (partial-content) request, WebSocket upgrade,
     * non-http(s), and URLs with a sensitive PATH segment (Phase 7 item 4:
     * auth, login, token, session, payment, … — path ONLY, never host,
     * never query).
     *
     * Phase 7 item 3: requests WITH an `Authorization` header are ALLOWED to
     * be cached (single-user personal app, app-private storage). The cache
     * key is METHOD + normalized URL only — request headers can never change
     * it — and the header/token itself is never stored on disk, never
     * forwarded to disk meta, and never logged (see README trade-off note).
     */
    fun shouldSkipRequest(
        method: String?,
        url: String?,
        requestHeaders: Map<String, String>
    ): Boolean {
        if (method.isNullOrBlank() || !method.equals("GET", ignoreCase = true)) return true
        if (url.isNullOrBlank()) return true
        val u = url.trim()
        if (!u.lowercase().startsWith("https://") && !u.lowercase().startsWith("http://")) {
            return true // incl. ws/wss
        }
        // Hotfix 1.6.1 item 3: partial-content requests must never be
        // intercepted (the WebView negotiates ranges itself).
        if (requestHeaders.keys.any { it.equals("Range", ignoreCase = true) }) return true
        if (requestHeaders.entries.any {
                it.key.equals("Upgrade", ignoreCase = true) &&
                    it.value.equals("websocket", ignoreCase = true)
            }
        ) return true
        // Phase 7 item 4: path-only segment match (never host, never query)
        // replaces the old substring rule.
        return urlHasSensitiveSegment(u)
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
     * Response-level store rules (Phase 7 item 5): only 200s; non-streaming;
     * with a storable content type. Responses WITH Set-Cookie ARE storable —
     * the cookie header is stripped from the persisted meta automatically
     * (only SAFE_RESPONSE_HEADERS reach disk) while the cookies themselves
     * are applied to the CookieManager when the response is fetched online.
     * Never stored: non-GET (request-level), 3xx, non-200,
     * text/event-stream, gRPC (STREAMING_TYPES).
     */
    fun shouldStoreResponse(
        statusCode: Int,
        responseHeaders: Map<String, List<String>>
    ): Boolean {
        if (statusCode != 200) return false
        val contentType = responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()?.lowercase() ?: return false
        if (STREAMING_TYPES.any { contentType.contains(it) }) return false
        return STORABLE_TYPES.any { contentType.startsWith(it) }
    }

    // ---- Cache-first policy (Phase 7 item 1, pure) ----------------------------

    /**
     * MIME types considered "API-like" data responses. When such an entry is
     * served cache-first while ONLINE, the cache schedules ONE background
     * refresh so the stored copy stays fresh for the next load. Static assets
     * (js/css/fonts/images/wasm/svg/html) are served as-is and are NEVER
     * revalidated (owner requirement: instant loads, nothing auto-deleted).
     */
    val API_LIKE_TYPES = listOf(
        "application/json",
        "application/x-ndjson",
        "application/msgpack",
        "application/x-protobuf",
        "text/plain"
    )

    /**
     * TRUE => a stored response with this Content-Type should be refreshed in
     * the background after being served cache-first online. Pure: case- and
     * parameter-insensitive (charset suffix ignored); null/unknown => false
     * (treated as a static asset — never refreshed).
     */
    fun isApiLikeType(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return false
        val mime = contentType.trim().lowercase().substringBefore(';').trim()
        return API_LIKE_TYPES.any { mime.startsWith(it) }
    }

    /**
     * Phase 7 item 2 — the ONLY size rule for saved data. TRUE => a new
     * store of [incomingBytes] fits under [capBytes] given [currentBytes]
     * already saved. FALSE => refuse the NEW store. Existing entries are
     * never deleted by this rule (no LRU, no eviction): "Clear saved data"
     * is the only deletion path.
     */
    fun storeAllowedWithinCap(currentBytes: Long, incomingBytes: Long, capBytes: Long): Boolean =
        currentBytes + incomingBytes <= capBytes

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

    // ---- Main-page shell (Phase 6 item 1, pure, tested) -----------------------
    //
    // The "shell" is a saved copy of a main-page HTML document (the app-shell
    // of vela.luxalgo.com/chart). It is fetched in the background while the
    // app is ONLINE (shadow fetch) and served by shouldInterceptRequest only
    // when the device is OFFLINE. Online navigation is never touched.

    /** Max stored shell body size — the document itself, not its assets. */
    const val SHELL_MAX_BYTES = 2 * 1024 * 1024

    /** One shadow fetch per URL per 10 minutes (spec throttle). */
    const val SHELL_THROTTLE_MS = 10L * 60 * 1000

    /**
     * Substrings (lower-cased) that identify a bot-protection / challenge
     * page body. A response whose body contains any of these is NEVER stored
     * as a shell — offline would otherwise show the challenge forever.
     * Login pages are covered separately by the sensitive-URL token filter
     * (auth/login/token/session/payment) applied in [shellFetchEligible].
     */
    val SHELL_CHALLENGE_MARKERS = listOf(
        "just a moment",
        "cf-chl",
        "_cf_chl", // Cloudflare's JS-side opts (window._cf_chl_opt, …)
        "challenge-platform",
        "cf-browser-verification",
        "checking your browser"
    )

    /** TRUE => the body looks like a Cloudflare challenge / bot wall, not the real app shell. */
    fun looksLikeChallengeOrLogin(htmlBody: String?): Boolean {
        if (htmlBody.isNullOrEmpty()) return false
        val lower = htmlBody.lowercase()
        return SHELL_CHALLENGE_MARKERS.any { lower.contains(it) }
    }

    /**
     * TRUE => this URL may be shadow-fetched and stored as a shell:
     * https only, host on the navigation allow-list, and the URL must not
     * have a sensitive PATH segment (Phase 7 item 4: path-only match —
     * query strings are never inspected).
     */
    fun shellFetchEligible(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        if (!trimmed.lowercase().startsWith("https://")) return false
        val host = try {
            java.net.URI(trimmed).host
        } catch (e: Exception) {
            return false
        } ?: return false
        if (!isAllowed(host)) return false
        return !urlHasSensitiveSegment(trimmed)
    }

    /**
     * Shell cache key: "SHELL|" namespace + [normalizeUrl]. The normalized
     * URL has no fragment (and a sorted query), so /chart#pane and /chart
     * share one shell entry while staying fully disjoint from the Tier-2
     * "GET|" keys in the same directory.
     */
    fun shellKey(url: String?): String? {
        val normalized = normalizeUrl(url) ?: return null
        return sha256Hex("SHELL|" + normalized)
    }

    /**
     * Throttle rule for shadow fetches: allowed only when at least
     * [minIntervalMs] has passed since the last attempt for the same key
     * (or there was no previous attempt).
     */
    fun shellThrottleAllowed(lastAttemptMs: Long, nowMs: Long, minIntervalMs: Long): Boolean =
        lastAttemptMs <= 0L || nowMs - lastAttemptMs >= minIntervalMs

    /**
     * SPA deep-link fallback rule (pure, tested): the start-URL shell may be
     * served for [requestUrl] when there is no exact shell match IF the
     * request stays on the start URL's host and its path lives inside the
     * start path (e.g. anything under /chart on vela.luxalgo.com, including
     * /chart?symbol=BTC). Boundary-safe: /charts does NOT match /chart.
     */
    fun spaShellFallback(requestUrl: String?, startUrl: String?): Boolean {
        val req = shellUrlParts(requestUrl) ?: return false
        val start = shellUrlParts(startUrl) ?: return false
        if (req.second != start.second) return false // must be the same host
        val reqPath = req.third.ifEmpty { "/" }
        val startPath = start.third.ifEmpty { "/" }
        if (reqPath == startPath) return true
        return reqPath.startsWith(startPath.trimEnd('/') + "/")
    }

    /** (scheme, lower-cased host, raw path) or null when unparseable. */
    private fun shellUrlParts(url: String?): Triple<String, String, String>? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = java.net.URI(url.trim())
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            Triple(scheme, host, uri.rawPath ?: "")
        } catch (e: Exception) {
            null
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
