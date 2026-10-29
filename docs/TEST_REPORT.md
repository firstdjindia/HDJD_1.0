# Test report

**Honest summary:** this project was produced in an environment with no Android SDK, no Maven/Gradle repositories, no access to jain.firstdj.com and no emulator or device. So the app has **not been compiled, installed or run**. What was verified, and what remains, is listed below. Items marked ⏳ need the first CI run or a device.

## Verified here

| Check | Result |
|---|---|
| Link routing logic (`LinkRouter`, `Coordinates`) compiled with Kotlin 2.0.21 and run on the JVM: 13 unit tests covering trusted host, look-alike/userinfo/backslash host tricks, WhatsApp (`api.whatsapp.com/send`, `wa.me`, `whatsapp://`, `web.whatsapp.com`; emoji, newlines, `&`/`=` preserved), WhatsApp web-fallback URL encoding, Google Maps variants (dir/place/short links/regional domains/`geo:`/`google.navigation:`), non-Maps Google links, tel/mail/sms, blocked schemes | ✅ 13/13 pass |
| All Kotlin files parse (no syntax errors) | ✅ |
| All XML resources and the manifest are well-formed | ✅ |
| Workflow YAML parses | ✅ |
| No signing secrets or keystores in the repo | ✅ |

## To verify (first CI run)

| Check | How |
|---|---|
| ⏳ Gradle sync, Kotlin and resource compilation | `./gradlew assembleDebug` in CI |
| ⏳ Android lint | `lintDebug` in CI (report uploaded) |
| ⏳ Unit tests under the real Gradle/JUnit setup | `testDebugUnitTest` in CI |
| ⏳ Only `INTERNET` + `ACCESS_NETWORK_STATE` requested; package is `com.firstdj.hdjd`; TV launcher present | `aapt2 dump badging` step in CI |
| ⏳ Real logo fetched and icons regenerated | CI log, step *Fetch brand logo* |

## To verify on a device or emulator

| Area | Test | Status |
|---|---|---|
| Splash | Correct logo; holds ~2.5 s; dissolves; no flicker on Android 7, 11, 12+, 15/16 | ⏳ |
| Website | Loads; images/forms/navigation work; JS-dependent features work | ⏳ |
| Back button | Steps back through pages, exits at first page; closes error screen/video first | ⏳ |
| WhatsApp | Tap the share link → WhatsApp share screen shows the exact text; nothing auto-sent; Business and both-installed chooser; not installed → web fallback + message | ⏳ |
| Google Maps | Tap directions → Maps opens with the site's destination; Maps disabled → browser fallback | ⏳ |
| Offline | Airplane mode at launch and mid-session → error screen; reconnect → auto-retry; Retry button works | ⏳ |
| Layouts | Small phone, large phone, tablet, landscape, split-screen, foldable, display cutout, gesture vs 3-button navigation | ⏳ |
| TV | Android TV emulator: launcher entry + banner, D-pad scrolls and focuses links, Retry focus ring, readable at distance, core site flows | ⏳ |
| Stability | Rotate repeatedly, background/foreground, low-memory renderer crash recovery, file upload, fullscreen video | ⏳ |
| Install | Debug APK installs and launches on a clean device | ⏳ |

## Assumptions to confirm against the live site

* The WhatsApp link is an ordinary navigation to one of the URL forms in the README. If it is a JavaScript `navigator.share()` call, it will not reach the app's link handler; that would need a small native bridge. Check by tapping it once on a device.
* The directions link is a Google Maps URL (the code was written expecting `destination=lat,lng`).
* The site does not need third-party pages (login/payment) to stay inside the app.
