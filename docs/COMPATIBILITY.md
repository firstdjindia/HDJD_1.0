# Supported Android versions and device categories

| | |
|---|---|
| Minimum | Android 7.0 (API 24) |
| Target / compile | Android 16 (API 36) |
| ABIs | Universal (pure Kotlin/Java, no native code) |
| WebView | Uses the device's *Android System WebView* (Chromium). Keep it updated on old devices. |

| Category | Support | Notes |
|---|---|---|
| Phones | Yes | Edge-to-edge, cutouts, keyboard insets, pull-to-refresh, portrait + landscape. |
| Tablets / foldables | Yes | `resizeableActivity=true`; split-screen and resizing handled without reloading. Larger dimensions for splash and error screen at ≥ 600 dp. |
| Android TV | Designed for, **untested** | `LEANBACK_LAUNCHER` entry + banner, touchscreen not required, D-pad focus on the Retry button and WebView, 120 % text zoom, TV-sized error screen. |
| Chromebooks | Expected to work | Runs as a resizable Android app. |

## Known limitations

* **Android TV:** Chromium WebView supports D-pad focus/scrolling, but pages that depend on hover, drag or a pointer may be awkward. Verify the site's main flows on a real TV or emulator before calling it fully supported. TV devices also usually have no WhatsApp; the web fallback opens in the TV browser if one exists, otherwise the user sees “No app can open that link”.
* **WhatsApp Business only / both installed:** handled; the chooser appears only when both are present.
* **Google Maps missing:** falls back to another maps app, then the browser.
* **Manufacturer behaviour:** some OEM skins (MIUI/ColorOS/Funtouch) restrict “install unknown apps” or auto-start; none affect a foreground WebView app once installed.
* **Android 12+ system splash:** the OS draws its own icon splash first (a short fade), then the app's full-screen logo screen holds for ~2.5 s. This is the platform's design; the two share one background colour to avoid flicker.
* **HTTP-only or mixed-content pages** on the website will not load (blocked on purpose).
* Third-party sign-in/payment pages open in the browser unless added to `LinkRouter`.
