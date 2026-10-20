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
     * TRUE for always-streaming content types (Phase 7 item 7): these are
     * NEVER buffered and NEVER stored — the raw stream is passed straight
     * through to the WebView, which owns the connection. Pure, tested.
     */
    fun isStreamingType(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return false
        val lower = contentType.trim().lowercase()
        return STREAMING_TYPES.any { lower.contains(it) }
    }

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
     * Request-level cache-skip rules with an EXPLAINABLE reason (Phase 7
     * item 10): returns null when the request may flow through the cache,
     * otherwise a short stable reason string for the request log
     * (SKIP:<reason> decision). Rules identical to [shouldSkipRequest].
     *
     * Phase 7 item 3: requests WITH an `Authorization` header are ALLOWED to
     * be cached (single-user personal app, app-private storage). The cache
     * key is METHOD + normalized URL only — request headers can never change
     * it — and the header/token itself is never stored on disk, never
     * forwarded to disk meta, and never logged (see README trade-off note).
     */
    fun skipReason(
        method: String?,
        url: String?,
        requestHeaders: Map<String, String>
    ): String? {
        if (method.isNullOrBlank() || !method.equals("GET", ignoreCase = true)) return "non-GET"
        if (url.isNullOrBlank()) return "bad-url"
        val u = url.trim()
        val lower = u.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return "non-http"
        // Hotfix 1.6.1 item 3: partial-content requests must never be
        // intercepted (the WebView negotiates ranges itself).
        if (requestHeaders.keys.any { it.equals("Range", ignoreCase = true) }) return "range"
        if (requestHeaders.entries.any {
                it.key.equals("Upgrade", ignoreCase = true) &&
                    it.value.equals("websocket", ignoreCase = true)
            }
        ) return "websocket"
        // Phase 7 item 4: path-only segment match (never host, never query).
        if (urlHasSensitiveSegment(u)) return "sensitive-path"
        return null
    }

    fun shouldSkipRequest(
        method: String?,
        url: String?,
        requestHeaders: Map<String, String>
    ): Boolean = skipReason(method, url, requestHeaders) != null

    // ---- Request-log sanitizer (Phase 7 item 10, pure) ------------------------

    /**
     * Privacy-safe host+path for the in-app request log:
     *   "vela.luxalgo.com/api/v1/candles [resolution,symbol]"
     * Query KEY NAMES only (never values), original order, de-duplicated,
     * fragment dropped, host never matches the sensitive-segment rule (host
     * is never inspected there anyway). Unparseable => "(bad-url)".
     * NEVER returns query values, headers, cookies, tokens or bodies.
     */
    fun sanitizedRequestUrl(url: String?): String {
        if (url.isNullOrBlank()) return "(bad-url)"
        return try {
            val uri = java.net.URI(url.trim())
            val host = uri.host?.lowercase() ?: return "(bad-url)"
            val path = uri.rawPath ?: ""
            val keys = uri.rawQuery
                ?.split('&')
                ?.mapNotNull { p ->
                    p.substringBefore('=').trim().takeIf { it.isNotEmpty() }
                }
                ?.distinct()
                ?: emptyList()
            val keyPart = if (keys.isEmpty()) "" else " [${keys.joinToString(",")}]"
            "$host$path$keyPart"
        } catch (e: Exception) {
            "(bad-url)"
        }
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

    // ---- Static-asset TTL revalidation (Phase 8 G2, pure, tested) --------------

    /**
     * Static-asset revalidation TTL (Phase 8 G2): 7 days. After this age a
     * static entry is revalidated IN THE BACKGROUND with a conditional GET
     * (If-None-Match / If-Modified-Since): 304 refreshes the timestamp,
     * 200 overwrites. STILL NO automatic deletion — Clear saved data is the
     * only delete path, and a stale entry is served instantly while the
     * revalidation runs.
     */
    const val STATIC_REVALIDATE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * TRUE => this served entry is a STATIC asset older than [ttlMs] and must
     * be revalidated in the background. API-like types never take this path
     * (they refresh after every online cache-first serve, Phase 7). Entries
     * without a saved timestamp (legacy v1.7.x/1.8.0 writes) are left alone —
     * their age is unknown, and they will gain a timestamp the next time
     * their exact URL is stored again. Pure + tested.
     */
    fun staticNeedsRevalidate(
        contentType: String?,
        savedAtMs: Long,
        nowMs: Long,
        ttlMs: Long
    ): Boolean {
        if (isApiLikeType(contentType)) return false
        if (savedAtMs <= 0L) return false
        return nowMs - savedAtMs > ttlMs
    }

    /**
     * Phase 9 F2 — the store decision for the G2 TTL-REVALIDATION 200 path.
     * The immediate-fetch path ([shouldStoreResponse] callers) is already
     * preceded by the challenge check, but the background revalidation used
     * to consult [shouldStoreResponse] ONLY — so a 200 carrying a
     * Cloudflare-challenge marker (`cf-mitigated: challenge`, `cf-chl-*`,
     * which [isChallengeResponse] also matches) could be persisted OVER a
     * good entry and served from saved data later (worst case offline).
     * Rule: only a plain 200 that is NOT challenge-marked and passes the
     * normal store rules may overwrite an entry during revalidation.
     * Pure + tested (Phase9PolicyTest).
     */
    fun revalidateStoreAllowed(
        statusCode: Int,
        responseHeaders: Map<String, List<String>>
    ): Boolean =
        statusCode == 200 &&
            !isChallengeResponse(statusCode, responseHeaders) &&
            shouldStoreResponse(statusCode, responseHeaders)

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
     * Query parameters IGNORED when building the cache key (Phase 7 item 6):
     * cache-busters, timestamps and nonces injected by the page or the API.
     * Matching is on the raw parameter NAME before the '=', case-insensitive.
     * ALL other parameters are kept (symbol, resolution, from, to, type, …)
     * and the surviving pairs are sorted for a stable key. Lives here (pure)
     * and is unit-tested — change together with Phase7PolicyTest.
     */
    val NOISY_QUERY_PARAMS = listOf(
        "_", "t", "ts", "_t", "timestamp", "cb", "cachebust", "nocache",
        "rnd", "random"
    )

    private val NOISY_PARAM_SET = NOISY_QUERY_PARAMS.toSet()

    /**
     * Cache-key normalization: lower-case scheme+host, drop the fragment,
     * drop the default port (80/443), drop an empty query separator, drop
     * [NOISY_QUERY_PARAMS] from the query, and sort the surviving query
     * parameters for a stable key. Path case is preserved.
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
                ?.filter { pair ->
                    val key = pair.substringBefore('=', "").trim().lowercase()
                    key !in NOISY_PARAM_SET
                }
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
     * Final cache key: SHA-256 of "METHOD|G<generation>|normalized-url" as a
     * hex string (filesystem-safe, no separators from the URL survive).
     * Phase 8 G3: [generation] keys the whole cache to the login state —
     * bumped on logout/cookie-loss/Clear-saved-data so a new login never
     * reads the previous login's entries (old entries are ignored, never
     * auto-deleted). Default 0 keeps the pre-G3 JVM tests valid.
     */
    fun cacheKey(method: String, url: String, generation: Int = 0): String? {
        val normalized = normalizeUrl(url) ?: return null
        return sha256Hex("$method|".uppercase() + "G$generation|" + normalized)
    }

    // ---- Relaxed cache key (Phase 8 B1, pure, tested — BUG B fix) --------------

    /**
     * Time-window query params STRIPPED from the RELAXED key. These are the
     * moving parts of chart candle requests (from/to/countback and friends
     * change on EVERY call) — the reason the exact key never repeated and
     * every timeframe re-fetch hit the network (BUG B root cause). Matching
     * is on the raw parameter NAME before the '=', case-insensitive.
     * NOTE: deliberately NOT in the exact-key [NOISY_QUERY_PARAMS] list —
     * exact keys must keep distinguishing different windows (pinned by test).
     */
    val TIME_WINDOW_QUERY_PARAMS = listOf(
        "from", "to", "countback", "since", "until", "start", "end",
        "start_time", "end_time", "starttime", "endtime",
        "limit-by-time", "cursor-by-time"
    )

    private val TIME_WINDOW_SET = TIME_WINDOW_QUERY_PARAMS.toSet()

    /**
     * Relaxed key normalization: exactly [normalizeUrl] but the query ALSO
     * loses every [TIME_WINDOW_QUERY_PARAMS] entry (on top of the noisy
     * cache-busters). Everything else is KEPT — symbol, resolution/timeframe,
     * type, interval and the path — so the same symbol+timeframe collapses to
     * ONE relaxed key while a different resolution/path can never collide.
     */
    fun relaxedNormalizeUrl(url: String?): String? {
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
                ?.filter { pair ->
                    val key = pair.substringBefore('=', "").trim().lowercase()
                    key !in NOISY_PARAM_SET && key !in TIME_WINDOW_SET
                }
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
     * Relaxed cache key: SHA-256 of "METHOD|R|G<generation>|relaxed-
     * normalized-url". The extra "R" namespace keeps relaxed keys disjoint
     * from exact "GET|" keys and "SHELL|" keys in the same directory.
     * Generation scoped like [cacheKey]. Null when unparseable.
     */
    fun relaxedCacheKey(method: String, url: String, generation: Int = 0): String? {
        val normalized = relaxedNormalizeUrl(url) ?: return null
        return sha256Hex("$method|R|".uppercase() + "G$generation|" + normalized)
    }

    // ---- Cloudflare challenge skip (Phase 8 G1, pure, tested) ------------------

    /**
     * Status codes that are, in practice, bot-protection challenge or
     * rate-limit responses when a NON-browser client (the Tier-2
     * HttpURLConnection fetch) hits a Cloudflare-protected origin.
     */
    val CHALLENGE_STATUSES = listOf(403, 429, 503)

    /**
     * Response-header NAMES (substring, case-insensitive) that mark a
     * Cloudflare-managed challenge/block regardless of status. Any value
     * counts. NOTE: a plain "server: cloudflare" header is NOT a hint — it
     * is on every CF-proxied response, challenge or not.
     */
    val CHALLENGE_HEADER_HINTS = listOf("cf-mitigated", "cf-challenge", "cf-chl")

    /**
     * TRUE => this response is a challenge / rate-limit wall, not the real
     * resource. The Tier-2 fetch has a different TLS fingerprint than
     * Chromium, so it gets challenges the WebView would never see; the
     * interceptor must return NULL (WebView loads it with its own network
     * stack) and NEVER store the body. Pure + tested.
     */
    fun isChallengeResponse(status: Int, responseHeaders: Map<String, List<String>>): Boolean {
        if (status in CHALLENGE_STATUSES) return true
        return responseHeaders.entries.any { (name, _) ->
            val n = (name as? String)?.trim()?.lowercase() ?: return@any false
            CHALLENGE_HEADER_HINTS.any { n.contains(it) }
        }
    }

    /** Hosts are challenge-blocked after this many consecutive challenges. */
    const val MAX_CHALLENGE_STRIKES = 3

    // ---- Chart-like request classification (Phase 8 evidence tool, pure) ------

    /**
     * PATH substrings that identify chart-data endpoints (candles, bars,
     * history …). Substring match on the lower-cased path only — never the
     * host, never the query.
     */
    val CHART_PATH_HINTS = listOf(
        "candle", "chart", "history", "bars", "kline", "ohlcv",
        "quote", "market-data", "marketdata", "tradingview", "series"
    )

    /**
     * Query KEY NAMES (never values) that mark a request as chart data even
     * when the path is generic (e.g. /api/v1/data?symbol=…&resolution=…).
     */
    val CHART_QUERY_KEY_HINTS = listOf(
        "symbol", "resolution", "timeframe", "countback", "ticker", "interval"
    )

    /**
     * TRUE => this request looks like chart data (candles/indicators/history).
     * Used ONLY by the in-app chart-cache report (evidence tool for BUG B):
     * POST bodies and WebSocket frames are invisible to the interceptor, so
     * the report's method column is how we LEARN which channel the chart
     * really uses. Unparseable => false.
     */
    fun chartLikeRequest(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = java.net.URI(url.trim())
            val path = (uri.rawPath ?: "").lowercase()
            if (CHART_PATH_HINTS.any { path.contains(it) }) return true
            val keys = uri.rawQuery
                ?.split('&')
                ?.map { it.substringBefore('=').trim().lowercase() }
                ?: emptyList()
            keys.any { it in CHART_QUERY_KEY_HINTS }
        } catch (e: Exception) {
            false
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ---- Blob display-name sanitizing (Phase 8 G4, pure, tested) ---------------

    /** Max length for a page-supplied download file name. */
    const val BLOB_NAME_MAX_CHARS = 80

    /**
     * Makes a page-supplied download file name safe (Phase 8 G4): rejects
     * ANY path separator ('/' or '\'), any ".." traversal sequence, control
     * characters, hidden (leading-dot) names; collapses whitespace; caps the
     * length while keeping a short extension. Returns null when nothing safe
     * remains — the caller then falls back to the generated default name.
     * The result can NEVER contain directory components.
     */
    fun sanitizeBlobFileName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val n = name.trim()
        if (n.isEmpty()) return null
        if (n.any { it.isISOControl() }) return null
        if (n.contains('/') || n.contains('\\')) return null
        if (n.contains("..")) return null
        if (n == "." || n.startsWith(".")) return null
        val collapsed = n.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return null
        var out = collapsed
        if (out.length > BLOB_NAME_MAX_CHARS) {
            val ext = out.substringAfterLast('.', "")
            out = if (ext.isNotEmpty() && ext.length <= 10 && !ext.contains(' ')) {
                val base = out.substringBeforeLast('.', out)
                base.take((BLOB_NAME_MAX_CHARS - ext.length - 1).coerceAtLeast(1)) + ".$ext"
            } else {
                out.take(BLOB_NAME_MAX_CHARS)
            }
        }
        return out
    }

    // ---- Cache generation (Phase 8 G3, pure, tested) ---------------------------

    /**
     * Phase 9 F5 — session-cookie loss needs TWO consecutive luxalgo page
     * starts WITHOUT cookies before the cache generation is bumped. A
     * short-lived analytics/marketing cookie expiring mid-session used to
     * abandon the whole saved-data namespace on the very next page start
     * while the user was still logged in. Real logout / remote revocation
     * always produces consecutive absences; any page start WITH cookies
     * resets the strike count.
     */
    const val COOKIE_LOSS_STRIKES_REQUIRED = 2

    /**
     * TRUE => the session-cookie loss is confirmed (>= [COOKIE_LOSS_STRIKES_REQUIRED]
     * consecutive luxalgo page starts without any cookie) and the caller must
     * bump the per-login cache generation. Pure + tested (Phase9PolicyTest).
     */
    fun cookieLossConfirmed(consecutiveMisses: Int): Boolean =
        consecutiveMisses >= COOKIE_LOSS_STRIKES_REQUIRED

    /**
     * PATH segments that mean LOG-OUT. A main-frame navigation to any of
     * these bumps the per-login cache generation (the session is ending, so
     * saved data must not leak into the next login). Distinct from the
     * broader [SENSITIVE_PATH_SEGMENTS] cache-skip list: "login" does not
     * end a session.
     */
    val LOGOUT_PATH_SEGMENTS = listOf("logout", "logoff", "signout", "sign-out")

    /**
     * TRUE => this URL is a logout navigation ([LOGOUT_PATH_SEGMENTS] on the
     * PATH only — never host, never query; unparseable => false).
     */
    fun isLogoutNavigation(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val path = java.net.URI(url.trim()).rawPath ?: return false
            path.split('/')
                .map { it.trim().lowercase() }
                .any { it.isNotEmpty() && it in LOGOUT_PATH_SEGMENTS }
        } catch (e: Exception) {
            false
        }
    }

    // ---- Last-URL restore decision (Phase 8 A1, pure, tested) -----------------

    /**
     * TRUE => [url] may be restored as the start page on a COLD launch
     * (BUG A fix): https only, host on the navigation allow-list, and the
     * PATH must have no sensitive segment (auth/login/oauth/token/session/
     * payment/checkout — the path-only segment rule; the query is never
     * inspected). FALSE for everything else => caller falls back to
     * START_URL. Internal pages (file:// error page) never qualify.
     */
    fun restoreAllowed(url: String?): Boolean {
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
     * Shell cache key: "SHELL|G<generation>|" namespace + [normalizeUrl].
     * The normalized URL has no fragment (and a sorted query), so /chart#pane
     * and /chart share one shell entry while staying fully disjoint from the
     * Tier-2 "GET|" keys in the same directory. Generation scoped per login
     * (Phase 8 G3).
     */
    fun shellKey(url: String?, generation: Int = 0): String? {
        val normalized = normalizeUrl(url) ?: return null
        return sha256Hex("SHELL|G$generation|" + normalized)
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

    // ---- Error-page decision (Phase 9 F3, pure, tested) ------------------------

    /**
     * WebViewClient.ERROR_UNKNOWN — Chromium's catch-all error code (-1).
     * Duplicated as a constant because [Policy] must stay free of Android
     * imports (JVM-tested).
     */
    const val WEBVIEW_ERROR_UNKNOWN = -1

    /**
     * Phase 9 F3 — TRUE => a main-frame load error should replace the current
     * page with the offline/error screen.
     *
     * Chromium surfaces navigation ABORTS (external-handoff, download-handoff
     * and canceled loads) as ERROR_UNKNOWN on the main frame even though the
     * page itself is fine and the device is online — swapping a live chart
     * for the offline screen in that case is wrong. Rule: skip the error page
     * ONLY when the code is ERROR_UNKNOWN AND the device is online AND a page
     * has already loaded AND no error page is currently showing. Every real
     * disconnect (ERROR_CONNECT, ERROR_TIMEOUT, ERROR_HOST_LOOKUP, …) and any
     * error while OFFLINE still shows the page exactly as before — the
     * airplane-mode flow is unchanged by construction (pinned by test).
     */
    fun shouldShowErrorPage(
        errorCode: Int,
        online: Boolean,
        pageLoaded: Boolean,
        alreadyShowing: Boolean
    ): Boolean =
        !(errorCode == WEBVIEW_ERROR_UNKNOWN && online && pageLoaded && !alreadyShowing)

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
