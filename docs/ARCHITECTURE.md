# Project Y: architecture and build notes

How the app works inside, how it's built, and how a version gets from source code to your phone. Written for whoever maintains this next, which will probably be you in six months.

Last updated: 22 Nov 2026 (version 0.1.1).

---

## 1. The big picture

Project Y is a **single-purpose browser**. It doesn't rebuild YouTube. It shows YouTube's real mobile site (`m.youtube.com`) in a browser engine with no browser UI, and it runs an ad blocker inside that engine.

```
┌──────────────────────────────────────────────────────────────┐
│  Android phone                                               │
│                                                              │
│  ┌───────────────── Project Y app (dev.projecty.app) ─────┐  │
│  │                                                        │  │
│  │  Our Kotlin code (~400 lines)                          │  │
│  │   ProjectYApp       owns the engine + the one "tab"    │  │
│  │   MainActivity      full-screen view, back, rotation   │  │
│  │   SitePolicy        which URLs may load                │  │
│  │   SettingsActivity  clear data, version, licences      │  │
│  │            │                                           │  │
│  │            ▼                                           │  │
│  │  GeckoView 160  (Firefox's engine as a library)        │  │
│  │   ├── uBlock Origin 1.76.6   built-in extension        │  │
│  │   └── tweaks                 our own built-in extension│  │
│  │            │                                           │  │
│  └────────────┼───────────────────────────────────────────┘  │
│               ▼ HTTPS                                        │
└───────────────┼──────────────────────────────────────────────┘
                ▼
   m.youtube.com, googlevideo.com (video), accounts.google.com
```

**What we wrote vs. what we reuse:**

| Part | Who made it | Size | Licence |
|---|---|---|---|
| App code (Kotlin), resources, tweaks extension | us | tiny | ours (no licence file yet) |
| GeckoView engine | Mozilla | most of the ~97 MB APK | MPL-2.0 |
| uBlock Origin | Raymond Hill and contributors | ~670 files | GPLv3 |
| YouTube's UI | Google, loaded live from the web | not in the APK | n/a |

Because YouTube's UI is loaded live, YouTube's own updates appear automatically and we never maintain UI code. The trade-off: if YouTube changes its site in a way that breaks something, we can only work around it (with uBlock filters or the tweaks extension). We can't change the page directly.

---

## 2. Why this design (decisions)

| Decision | Chosen | Rejected | Why |
|---|---|---|---|
| Engine | GeckoView | Brave/Chromium fork | A Chromium fork needs a Linux machine with 100+ GB disk and hours per build, and you'd re-apply patches every release. GeckoView is a normal Gradle dependency: one version number to bump. |
| Engine | GeckoView | Android System WebView | WebView can't run browser extensions, so no uBlock Origin. Ad blocking would have to be written and maintained by hand. |
| UI | YouTube's own mobile site | Custom UI using the YouTube Data API | The API has a 100-searches-per-day quota, forbids ad blocking, and the app wouldn't look like YouTube. |
| Ad blocking | uBlock Origin, bundled | Custom filter code | uBO is the best-maintained blocker, and its filter lists update themselves without an app update. |
| Distribution | APK via GitHub Releases | Google Play | Play would remove an ad-blocking YouTube client. See `docs/phase1-research-report.md`. |

---

## 3. Source layout

```
yt app/
├── app/
│   ├── build.gradle.kts            app build config: SDK levels, ABI splits, signing, deps
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml          activities, link handling, permissions
│       │   ├── java/dev/projecty/app/
│       │   │   ├── ProjectYApp.kt           engine + session owner, installs extensions
│       │   │   ├── MainActivity.kt          the screen you see
│       │   │   ├── SitePolicy.kt            URL allowlist (pure Kotlin, unit-tested)
│       │   │   └── SettingsActivity.kt      settings screen
│       │   ├── res/                         layouts, strings, colours, icon, shortcut
│       │   └── assets/extensions/
│       │       ├── ublock/                  uBlock Origin, unpacked (from scripts/update-ublock.ps1)
│       │       └── tweaks/                  our extension: manifest.json + no-av1.js
│       ├── debug/assets/debug/              test pages, ONLY in debug builds
│       │   └── fullscreen.html
│       └── test/java/.../SitePolicyTest.kt  JVM unit tests
├── build.gradle.kts                pins plugin versions (AGP, Kotlin)
├── settings.gradle.kts             repositories (Google, Maven Central, Mozilla)
├── gradle.properties               Gradle memory, caching, AndroidX flags
├── gradlew / gradlew.bat           Gradle wrapper (downloads Gradle 9.9.2 itself)
├── scripts/update-ublock.ps1       fetches the latest uBlock Origin release
├── docs/                           research report, this file
├── keystore/ + keystore.properties release signing key + passwords  (GIT-IGNORED)
├── local.properties                path to the Android SDK            (GIT-IGNORED)
├── README.md, TODO.md
```

---

## 4. Runtime: what happens when you use the app

### 4.1 App start
1. Android creates `ProjectYApp` (the `Application` class).
2. `MainActivity.onCreate` runs:
   - Turns on **edge-to-edge** drawing and covers the view with the theme background (white, or `#0F0F0F` in dark mode) until the first page paints, so there's no flash.
   - Asks `ProjectYApp` for the **runtime**. The first access creates it, which is why it's `lazy`. GeckoView starts extra helper processes for the same app, and those must not create a second runtime (Android allows only one per app).
   - While creating the runtime, it installs both built-in extensions with `ensureBuiltIn(...)`. That call is idempotent: it installs on first run, updates the extension when the bundled version changes, and does nothing otherwise.
   - Asks for the **session** (the single "tab"). It is created once and kept in `ProjectYApp`, not in the Activity, so it survives the Activity being destroyed and recreated (dark-mode switch, etc.) without reloading YouTube.
   - Attaches the delegates (navigation, content, orientation), opens the session, and loads:
     - the YouTube link it was opened with ("Open with" / "Share"), **or**
     - `https://m.youtube.com/` on first open, **or**
     - nothing, if the session already has a page (it just reattaches).

Session settings: **not private mode** (cookies kept, so you stay signed in), mobile user agent, mobile viewport.

### 4.2 Every navigation: the site lock
GeckoView asks `onLoadRequest` before loading any page. `SitePolicy.decide(url)` answers:

| URL | Decision | What happens |
|---|---|---|
| `*.youtube.com`, `youtu.be`, `*.youtube-nocookie.com` | ALLOW | loads in the app. "Open in new tab" links load in place, since there are no tabs. |
| `accounts.google.com`, `myaccount.`, `consent.`, `gds.google.com`, country sign-in hosts like `accounts.google.co.in` | ALLOW | needed for Google sign-in |
| any other `http(s)` link | OPEN_EXTERNALLY | handed to your normal browser |
| `mailto:`, `tel:`, `sms:`, `market:` | OPEN_EXTERNALLY | handed to the matching app |
| `about:blank` | ALLOW | used internally by pages |
| `intent:`, `vnd.youtube:`, `javascript:`, `data:`, `file:`, anything else | BLOCK | ignored. This stops YouTube's site from bouncing you into the official YouTube app. |

Notes:
- `SitePolicy.hostOf()` parses the host by hand because `java.net.URI` rejects some characters that real YouTube URLs contain.
- Only page navigations go through this check. Sub-resources (video from `googlevideo.com`, images from `ytimg.com`, scripts) load normally. uBlock Origin is what filters those.
- `SitePolicy` has no Android imports, so `SitePolicyTest` runs as a plain JVM test.

### 4.3 Ad blocking (uBlock Origin)
- It ships unpacked in `assets/extensions/ublock/` and is loaded from `resource://android/assets/extensions/ublock/` with the id `uBlock0@raymondhill.net`.
- It blocks ad requests (network level) and hides ad elements (cosmetic filters), including YouTube's pre-roll and in-feed ads.
- **Filter lists update themselves** from the internet inside the app. Only uBO's *code* needs an app update (`scripts/update-ublock.ps1`).
- There is no uBO dashboard UI in the app (no address bar or menu to reach it). It runs on default settings.

### 4.4 Video performance (tweaks extension)
- `assets/extensions/tweaks/`, id `tweaks@projecty.dev`, Manifest V2.
- One content script, `no-av1.js`, runs at `document_start` in all YouTube frames, before any YouTube script.
- It replaces `MediaSource.isTypeSupported` and `HTMLMediaElement.canPlayType` on the page (using Firefox's `exportFunction`) so that any AV1 codec string gets "not supported".
- Result: YouTube picks **VP9 or H.264**, which phones decode in **hardware**, instead of AV1, which most phones can only decode in software. That software decoding is what made Home to Shorts laggy in 0.1.0.
- Verified on the emulator: the log shows `[ProjectY] hid AV1 from YouTube (...)`, and the hardware VP9 decoder is used.
- This extension is also the place for future page-level fixes.

### 4.4b Speed-test switches (Settings, since 0.1.2)
Saved in SharedPreferences `speed` and applied by `ProjectYApp`. Each change reloads YouTube.

| Switch | Default | How it works |
|---|---|---|
| H.264 video only | off | enables the built-in extension `assets/extensions/h264/` (`h264@projecty.dev`), which also hides VP9/VP8, so YouTube sends H.264 (max 1080p) |
| Identify as Chrome | off | sets `session.settings.userAgentOverride` to a Chrome-on-Android user agent, so YouTube may serve the code path it optimises most |
| Ad blocker | on | enables/disables uBlock Origin. Off is only for measuring its cost. |

These are for finding out what actually helps on a given phone. The winners should become defaults.

### 4.5 Full-screen video and rotation
- YouTube's player asks for full screen, and GeckoView calls `onFullScreen(true)`. We hide the status and navigation bars (swipe from the edge shows them briefly) and remove the inset padding.
- YouTube also asks to lock landscape, and GeckoView calls `onOrientationLock`. We set `requestedOrientation` to match.
- Leaving full screen shows the bars again and unlocks rotation.
- The manifest's `configChanges` stops Android from restarting the Activity when the phone rotates, so video doesn't stutter or reload.

### 4.6 Back button
Checked in this order:
1. In full screen: exit full screen.
2. If YouTube has history: go back a page.
3. Otherwise: `moveTaskToBack(true)`. The app goes to the background without being destroyed, like the official app, so the page is still there when you return.

### 4.7 Links from other apps
- **"Open with"**: the manifest claims `https` links on `www.youtube.com`, `m.youtube.com`, `youtube.com` and `youtu.be`.
- **"Share"**: it accepts `text/plain` and pulls the first `http(s)://` URL out of the text.
- Either way, the URL must pass `SitePolicy` (ALLOW) or it's ignored.
- `launchMode="singleTask"` means a new link reuses the existing screen (`onNewIntent`) instead of stacking a second copy.

### 4.8 Settings
- There's no settings button inside the app, because the app shows only YouTube. Long-press the app icon and tap **Settings** (`res/xml/shortcuts.xml`).
- The screen shows:
  - the version;
  - **Go to home page**;
  - **Clear all data and sign out**, which calls `storageController.clearData(ALL)`: cookies, cache, site storage;
  - about and licence text.

### 4.9 Where data lives
- All browsing data (cookies, sign-in, cache, uBO settings) is stored in GeckoView's profile inside the app's private storage. Other apps can't read it.
- `allowBackup="false"`: Android cloud backup doesn't copy it, so sign-in cookies don't leak into backups.
- The only permission is `INTERNET`.

---

## 5. Build system

### 5.1 Toolchain (on this PC)
| Tool | Version | Where it comes from |
|---|---|---|
| JDK | 25 (JetBrains Runtime) | bundled with Android Studio: `C:\Program Files\Android\Android Studio\jbr` |
| Gradle | 9.9.2 | downloaded automatically by `gradlew.bat` |
| Android Gradle Plugin (AGP) | 9.5.2 | `build.gradle.kts` |
| Kotlin | 2.4.21 | `build.gradle.kts` (see 5.3) |
| Android SDK | compile 37.2 | `C:\Users\sylve\AppData\Local\Android\Sdk` (path in `local.properties`) |
| Git | 2.56.1 | `C:\Program Files\Git\cmd\git.exe` |
| GitHub CLI | signed in | `C:\Program Files\GitHub CLI\gh.exe` |

`JAVA_HOME` isn't set globally, so every Gradle command starts with `$env:JAVA_HOME=...` (see README).

### 5.2 SDK levels
- `minSdk 26` (Android 8.0): GeckoView's minimum.
- `targetSdk 36`: which Android behaviour rules the app opts into.
- `compileSdk 37.2`: GeckoView 160 requires it. This uses AGP 9's new syntax, `compileSdk { version = release(37) { minorApiLevel = 2 } }`. It doesn't affect which phones can install the app.

### 5.3 Build gotchas (and why the config looks the way it does)
| Setting | Why it's there |
|---|---|
| `org.jetbrains.kotlin.android` 2.4.21 declared with `apply false` in the root build file | AGP 9 has built-in Kotlin, but its default compiler is older than the Kotlin metadata inside GeckoView 160. Declaring the plugin pins a newer compiler. |
| Custom `androidResources.ignoreAssetsPatterns` | Android's packager silently drops asset folders starting with `_`. uBlock Origin needs `_locales/`, and without it GeckoView rejects uBO as "Extension is invalid". The list is the default minus that rule. |
| `splits.abi` (arm64-v8a, armeabi-v7a, x86_64) | The engine is native code per CPU type. One APK per type keeps downloads ~97 MB instead of ~250 MB. |
| `packaging.jniLibs.useLegacyPackaging = true` | Stores native libraries compressed. This halved the APK; installed size is about the same. |
| `isMinifyEnabled = false` (no R8) | Our code is tiny. Shrinking risks breaking GeckoView's reflection for very little gain. |
| `testDebugUnitTest`, not `testReleaseUnitTest` | AGP 9 only creates unit-test tasks for debug by default. |

### 5.4 Build commands
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest assembleDebug
```
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest assembleRelease
```
Outputs: `app\build\outputs\apk\debug\` and `app\build\outputs\apk\release\`, one APK per CPU type.

### 5.5 Debug vs. release
| | Debug | Release |
|---|---|---|
| Signing key | Android's auto-generated debug key | `keystore/projecty-release.jks` |
| Console logging from pages | on (in `adb logcat`) | off |
| Remote debugging (Firefox DevTools) | on | off |
| Test pages (`src/debug/assets/debug/`) | included | not included |
| `debug_uri` / `debug_ublock` intent extras | work | ignored |

Different keys mean Android won't install one over the other. Uninstall first when switching.

Debug helpers (debug builds only):
```powershell
adb shell am start -n dev.projecty.app/.MainActivity --es debug_uri resource://android/assets/debug/fullscreen.html
```
```powershell
adb shell am start -n dev.projecty.app/.MainActivity --es debug_ublock off
```

---

## 6. Signing

- **Keystore:** `keystore/projecty-release.jks`, PKCS12, alias `projecty`, RSA 4096-bit.
- **Passwords:** stored in `keystore.properties`, which `app/build.gradle.kts` reads at build time. If that file is missing, the release build is simply unsigned.
- **Both files are git-ignored** and exist only on this PC. Back them up. Android only installs an update over an existing app if it's signed with the **same key**. Lose the key, and every user must uninstall (losing their sign-in) to move to a new build.

---

## 7. Release pipeline (source to phone)

```
edit code ──► build release ──► copy APK ──► commit + push ──► GitHub Release ──► phone downloads
              (gradlew)         ProjectY-x.y.z-arm64.apk       (git)          (gh release create)
```

1. Bump `versionCode` (+1, must always increase) and `versionName` in `app/build.gradle.kts`.
2. Build:
   ```powershell
   $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest assembleRelease
   ```
3. Copy the APK to the project root with a versioned name:
   ```powershell
   Copy-Item app\build\outputs\apk\release\app-arm64-v8a-release.apk ProjectY-0.1.2-arm64.apk
   ```
4. Commit and push the code. `*.apk` is git-ignored on purpose.
5. Publish:
   ```powershell
   & "C:\Program Files\GitHub CLI\gh.exe" release create v0.1.2 ProjectY-0.1.2-arm64.apk --title "Project Y 0.1.2" --notes "What changed"
   ```
6. On the phone, open https://github.com/sylvesterbraganza/yt-ad-free/releases/latest and install over the old version.

**Why Releases and not a folder in the repo:** the APK is ~97 MB, just under GitHub's 100 MB per-file limit for repo files (Releases allow 2 GB). Committed APKs would also stay in git history forever.

| Version | versionCode | Date | Change |
|---|---|---|---|
| 0.1.0 | 1 | 22 Nov 2026 | first release |
| 0.1.1 | 2 | 22 Nov 2026 | hide AV1, so video uses hardware decoding (fixes Shorts lag) |
| 0.1.2 | 3 | 22 Nov 2026 | Settings → Speed tests: H.264 only, identify as Chrome, ad blocker on/off |

---

## 8. Testing

- **Unit tests:** `SitePolicyTest` (5 tests) checks the allowlist, sign-in hosts, external links and blocked schemes. Run them with `testDebugUnitTest`.
- **Emulator:** the AVD is `Medium_Phone_API_37.0`, x86_64, so install `app-x86_64-debug.apk`.
- **Manual checks done so far:**
  - YouTube loads;
  - uBO loads and there's no pre-roll ad;
  - full screen and rotation work;
  - Back works;
  - the Settings shortcut works;
  - Google sign-in works;
  - Shorts uses VP9 hardware decoding.
- **Limitation:** `adb input tap` doesn't trigger YouTube's player buttons (e.g. full screen). Test player controls with real taps.

---

## 9. Maintenance

| What | How often | How |
|---|---|---|
| GeckoView (security fixes) | about every 4 weeks, matching Firefox releases | change the version in `app/build.gradle.kts`. Latest is listed at `https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/maven-metadata.xml`. Rebuild, test, release. |
| uBlock Origin code | every few months | run `powershell -ExecutionPolicy Bypass -File scripts\update-ublock.ps1`, then rebuild |
| uBO filter lists | automatic | nothing to do |
| AGP / Gradle / Kotlin | when Android Studio suggests it | bump versions; new GeckoView releases sometimes force this (as 160 did with compileSdk 37.2 and Kotlin 2.4) |

---

## 10. Known limits and open questions
- **Policy:** blocking ads breaks YouTube's Terms of Service. Keep it off Google Play and keep "YouTube"/"YT" out of the name and icon.
- **Public repo:** the code and APK are downloadable by anyone with the link.
- **No licence file yet:** the code is publicly visible but not legally open source.
- **Process death:** if Android kills the app in the background, it reopens on the home page instead of where you were. Restoring the session is in TODO.
- **No background audio or picture-in-picture:** not built yet; a decision is pending.
- **No in-app update check:** you have to check Releases manually.
- **APK size:** ~97 MB, close to GitHub's repo file limit (Releases are fine) and heavy for low-end phones. Nearly all of it is the engine.
- **Placeholder name and icon:** "Project Y" and the teal "Y".
