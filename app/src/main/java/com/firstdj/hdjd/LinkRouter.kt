package com.firstdj.hdjd

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Where a URL tapped inside the WebView should go.
 *
 * Kept free of Android classes so it can be unit-tested on the JVM.
 */
sealed interface Route {
    /** Stay inside the app's WebView. */
    data object Internal : Route

    /** A WhatsApp "share text" (or chat) link. [text] is already URL-decoded. */
    data class WhatsApp(val url: String, val text: String?, val phone: String?) : Route

    /** A Google Maps directions/search/place link. [destination] is decoded, if known. */
    data class Maps(val url: String, val destination: String?) : Route

    data class Dial(val url: String) : Route
    data class Mail(val url: String) : Route
    data class Sms(val url: String) : Route

    /** An Android `intent://` URI. Always sanitised before use. */
    data class IntentUri(val url: String) : Route

    /** Any other http(s)/market link: hand to the browser / owning app. */
    data class External(val url: String) : Route

    /** Never navigated to or launched (javascript:, file:, malformed, ...). */
    data class Blocked(val reason: String) : Route
}

class LinkRouter(trustedHost: String) {

    private val trustedHost = trustedHost.lowercase(Locale.ROOT)

    fun classify(rawUrl: String): Route {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Route.Blocked("empty url")
        val parsed = parse(url) ?: return Route.Blocked("unparseable url")

        return when (parsed.scheme) {
            "http", "https" -> classifyWeb(url, parsed)
            "whatsapp" -> Route.WhatsApp(
                url = url,
                text = queryParam(parsed.query, "text"),
                phone = queryParam(parsed.query, "phone")
            )
            "tel" -> Route.Dial(url)
            "mailto" -> Route.Mail(url)
            "sms", "smsto" -> Route.Sms(url)
            "geo", "google.navigation", "comgooglemaps" -> Route.Maps(url, null)
            "intent" -> Route.IntentUri(url)
            "market" -> Route.External(url)
            "about", "blob" -> Route.Internal
            else -> Route.Blocked("scheme '${parsed.scheme}' not allowed")
        }
    }

    private fun classifyWeb(url: String, p: ParsedUrl): Route {
        val host = p.host ?: return Route.Blocked("no host")

        // WhatsApp: api.whatsapp.com/send?text=..., web.whatsapp.com/send?..., wa.me/<phone>?text=...
        if ((host == "api.whatsapp.com" || host == "web.whatsapp.com") &&
            (p.path == "/send" || p.path == "/send/")
        ) {
            return Route.WhatsApp(url, queryParam(p.query, "text"), queryParam(p.query, "phone"))
        }
        if (host == "wa.me") {
            val phone = p.path.trim('/').takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            val text = queryParam(p.query, "text")
            if (phone != null || text != null) return Route.WhatsApp(url, text, phone)
        }

        if (isMapsLink(host, p.path)) {
            val destination = queryParam(p.query, "destination") ?: queryParam(p.query, "query")
            return Route.Maps(url, destination)
        }

        if (host == trustedHost) return Route.Internal
        return Route.External(url)
    }

    private fun isMapsLink(host: String, path: String): Boolean {
        if (host == "maps.app.goo.gl") return true
        if (host == "goo.gl" && path.startsWith("/maps")) return true
        if (MAPS_SUBDOMAIN.matches(host)) return true
        return GOOGLE_HOST.matches(host) && (path == "/maps" || path.startsWith("/maps/"))
    }

    companion object {
        private val URL_PATTERN =
            Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*):(?://([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#.*)?$""")
        private val GOOGLE_HOST = Regex("""^(www\.)?google\.[a-z]{2,3}(\.[a-z]{2})?$""")
        private val MAPS_SUBDOMAIN = Regex("""^maps\.google\.[a-z]{2,3}(\.[a-z]{2})?$""")

        internal data class ParsedUrl(
            val scheme: String,
            val host: String?,
            val path: String,
            val query: String?
        )

        /**
         * Lenient, security-conscious parser. Deliberately does not use java.net.URI, which
         * rejects many URLs real websites emit, and never trusts text before a '@' in the
         * authority (the "https://trusted.com@evil.com" trick).
         */
        internal fun parse(url: String): ParsedUrl? {
            val m = URL_PATTERN.matchEntire(url) ?: return null
            val scheme = m.groupValues[1].lowercase(Locale.ROOT)
            val authority = m.groups[2]?.value
            val path = m.groupValues[3]
            val query = m.groups[4]?.value

            var host: String? = null
            if (authority != null) {
                // Browsers normalise backslashes to slashes and strip control chars; reject
                // anything where our reading of the host could differ from the WebView's.
                if (authority.any { it == '\\' || it.code <= 0x20 }) return null
                val hostPort = authority.substringAfterLast('@')
                val bare = if (hostPort.startsWith("[")) {
                    hostPort.substringBefore(']') + "]"
                } else {
                    hostPort.substringBefore(':')
                }
                host = bare.lowercase(Locale.ROOT).trimEnd('.').ifEmpty { null }
            }
            return ParsedUrl(scheme, host, path, query)
        }

        internal fun queryParam(query: String?, name: String): String? {
            if (query.isNullOrEmpty()) return null
            for (pair in query.split('&')) {
                val idx = pair.indexOf('=')
                val key = if (idx >= 0) pair.substring(0, idx) else pair
                if (decode(key) == name) {
                    return if (idx >= 0) decode(pair.substring(idx + 1)) else ""
                }
            }
            return null
        }

        private fun decode(s: String): String =
            try {
                URLDecoder.decode(s, "UTF-8")
            } catch (e: IllegalArgumentException) {
                s
            }

        /** Web-share URL used when the WhatsApp app is not installed. */
        fun whatsAppWebUrl(route: Route.WhatsApp): String {
            if (route.url.startsWith("http://", ignoreCase = true) ||
                route.url.startsWith("https://", ignoreCase = true)
            ) {
                return route.url
            }
            val params = buildList {
                route.phone?.let { add("phone=" + encode(it)) }
                route.text?.let { add("text=" + encode(it)) }
            }
            return "https://api.whatsapp.com/send" +
                if (params.isEmpty()) "" else "?" + params.joinToString("&")
        }

        private fun encode(s: String): String =
            URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** Parses "lat,lng" strings such as the website's `destination=12.9839,75.9892`. */
object Coordinates {
    private val PAIR = Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

    fun parse(value: String?): Pair<String, String>? {
        val m = value?.let { PAIR.matchEntire(it) } ?: return null
        val lat = m.groupValues[1]
        val lng = m.groupValues[2]
        val latOk = lat.toDoubleOrNull()?.let { it in -90.0..90.0 } == true
        val lngOk = lng.toDoubleOrNull()?.let { it in -180.0..180.0 } == true
        return if (latOk && lngOk) lat to lng else null
    }
}
