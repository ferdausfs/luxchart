package com.ferdausfs.luxchart

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
 * LuxChart — single-Activity WebView shell for https://www.luxalgo.com/
 *
 * Implemented features (Session 2):
 *  1. Persistent login: cookies (incl. third-party for OAuth) + flush on pause
 *  2. Immersive fullscreen + landscape (rotation without reload via configChanges)
 *  3. Back button: goBack() when possible, else exit (OnBackPressedCallback)
 *  4. Offline/error page with Retry + auto-retry when the network returns
 *  5. Pull-to-refresh: none exists (no SwipeRefreshLayout) + overscroll disabled
 *  6. Chart gesture fix: WebView-level pinch-zoom disabled so the chart canvas
 *     owns pan/pinch; wide viewport + desktop UA kept; long-press selection off
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
        setContentView(rootLayout)
        applyEdgeToEdgeAndInsets()
        onBackPressedDispatcher.addCallback(this, backCallback)

        configureWebView()
        webView.webViewClient = LuxChartWebViewClient()
        webView.webChromeClient = LuxChartWebChromeClient()
        webView.addJavascriptInterface(ErrorPageBridge(), "LuxBridge")
        webView.setDownloadListener { url, ua, disposition, mime, _ ->
            handleDownload(url, ua, disposition, mime)
        }

        // Persistent login (feature 1): accept cookies incl. third-party (OAuth).
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        if (savedInstanceState != null) {
            errorShowing = savedInstanceState.getBoolean(KEY_ERROR_SHOWING, false)
            pendingErrorUrl = savedInstanceState.getString(KEY_ERROR_URL)
            if (errorShowing) {
                webView.loadUrl("file:///android_asset/error.html")
                contentReady = true
                revealContent()
            } else {
                webView.restoreState(savedInstanceState)
            }
        } else {
            webView.loadUrl(START_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
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
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(BG_COLOR)
        }
        rootLayout.addView(webView)
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
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = DESKTOP_USER_AGENT
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Feature 6: WebView must not steal pinch/pan from the chart canvas.
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        // View-level properties (not WebSettings): no overscroll glow/stretch,
        // no scrollbars over the charts.
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        // Long-press on charts is a drawing-tool gesture in luxalgo — suppress
        // the text-selection popup so it is not stolen from the canvas.
        webView.setOnLongClickListener { true }
    }

    // ---- Clients -------------------------------------------------------------

    private inner class LuxChartWebViewClient : WebViewClient() {

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

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            response: WebResourceResponse
        ) {
            if (request.isForMainFrame && response.statusCode >= 400) {
                showError(request.url.toString(), "HTTP ${response.statusCode}")
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (!errorShowing && url != null && !url.startsWith("file:")) {
                contentReady = true
                revealContent()
            }
        }
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
        contentReady = true
        revealContent()
        progressBar.visibility = View.GONE
        val raw = message?.take(140) ?: "The page failed to load"
        val encoded = runCatching { URLEncoder.encode(raw, "UTF-8") }
            .getOrDefault("Load+failed")
        webView.loadUrl("file:///android_asset/error.html?msg=$encoded")
    }

    private fun retryLoading() {
        errorShowing = false
        val target = pendingErrorUrl ?: START_URL
        pendingErrorUrl = null
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
        private const val START_URL = "https://www.luxalgo.com/"
        private const val KEY_ERROR_SHOWING = "lux.errorShowing"
        private const val KEY_ERROR_URL = "lux.errorUrl"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** Hosts (exact match or any subdomain) that stay inside the WebView. */
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
