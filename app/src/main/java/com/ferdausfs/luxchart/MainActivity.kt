package com.ferdausfs.luxchart

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * LuxChart — single-Activity WebView shell for https://www.luxalgo.com/
 *
 * Scope of this build (CI FIRST skeleton):
 *  - loads the start URL in a WebView
 *  - desktop user-agent + wide viewport so chart features are not hidden
 *  - allow-list keeps luxalgo.com (and subdomains) plus the Google / Apple /
 *    Facebook sign-in hosts inside the WebView; everything else opens in the
 *    external browser
 *
 * Future features are tracked in AGENT_LOG.md (pending items) and are
 * intentionally NOT implemented in this session.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(webView)

        configureWebView()
        webView.webViewClient = AllowListWebViewClient()
        webView.webChromeClient = WebChromeClient()

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = DESKTOP_USER_AGENT
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
    }

    private inner class AllowListWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                val host = uri.host?.lowercase()
                if (host != null && isAllowed(host)) {
                    return false // stay inside the WebView
                }
                return openExternal(uri.toString())
            }
            // mailto:, tel:, intent:, ... -> external handler
            return openExternal(uri.toString())
        }
    }

    private fun isAllowed(host: String): Boolean =
        ALLOWED_DOMAINS.any { d -> host == d || host.endsWith(".$d") }

    private fun openExternal(url: String): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val START_URL = "https://www.luxalgo.com/"

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
    }
}
