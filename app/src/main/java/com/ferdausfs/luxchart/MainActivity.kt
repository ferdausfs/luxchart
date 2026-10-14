package com.ferdausfs.luxchart

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.HapticFeedbackConstants
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    private var contentReady = false
    private var contentRevealed = false
    private var errorShowing = false
    private var pendingErrorUrl: String? = null
    private var reducedMotion = false
    private var networkCallbackRegistered = false
    private var renderCrashCount = 0

    /** Last real (http/https) page URL — kept current in onPageStarted. */
    private var currentUrl: String = START_URL

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: PendingDownload? = null

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
            when {
                data == null -> Unit
                granted -> startDownload(data, publicDir = true)
                else -> startDownload(data, publicDir = false) // app-private dir fallback
            }
        }

    // ---- Auto-retry when the network comes back (feature 4) -----------------

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { if (errorShowing) retryLoading() }
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
            // Desktop mode with the device's REAL WebView Chrome major version
            // (pure builder in Policy.kt, unit-tested) — never a hardcoded UA.
            userAgentString = Policy.desktopUa(WebSettings.getDefaultUserAgent(this@MainActivity))
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

    // ---- Allow-list + external browser ---------------------------------------

    private fun isAllowed(host: String): Boolean =
        ALLOWED_DOMAINS.any { d -> host == d || host.endsWith(".$d") }

    private fun openExternal(url: String): Boolean {
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
            // DownloadManager cannot fetch these schemes.
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
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

        /** Hosts (exact match or any subdomain) that stay inside the WebView.
         *  (Moves to Policy.kt as the single source of truth in the next commit.) */
        private val ALLOWED_DOMAINS = listOf(
            "luxalgo.com",
            "accounts.google.com",
            "appleid.apple.com",
            "facebook.com"
        )

        private val BG_COLOR = 0xFF0A0E14.toInt()
        private val ACCENT_COLOR = 0xFF2DD4BF.toInt()
        private val PROGRESS_BG_COLOR = 0xFF1E293B.toInt()
    }
}
