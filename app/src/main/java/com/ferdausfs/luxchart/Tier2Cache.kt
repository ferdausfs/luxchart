package com.ferdausfs.luxchart

import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tier-2 offline cache: a stale-while-revalidate disk cache for GET requests
 * to luxalgo hosts, stored in app-private storage.
 *
 * Behaviour (see README "Offline" section for the full contract):
 *  - Online: fetch with HttpURLConnection (cookies + UA attached), return the
 *    response to the WebView, and store 200 responses passing the pure
 *    Policy.shouldStoreResponse rules.
 *  - Offline: serve the stored copy with its original safe headers (incl.
 *    CORS). If nothing is stored, return null so the WebView/Tier-1 handles
 *    the failure.
 *  - Any exception => return null (WebView default path). Three consecutive
 *    exceptions disable this cache for the rest of the session. The cache
 *    must never break the app.
 *  - 500 MB LRU cap, versioned directory, key = METHOD + normalized URL.
 *  - Diagnostics: hit/miss/stored/skipped counters + last 50 skipped
 *    luxalgo host+path entries (never query strings, cookies or tokens).
 *
 * Threading: shouldInterceptRequest runs on WebView IO threads. Network fetch
 * happens outside the lock; disk writes + eviction are serialized under
 * [lock]. Eviction runs on a private single-thread executor.
 */
class Tier2Cache(
    private val dir: File,
    private val isOnline: () -> Boolean,
    private val cookieHeader: (String) -> String?,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxSingleResponseBytes: Int = DEFAULT_MAX_SINGLE_RESPONSE
) {

    class Diagnostics internal constructor() {
        val hits = AtomicLong()
        val misses = AtomicLong()
        val stored = AtomicLong()
        val skipped = AtomicLong()
        val diskBytes = AtomicLong()
        val disabled = AtomicBoolean(false)
        val failures = AtomicInteger(0)

        private val recentSkipped = ArrayDeque<String>()
        private val ringLock = Any()

        internal fun recordSkipped(hostPath: String) {
            synchronized(ringLock) {
                recentSkipped.addLast(hostPath)
                while (recentSkipped.size > MAX_SKIPPED_RECENT) recentSkipped.removeFirst()
            }
        }

        fun recentSkippedSnapshot(): List<String> = synchronized(ringLock) {
            recentSkipped.toList()
        }

        /** Human-readable, secret-free summary for the in-app dialog. */
        fun summary(): String {
            val sb = StringBuilder()
            sb.append("LuxChart offline cache (Tier-2)\n")
            sb.append("enabled: ").append(if (disabled.get()) "NO (3+ failures this session)" else "yes")
                .append('\n')
            sb.append("hits: ").append(hits.get())
                .append("  misses: ").append(misses.get())
                .append("  stored: ").append(stored.get())
                .append("  skipped: ").append(skipped.get()).append('\n')
            sb.append("disk: ").append(fmt(diskBytes.get())).append('\n')
            sb.append("consecutive fetch failures: ").append(failures.get()).append('\n')
            val recent = recentSkippedSnapshot()
            if (recent.isEmpty()) {
                sb.append("recently skipped: (none)\n")
            } else {
                sb.append("recently skipped (host/path, newest first, max ")
                    .append(MAX_SKIPPED_RECENT).append("):\n")
                recent.asReversed().forEach { sb.append("  ").append(it).append('\n') }
            }
            return sb.toString()
        }

        private fun fmt(bytes: Long): String = when {
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
            bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }

        companion object {
            internal const val MAX_SKIPPED_RECENT = 50
        }
    }

    val diagnostics = Diagnostics()
    private val evictExecutor = Executors.newSingleThreadExecutor()
    private val lock = Any()

    /**
     * Entry point from WebViewClient.shouldInterceptRequest. ALWAYS returns a
     * usable response or null — never throws.
     */
    fun intercept(
        method: String?,
        url: String?,
        requestHeaders: Map<String, String>?,
        userAgent: String?
    ): WebResourceResponse? {
        if (diagnostics.disabled.get()) return null
        try {
            val host = try { URI(url ?: return null).host } catch (e: Exception) { return null }
            if (!Policy.isLuxAlgo(host)) return null // third-party: never touched, never logged
            if (Policy.shouldSkipRequest(method, url, requestHeaders ?: emptyMap())) {
                diagnostics.skipped.incrementAndGet()
                diagnostics.recordSkipped("$host${pathOf(url!!)}")
                return null
            }
            return if (isOnline()) {
                fetchAndStore(method!!, url, requestHeaders ?: emptyMap(), userAgent)
            } else {
                serveFromDisk(method!!, url)
            }
        } catch (e: Exception) {
            noteFailure()
            return null
        }
    }

    // ---- Online path ---------------------------------------------------------

    private fun fetchAndStore(
        method: String,
        url: String,
        requestHeaders: Map<String, String>,
        userAgent: String?
    ): WebResourceResponse? {
        val key = Policy.cacheKey(method, url) ?: return null
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // Hotfix 1.6.1 item 3: redirects are the WebView's business —
                // this fetch must answer with the 3xx itself (caller returns
                // null for it) instead of silently following it.
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity") // predictable body
                userAgent?.let { setRequestProperty("User-Agent", it) }
                cookieHeader(url)?.takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("Cookie", it) }
                // Hotfix 1.6.1 item 3: forward the page's own CORS/context
                // headers (Origin/Referer/Accept/Accept-Language) so gated
                // endpoints answer exactly as they would for the WebView.
                // Pure filter — Authorization/Range/cookies are NEVER forwarded.
                Policy.forwardableRequestHeaders(requestHeaders).forEach { (n, v) ->
                    setRequestProperty(n, v)
                }
            }
        } catch (e: Exception) {
            noteFailure()
            return null
        }

        val body: ByteArray
        val status: Int
        val headers: Map<String, List<String>>
        try {
            status = conn.responseCode
            headers = conn.headerFields.filterKeys { it != null }
                .mapKeys { it.key as String }
            if (status in 300..399) {
                // Hotfix 1.6.1 item 3: never intercept redirects — the WebView
                // must run its own redirect + cookie handling for them.
                diagnostics.skipped.incrementAndGet()
                diagnostics.recordSkipped(hostOf(url) + pathOf(url))
                return null
            }
            val contentLength = conn.contentLengthLong
            if (contentLength > maxSingleResponseBytes) {
                // Too big to buffer — let the WebView stream it itself.
                diagnostics.skipped.incrementAndGet()
                diagnostics.recordSkipped(hostOf(url) + pathOf(url))
                return null
            }
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            // readNBytes is API 33+; manual chunked read keeps minSdk 24.
            body = if (stream != null) {
                val out = java.io.ByteArrayOutputStream(maxSingleResponseBytes.coerceAtMost(1 shl 20))
                val chunk = ByteArray(64 * 1024)
                val limit = maxSingleResponseBytes + 1
                stream.use { input ->
                    while (true) {
                        val n = input.read(chunk)
                        if (n == -1) break
                        if (out.size() + n > limit) {
                            out.write(chunk, 0, limit - out.size())
                            break
                        }
                        out.write(chunk, 0, n)
                    }
                }
                out.toByteArray()
            } else {
                ByteArray(0)
            }
            if (body.size > maxSingleResponseBytes) {
                diagnostics.skipped.incrementAndGet()
                diagnostics.recordSkipped(hostOf(url) + pathOf(url))
                return null
            }
        } catch (e: Exception) {
            noteFailure()
            return null
        } finally {
            conn.disconnect()
        }

        if (status !in 200..299) {
            // Not storable; hand the WebView a null so it renders the page's
            // own error handling (Cloudflare etc. must still work).
            return null
        }

        // Hotfix 1.6.1 item 3: hand every Set-Cookie of an intercepted
        // response to the WebView's cookie store BEFORE returning, so the
        // page's session state stays coherent. (The response we replay to
        // the WebView never carries cookie headers, and disk storage still
        // refuses Set-Cookie responses — see Policy.shouldStoreResponse.)
        applySetCookies(url, headers)

        val response = buildResponse(status, headers, body)
        diagnostics.stored.incrementAndGet()
        resetFailures()

        if (Policy.shouldStoreResponse(status, headers)) {
            try {
                storeToDisk(key, url, status, headers, body)
            } catch (e: Exception) {
                // Storing must never break serving.
            }
        }
        return response
    }

    private fun buildResponse(
        status: Int,
        headers: Map<String, List<String>>,
        body: ByteArray
    ): WebResourceResponse {
        val contentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()
        val mime = contentType?.substringBefore(';')?.trim()?.ifEmpty { null }
        val charset = contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", true) }
            ?.substringAfter('=')?.trim(' ', '"')
        val response = WebResourceResponse(
            mime, charset, status, "OK", mapOf(), ByteArrayInputStream(body)
        )
        // Preserve the original safe headers (CORS included) — required for
        // fetch()/XHR from the page to accept the response offline.
        headers.forEach { (name, values) ->
            if (SAFE_RESPONSE_HEADERS.any { it.equals(name, true) }) {
                values.forEach { v -> response.responseHeaders[name] = v }
            }
        }
        return response
    }

    /**
     * Applies intercepted Set-Cookie headers to the WebView cookie store.
     * CookieManager is documented as usable from any thread once a WebView
     * exists (the engine already calls getCookie from this same IO thread);
     * every call is still guarded — cookie sync is best-effort, never a
     * crash source (hotfix 1.6.1).
     */
    private fun applySetCookies(url: String, headers: Map<String, List<String>>) {
        val cookies = headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
        if (cookies.isEmpty()) return
        try {
            val cm = android.webkit.CookieManager.getInstance()
            cookies.forEach { raw -> runCatching { cm.setCookie(url, raw) } }
            cm.flush()
        } catch (e: Exception) {
            // best-effort
        }
    }

    // ---- Disk ----------------------------------------------------------------

    private fun storeToDisk(
        key: String,
        url: String,
        status: Int,
        headers: Map<String, List<String>>,
        body: ByteArray
    ) {
        synchronized(lock) {
            if (!dir.exists()) dir.mkdirs()
            val bodyFile = File(dir, "$key.bin")
            val metaFile = File(dir, "$key.meta")
            val meta = JSONObject()
            meta.put("url", url)
            meta.put("status", status)
            val contentType = headers.entries
                .firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()
            if (contentType != null) meta.put("content-type", contentType)
            val safe = JSONObject()
            headers.forEach { (name, values) ->
                if (SAFE_RESPONSE_HEADERS.any { it.equals(name, true) } && values.isNotEmpty()) {
                    safe.put(name, values.joinToString(", "))
                }
            }
            meta.put("headers", safe)
            bodyFile.writeBytes(body)
            metaFile.writeText(meta.toString())
            evictIfNeededAsync()
        }
    }

    private fun serveFromDisk(method: String, url: String): WebResourceResponse? {
        val key = Policy.cacheKey(method, url)
        synchronized(lock) {
            val bodyFile = key?.let { File(dir, "$it.bin") }
            val metaFile = key?.let { File(dir, "$it.meta") }
            if (bodyFile == null || metaFile == null || !bodyFile.exists() || !metaFile.exists()) {
                diagnostics.misses.incrementAndGet()
                return null
            }
            val meta = JSONObject(metaFile.readText())
            val bytes = bodyFile.readBytes()
            bodyFile.setLastModified(System.currentTimeMillis()) // LRU touch
            diagnostics.hits.incrementAndGet()
            resetFailures()
            val status = meta.optInt("status", 200)
            val contentType = meta.optString("content-type", null)
            val mime = contentType?.substringBefore(';')?.trim()?.ifEmpty { null }
            val charset = contentType
                ?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("charset=", true) }
                ?.substringAfter('=')?.trim(' ', '"')
            val response = WebResourceResponse(
                mime, charset, status, "OK", mapOf(), ByteArrayInputStream(bytes)
            )
            val headers = meta.optJSONObject("headers")
            headers?.keys()?.forEach { name -> response.responseHeaders[name] = headers.getString(name) }
            return response
        }
    }

    /** LRU eviction to ~80% of the cap, run off-thread after each store. */
    private fun evictIfNeededAsync() {
        evictExecutor.execute {
            try {
                synchronized(lock) {
                    var files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".bin") }
                        ?: return@synchronized
                    var total = totalBytesLocked()
                    diagnostics.diskBytes.set(total)
                    if (total <= maxBytes) return@synchronized
                    files = files.sortedBy { it.lastModified() }
                    for (f in files) {
                        if (total <= maxBytes * 80 / 100) break
                        val meta = File(dir, f.nameWithoutExtension + ".meta")
                        total -= f.length()
                        f.delete()
                        meta.delete()
                    }
                    diagnostics.diskBytes.set(totalBytesLocked())
                }
            } catch (e: Exception) {
                // Eviction is best-effort.
            }
        }
    }

    private fun totalBytesLocked(): Long =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".bin") }?.sumOf { it.length() } ?: 0L

    /** "Clear saved data" — deletes every cached entry. Safe from any thread. */
    fun clear() {
        synchronized(lock) {
            dir.listFiles()?.forEach { it.delete() }
            diagnostics.diskBytes.set(0)
        }
    }

    // ---- Main-page shell (Phase 6) ---------------------------------------------
    //
    // Shells live in the SAME directory / LRU / lock as Tier-2 entries but use
    // the "SHELL|" key namespace (Policy.shellKey), so they are disjoint from
    // sub-resource entries. clear() wipes both; LRU eviction covers both.

    /** A stored main-page shell: safe metadata + the HTML body. */
    class StoredShell(
        val url: String,
        val host: String,
        val path: String,
        val savedAtMs: Long,
        val status: Int,
        val contentType: String?,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private val shellExecutor = Executors.newSingleThreadExecutor()

    /** key(normalized URL) -> last shadow-fetch attempt (ms). */
    private val shellThrottle = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Last shadow-fetch outcome for diagnostics: stored / skipped:reason / failed:code. */
    @Volatile
    var lastShellResult: String = "(no shadow fetch yet)"
        private set

    /**
     * Schedules ONE background shadow fetch of [url] (the page that just
     * finished loading ONLINE). Called from the main thread (onPageFinished);
     * all work happens on [shellExecutor]. Never throws.
     */
    fun maybeShadowFetch(url: String, userAgent: String?) {
        try {
            shellExecutor.execute { shadowFetch(url, userAgent) }
        } catch (e: Exception) {
            // Executor rejected/shutdown — never break the page.
        }
    }

    private fun shadowFetch(url: String, userAgent: String?) {
        if (diagnostics.disabled.get()) {
            lastShellResult = "skipped:cache-disabled"
            return
        }
        if (!isOnline()) {
            lastShellResult = "skipped:offline"
            return
        }
        if (!Policy.shellFetchEligible(url)) {
            lastShellResult = "skipped:ineligible-url"
            return
        }
        val key = Policy.shellKey(url) ?: run {
            lastShellResult = "skipped:bad-url"
            return
        }
        val now = System.currentTimeMillis()
        if (!Policy.shellThrottleAllowed(
                shellThrottle[key] ?: 0L, now, Policy.SHELL_THROTTLE_MS
            )
        ) {
            lastShellResult = "skipped:throttled"
            return
        }
        shellThrottle[key] = now // claim the slot BEFORE the network call

        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = SHELL_CONNECT_TIMEOUT_MS
                readTimeout = SHELL_READ_TIMEOUT_MS
                // Redirects are the WebView's business; a 3xx is skipped.
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity") // predictable body
                setRequestProperty("Accept", "text/html")
                userAgent?.takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("User-Agent", it) }
                cookieHeader(url)?.takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("Cookie", it) }
            }
        } catch (e: Exception) {
            lastShellResult = "failed:connect"
            return
        }

        try {
            val status = conn.responseCode
            val headers = conn.headerFields.filterKeys { it != null }
                .mapKeys { it.key as String }
            if (status in 300..399) {
                // No redirect following => the requested URL is always the
                // "final URL"; a 3xx is simply not storable.
                lastShellResult = "skipped:redirect-$status"
                return
            }
            if (status != 200) {
                lastShellResult = "failed:http-$status"
                return
            }
            val contentType = headers.entries
                .firstOrNull { it.key.equals("Content-Type", true) }
                ?.value?.firstOrNull()
            if (contentType == null ||
                !contentType.trim().lowercase().startsWith("text/html")
            ) {
                lastShellResult = "skipped:content-type"
                return
            }
            val body = readCapped(conn.inputStream, Policy.SHELL_MAX_BYTES)
            if (body.size > Policy.SHELL_MAX_BYTES) {
                lastShellResult = "skipped:too-large"
                return
            }
            if (Policy.looksLikeChallengeOrLogin(String(body, Charsets.UTF_8))) {
                lastShellResult = "skipped:challenge"
                return
            }
            // NOTE: Set-Cookie from this shadow request is deliberately
            // IGNORED — it must never touch the WebView cookie store.
            storeShell(url, status, contentType, headers, body)
            lastShellResult = "stored"
        } catch (e: Exception) {
            lastShellResult = "failed:io"
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Reads at most [maxBytes] + 1 bytes (oversize detection), minSdk-safe. */
    private fun readCapped(stream: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(maxBytes.coerceAtMost(1 shl 20))
        val chunk = ByteArray(64 * 1024)
        stream.use { input ->
            while (true) {
                val n = input.read(chunk)
                if (n == -1) break
                if (out.size() + n > maxBytes + 1) {
                    out.write(chunk, 0, maxBytes + 1 - out.size())
                    break
                }
                out.write(chunk, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** Persists a shell entry (safe headers only) under the "SHELL|" namespace. */
    private fun storeShell(
        url: String,
        status: Int,
        contentType: String?,
        headers: Map<String, List<String>>,
        body: ByteArray
    ) {
        val key = Policy.shellKey(url) ?: return
        synchronized(lock) {
            if (!dir.exists()) dir.mkdirs()
            val meta = JSONObject()
            meta.put("type", "shell")
            meta.put("url", url)
            meta.put("status", status)
            meta.put("saved-at", System.currentTimeMillis())
            meta.put("size", body.size)
            if (contentType != null) meta.put("content-type", contentType)
            val safe = JSONObject()
            headers.forEach { (name, values) ->
                if (SAFE_RESPONSE_HEADERS.any { it.equals(name, true) } && values.isNotEmpty()) {
                    safe.put(name, values.joinToString(", "))
                }
            }
            meta.put("headers", safe)
            File(dir, "$key.bin").writeBytes(body)
            File(dir, "$key.meta").writeText(meta.toString())
            evictIfNeededAsync()
        }
    }

    /** Loads a stored shell for [url], or null. Touches LRU recency on hit. */
    fun shellFor(url: String): StoredShell? {
        val key = Policy.shellKey(url) ?: return null
        synchronized(lock) {
            val bodyFile = File(dir, "$key.bin")
            val metaFile = File(dir, "$key.meta")
            if (!bodyFile.exists() || !metaFile.exists()) return null
            return try {
                val meta = JSONObject(metaFile.readText())
                val bytes = bodyFile.readBytes()
                bodyFile.setLastModified(System.currentTimeMillis()) // LRU touch
                val shellUrl = meta.optString("url", url)
                val headersJson = meta.optJSONObject("headers")
                val headers = LinkedHashMap<String, String>()
                headersJson?.keys()?.forEach { n -> headers[n] = headersJson.getString(n) }
                StoredShell(
                    url = shellUrl,
                    host = hostOf(shellUrl),
                    path = pathOf(shellUrl),
                    savedAtMs = meta.optLong("saved-at", 0L),
                    status = meta.optInt("status", 200),
                    contentType = meta.optString("content-type", "").ifEmpty { null },
                    headers = headers,
                    body = bytes
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    // ---- Failure circuit breaker ----------------------------------------------

    private fun noteFailure() {
        val n = diagnostics.failures.incrementAndGet()
        if (n >= MAX_CONSECUTIVE_FAILURES) {
            diagnostics.disabled.set(true)
        }
    }

    private fun resetFailures() {
        diagnostics.failures.set(0)
    }

    // ---- Small helpers ---------------------------------------------------------

    private fun hostOf(url: String): String =
        try { URI(url).host ?: "unknown" } catch (e: Exception) { "unknown" }

    private fun pathOf(url: String): String =
        try { URI(url).rawPath ?: "/" } catch (e: Exception) { "/" }

    companion object {
        /** 500 MB total cap (spec). */
        const val DEFAULT_MAX_BYTES = 500L * 1024 * 1024
        /** Never buffer more than this per response (memory safety). */
        const val DEFAULT_MAX_SINGLE_RESPONSE = 25 * 1024 * 1024
        const val MAX_CONSECUTIVE_FAILURES = 3
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000

        /** Shadow-fetch timeouts (spec: 10 s). */
        private const val SHELL_CONNECT_TIMEOUT_MS = 10_000
        private const val SHELL_READ_TIMEOUT_MS = 10_000

        /** Headers worth persisting and replaying (CORS included, nothing sensitive). */
        private val SAFE_RESPONSE_HEADERS = listOf(
            "Content-Type",
            "Access-Control-Allow-Origin",
            "Access-Control-Allow-Headers",
            "Access-Control-Allow-Methods",
            "Access-Control-Expose-Headers",
            "Access-Control-Max-Age",
            "Access-Control-Allow-Credentials",
            "Timing-Allow-Origin",
            "X-Content-Type-Options"
        )
    }
}
