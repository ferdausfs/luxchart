package com.ferdausfs.luxchart

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.app.AlertDialog
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.net.URLEncoder

/**
 * LuxChart — single-Activity WebView shell for https://vela.luxalgo.com/chart
 *
 * Implemented features (Session 2):
 *  1. Persistent login: cookies (incl. third-party for OAuth) + flush on pause
 *  2. Immersive fullscreen + landscape (rotation without reload via configChanges)
 *  3. Back button: goBack() when possible, else exit (OnBackPressedCallback)
 *  4. Offline/error page with Retry + auto-retry when the network returns
 *     (REAL network errors on the main frame only — HTTP >= 400 responses
 *     such as Cloudflare challenges, 403 and 429 render normally so the
 *     site can run its own interstitials)
 *  5. Pull-to-refresh: none exists (no SwipeRefreshLayout) + overscroll disabled
 *  6. Chart gesture fix: WebView-level pinch-zoom disabled so the chart canvas
 *     owns pan/pinch; wide viewport + desktop UA kept; long-press selection
 *     suppressed on CANVAS elements only via injected CSS (login fields keep
 *     native select/paste)
 *  7. File upload (onShowFileChooser) + downloads via DownloadManager
 *  8. Splash screen (androidx core-splashscreen) with no-white-flash handoff
 *  9. Adaptive icon (foreground/background/monochrome) — res/mipmap-anydpi-v26
 *
 * Future features are tracked in AGENT_LOG.md (pending items).
 */
class MainActivity : ComponentActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var offlineBanner: TextView? = null

    private var contentReady = false
    private var contentRevealed = false
    private var errorShowing = false
    private var pendingErrorUrl: String? = null
    private var reducedMotion = false
    private var networkCallbackRegistered = false
    private var renderCrashCount = 0

    /** Online state — drives WebView cache mode + the offline banner. */
    private var isOnline = true

    /** Last real (http/https) page URL — kept current in onPageStarted. */
    private var currentUrl: String = START_URL

    /** Tier-2 offline cache (stale-while-revalidate, app-private). */
    private var tier2Cache: Tier2Cache? = null

    /**
     * Desktop UA captured ONCE on the main thread in [configureWebView].
     * Hotfix 1.6.1 (root cause): `shouldInterceptRequest` runs on a WebView
     * IO thread where ANY WebView/WebSettings access throws
     * "All WebView methods must be called on the same thread" — the 1.6.0
     * launch crash. Read it here, never from the WebView.
     */
    @Volatile
    private var requestUserAgent: String? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: PendingDownload? = null
    private var pendingBlob: PendingBlob? = null

    /** Single background worker for blob/data file saves (never the main thread). */
    private val blobIoExecutor = Executors.newSingleThreadExecutor()

    private class PendingBlob(val bytes: ByteArray, val mime: String?, val displayName: String)

    private data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?
    )

    // ---- Activity result launchers (registered before onStart) --------------

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            callback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val data = pendingDownload
            pendingDownload = null
            val blob = pendingBlob
            pendingBlob = null
            when {
                data == null && blob == null -> Unit
                data != null && granted -> startDownload(data, publicDir = true)
                data != null -> startDownload(data, publicDir = false) // app-private dir fallback
                blob != null && granted -> blobIoExecutor.execute {
                    saveBlobToDisk(blob, publicDir = true)
                }
                blob != null -> blobIoExecutor.execute {
                    saveBlobToDisk(blob, publicDir = false) // app-private fallback
                }
            }
        }

    // ---- Auto-retry when the network comes back (feature 4) -----------------

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                setOnlineState(true)
                if (errorShowing) retryLoading()
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread { reevaluateOnlineState() }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            val online = capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            ) && capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
            runOnUiThread { setOnlineState(online) }
        }
    }

    // ---- Back handling (feature 3) ------------------------------------------

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                errorShowing -> finish()
                webView.canGoBack() -> webView.goBack()
                else -> finish()
            }
        }
    }

    // ---- Lifecycle -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Respect the system reduced-motion setting for all in-app animations.
        reducedMotion = Settings.Global.getFloat(
            contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f

        // Hold the splash until the WebView starts rendering (no white flash:
        // splash, window and WebView share the same dark background).
        splashScreen.setKeepOnScreenCondition { !contentReady }

        buildLayout()
        createAndAttachWebView()

        // Tier-2 offline cache — app-private, versioned directory. Lives for
        // the whole Activity (survives WebView recreation), created BEFORE the
        // first load so the very first request can already populate it.
        tier2Cache = Tier2Cache(
            dir = File(filesDir, "swr-cache/v1"),
            isOnline = { isOnline },
            cookieHeader = { url -> CookieManager.getInstance().getCookie(url) }
        )

        setContentView(rootLayout)
        applyEdgeToEdgeAndInsets()
        onBackPressedDispatcher.addCallback(this, backCallback)

        if (savedInstanceState != null) {
            errorShowing = savedInstanceState.getBoolean(KEY_ERROR_SHOWING, false)
            pendingErrorUrl = savedInstanceState.getString(KEY_ERROR_URL)
            currentUrl = savedInstanceState.getString(KEY_CURRENT_URL) ?: currentUrl
        }
        if (errorShowing) {
            showErrorPage()
        } else {
            // First launch, process-death restore and crash recovery all
            // funnel through the same single START/current URL.
            webView.loadUrl(currentUrl)
        }

        // Set the offline/online cache mode correctly BEFORE the first load
        // (Tier-1 offline), so an app started in airplane mode immediately
        // serves whatever the WebView has saved.
        reevaluateOnlineState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Deliberately NOT calling webView.saveState(): the restored back/forward
        // state can exceed the ~1 MB Binder transaction limit on data-heavy
        // pages (TransactionTooLargeException). We persist only the current URL
        // + error flag and restore by simply loading the URL again.
        outState.putString(KEY_CURRENT_URL, currentUrl)
        outState.putBoolean(KEY_ERROR_SHOWING, errorShowing)
        pendingErrorUrl?.let { outState.putString(KEY_ERROR_URL, it) }
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
    }

    override fun onStop() {
        unregisterNetworkCallback()
        super.onStop()
    }

    override fun onPause() {
        // Persist session cookies to disk so logins survive process death.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    // ---- View construction ---------------------------------------------------

    private fun buildLayout() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(BG_COLOR)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
            )
            max = 100
            progressTintList = ColorStateList.valueOf(ACCENT_COLOR)
            progressBackgroundTintList = ColorStateList.valueOf(PROGRESS_BG_COLOR)
            visibility = View.GONE
        }
        rootLayout.addView(progressBar)

        // Non-blocking offline banner (Tier-1 offline): sits above the WebView,
        // not clickable => touches pass straight through to the charts.
        offlineBanner = TextView(this).apply {
            text = getString(R.string.offline_banner)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(BANNER_TEXT_COLOR)
            background = ColorDrawable(BANNER_BG_COLOR)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        offlineBanner?.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // Long-press => offline-cache diagnostics (counters + recently skipped
        // host/path — never query strings, cookies or tokens). Non-blocking:
        // single taps still pass through to the charts.
        offlineBanner?.setOnLongClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showDiagnosticsDialog()
            true
        }
        rootLayout.addView(offlineBanner, 1)
    }

    /**
     * Builds a fresh WebView, attaches it below the progress bar and wires up
     * the exact same settings, clients, bridges and cookie config every time.
     * Used on first launch AND after a renderer crash (never crash).
     */
    private fun createAndAttachWebView() {
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(BG_COLOR)
        }
        rootLayout.addView(webView, 0)
        setupWebView(webView)
    }

    /** All per-instance WebView wiring lives here so recreation is identical. */
    private fun setupWebView(wv: WebView) {
        configureWebView(wv)
        wv.webViewClient = LuxChartWebViewClient()
        wv.webChromeClient = LuxChartWebChromeClient()
        wv.addJavascriptInterface(ErrorPageBridge(), "LuxBridge")
        wv.addJavascriptInterface(BlobBridge(), "LuxBlob")
        wv.setDownloadListener { url, ua, disposition, mime, _ ->
            handleDownload(url, ua, disposition, mime)
        }
        // Persistent login (feature 1): accept cookies incl. third-party (OAuth).
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
    }

    /**
     * Destroys the current WebView (e.g. dead renderer) and rebuilds an
     * identical one, reloading the last URL. Must be called on the main thread
     * (onRenderProcessGone is always delivered there).
     */
    private fun recreateWebView() {
        val dead = webView
        (dead.parent as? ViewGroup)?.removeView(dead)
        dead.stopLoading()
        dead.destroy()
        createAndAttachWebView()
        webView.loadUrl(currentUrl)
    }

    // ---- Edge-to-edge, immersive mode, insets (features 2 + dark polish) -----

    private fun applyEdgeToEdgeAndInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        val controller = WindowCompat.getInsetsController(window, webView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        // Smooth fullscreen enter/exit: transient bars animate over the content.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Keep web content clear of system bars / keyboard on every API level.
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        if (::webView.isInitialized) {
            WindowCompat.getInsetsController(window, webView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- WebView configuration (features 5 + 6 + desktop mode) ---------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            // Tier-1 offline: online = normal HTTP cache, offline = serve
            // whatever the WebView has saved for this page (chart data,
            // assets, DOM). DOM storage + IndexedDB stay ON (above); Service
            // Workers are left at WebView defaults — deliberately NOT blocked.
            cacheMode = if (isOnline) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
            // Desktop mode with the device's REAL WebView Chrome major version
            // (pure builder in Policy.kt, unit-tested) — never a hardcoded UA.
            userAgentString = Policy.desktopUa(WebSettings.getDefaultUserAgent(this@MainActivity))
            // Hotfix 1.6.1: capture the final UA NOW, on the main thread, for
            // the Tier-2 interceptor (it must never read WebSettings itself).
            requestUserAgent = userAgentString
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Feature 6: WebView must not steal pinch/pan from the chart canvas.
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        // View-level properties (not WebSettings): no overscroll glow/stretch,
        // no scrollbars over the charts.
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        // NOTE: no blanket setOnLongClickListener { true } here — that broke
        // paste/select in the login fields. Canvas-only suppression is injected
        // per page in injectCanvasCssLock() from onPageFinished.
    }

    // ---- Clients -------------------------------------------------------------

    private inner class LuxChartWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            // Track the last real page URL for crash recovery, state restore
            // and retry. Internal pages (file://) are never tracked.
            if (url != null && url.startsWith("http")) currentUrl = url
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            // Tier-2 offline cache. Runs on a WebView IO thread — the engine
            // is fully thread-safe and ALWAYS returns null on any doubt.
            // Returns null for everything it does not own (third-party,
            // auth, non-GET, streaming…): the WebView then loads normally.
            //
            // THREAD RULE (hotfix 1.6.1, root-cause fix): this callback is NOT
            // on the main thread, so it must NEVER touch `view`, `webView` or
            // any WebSettings — that was the 1.6.0 launch crash. The UA comes
            // from @Volatile requestUserAgent, captured in configureWebView.
            //
            // Hotfix 1.6.1 item 2: NOTHING may escape this callback. Any
            // Throwable (including Errors from the engine itself) degrades to
            // null so the WebView simply loads the resource its own way.
            return try {
                tier2Cache?.intercept(
                    request.method,
                    request.url.toString(),
                    request.requestHeaders,
                    requestUserAgent
                )
            } catch (t: Throwable) {
                android.util.Log.w("LuxChart", "shouldInterceptRequest degraded to null", t)
                null
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    val host = uri.host?.lowercase()
                    if (host != null && isAllowed(host)) false // stay in WebView
                    else openExternal(uri.toString())
                }
                // Internal schemes must never be pushed to the external browser
                // (the error page itself is a file:// URL; charts use blob:/data:).
                "file", "blob", "data", "javascript", "about", null -> false
                else -> openExternal(uri.toString())
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                showError(request.url.toString(), error.description?.toString())
            }
        }

        // NOTE: onReceivedHttpError is intentionally NOT overridden. HTTP >= 400
        // responses (Cloudflare challenge, 403, 429, …) must be rendered by the
        // page itself; replacing them with our offline screen would break logins
        // and bot-protection interstitials. The offline error page is shown
        // exclusively for real main-frame network errors above.

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail
        ): Boolean {
            // THREAD AUDIT (hotfix 1.6.1): this callback is delivered on the
            // main thread — touching the WebView here is safe. The only
            // IO-thread callback in this client is shouldInterceptRequest.
            // The WebView renderer died (OOM, GPU driver, site bug). Returning
            // false would kill the whole app — return true, destroy the dead
            // WebView and rebuild an identical one that reloads the last URL.
            renderCrashCount++
            if (renderCrashCount > MAX_RENDER_CRASHES) {
                showError(currentUrl, "Renderer crashed repeatedly")
            } else {
                recreateWebView()
            }
            return true
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            injectCanvasCssLock(view)
            if (!errorShowing && url != null && !url.startsWith("file:")) {
                contentReady = true
                revealContent()
            }
        }
    }

    /**
     * Suppresses text selection / touch callout on CANVAS elements only (the
     * chart surface must own its long-press), while login fields keep native
     * select/paste. A style tag is used instead of inline styles so canvases
     * created later by the SPA are covered too. Idempotent; runs on the main
     * thread (WebView callback context).
     */
    private fun injectCanvasCssLock(view: WebView?) {
        view ?: return
        val js = "(function(){" +
            "if(document.getElementById('luxchart-canvas-lock'))return;" +
            "var s=document.createElement('style');" +
            "s.id='luxchart-canvas-lock';" +
            "s.textContent='canvas{-webkit-user-select:none!important;-webkit-touch-callout:none!important}';" +
            "(document.head||document.documentElement).appendChild(s);" +
            "})()"
        view.evaluateJavascript(js, null)
    }

    private inner class LuxChartWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            updateProgressBar(newProgress)
        }

        // Feature 7a: file upload (file chooser).
        override fun onShowFileChooser(
            webView: WebView?,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            return try {
                fileChooserLauncher.launch(params.createIntent())
                true
            } catch (e: ActivityNotFoundException) {
                filePathCallback = null
                false
            }
        }
    }

    // JS bridge used by the offline page (feature 4). Kept by ProGuard rules.
    private inner class ErrorPageBridge {
        @JavascriptInterface
        fun retry() {
            runOnUiThread {
                rootLayout.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                retryLoading()
            }
        }

        /** "Clear saved data" control on the error/offline screen (Tier-2). */
        @JavascriptInterface
        fun clearSavedData() {
            tier2Cache?.clear()
            toastInternal(getString(R.string.saved_data_cleared))
        }
    }

    // ---- Progress bar + splash handoff (feature 8 polish) --------------------

    private fun updateProgressBar(newProgress: Int) {
        // Release the splash as soon as the first content paints.
        if (newProgress >= 1 && !contentReady) {
            contentReady = true
            revealContent()
        }
        if (newProgress >= 100) {
            if (progressBar.visibility == View.VISIBLE) {
                if (reducedMotion) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.animate().alpha(0f).setDuration(180)
                        .withEndAction {
                            progressBar.visibility = View.GONE
                            progressBar.alpha = 1f
                        }.start()
                }
            }
            return
        }
        if (progressBar.visibility == View.GONE) {
            progressBar.alpha = 1f
            progressBar.visibility = View.VISIBLE
        }
        if (reducedMotion) {
            progressBar.progress = newProgress
        } else {
            animateBarTo(newProgress)
        }
    }

    private fun animateBarTo(target: Int) {
        val from = progressBar.progress
        if (target <= from) {
            progressBar.progress = target
            return
        }
        ValueAnimator.ofInt(from, target).apply {
            duration = 150
            addUpdateListener { animator ->
                progressBar.progress = animator.animatedValue as Int
            }
            start()
        }
    }

    /** Splash-to-content fade (skipped when the system requests reduced motion). */
    private fun revealContent() {
        if (contentRevealed) return
        contentRevealed = true
        if (reducedMotion) {
            rootLayout.alpha = 1f
            return
        }
        rootLayout.alpha = 0f
        rootLayout.animate().alpha(1f).setDuration(220).withEndAction(null).start()
    }

    // ---- Offline / error page (feature 4) ------------------------------------

    private fun showError(failingUrl: String, message: String?) {
        pendingErrorUrl = failingUrl
        errorShowing = true
        showErrorPage(message)
    }

    /** Loads the local offline/error page (optionally with an encoded reason). */
    private fun showErrorPage(message: String? = null) {
        contentReady = true
        revealContent()
        progressBar.visibility = View.GONE
        if (message == null) {
            webView.loadUrl("file:///android_asset/error.html")
        } else {
            val encoded = runCatching { URLEncoder.encode(message.take(140), "UTF-8") }
                .getOrDefault("Load+failed")
            webView.loadUrl("file:///android_asset/error.html?msg=$encoded")
        }
    }

    private fun retryLoading() {
        errorShowing = false
        val target = pendingErrorUrl ?: START_URL
        pendingErrorUrl = null
        currentUrl = target
        progressBar.progress = 0
        webView.loadUrl(target)
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            // Auto-retry is a nicety, never a crash source.
            networkCallbackRegistered = false
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // ignore — already unregistered
        }
        networkCallbackRegistered = false
    }

    // ---- Tier-1 offline: dynamic cache mode (feature: offline) ----------------

    /**
     * Queries the current default network and applies the resulting state.
     * Called at app start and whenever a network is lost. Main thread only.
     */
    private fun reevaluateOnlineState() {
        val online = try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            // If we cannot know, assume online and let the error page handle it.
            true
        }
        setOnlineState(online)
    }

    /** Applies an online/offline transition: cache mode + banner (Tier-2 hooks later). */
    private fun setOnlineState(online: Boolean) {
        if (isOnline == online) return
        isOnline = online
        applyCacheMode()
        updateOfflineBanner()
    }

    private fun applyCacheMode() {
        if (::webView.isInitialized) {
            webView.settings.cacheMode = if (isOnline) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
        }
    }

    private fun updateOfflineBanner() {
        offlineBanner?.visibility = if (isOnline) View.GONE else View.VISIBLE
    }

    /** Long-press on the offline banner: copyable/shareable cache diagnostics. */
    private fun showDiagnosticsDialog() {
        val summary = tier2Cache?.diagnostics?.summary() ?: return
        val body = TextView(this).apply {
            text = summary
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFCBD5E1.toInt())
            setPadding(dp(20), dp(14), dp(20), dp(4))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle("Offline cache diagnostics")
            .setView(scroll)
            .setPositiveButton(R.string.diag_copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(
                    ClipData.newPlainText("LuxChart offline diagnostics", summary)
                )
                Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.diag_share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, summary)
                    putExtra(Intent.EXTRA_TITLE, "LuxChart offline diagnostics")
                }
                runCatching {
                    startActivity(Intent.createChooser(send, getString(R.string.diag_share)))
                }
            }
            .setNeutralButton(R.string.diag_close, null)
            .show()
    }

    // ---- Allow-list + external browser ---------------------------------------

    private fun isAllowed(host: String): Boolean = Policy.isAllowed(host)

    private fun openExternal(url: String): Boolean {
        // Short, honest feedback about WHY we are leaving the app.
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        val what = host ?: url.take(60)
        Toast.makeText(this, getString(R.string.opening_external, what), Toast.LENGTH_SHORT)
            .show()
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            true
        }
    }

    // ---- Downloads (feature 7b) ----------------------------------------------

    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            // DownloadManager cannot fetch these schemes — save in-app instead.
            handleInternalDownload(url)
            return
        }
        val data = PendingDownload(url, userAgent, contentDisposition, mimeType)
        val needsLegacyWrite = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyWrite) {
            pendingDownload = data
            storagePermissionLauncher.launch(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            startDownload(data, publicDir = true)
        }
    }

    private fun startDownload(data: PendingDownload, publicDir: Boolean) {
        val name = URLUtil.guessFileName(
            data.url, data.contentDisposition, data.mimeType
        )
        try {
            val request = DownloadManager.Request(Uri.parse(data.url)).apply {
                setTitle(name)
                setDescription("LuxChart download")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                data.userAgent?.let { addRequestHeader("User-Agent", it) }
                CookieManager.getInstance().getCookie(data.url)?.let {
                    addRequestHeader("Cookie", it)
                }
                if (publicDir) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                } else {
                    setDestinationInExternalFilesDir(
                        this@MainActivity, Environment.DIRECTORY_DOWNLOADS, name
                    )
                }
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            openExternal(data.url)
        }
    }

    // ---- blob:/data: downloads (in-page fetch -> base64 -> disk) -------------

    private fun handleInternalDownload(url: String) {
        if (url.startsWith("data:")) {
            // data: URLs carry the bytes themselves — parse + save directly,
            // off the main thread (payloads can be large).
            val parts = Policy.dataUrlParts(url)
            if (parts == null) {
                toastInternal(R.string.download_failed)
                return
            }
            blobIoExecutor.execute {
                val bytes = try {
                    if (parts.isBase64) {
                        Base64.decode(parts.payload, Base64.DEFAULT)
                    } else {
                        java.net.URLDecoder.decode(parts.payload, "UTF-8")
                            .toByteArray(Charsets.UTF_8)
                    }
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (bytes == null || bytes.size > MAX_BLOB_BYTES) {
                    toastInternal(R.string.download_failed)
                } else {
                    saveBlobToDisk(
                        PendingBlob(bytes, parts.mime, defaultBlobName(parts.mime)),
                        publicDir = true
                    )
                }
            }
        } else {
            // blob: URLs only exist inside the page's JS context — ask the page
            // to fetch the blob and hand us the base64 via the LuxBlob bridge.
            fetchBlobViaJs(url)
        }
    }

    private fun fetchBlobViaJs(blobUrl: String) {
        val jsonUrl = JSONObject.quote(blobUrl)
        val js = "(function(){" +
            "var cb=window.LuxBlob;if(!cb)return;" +
            "var url=" + jsonUrl + ";" +
            "function fail(e){try{cb.saveError(String(e&&e.message||e));}catch(_){}}" +
            "try{fetch(url).then(function(r){if(!r.ok)throw 'HTTP '+r.status;return r.blob();})" +
            ".then(function(b){var fr=new FileReader();" +
            "fr.onload=function(){try{cb.save(null,b.type||'',String(fr.result).split(',')[1]||'');}catch(e){fail(e);}};" +
            "fr.onerror=function(){fail('read-failed');};" +
            "fr.readAsDataURL(b);}).catch(fail);}catch(e){fail(e);}})()"
        webView.evaluateJavascript(js, null)
    }

    /**
     * JS bridge for blob downloads. Invoked from the page on a WebView-internal
     * thread — never touches the UI directly. Kept by ProGuard rules.
     */
    private inner class BlobBridge {
        @JavascriptInterface
        fun save(name: String?, mime: String?, base64: String) {
            // Tolerate a full data URL being passed instead of bare base64.
            val clean = base64.substringAfterLast(",").trim()
            if (clean.isEmpty() || clean.length > MAX_BLOB_BYTES / 3 * 4 + 4) {
                toastInternal(R.string.download_failed)
                return
            }
            val bytes = try {
                Base64.decode(clean, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                toastInternal(R.string.download_failed)
                return
            }
            if (bytes.size > MAX_BLOB_BYTES) {
                toastInternal(R.string.download_failed)
                return
            }
            val displayName = name?.takeIf { it.isNotBlank() && !it.contains('/') }
                ?: defaultBlobName(mime)
            blobIoExecutor.execute {
                saveBlobToDisk(PendingBlob(bytes, mime, displayName), publicDir = true)
            }
        }

        @JavascriptInterface
        fun saveError(reason: String?) {
            toastInternal(R.string.download_failed)
        }
    }

    /** Saves to public Downloads (MediaStore on 29+, legacy file below). */
    private fun saveBlobToDisk(blob: PendingBlob, publicDir: Boolean) {
        val savedName: String? = try {
            when {
                !publicDir -> saveBlobPrivate(blob)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    saveBlobMediaStore(blob)
                ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED ->
                    saveBlobLegacyPublic(blob)
                else -> {
                    // Ask once; fall back to app-private dir if denied.
                    pendingBlob = blob
                    runOnUiThread {
                        storagePermissionLauncher.launch(
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    }
                    return
                }
            }
        } catch (e: Exception) {
            null
        }
        if (savedName != null) {
            toastInternal(getString(R.string.blob_saved, savedName))
        } else {
            toastInternal(R.string.blob_failed)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveBlobMediaStore(blob: PendingBlob): String? {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, blob.displayName)
            put(MediaStore.Downloads.MIME_TYPE, blob.mime ?: "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { it.write(blob.bytes) } ?: return null
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return blob.displayName
    }

    @Suppress("DEPRECATION")
    private fun saveBlobLegacyPublic(blob: PendingBlob): String? {
        val dir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!dir.exists()) dir.mkdirs()
        val file = uniquify(File(dir, blob.displayName))
        file.outputStream().use { it.write(blob.bytes) }
        return file.name
    }

    private fun saveBlobPrivate(blob: PendingBlob): String? {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val file = uniquify(File(dir, blob.displayName))
        file.outputStream().use { it.write(blob.bytes) }
        return file.name
    }

    /** "name (2).ext" style de-duplication for the legacy/private paths. */
    private fun uniquify(file: File): File {
        if (!file.exists()) return file
        val base = file.name.substringBeforeLast('.', file.name)
        val ext = file.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
        var i = 1
        var candidate = file
        while (candidate.exists()) {
            val n = "$base ($i)" + (ext?.let { ".$it" } ?: "")
            candidate = File(file.parentFile, n)
            i++
        }
        return candidate
    }

    private fun defaultBlobName(mime: String?): String {
        val ext = EXT_BY_MIME.entries.firstOrNull { mime?.startsWith(it.key) == true }?.value
            ?: "bin"
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "luxchart-$ts.$ext"
    }

    /** Toast helper safe to call from any thread. */
    private fun toastInternal(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toastInternal(resId: Int) {
        toastInternal(getString(resId))
    }

    // ---- Helpers -------------------------------------------------------------

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        /**
         * The one and only start URL: opened on first launch, on error-retry
         * and on any WebView re-creation. Navigation allow-list still covers
         * it (vela.luxalgo.com is a luxalgo.com subdomain).
         */
        const val START_URL = "https://vela.luxalgo.com/chart"
        private const val KEY_ERROR_SHOWING = "lux.errorShowing"
        private const val KEY_ERROR_URL = "lux.errorUrl"
        private const val KEY_CURRENT_URL = "lux.currentUrl"
        private const val MAX_RENDER_CRASHES = 3

        /** Cap for in-app blob/data saves — avoids OOM on absurd payloads. */
        private const val MAX_BLOB_BYTES = 80 * 1024 * 1024

        /** mime prefix -> file extension for generated blob/data filenames. */
        private val EXT_BY_MIME = mapOf(
            "application/pdf" to "pdf",
            "text/csv" to "csv",
            "text/plain" to "txt",
            "text/html" to "html",
            "application/json" to "json",
            "application/zip" to "zip",
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/webp" to "webp",
            "image/gif" to "gif",
            "image/svg" to "svg",
            "application/vnd.ms-excel" to "xls",
            "application/vnd.openxmlformats-officedocument.spreadsheetml" to "xlsx",
            "application/msword" to "doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml" to "docx",
            "audio/mpeg" to "mp3",
            "video/mp4" to "mp4",
            "video/webm" to "webm"
        )

        private val BG_COLOR = 0xFF0A0E14.toInt()
        private val ACCENT_COLOR = 0xFF2DD4BF.toInt()
        private val PROGRESS_BG_COLOR = 0xFF1E293B.toInt()
        private val BANNER_BG_COLOR = 0xFF334155.toInt()
        private val BANNER_TEXT_COLOR = 0xFFFCD34D.toInt()
    }
}
