package com.firstdj.hdjd

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Single-activity shell around https://jain.firstdj.com/.
 *
 * Launch sequence: system splash (AndroidX SplashScreen API) -> in-app full-screen logo that holds
 * for [AppConfig.SPLASH_MIN_MS] while the website loads underneath -> dissolve into the page.
 * If the page is slow, the same screen shows a small spinner instead of a blank page.
 */
class MainActivity : AppCompatActivity(), HdjdWebViewClient.Listener {

    private lateinit var root: FrameLayout
    private lateinit var content: View
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: Button
    private lateinit var splash: View
    private lateinit var splashLogo: View
    private lateinit var splashSpinner: View

    private val handler = Handler(Looper.getMainLooper())
    private val launcher by lazy { ExternalLauncher(this) }

    private var isTv = false
    private var errorShowing = false
    private var failingUrl: String? = null
    private var rendererDead = false

    // Splash state
    private var splashShowing = true
    private var minSplashElapsed = false
    private var pageReady = false

    // Full-screen video support
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // File upload support (<input type="file">), no storage permission needed.
    private var fileCallback: ValueCallback<Array<android.net.Uri>>? = null
    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            fileCallback = null
        }

    private var lastInteraction = 0L

    private companion object {
        /** Survives recreate() so a crash loop can be detected. */
        var lastRendererGone = 0L
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val systemSplash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Hand over from the system splash with a quick cross-fade; our in-app splash is already
        // drawn underneath with the same background, so there is no flicker.
        systemSplash.setOnExitAnimationListener { provider ->
            provider.view.animate().alpha(0f).setDuration(150L)
                .withEndAction { provider.remove() }.start()
        }

        val barBg = ContextCompat.getColor(this, R.color.hdjd_background)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(barBg),
            navigationBarStyle = SystemBarStyle.dark(barBg)
        )
        setContentView(R.layout.activity_main)

        isTv = (getSystemService(UI_MODE_SERVICE) as? UiModeManager)?.currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION

        bindViews()
        applyInsets()
        configureWebView()
        configureErrorView()
        registerBackHandler()

        if (savedInstanceState != null) {
            // Process restore / renderer recovery: no need to show the splash again.
            skipSplash()
            if (webView.restoreState(savedInstanceState) == null) loadStartPage()
        } else {
            startSplashTimers()
            loadStartPage()
        }
    }

    // ------------------------------------------------------------------ setup

    private fun bindViews() {
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        swipe = findViewById(R.id.swipe)
        webView = findViewById(R.id.webview)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error_view)
        errorTitle = errorView.findViewById(R.id.error_title)
        errorMessage = errorView.findViewById(R.id.error_message)
        retryButton = errorView.findViewById(R.id.retry_button)
        splash = findViewById(R.id.splash)
        splashLogo = findViewById(R.id.splash_logo)
        splashSpinner = findViewById(R.id.splash_spinner)
    }

    /** Edge-to-edge: the content area avoids system bars, display cutouts and the keyboard. */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView.settings.apply {
            javaScriptEnabled = true            // required by the website
            domStorageEnabled = true
            // Hardening: the site never needs local files or content:// access.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // target=_blank / window.open load in this same WebView, where our router sees them.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = "$userAgentString HDJDApp/${BuildConfig.VERSION_NAME}"
            if (isTv) textZoom = AppConfig.TV_TEXT_ZOOM
        }
        // Nothing is exposed to page JavaScript (no addJavascriptInterface).
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.hdjd_background))
        webView.isVerticalScrollBarEnabled = true

        // Track recent taps / OK presses so external launches only follow user actions.
        webView.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_UP) {
                lastInteraction = SystemClock.uptimeMillis()
            }
            false
        }
        webView.setOnKeyListener { _, keyCode, e ->
            if (e.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                lastInteraction = SystemClock.uptimeMillis()
            }
            false
        }

        webView.webViewClient = HdjdWebViewClient(
            router = LinkRouter(AppConfig.TRUSTED_HOST),
            launcher = launcher,
            trustedHost = AppConfig.TRUSTED_HOST,
            isUserInitiated = { request ->
                request.hasGesture() ||
                    SystemClock.uptimeMillis() - lastInteraction <= AppConfig.USER_INTERACTION_WINDOW_MS
            },
            listener = this
        )
        webView.webChromeClient = HdjdChromeClient()

        // Downloads (PDFs etc.) are handed to the browser / download manager.
        webView.setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("https://", ignoreCase = true)) launcher.openUrl(url)
        }

        // Pull-to-refresh on touch devices only; only when the page itself is scrolled to the top.
        swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.hdjd_accent))
        swipe.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.hdjd_background))
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipe.setOnRefreshListener { webView.reload() }
        swipe.isEnabled = !isTv

        if (isTv) webView.requestFocus()
    }

    private fun configureErrorView() {
        retryButton.setOnClickListener { retry() }
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    splashShowing -> finish()
                    errorShowing -> {
                        if (webView.canGoBack()) {
                            hideError()
                            webView.goBack()
                        } else {
                            finish()
                        }
                    }
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    // ----------------------------------------------------------------- loading

    private fun loadStartPage() {
        if (!NetworkUtils.isOnline(this)) {
            failingUrl = AppConfig.START_URL
            showError(ErrorKind.NETWORK)
            return
        }
        webView.loadUrl(AppConfig.START_URL)
    }

    private fun retry() {
        if (rendererDead) {          // a crashed WebView cannot be reused; build a fresh one
            recreate()
            return
        }
        val target = failingUrl ?: AppConfig.START_URL
        hideError()
        if (!NetworkUtils.isOnline(this)) {
            failingUrl = target
            showError(ErrorKind.NETWORK)
            return
        }
        webView.loadUrl(target)
    }

    // ---------------------------------------------------------- error handling

    private fun showError(kind: ErrorKind) {
        errorShowing = true
        val (title, message) = when (kind) {
            ErrorKind.NETWORK -> R.string.error_network_title to R.string.error_network_message
            ErrorKind.SERVER -> R.string.error_server_title to R.string.error_server_message
            ErrorKind.SSL -> R.string.error_ssl_title to R.string.error_ssl_message
        }
        errorTitle.setText(title)
        errorMessage.setText(message)
        webView.stopLoading()
        swipe.isRefreshing = false
        progress.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        retryButton.requestFocus()      // D-pad users land on the button
        pageReady = true
        trySplashDismiss()
    }

    private fun hideError() {
        errorShowing = false
        errorView.visibility = View.GONE
        if (isTv) webView.requestFocus()
    }

    // ----------------------------------------- HdjdWebViewClient.Listener

    override fun onLoadStarted() {
        if (errorShowing) return
        progress.progress = 0
        progress.visibility = View.VISIBLE
    }

    override fun onPageVisible() {
        if (errorShowing) return
        pageReady = true
        trySplashDismiss()
    }

    override fun onPageFinished() {
        if (errorShowing) return
        swipe.isRefreshing = false
        progress.visibility = View.GONE
        pageReady = true
        trySplashDismiss()
        CookieManager.getInstance().flush()
    }

    override fun onMainFrameError(kind: ErrorKind, failingUrl: String?) {
        this.failingUrl = failingUrl ?: AppConfig.START_URL
        showError(kind)
    }

    override fun onRenderProcessGone() {
        // The WebView renderer died (e.g. low memory). A dead WebView cannot be reused, so
        // rebuild the activity instead of crashing the app. If it happens again within
        // seconds, stop looping and let the user press Retry.
        rendererDead = true
        val now = SystemClock.uptimeMillis()
        val repeated = now - lastRendererGone < 15_000L
        lastRendererGone = now
        if (repeated) {
            showError(ErrorKind.SERVER)
        } else {
            Toast.makeText(this, R.string.renderer_gone, Toast.LENGTH_SHORT).show()
            recreate()
        }
    }

    // ------------------------------------------------------------------ splash

    private fun startSplashTimers() {
        // Gentle settle-in so the logo feels alive while the page loads.
        splashLogo.scaleX = 0.94f
        splashLogo.scaleY = 0.94f
        splashLogo.animate().scaleX(1f).scaleY(1f).setDuration(1_200L)
            .setInterpolator(DecelerateInterpolator()).start()

        handler.postDelayed({
            minSplashElapsed = true
            // Page still loading after the hold time: show a small spinner, not a blank screen.
            if (!pageReady) splashSpinner.animate().alpha(1f).setDuration(300L).start()
            trySplashDismiss()
        }, AppConfig.SPLASH_MIN_MS)

        handler.postDelayed({ dismissSplash() }, AppConfig.SPLASH_MAX_MS)
    }

    private fun skipSplash() {
        splashShowing = false
        splash.visibility = View.GONE
    }

    private fun trySplashDismiss() {
        if (splashShowing && minSplashElapsed && pageReady) dismissSplash()
    }

    private fun dismissSplash() {
        if (!splashShowing) return
        splashShowing = false
        handler.removeCallbacksAndMessages(null)
        splashLogo.animate().scaleX(1.06f).scaleY(1.06f)
            .setDuration(AppConfig.SPLASH_FADE_MS).start()
        splash.animate().alpha(0f).setDuration(AppConfig.SPLASH_FADE_MS)
            .withEndAction { splash.visibility = View.GONE }
            .start()
    }

    // ------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        // If the connection drops while the error screen is up, retry automatically on return.
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { if (errorShowing) retry() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: RuntimeException) {
            // Some OEM builds throw when the callback limit is exceeded; the Retry button still works.
        }
    }

    override fun onStop() {
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {
                // already unregistered
            }
        }
        networkCallback = null
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideCustomView()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------ custom view (video)

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        content.visibility = View.INVISIBLE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        content.visibility = View.VISIBLE
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    // --------------------------------------------------------------- chrome client

    private inner class HdjdChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (errorShowing) return
            progress.progress = newProgress
            if (newProgress >= 100) progress.visibility = View.GONE
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) =
            showCustomView(view, callback)

        override fun onHideCustomView() = hideCustomView()

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<android.net.Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                fileChooser.launch(fileChooserParams.createIntent())
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback?.onReceiveValue(null)
                fileCallback = null
                false
            }
        }

        // Camera, microphone and location requests from the page are denied by default
        // (onPermissionRequest / onGeolocationPermissionsShowPrompt are intentionally not overridden).

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(R.string.dialog_ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(R.string.dialog_ok) { _, _ -> result.confirm() }
                .setNegativeButton(R.string.dialog_cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }
}
