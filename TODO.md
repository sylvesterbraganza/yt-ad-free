# TODO

## Decisions made
- **22 Nov 2026:** free product, no monetisation.
- **22 Nov 2026:** show YouTube's own mobile site unchanged inside the app; don't rebuild YouTube's UI.
- **22 Nov 2026:** engine is GeckoView with uBlock Origin bundled, not a Brave fork.
  - A Brave fork needs a Linux build machine and hours-long builds, and you'd re-apply your changes to every Chromium release.
  - GeckoView builds on Windows, and updating it means changing one version number.
- App name and icon must be our own, not YouTube's (trademark risk; Vanced precedent).

## Phase 2: prototype
- [x] Gradle project: AGP 9.5.2, Kotlin 2.4.21, GeckoView 160
- [x] Full-screen GeckoView showing m.youtube.com, no browser UI
- [x] Site lock (`SitePolicy`) with unit tests
- [x] Bundled uBlock Origin as a built-in extension
- [x] Full-screen video, orientation lock, back button, edge-to-edge layout
- [x] "Open with" and "Share" for YouTube links
- [x] Settings screen via the app-icon long-press shortcut (clear data, about and licences)
- [x] Debug APKs build (one per CPU type)
- [x] Emulator: YouTube loads, uBlock Origin loads (1.76.6), a link opened with "Open with" goes to the video, the video plays with no pre-roll ad (22 Nov 2026)
- [x] Emulator: full screen hides system bars and locks to landscape; Back exits full screen and unlocks rotation (22 Nov 2026)
- [x] Emulator: Settings shortcut registered, clear-data dialog, "Go to home page"
- [x] Google sign-in works; subscriptions and history visible (tested by you on the emulator, 22 Nov 2026)
- [ ] Real phone (arm64)
- Note: `adb input tap` doesn't trigger YouTube's full-screen button; real taps do. Use manual taps for player-control tests.
- [ ] Test on a real phone (arm64)

## Next
- [ ] Choose the final name and icon (current "Y" mark is a placeholder)
- [x] Release signing key (`keystore/`, git-ignored) and signed release APKs, about 97 MB each (22 Nov 2026)
- [ ] **Back up `keystore/projecty-release.jks` and `keystore.properties`**
- [x] Release 0.1.0 installed on your phone via Drive: works, but laggy, especially going from Home to Shorts
- [x] 0.1.1: hide AV1 from YouTube so the phone hardware-decodes VP9/H.264 (verified on the emulator)
- [ ] Check 0.1.1 on the phone: is Home to Shorts smoother?
- [x] 0.1.2: "Speed tests" switches in Settings: H.264 only, identify as Chrome, ad blocker on/off (verified on the emulator)
- [x] Your phone: Galaxy S23 (Snapdragon 8 Gen 2, 120 Hz screen)
- [x] 0.1.3: request 120 Hz (verified it doesn't crash on the emulator, which only has 60 Hz)
- [x] You: 0.1.3 on the S23 "working good"
- [x] 0.2.0: taps landed a few mm low (padding was on the GeckoView); fixed and measured on the emulator
- [x] 0.2.0: mini player: Back on a video shrinks it, keep browsing, tap to expand, play/pause, close (verified on the emulator)
- [ ] You: try 0.2.0 on the S23: tap accuracy, mini player, and opening videos from Home/search/subscriptions
- [ ] Mini player polish ideas: drag to move/dismiss, keep playing while the app is in the background (PiP)
- [ ] You: on the phone, try each switch and report which ones make it smoother (and the phone model)
- [ ] Based on results: make the winning switches the default; if still slow, profile on the phone over wireless debugging
- [ ] Restore the last page after Android kills the app in the background (save and restore the browsing session)
- [ ] Decide on background audio and picture-in-picture (not built; YouTube restricts it)
- [ ] Check memory use and smoothness on a low-end phone
- [x] Git set up, linked to github.com/sylvesterbraganza/yt-ad-free (22 Nov 2026)
- [ ] Update mechanism for sideloaded APKs (simple version check against a file you host)
