# HDJD – Android app

Native Android shell for **https://jain.firstdj.com/** · package `com.firstdj.hdjd` · Kotlin · min SDK 24 (Android 7.0) · target SDK 36

Features: branded splash with dissolve · secure WebView · WhatsApp sharing opens the native app (Messenger or Business, with web fallback) · Google Maps directions open the native app (with fallback) · offline/error screen with Retry and auto-retry · back button walks site history · phones, tablets and Android TV (D-pad) · only `INTERNET` and `ACCESS_NETWORK_STATE` permissions.

> **Status of this repository:** the source was written without access to the Android SDK, so it has **not yet been compiled or run on a device**. The first GitHub Actions run is the first real build; see [docs/TEST_REPORT.md](docs/TEST_REPORT.md) for exactly what has and hasn't been verified.

## Get the APK (no Android Studio needed)

1. Push this folder to a GitHub repository (see below).
2. Open **Actions → Build HDJD APK** (it runs on every push; or press *Run workflow*).
3. When the run is green, download the **hdjd-debug-apk** artifact (a zip containing `app-debug.apk`).
4. Copy the APK to the phone, open it, and allow “install from this source” when Android asks.

```bash
git init -b main && git add . && git commit -m "HDJD Android app"
gh repo create hdjd-android --private --source=. --push      # or create the repo on github.com and push
```

The workflow downloads the real logo from `https://jain.firstdj.com/image/logo.png` and regenerates the launcher icons and TV banner from it before building. If that download fails it uses the placeholder logo committed in the repo and prints a warning.

## Signed release APK (optional)

A debug APK installs fine for testing. For a release APK (needed for Google Play, and smaller/faster):

```bash
keytool -genkeypair -v -keystore hdjd.jks -alias hdjd -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 hdjd.jks        # macOS: base64 -i hdjd.jks
```

Add four **repository secrets** (Settings → Secrets and variables → Actions): `HDJD_KEYSTORE_BASE64` (the base64 text), `HDJD_KEYSTORE_PASSWORD`, `HDJD_KEY_ALIAS`, `HDJD_KEY_PASSWORD`. Keep `hdjd.jks` somewhere safe and **back it up**: losing it means you can never update the app under the same signature. Then either push a tag (`git tag v1.0.0 && git push --tags`) or run the workflow manually with *release_build* ticked. Tag builds also create a GitHub Release with `HDJD-release.apk`. Keystores and passwords are never stored in the repo (`.gitignore` blocks `*.jks`).

## Build locally

Requires JDK 17 and the Android SDK (API 36). Android Studio does this for you: *File → Open* this folder.

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Customising

| What | Where |
|---|---|
| Website URL, trusted host, splash timing, TV text size | `app/src/main/java/com/firstdj/hdjd/AppConfig.kt` |
| Logo (+ launcher icons, TV banner) | `tools/update_logo.sh <file-or-https-url>` (needs Python + Pillow) |
| Brand colours | `app/src/main/res/values/colors.xml` – then re-run `update_logo.sh` so legacy icons match |
| Error-screen wording | `app/src/main/res/values/strings.xml` |
| Which links open in other apps | `LinkRouter.kt` (pure Kotlin, unit-tested in `LinkRouterTest.kt`) |

**Colours are placeholders.** I could not read the website, so the navy/gold palette is a neutral guess. Set `hdjd_background` / `hdjd_accent` to the brand colours. Use a **transparent PNG** logo if possible, otherwise it shows as a rectangle on the splash.

## How links are handled

| Link | Behaviour |
|---|---|
| `jain.firstdj.com` pages | Stay in the app (HTTP is upgraded to HTTPS) |
| `wa.me`, `api.whatsapp.com/send`, `web.whatsapp.com/send`, `whatsapp://` | WhatsApp opens on its share screen with the exact text (decoded and re-encoded safely). If both Messenger and Business are installed the user chooses. Not installed → WhatsApp Web in the browser. Nothing is sent automatically. |
| `google.com/maps…`, `maps.google.*`, `maps.app.goo.gl`, `goo.gl/maps`, `geo:`, `google.navigation:` | Google Maps app with the same URL (destination preserved) → any maps app via `geo:` → browser |
| `tel:`, `mailto:`, `sms:` | Dialer / mail / messaging app |
| Other websites | Default browser |
| `javascript:`, `file:`, `content:`, unknown schemes, look-alike hosts (`jain.firstdj.com@evil.com`) | Blocked |

External apps only open after a user gesture (or a tap/OK within 1.5 s); a page cannot launch apps on its own.

## Security notes

HTTPS only (network security config); TLS errors are never bypassed; no `addJavascriptInterface`; file and content access disabled; mixed content blocked; camera/microphone/location requests from pages are denied; backups disabled; WebView remote debugging only in debug builds; `intent://` links are sanitised and restricted to a short allow-list of schemes.

## Troubleshooting

| Symptom | Fix |
|---|---|
| “App not installed” | Uninstall any older copy signed with a different key; allow installs from unknown sources |
| CI fails at *Fetch brand logo* | Non-fatal; the committed logo is used. Check the URL in the workflow’s `LOGO_URL` |
| CI fails at lint | Open the `hdjd-reports` artifact → `lint-results-debug.html` |
| Page shows the error screen but the browser works | Site may block WebView user-agents or need a host added in `LinkRouter`; check `adb logcat -s HDJD` |
| A third-party login/payment page opens in the browser instead of the app | Add that host as internal in `LinkRouter.classifyWeb` |
| Website works on phone but not with a TV remote | Site relies on hover/mouse; see limitations in [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) |
| Release build is unsigned | Secrets missing or misnamed; the workflow prints a warning and skips the release |
