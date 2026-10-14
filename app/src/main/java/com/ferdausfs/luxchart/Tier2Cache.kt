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
 *  - SAVED DATA STAYS UNTIL I CLEAR IT (Phase 7 item 2): NO automatic
 *    deletion of any kind. The old LRU eviction is GONE. New stores are
 *    refused with a one-time "Saved-data limit reached" toast once the
 *    4 GB total cap is reached — existing entries are never deleted;
 *    "Clear saved data" is the ONLY deletion. Key = METHOD + normalized URL.
 *  - Diagnostics: hit/miss/stored/skipped counters + last 50 skipped
 *    luxalgo host+path entries (never query strings, cookies or tokens).
 *
 * Threading: shouldInterceptRequest runs on WebView IO threads. Network fetch
 * happens outside the lock; disk writes are serialized under [lock].
 */
class Tier2Cache(
    private val dir: File,
    private val isOnline: () -> Boolean,
    private val cookieHeader: (String) -> String?,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxSingleResponseBytes: Int = DEFAULT_MAX_SINGLE_RESPONSE,
    private val onLimitReached: (() -> Unit)? = null
) {

    class Diagnostics internal constructor() {
        val hits = AtomicLong()
        val misses = AtomicLong()
        val stored = AtomicLong()
        val skipped = AtomicLong()
        val diskBytes = AtomicLong()
        val disabled = AtomicBoolean(false)
        val failures = AtomicInteger(0)

        /** Phase 6: offline main-frame documents served from a stored shell. */
        val shellHits = AtomicLong()

        /** Phase 7 item 9: cache-first hits (stored entry served while ONLINE). */
        val cacheFirstHits = AtomicLong()

        /** Phase 7 item 9: background refreshes that really overwrote an entry. */
        val refreshUpdates = AtomicLong()

        /** Phase 7 item 9: bytes served from the disk cache (online + offline). */
        val bytesServed = AtomicLong()

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
            sb.append("cache-first hits (online): ").append(cacheFirstHits.get())
                .append("  background refresh updates: ").append(refreshUpdates.get()).append('\n')
            sb.append("bytes served from cache: ").append(fmt(bytesServed.get())).append('\n')
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
                // Phase 7 item 1 — CACHE-FIRST ONLINE: a stored entry is
                // served IMMEDIATELY even while online (counted as a hit).
                // Static assets are never revalidated; API-like JSON/text
                // gets ONE background refresh (one in-flight per key) that
                // overwrites the stored copy for next time. No entry =>
                // fetch, store, return (Tier-2 behaviour as before).
                val key = Policy.cacheKey(method!!, url)
                val hit = readFromDisk(method, url)
                if (hit != null) {
                    diagnostics.cacheFirstHits.incrementAndGet()
                    if (key != null && Policy.isApiLikeType(hit.contentType)) {
                        scheduleBackgroundRefresh(method, url, key, requestHeaders ?: emptyMap(), userAgent)
                    }
                    return hit.response
                }
                fetchAndStore(method, url, requestHeaders ?: emptyMap(), userAgent)
            } else {
                readFromDisk(method!!, url)?.response
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
        userAgent: String?,
        background: Boolean = false
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
                // Phase 7 item 7: NO "Accept-Encoding: identity" anymore.
                // HttpURLConnection then transparently adds gzip and gunzips
                // the body for us — the bytes read here are exactly what the
                // WebView expects (Content-Encoding never reaches a response).
                userAgent?.let { setRequestProperty("User-Agent", it) }
                cookieHeader(url)?.takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("Cookie", it) }
                // Hotfix 1.6.1 item 3: forward the page's own CORS/context
                // headers (Origin/Referer/Accept/Accept-Language) so gated
                // endpoints answer exactly as they would for the WebView.
                // Pure filter — Range/cookies are NEVER forwarded.
                Policy.forwardableRequestHeaders(requestHeaders).forEach { (n, v) ->
                    setRequestProperty(n, v)
                }
                // Phase 7 item 3 — Authorization ALLOWED: the original request's
                // Authorization header is forwarded for origin calls (immediate
                // fetch AND background refresh), in memory only. It is never
                // persisted (disk meta carries only SAFE_RESPONSE_HEADERS),
                // never logged, and never part of the cache key (key = METHOD +
                // normalized URL). If an authed refresh still fails, the stored
                // copy stays untouched (only 200s overwrite).
                requestHeaders.entries
                    .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                    ?.value?.takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("Authorization", it) }
            }
        } catch (e: Exception) {
            noteFailure()
            return null
        }

        try {
            val status = conn.responseCode
            if (status in 300..399) {
                // Hotfix 1.6.1 item 3: never intercept redirects — the WebView
                // must run its own redirect + cookie handling for them.
                diagnostics.skipped.incrementAndGet()
                diagnostics.recordSkipped(hostOf(url) + pathOf(url))
                return null
            }
            val headers = conn.headerFields.filterKeys { it != null }
                .mapKeys { it.key as String }
            val contentType = headers.entries
                .firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()
            val contentLength = conn.contentLengthLong // -1 when gzip/chunked

            // Hotfix 1.6.1 item 3 (kept): every intercepted response hands its
            // Set-Cookie to the WebView cookie store before returning.
            applySetCookies(url, headers)

            // Phase 7 item 7 — 4xx/5xx return the REAL response (status, body,
            // reason phrase) instead of null, so the WebView does not re-request
            // (no double download). Never stored. Streamed, not buffered.
            if (status >= 400) {
                resetFailures()
                val err = conn.errorStream
                val data = if (err != null) err else ByteArrayInputStream(ByteArray(0))
                return buildStreamingResponse(conn, status, headers, data)
            }

            // Phase 7 item 7 — always-streaming types (text/event-stream, gRPC)
            // are NEVER buffered and NEVER stored: the live stream goes straight
            // to the WebView (it closes the connection when done).
            if (Policy.isStreamingType(contentType)) {
                resetFailures()
                return buildStreamingResponse(conn, status, headers, conn.inputStream)
            }

            val storable = Policy.shouldStoreResponse(status, headers)

            if (contentLength in 0..maxSingleResponseBytes.toLong()) {
                // Known size that fits the store limit — buffer, serve, store.
                val body = readCapped(conn.inputStream, maxSingleResponseBytes)
                conn.disconnect()
                if (body.size > maxSingleResponseBytes) {
                    // Server lied about Content-Length — refuse to serve a
                    // truncated body; null lets the WebView handle it itself.
                    diagnostics.skipped.incrementAndGet()
                    return null
                }
                return finishBuffered(key, url, status, headers, body, storable, background)
            }

            if (contentLength > maxSingleResponseBytes.toLong()) {
                // Known size too big for the cache — stream it through without
                // buffering and without a second download. Not stored.
                resetFailures()
                return buildStreamingResponse(conn, status, headers, conn.inputStream)
            }

            // Unknown length (typical for gzip/chunked): probe-read a bounded
            // window. Body ends inside the window => buffered path (serve +
            // store). Window exceeded => stream prefix + rest via
            // SequenceInputStream (already-read bytes are NOT re-downloaded).
            val probe = probeRead(conn.inputStream, PROBE_WINDOW_BYTES)
            return if (probe.rest == null) {
                conn.disconnect()
                finishBuffered(key, url, status, headers, probe.prefix, storable, background)
            } else {
                resetFailures()
                val data = java.io.SequenceInputStream(
                    ByteArrayInputStream(probe.prefix), probe.rest
                )
                buildStreamingResponse(conn, status, headers, data)
            }
        } catch (e: Exception) {
            noteFailure()
            runCatching { conn.disconnect() }
            return null
        }
    }

    /**
     * Buffered 2xx: serve the bytes and (when storable AND the write really
     * succeeded) count it. Phase 7 item 9: "stored" now counts ONLY entries
     * really written to disk; a background refresh that writes increments
     * refreshUpdates instead.
     */
    private fun finishBuffered(
        key: String,
        url: String,
        status: Int,
        headers: Map<String, List<String>>,
        body: ByteArray,
        storable: Boolean,
        background: Boolean
    ): WebResourceResponse {
        val response = buildResponse(status, headers, body)
        val written = if (storable) {
            try {
                storeToDisk(key, url, status, headers, body)
            } catch (e: Exception) {
                false // Storing must never break serving.
            }
        } else {
            false
        }
        if (written) {
            diagnostics.stored.incrementAndGet()
            if (background) diagnostics.refreshUpdates.incrementAndGet()
        }
        resetFailures()
        return response
    }

    /** Prefix bytes + the remaining stream; rest == null when the body ended inside the window. */
    private class ProbeResult(val prefix: ByteArray, val rest: java.io.InputStream?)

    /**
     * Reads up to [window] bytes; if the body continues past the window, the
     * un-consumed tail of the last chunk is kept and the rest of the stream
     * is returned so NO byte is lost (prefix + leftover + stream).
     */
    private fun probeRead(stream: java.io.InputStream, window: Int): ProbeResult {
        val out = java.io.ByteArrayOutputStream(window.coerceAtMost(1 shl 20))
        val chunk = ByteArray(64 * 1024)
        var leftover: ByteArray? = null
        while (true) {
            // IOExceptions propagate to fetchAndStore's catch (a mid-body
            // failure must NOT be served as a complete body).
            val n = stream.read(chunk)
            if (n == -1) break
            val space = window - out.size()
            if (n <= space) {
                out.write(chunk, 0, n)
                if (out.size() == window) continue
            } else {
                out.write(chunk, 0, space)
                leftover = chunk.copyOfRange(space, n)
                break
            }
        }
        val prefix = out.toByteArray()
        val rest = leftover?.let {
            java.io.SequenceInputStream(ByteArrayInputStream(it), stream)
        }
        return ProbeResult(prefix, rest)
    }

    /**
     * Builds a streaming WebResourceResponse: real status + reason phrase,
     * safe replayed headers, and an InputStream whose close() disconnects the
     * underlying connection (the WebView calls close when done reading).
     */
    private fun buildStreamingResponse(
        conn: HttpURLConnection,
        status: Int,
        headers: Map<String, List<String>>,
        data: java.io.InputStream
    ): WebResourceResponse {
        val reason = conn.responseMessage?.takeIf { it.isNotBlank() } ?: reasonFor(status)
        val stream = object : java.io.InputStream() {
            override fun read(): Int = data.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = data.read(b, off, len)
            override fun available(): Int = try { data.available() } catch (e: Exception) { 0 }
            override fun close() {
                runCatching { data.close() }
                runCatching { conn.disconnect() }
            }
        }
        val response = WebResourceResponse(
            mimeOf(headers), charsetOf(headers), status, reason, mapOf(), stream
        )
        headers.forEach { (name, values) ->
            if (SAFE_RESPONSE_HEADERS.any { it.equals(name, true) }) {
                values.forEach { v -> response.responseHeaders[name] = v }
            }
        }
        return response
    }

    private fun mimeOf(headers: Map<String, List<String>>): String? =
        headers.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.firstOrNull()?.substringBefore(';')?.trim()?.ifEmpty { null }

    private fun charsetOf(headers: Map<String, List<String>>): String? =
        headers.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.firstOrNull()
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", true) }
            ?.substringAfter('=')?.trim(' ', '"')

    /** Non-empty reason phrase fallback for statuses without a message. */
    private fun reasonFor(status: Int): String = when (status) {
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        in 500..599 -> "Server Error"
        else -> "OK"
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
    ): Boolean {
        synchronized(lock) {
            if (!dir.exists()) dir.mkdirs()
            // Phase 7 item 2: hard stop for NEW stores at the cap — never delete.
            if (!capAllowsLocked(body.size.toLong() + META_OVERHEAD_BYTES)) return false
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
            // Phase 7 item 8: the meta records the exact body length and is
            // written LAST, so its presence proves the body file is complete.
            meta.put("body-length", body.size)
            val ok = writeEntryAtomically(key, meta, body)
            if (ok) updateDiskBytesLocked()
            return ok
        }
    }

    /**
     * Phase 7 item 8 — atomic entry write: body and meta are each written to
     * a tmp file and RENAMED into place, body first, meta LAST. A reader can
     * therefore never observe a body without its (complete-length) meta, so
     * a crash mid-write can only lose the new entry, never corrupt a served
     * one. rename(2) replaces existing files on Android; the delete+rename
     * fallback covers any filesystem that refuses. Returns TRUE on success;
     * on failure the tmp files are cleaned up and the previous entry (if
     * any) is left untouched.
     */
    private fun writeEntryAtomically(key: String, meta: JSONObject, body: ByteArray): Boolean {
        val bodyFile = File(dir, "$key.bin")
        val metaFile = File(dir, "$key.meta")
        val bodyTmp = File(dir, "$key.bin.tmp")
        val metaTmp = File(dir, "$key.meta.tmp")
        return try {
            bodyTmp.writeBytes(body)
            metaTmp.writeText(meta.toString())
            if (!bodyTmp.renameTo(bodyFile)) {
                bodyFile.delete()
                if (!bodyTmp.renameTo(bodyFile)) return false
            }
            if (!metaTmp.renameTo(metaFile)) {
                metaFile.delete()
                if (!metaTmp.renameTo(metaFile)) return false
            }
            true
        } catch (e: Exception) {
            runCatching { bodyTmp.delete() }
            runCatching { metaTmp.delete() }
            false
        }
    }

    /**
     * Phase 7 item 8 — read-side integrity: when the meta carries a
     * body-length (every entry written by this version), a size mismatch
     * means the entry is corrupt: BOTH files are deleted and the read is
     * treated as a plain MISS — never an exception, never counted toward
     * the 3-failure disable. Entries without the field (written by v1.7.0)
     * are accepted so saved data survives the upgrade.
     */
    private fun entryIntactLocked(bodyFile: File, metaFile: File, meta: JSONObject, bytes: ByteArray): Boolean {
        val expected = meta.optLong("body-length", -1L)
        if (expected >= 0 && expected != bytes.size.toLong()) {
            // corrupt (or meta of a different body) — delete and MISS
            runCatching { bodyFile.delete() }
            runCatching { metaFile.delete() }
            return false
        }
        return true // new-version entry with matching length, or legacy entry
    }

    /**
     * Phase 7 item 2 — the 4 GB hard stop for NEW stores. TRUE => the store
     * may proceed. FALSE => the incoming body is refused (existing entries
     * are NEVER touched) and the one-time "Saved-data limit reached" toast
     * fires exactly once per install (until Clear saved data resets it).
     * Must be called while holding [lock] (reentrant reads of the dir).
     */
    private fun capAllowsLocked(incomingBytes: Long): Boolean {
        val allowed = Policy.storeAllowedWithinCap(totalBytesLocked(), incomingBytes, maxBytes)
        if (!allowed && limitToastShown.compareAndSet(false, true)) {
            try { onLimitReached?.invoke() } catch (e: Exception) { /* never a crash source */ }
        }
        return allowed
    }

    /** Recomputes the saved-data size shown in diagnostics. Lock held. */
    private fun updateDiskBytesLocked() {
        diagnostics.diskBytes.set(totalBytesLocked())
    }

    /** A served disk entry: the WebView response plus the metadata needed by
     *  the cache-first logic (content-type for the refresh decision). */
    private class DiskHit(
        val response: WebResourceResponse,
        val contentType: String?,
        val byteCount: Int
    )

    /**
     * Reads a stored entry from disk (online cache-first AND offline serving).
     * Counts a hit; returns null on any miss/problem.
     */
    private fun readFromDisk(method: String, url: String): DiskHit? {
        val key = Policy.cacheKey(method, url)
        synchronized(lock) {
            val bodyFile = key?.let { File(dir, "$it.bin") }
            val metaFile = key?.let { File(dir, "$it.meta") }
            if (bodyFile == null || metaFile == null || !bodyFile.exists() || !metaFile.exists()) {
                diagnostics.misses.incrementAndGet()
                return null
            }
            return try {
                val meta = JSONObject(metaFile.readText())
                val bytes = bodyFile.readBytes()
                if (!entryIntactLocked(bodyFile, metaFile, meta, bytes)) {
                    diagnostics.misses.incrementAndGet()
                    return null
                }
                diagnostics.hits.incrementAndGet()
                resetFailures()
                // Phase 7 item 9: count every byte the cache puts back —
                // online cache-first and offline serving alike.
                diagnostics.bytesServed.addAndGet(bytes.size.toLong())
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
                DiskHit(response, contentType, bytes.size)
            } catch (e: Exception) {
                // Corrupt/unreadable meta => delete the pair, count a MISS —
                // never an exception to the caller, never a failure count.
                runCatching { bodyFile.delete() }
                runCatching { metaFile.delete() }
                diagnostics.misses.incrementAndGet()
                null
            }
        }
    }

    // ---- Background refresh (Phase 7 item 1) ----------------------------------

    /** Small private pool for stale-while-revalidate refreshes. */
    private val refreshExecutor = Executors.newFixedThreadPool(2)

    /** Keys with a refresh currently running — ONE in-flight per key (spec). */
    private val refreshInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Schedules ONE background refresh of a just-served cache-first entry:
     * the same fetch+store path as a normal online fetch, its response
     * discarded — the point is to overwrite the stored copy for next time.
     * One in-flight per key; never throws; nothing touches the WebView.
     */
    private fun scheduleBackgroundRefresh(
        method: String,
        url: String,
        key: String,
        requestHeaders: Map<String, String>,
        userAgent: String?
    ) {
        if (!refreshInFlight.add(key)) return // already refreshing this key
        try {
            refreshExecutor.execute {
                try {
                    fetchAndStore(method, url, requestHeaders, userAgent, background = true)
                } catch (e: Exception) {
                    // Background refresh is best-effort — never a crash source.
                } finally {
                    refreshInFlight.remove(key)
                }
            }
        } catch (e: Exception) {
            refreshInFlight.remove(key)
        }
    }

    /**
     * Phase 7 item 2: the old LRU eviction is REMOVED — saved data stays
     * until "Clear saved data". The only remaining size rule is the hard
     * stop for NEW stores ([capAllowsLocked], checked by the store paths).
     */
    private fun totalBytesLocked(): Long =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".bin") }?.sumOf { it.length() } ?: 0L

    /**
     * "Clear saved data" — deletes every cached entry (Tier-2 resources AND
     * main-page shells, same directory) and resets the shell shadow-fetch
     * throttle so the next online page load re-fetches a fresh shell.
     * Safe from any thread.
     */
    fun clear() {
        synchronized(lock) {
            dir.listFiles()?.forEach { it.delete() }
            diagnostics.diskBytes.set(0)
        }
        limitToastShown.set(false) // the 4 GB toast may fire again after a refill
        shellThrottle.clear()
        lastShellResult = "(cleared saved data)"
    }

    // ---- Main-page shell (Phase 6) ---------------------------------------------
    //
    // Shells live in the SAME directory / lock / 4 GB hard-stop cap as Tier-2
    // entries but use the "SHELL|" key namespace (Policy.shellKey), so they are
    // disjoint from sub-resource entries. clear() wipes both; the cap covers
    // both (nothing is ever auto-deleted).

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

    /** One-time "Saved-data limit reached" toast guard (Phase 7 item 2). */
    private val limitToastShown = AtomicBoolean(false)

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
                // Phase 7 item 7: no Accept-Encoding override — transparent gzip.
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

    /**
     * Persists a shell entry (safe headers only) under the "SHELL|" namespace.
     * Storage contract (Phase 6 item 3):
     *  - SAME app-private versioned dir + 4 GB hard stop as Tier-2 entries
     *    (Phase 7: no LRU, no eviction — saved data stays until cleared);
     *    only the KEY namespace differs (Policy.shellKey), so eviction and
     *    "clear saved data" cover shells with zero extra machinery.
     *  - key = SHA-256("SHELL|" + normalized URL); the normalized URL keeps
     *    its (sorted) query but drops the fragment, so /chart#x == /chart.
     *  - NEVER persisted: Set-Cookie / Cookie / Authorization (or any request
     *    header at all) — only the SAFE_RESPONSE_HEADERS response subset.
     */
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
                // Defensive: sensitive header names can never slip into the
                // persisted meta, independent of SAFE_RESPONSE_HEADERS.
                if (name.equals("Set-Cookie", true) ||
                    name.equals("Cookie", true) ||
                    name.equals("Authorization", true)
                ) return@forEach
                if (SAFE_RESPONSE_HEADERS.any { it.equals(name, true) } && values.isNotEmpty()) {
                    safe.put(name, values.joinToString(", "))
                }
            }
            meta.put("headers", safe)
            if (!capAllowsLocked(body.size.toLong() + META_OVERHEAD_BYTES)) {
                lastShellResult = "skipped:cap"
                return
            }
            // Phase 7 item 8: atomic write, meta (with body-length) last.
            meta.put("body-length", body.size)
            if (!writeEntryAtomically(key, meta, body)) {
                lastShellResult = "failed:write"
                return
            }
            updateDiskBytesLocked()
        }
    }

    /** Loads a stored shell for [url], or null. */
    fun shellFor(url: String): StoredShell? {
        val key = Policy.shellKey(url) ?: return null
        synchronized(lock) {
            val bodyFile = File(dir, "$key.bin")
            val metaFile = File(dir, "$key.meta")
            if (!bodyFile.exists() || !metaFile.exists()) return null
            return try {
                val meta = JSONObject(metaFile.readText())
                val bytes = bodyFile.readBytes()
                if (!entryIntactLocked(bodyFile, metaFile, meta, bytes)) return null
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
                // Corrupt shell meta => delete the pair, treat as no shell.
                runCatching { bodyFile.delete() }
                runCatching { metaFile.delete() }
                null
            }
        }
    }

    /**
     * Phase 6 item 2: serves a main-frame document OFFLINE.
     * Exact shell match first; SPA deep-link fallback to the start-URL shell
     * (same host, path inside the start path). Auth-ish / non-https URLs are
     * never served (same sensitive-URL rules as storage). Returns null when
     * nothing applies — the WebView then fails naturally into the error page.
     */
    fun serveMainFrameOffline(url: String, startUrl: String): WebResourceResponse? {
        return try {
            if (!Policy.shellFetchEligible(url)) return null
            serveShellInternal(url)
                ?: if (Policy.spaShellFallback(url, startUrl)) serveShellInternal(startUrl)
                else null
        } catch (e: Exception) {
            null
        }
    }

    private fun serveShellInternal(url: String): WebResourceResponse? {
        val shell = shellFor(url) ?: return null
        diagnostics.shellHits.incrementAndGet()
        val mime = shell.contentType?.substringBefore(';')?.trim()?.ifEmpty { null }
            ?: "text/html"
        val charset = shell.contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", true) }
            ?.substringAfter('=')?.trim(' ', '"')
            ?: "utf-8"
        val response = WebResourceResponse(
            mime, charset, 200, "OK", mapOf(),
            ByteArrayInputStream(shell.body)
        )
        // Replay only the safe stored headers (CORS included). Shells never
        // carry cookie/token headers — they were filtered at store time.
        shell.headers.forEach { (name, value) -> response.responseHeaders[name] = value }
        return response
    }

    /**
     * Phase 6 item 5: full diagnostics = the Tier-2 summary PLUS the
     * main-page-shell section (offline shell-serve count, last shadow-fetch
     * result code, stored shells as host+path — never query strings, cookies
     * or tokens — with size and age).
     */
    fun diagnosticsSummary(): String {
        val sb = StringBuilder(diagnostics.summary())
        sb.append("main-page shell (Phase 6)\n")
        sb.append("offline shell serves: ").append(diagnostics.shellHits.get()).append('\n')
        sb.append("last shadow-fetch: ").append(lastShellResult).append('\n')
        val shells = shellEntriesSnapshot()
        if (shells.isEmpty()) {
            sb.append("stored shells: (none)\n")
        } else {
            sb.append("stored shells: ").append(shells.size).append('\n')
            shells.take(MAX_SHELL_DIAG_LINES).forEach { s -> sb.append("  ").append(s).append('\n') }
            if (shells.size > MAX_SHELL_DIAG_LINES) {
                sb.append("  … (").append(shells.size - MAX_SHELL_DIAG_LINES).append(" more)\n")
            }
        }
        return sb.toString()
    }

    /** One line per stored shell: "host/path — size — age". Query-free. */
    private fun shellEntriesSnapshot(): List<String> = try {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".meta") }
                ?.mapNotNull { metaFile ->
                    try {
                        val meta = JSONObject(metaFile.readText())
                        if (meta.optString("type") != "shell") return@mapNotNull null
                        val url = meta.optString("url")
                        val host = hostOf(url)
                        val path = pathOf(url) // rawPath — no query string
                        val size = meta.optLong("size", 0L)
                        val ageMs = now - meta.optLong("saved-at", 0L)
                        "$host$path — ${fmtBytes(size)} — age ${fmtAge(ageMs)}"
                    } catch (e: Exception) {
                        null
                    }
                }
                ?.sorted()
                ?: emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun fmtBytes(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun fmtAge(ms: Long): String = when {
        ms < 60_000L -> "${ms / 1000}s"
        ms < 3_600_000L -> "${ms / 60_000}m"
        ms < 86_400_000L -> "${ms / 3_600_000}h"
        else -> "${ms / 86_400_000}d"
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
        /**
         * 4 GB total cap for saved data (Phase 7 item 2). NOT an eviction
         * target: when the total reaches this size, NEW stores are refused
         * (one-time toast) and everything already saved stays on disk until
         * "Clear saved data" — the only deletion path.
         */
        const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024 * 1024
        /**
         * Per-response store limit (Phase 7 item 7): raised from 25 MB to
         * 100 MB per spec. Responses with a KNOWN length up to this size are
         * buffered and stored; larger ones stream through without storing.
         * Unknown-length responses are probe-read up to PROBE_WINDOW_BYTES
         * first (memory safety) and only buffered when they end inside it.
         */
        const val DEFAULT_MAX_SINGLE_RESPONSE = 100 * 1024 * 1024
        /**
         * In-memory probe window for unknown-length (gzip/chunked) responses
         * (Phase 7 item 7). Everything that ends inside this window is served
         * + stored normally (this is what keeps gzipped JSON API responses
         * cacheable — the platform hides Content-Length when it gunzips);
         * anything larger streams through as prefix + rest, no double download.
         */
        const val PROBE_WINDOW_BYTES = 4 * 1024 * 1024
        const val MAX_CONSECUTIVE_FAILURES = 3
        /** Slack added to the cap check for meta files + filesystem overhead. */
        private const val META_OVERHEAD_BYTES = 4L * 1024
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000

        /** Shadow-fetch timeouts (spec: 10 s). */
        private const val SHELL_CONNECT_TIMEOUT_MS = 10_000
        private const val SHELL_READ_TIMEOUT_MS = 10_000

        /** Max shell lines printed in the diagnostics dialog. */
        private const val MAX_SHELL_DIAG_LINES = 20

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
