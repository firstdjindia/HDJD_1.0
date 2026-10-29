package com.firstdj.hdjd

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

enum class ErrorKind { NETWORK, SERVER, SSL }

/**
 * Keeps trusted pages in the WebView, sends WhatsApp / Maps / phone / mail / other links to the
 * right native app, and reports main-frame failures so the activity can show its error screen.
 */
class HdjdWebViewClient(
    private val router: LinkRouter,
    private val launcher: ExternalLauncher,
    private val trustedHost: String,
    private val isUserInitiated: (WebResourceRequest) -> Boolean,
    private val listener: Listener
) : WebViewClient() {

    interface Listener {
        fun onLoadStarted()
        fun onPageVisible()
        fun onPageFinished()
        fun onMainFrameError(kind: ErrorKind, failingUrl: String?)
        fun onRenderProcessGone()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // Let iframes (e.g. embedded maps) load normally; routing applies to page navigations.
        if (!request.isForMainFrame) return false

        val url = request.url.toString()
        return when (val route = router.classify(url)) {
            Route.Internal -> {
                // Never load the trusted site over plain http.
                if (url.startsWith("http://", ignoreCase = true)) {
                    view.loadUrl("https://" + url.substring("http://".length))
                    true
                } else {
                    false
                }
            }
            is Route.Blocked -> {
                Log.w(TAG, "Blocked navigation: ${route.reason}")
                true
            }
            else -> {
                if (isUserInitiated(request)) {
                    launcher.launch(route)
                } else {
                    // Pages must not open other apps on their own, only in response to a tap.
                    Log.w(TAG, "Ignored non-user-initiated external navigation")
                }
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        listener.onLoadStarted()
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        listener.onPageVisible()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        listener.onPageFinished()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (!request.isForMainFrame) return
        if (error.errorCode == ERROR_UNSUPPORTED_SCHEME) return
        listener.onMainFrameError(ErrorKind.NETWORK, request.url.toString())
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        if (request.isForMainFrame && errorResponse.statusCode >= 500) {
            listener.onMainFrameError(ErrorKind.SERVER, request.url.toString())
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Never proceed past certificate problems.
        handler.cancel()
        val host = Uri.parse(error.url).host
        if (host == trustedHost) listener.onMainFrameError(ErrorKind.SSL, error.url)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Log.w(TAG, "WebView renderer gone (crashed=${detail.didCrash()})")
        listener.onRenderProcessGone()
        return true
    }

    private companion object {
        const val TAG = "HDJD"
    }
}
