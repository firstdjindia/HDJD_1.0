package com.firstdj.hdjd

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import java.net.URISyntaxException

/**
 * Opens links in native apps using plain Android intents (no runtime permissions needed).
 * Every function here is only called after the user tapped something; see
 * [HdjdWebViewClient] for the gesture check.
 */
class ExternalLauncher(private val activity: Activity) {

    fun launch(route: Route) {
        when (route) {
            is Route.WhatsApp -> openWhatsApp(route)
            is Route.Maps -> openMaps(route)
            is Route.Dial -> startOrToast(Intent(Intent.ACTION_DIAL, Uri.parse(route.url)))
            is Route.Mail -> startOrToast(Intent(Intent.ACTION_SENDTO, Uri.parse(route.url)))
            is Route.Sms -> startOrToast(Intent(Intent.ACTION_SENDTO, Uri.parse(route.url)))
            is Route.IntentUri -> openIntentUri(route.url)
            is Route.External -> openUrl(route.url)
            Route.Internal, is Route.Blocked -> Unit
        }
    }

    fun openUrl(url: String) {
        startOrToast(browserIntent(Uri.parse(url)))
    }

    // ---------------------------------------------------------------- WhatsApp

    /**
     * Hands the message text to WhatsApp's own "share" screen. The user then picks the
     * contact/group and presses send; nothing is ever sent automatically.
     */
    private fun openWhatsApp(route: Route.WhatsApp) {
        val installed = WHATSAPP_PACKAGES.filter { isInstalled(it) }
        if (installed.isEmpty()) {
            openWhatsAppWebFallback(route)
            return
        }
        val intents = installed.map { buildWhatsAppIntent(route, it) }
        val toStart = if (intents.size == 1) {
            intents[0]
        } else {
            // Both WhatsApp Messenger and WhatsApp Business are installed: let the user choose.
            Intent.createChooser(intents[0], activity.getString(R.string.whatsapp_choose_app))
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<Parcelable>(intents[1]))
        }
        if (!tryStart(toStart)) openWhatsAppWebFallback(route)
    }

    private fun buildWhatsAppIntent(route: Route.WhatsApp, pkg: String): Intent {
        val phone = route.phone?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
        return when {
            phone != null -> {
                val uri = Uri.Builder()
                    .scheme("https")
                    .authority("wa.me")
                    .appendPath(phone)
                    .apply { route.text?.let { appendQueryParameter("text", it) } }
                    .build()
                Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
            }
            !route.text.isNullOrEmpty() ->
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, route.text)
                    .setPackage(pkg)
            else -> Intent(Intent.ACTION_VIEW, Uri.parse(route.url)).setPackage(pkg)
        }
    }

    private fun openWhatsAppWebFallback(route: Route.WhatsApp) {
        toast(R.string.whatsapp_not_installed)
        val url = LinkRouter.whatsAppWebUrl(route)
        if (!tryStart(browserIntent(Uri.parse(url)))) toast(R.string.no_app_for_link)
    }

    // -------------------------------------------------------------------- Maps

    /**
     * Order of preference: Google Maps app (same URL the website uses, so the destination is
     * preserved exactly) -> any installed maps app via a geo: URI -> web browser.
     */
    private fun openMaps(route: Route.Maps) {
        val uri = Uri.parse(route.url)
        if (tryStart(Intent(Intent.ACTION_VIEW, uri).setPackage(GOOGLE_MAPS))) return

        geoIntent(route)?.let { if (tryStart(it)) return }

        if (route.url.startsWith("http", ignoreCase = true)) {
            toast(R.string.maps_opening_in_browser)
            if (tryStart(browserIntent(uri))) return
        } else if (tryStart(Intent(Intent.ACTION_VIEW, uri))) {
            return
        }
        toast(R.string.no_app_for_link)
    }

    private fun geoIntent(route: Route.Maps): Intent? {
        val dest = route.destination?.trim().orEmpty()
        if (dest.isEmpty()) return null
        val coords = Coordinates.parse(dest)
        val uri = if (coords != null) {
            Uri.parse("geo:${coords.first},${coords.second}?q=${coords.first},${coords.second}")
        } else {
            Uri.parse("geo:0,0?q=" + Uri.encode(dest))
        }
        return Intent(Intent.ACTION_VIEW, uri)
    }

    // ------------------------------------------------------------ intent:// URIs

    /**
     * intent:// links are a known abuse vector, so only plain VIEW intents for a short list of
     * schemes are honoured, with component/selector stripped. Otherwise use the link's own
     * browser_fallback_url, if it has one.
     */
    private fun openIntentUri(url: String) {
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            Log.w(TAG, "Bad intent URI", e)
            return
        }
        val fallback = intent.getStringExtra("browser_fallback_url")
        val scheme = intent.data?.scheme?.lowercase()
        val allowed = intent.action == Intent.ACTION_VIEW && scheme in INTENT_URI_SCHEMES

        if (allowed) {
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            if (tryStart(intent)) return
        }
        if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
            openUrl(fallback)
        } else {
            toast(R.string.no_app_for_link)
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun browserIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)

    private fun startOrToast(intent: Intent) {
        if (!tryStart(intent)) toast(R.string.no_app_for_link)
    }

    private fun tryStart(intent: Intent): Boolean =
        try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Launch blocked", e)
            false
        }

    @Suppress("DEPRECATION")
    private fun isInstalled(pkg: String): Boolean =
        try {
            val pm = activity.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                pm.getApplicationInfo(pkg, 0)
            }
            info.enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "HDJD"
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        val INTENT_URI_SCHEMES = setOf("http", "https", "geo", "tel", "mailto", "market", "whatsapp")
    }
}
