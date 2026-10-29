package com.firstdj.hdjd

/** Everything you are likely to change when updating the app lives here. */
object AppConfig {
    /** Page opened after the splash screen. Must be HTTPS. */
    const val START_URL = "https://jain.firstdj.com/"

    /**
     * The only host that stays inside the WebView. Links to any other site open in the
     * user's browser or the owning app (see [LinkRouter]). If the website uses a third-party
     * login or payment page that must stay in-app, extend [LinkRouter] to allow that host.
     */
    const val TRUSTED_HOST = "jain.firstdj.com"

    /** The logo is shown at least this long (spec: ~2-3 s), then dissolves. */
    const val SPLASH_MIN_MS = 2500L

    /** Length of the dissolve into the website. */
    const val SPLASH_FADE_MS = 700L

    /** Safety net: never keep the splash up longer than this if the page is very slow. */
    const val SPLASH_MAX_MS = 10_000L

    /**
     * A WebView navigation to another app is only honoured when the WebView reports a user
     * gesture, or the user touched / pressed OK within this window (some scripts lose the
     * gesture flag when they redirect after a tap).
     */
    const val USER_INTERACTION_WINDOW_MS = 1_500L

    /** Bigger page text on television (percent). */
    const val TV_TEXT_ZOOM = 120
}
