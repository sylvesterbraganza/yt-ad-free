# Project Y (working name)

A free Android app that shows YouTube's mobile site full-screen. It has no address bar and no browser UI, and uBlock Origin blocks the ads. Inside, it looks like YouTube. Outside, it has its own name and icon.

> Status: **Phase 2 prototype.** The app builds and installs on the emulator. Not yet tested on a real phone.

## Download (for your phone)
Open **https://github.com/sylvesterbraganza/yt-ad-free/releases/latest** on the phone and tap `ProjectY-…-arm64.apk`. APKs live in GitHub Releases, not in the repo: each one is about 97 MB, just under GitHub's 100 MB per-file limit, and committing every version would bloat the repo.

**Publish a new version** after a release build (replace `0.1.2` with the new version):
```powershell
& "C:\Program Files\GitHub CLI\gh.exe" release create v0.1.2 ProjectY-0.1.2-arm64.apk --title "Project Y 0.1.2" --notes "What changed"
```

## How it works
```
Project Y app (Kotlin)
 ├── MainActivity     one full-screen GeckoView showing m.youtube.com
 ├── SitePolicy       which pages may load (YouTube and Google sign-in only)
 ├── SettingsActivity long-press the app icon -> "Settings"
 └── GeckoView        Firefox's browser engine, as a library (MPL-2.0)
       ├── uBlock Origin   bundled as a built-in extension (GPLv3)
       └── tweaks          our own tiny built-in extension: hides AV1 from YouTube
```
- **No AV1 (performance):** YouTube sends AV1 video to Firefox-based browsers, and most phones can only decode AV1 in software, which is slow. `assets/extensions/tweaks/no-av1.js` tells YouTube that AV1 isn't supported, so it sends VP9 or H.264, which phones decode in hardware. Verified on the emulator: the hardware VP9 decoder is used for Shorts.
- **Site lock:** YouTube and Google sign-in pages load in the app. Any other web link opens in your normal browser. Links like `intent:` and `vnd.youtube:` are ignored, so YouTube's official app can't take over.
- **Full-screen video:** system bars hide, and the screen rotates when YouTube's player asks for landscape.
- **Back button:** goes back in YouTube. On the first page it sends the app to the background, like the official app.
- **Opening links:** "Open with…" and "Share" for YouTube links both open the app.
- **Dark mode:** follows the system setting, and YouTube's site follows it too.

Why GeckoView instead of a Brave fork: see `docs/phase1-research-report.md` and the decision notes in `TODO.md`. In short, it builds on Windows in minutes, and updating it means changing one version number.

## Setup (Windows)
1. **Android Studio** (already installed here). It includes the Java runtime (JDK), the Android SDK and the emulator.
2. Open this folder in Android Studio, or build from a terminal.

**Run the unit tests and build the debug APKs** (one per CPU type):
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest assembleDebug
```
The APKs end up in `app\build\outputs\apk\debug\`. Which one to use:
- `app-arm64-v8a-debug.apk` for almost every real phone
- `app-armeabi-v7a-debug.apk` for old 32-bit phones
- `app-x86_64-debug.apk` for the emulator

**Install on a connected phone or a running emulator:**
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
```

### Release build (for real phones)
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleRelease
```
- The output goes to `app\build\outputs\apk\release\`. Most phones need `app-arm64-v8a-release.apk` (about 97 MB).
- It is signed with the release key in `keystore\projecty-release.jks`. The passwords are in `keystore.properties`.
- Both files are git-ignored. **Back them up somewhere safe**, such as a password manager or an encrypted USB drive. If you lose them, you cannot publish updates that install over existing copies: users would have to uninstall, and lose their sign-in, to switch to a newly signed build.
- Release and debug builds use different keys, so Android won't install one over the other. Uninstall first when switching.
- Native libraries are stored compressed, which roughly halves the download. Code shrinking (R8) is deliberately off.

**Update the bundled uBlock Origin** (downloads the latest release from its official GitHub page), then rebuild:
```powershell
powershell -ExecutionPolicy Bypass -File scripts\update-ublock.ps1
```

## Keeping it up to date
- **GeckoView:** bump the version in `app/build.gradle.kts` about every 4 weeks (Firefox's release cycle) for security fixes. Find the latest at `https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/maven-metadata.xml`.
- **uBlock Origin filter lists:** update themselves inside the app. Only the extension code needs the script above.

## Versions
| Component | Version |
|---|---|
| Android Gradle Plugin | 9.5.2 |
| Gradle | 9.9.2 |
| Kotlin | 2.4.21 |
| GeckoView | 160 |
| uBlock Origin | 1.76.6 |

- `minSdk` is 26 (Android 8.0).
- `compileSdk` is 37.2 (required by GeckoView).
- `targetSdk` is 36.

## Things to know
- **Distribution:** this app blocks YouTube ads, which YouTube's Terms of Service don't allow. Keep it off Google Play, keep "YouTube"/"YT" out of the name and icon, and share it privately. See the research report for the full reasoning.
- **Licences:** if you share the APK, uBlock Origin's GPLv3 licence means its source must also be available. It is already public on GitHub, and the About screen links to it.
- **Not affiliated:** the app is not affiliated with or endorsed by YouTube or Google.
