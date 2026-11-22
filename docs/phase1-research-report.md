# Project Y: Phase 1 Research Report

*Prepared: 22 Nov 2026. Status: awaiting your decision. No application code has been written.*

> **Not legal advice.** This is an engineering and product read of the published terms. Before you take money from anyone, get the commercial and legal conclusions in §5 and §8 checked by an Indian lawyer who knows IT/IP law.

---

## Update 2 (22 Nov 2026): architecture changed

**The chosen direction:**
- **What it is:** a stripped-down browser that shows YouTube's own mobile site unchanged and is locked to YouTube.
- **Engine:** GeckoView, Firefox's engine as a library, rather than a Brave fork. It builds on Windows and updates with a one-line version change.
- **Ads:** blocked by a bundled copy of uBlock Origin.

**What this means for the earlier sections:**
- §1 and §7 (native app using the Data API and the embedded player) are **superseded** for this project.
- §3 and §5 still describe YouTube's rules accurately. This version blocks ads, so it is *not* compliant with YouTube's terms. It is meant to be shared privately, kept off Google Play, and carry its own name and icon.

See `README.md` and `TODO.md`.

## Update (22 Nov 2026): the product will be free, with no monetisation

You have decided Project Y will be **free, with no monetisation**. This changes parts of this report.

**No longer applies**
- §9 entirely: pricing, payment gateway, licence validation, refunds.
- Everything in §8 that only matters when selling: GST, RBI e-mandate rules, E-Commerce Rules and dark-patterns rules on subscriptions.
- The §III.F.3.1 risk ("must not charge users to watch") and the G.1.2/G.2.1 ambiguity.
- The "lifetime price vs revocable API access" refund risk.

**Still applies (free does not exempt you)**
- All YouTube ToS and API policies in §3:
  - no ad blocking
  - no background play
  - no downloads
  - no overlays
  - branding rules
  - 30-day data rules
  - a privacy policy that links YouTube ToS and the Google Privacy Policy
  - remote updatability
- **§III.I.1, the "not a substitute for YouTube" rule.** This is now the *main* risk. The product's independent value (focus mode, collections, allowlists, etc.) becomes the core of the app rather than a paid tier.
- Quota limits and the compliance audit. Quota is free of charge, but it is still capped at 100 searches per day until the audit is passed.
- Brave fork vs native app. The engineering conclusion is unchanged: option D.
- DPDP Act. It applies only if you collect personal data. With local-only storage and no account system you collect almost none.
- Android developer verification. It applies to free sideloaded apps too.

**New options opened up by being free**
- **Google Play distribution** becomes the simplest route: automatic updates, no sideloading friction, and verification included. Play policy requires that your app comply with YouTube's terms, which option D already does.
- **Open-sourcing is possible**, but the API key must not be committed (Developer Policies §III.D.1.4). Each builder supplies their own key via `local.properties`.

---

## 0. Executive summary

1. **Forking Brave is technically possible but the wrong approach.** It needs a Linux build machine (Android builds are not supported on Windows), more than 100 GB of disk, ideally 32 GB of RAM, and you would have to rebase onto every Chromium release (about every 4 weeks) to stay secure. Its main selling point for this product, Shields blocking ads on YouTube, is exactly what YouTube's terms forbid. So you would take on all of Brave's cost and get almost none of its benefit.
2. **Wrapping youtube.com in a WebView also fails.** Google blocks Google sign-in inside embedded WebViews, so subscriptions can't work. Re-skinning the site with injected CSS or JS counts as "altering/modifying the Service". Charging money for that amounts to selling access to YouTube.
3. **The only compliant route is a native Android app** that gets metadata from the **YouTube Data API v3** and plays video through the **official embedded player (IFrame Player API) in a WebView**. Everything in this report assumes that architecture.
4. **The product as briefed collides with YouTube's API policies, whatever engine you use.** The three biggest conflicts:
   - **§III.I.1:** an API client must not act as a "substitute for" a YouTube app or "replicate YouTube's core user experiences" unless it adds *significant independent value*. A cleaner YouTube with Home, Search, Subscriptions and Library tabs is very close to a textbook substitute.
   - **§III.F.3.1:** you must not "charge users to watch content in an embedded YouTube player." §III.G.2.1 does allow selling an API client. Where the line falls between the two is ambiguous, and you should get written clarification from YouTube.
   - **No ad blocking** (§III.I.5), **no background playback** (§III.I.9), **no downloads or offline** (§III.E.1), **no overlays on the player** (Required Minimum Functionality).
5. **The API is missing features the brief depends on.** It has no personalised recommendations, no "related videos" (removed August 2023), and no access to the user's YouTube watch history. Search is limited to **100 calls per day for your whole user base** by default. More requires passing a YouTube API compliance audit.
6. **Recommendation:** build the native-app architecture, but **reposition the product** so its paid value is your own functionality. Examples: intentional, algorithm-free viewing, focus and time controls, curated collections, learning progress and notes. Do not sell "YouTube, but nicer". Apply for the YouTube API compliance audit early and use it to get written answers on the paid model. Details are in §9.

---

## 1. Options compared

| | **A. Fork Brave Android** | **B. Custom Chromium / GeckoView app** | **C. WebView wrapper of m.youtube.com** | **D. Native app + Data API + IFrame player (recommended)** |
|---|---|---|---|---|
| What it is | Rebrand Brave, lock it to YouTube, hide browser UI | Embed a browser engine (e.g. GeckoView, MPL-2.0) in your own app | Your app shell plus a WebView showing YouTube's mobile site | Your own Kotlin/Compose UI; video via YouTube's embeddable player |
| Builds on Windows? | **No.** Linux only, more than 100 GB disk, 16–32 GB RAM, hours per build | GeckoView yes (prebuilt AAR); custom Chromium no | Yes | Yes |
| Solo-dev maintenance | Very high: rebase onto Chromium every 4 weeks, plus weekly security patches | Medium–high | Low, but breaks whenever YouTube changes its site | Low–medium |
| APK size | About 150–250 MB | About 60–100 MB | Under 10 MB | About 10–15 MB |
| Google sign-in / subscriptions | Works (it is a full browser) | Works | **Blocked.** Google refuses OAuth in embedded WebViews | Works via proper Google OAuth (needs OAuth app verification) |
| Ad/tracker blocking on YouTube | Technically yes, **but prohibited** | Same | Same | Not offered |
| YouTube ToS position | Re-skinning plus restricting the site = "alter/modify" the Service; charging = selling access | Same | Same | Governed by the API Terms and Developer Policies; compliant if designed for them |
| Can be sold commercially | Very unlikely | Very unlikely | Very unlikely | **Possibly**, subject to §5 |

**Verdict:** **D**. You can still meet the "keep the engine replaceable" requirement from the brief: the player sits behind a `VideoPlayer` interface (§7).

---

## 2. Brave repository analysis

### 2.1 Structure
- **`brave/brave-browser`** holds only issues, releases and the wiki. Its README says it is not needed for building.
- **`brave/brave-core`** is the actual source. It is checked out at `src/brave` inside a full Chromium checkout and changes Chromium in three ways:
  - `chromium_src/`: file-level overrides of Chromium sources
  - `patches/`: diff patches against Chromium, applied by `pnpm run sync`
  - its own code under `components/`, `browser/` and `android/` (Java/Kotlin UI for Android)
- **Shields** lives in `components/brave_shields/` and uses the **adblock-rust** engine through `components/adblock_rust_ffi`. Filter lists are downloaded at runtime from Brave's own servers (`go-updater.brave.com`, `brave-core-ext.s3.brave.com`).

### 2.2 Build requirements (Android)
- **Host OS:** Linux x86-64 (Debian/Ubuntu). Chromium states that Android builds on Windows or Mac are "not supported." WSL2 may work but is unsupported. A dedicated Linux machine or cloud VM is the realistic option.
- **Hardware:** at least 100 GB free disk (the Chromium source alone is about 30 GB). At least 8 GB of RAM, with more than 16 GB "highly recommended".
- **Toolchain:** depot_tools, Git 2.41+, Python 3, Node.js, pnpm, plus `install-build-deps.sh --android`.
- **Time:** the first fetch takes 30 minutes to several hours. The first build takes "a few hours". Later incremental builds still take minutes to tens of minutes.

### 2.3 Brave-operated services a fork would contact
By default the browser talks to Brave infrastructure: the component updater and ad-block lists, `variations.brave.com` (feature rollout), `usage-ping.brave.com` and STAR telemetry, the Safe Browsing proxy, the update server, and the Rewards, Sync and Wallet backends. A commercial fork would have to **disable or replace every one of these**. You have no right to use Brave's servers for your own product. You would also need your own Google API keys for any Google services that Chromium uses.

### 2.4 What Brave permits
- **Code:** MPL-2.0 permits forking, modifying and selling (see §4).
- **Brand:** the name and logo are **not licensed**. MPL §2.3 grants no trademark rights, and Brave's Terms of Use say Brave's marks may not be used without consent. A fork must be fully rebranded and must not suggest Brave endorses it.

### 2.5 Which Shields features you could legitimately keep
| Shields feature | On youtube.com |
|---|---|
| Blocking YouTube ads | **Not allowed** (YouTube ToS "circumvent… interfere"; Developer Policies §III.I.5) |
| Blocking YouTube's own analytics/tracking requests | Grey area. It interferes with the service and may break playback. **Not recommended** |
| Blocking *third-party* trackers | Little benefit: a YouTube-only app loads almost no third-party content |
| HTTPS upgrades, fingerprinting protection | Harmless but of minor value here |

**Conclusion:** none of Shields' *value* survives in a compliant product. Get your privacy story another way (§6).

---

## 3. YouTube policy assessment

Sources: [YouTube ToS](https://www.youtube.com/t/terms), [API Services Terms](https://developers.google.com/youtube/terms/api-services-terms-of-service), [Developer Policies](https://developers.google.com/youtube/terms/developer-policies), [Required Minimum Functionality (RMF)](https://developers.google.com/youtube/terms/required-minimum-functionality), [Branding Guidelines](https://developers.google.com/youtube/terms/branding-guidelines), [Quota costs](https://developers.google.com/youtube/v3/determine_quota_cost), [Revision history](https://developers.google.com/youtube/v3/revision_history).

### 3.1 Key rules
**YouTube ToS (Permissions and Restrictions)**
- You may view content for "personal, non-commercial use" and may "show YouTube videos through the embeddable YouTube player".
- You must not alter or modify the Service except as expressly authorised.
- You must not circumvent, disable or interfere with any part of the Service.
- No automated access (scrapers).
- You must not sell ads where YouTube content is the main basis for those sales.

**API Services Terms**
- YouTube may audit your client at any time.
- Your quota must not be exceeded or circumvented.
- YouTube can terminate your access at any time. On termination you must delete all API data.
- You indemnify YouTube.
- California law applies.

**Developer Policies**
| Section | Rule | Effect on Project Y |
|---|---|---|
| III.I.1 | No substitute for a YouTube app; don't replicate its core UX or browse experience without *significant independent value* | **Main risk to the product concept** |
| III.F.3.1 | Must not charge users to watch content in an embedded player | **Main risk to the paid model** |
| III.G.2.1 / G.1.2 | Selling an API client is allowed; selling access to API services needs written approval | Ambiguous: get it in writing |
| III.I.5, III.I.4, III.I.6 | No blocking, modifying or replacing ads; no modifying or disabling player functions or links | No ad blocking |
| III.I.9 | No playback from a player that isn't visible | No background play |
| III.I.7 / I.8 | Don't separate audio from video | No audio-only mode |
| III.E.1 | No caching or downloading audiovisual content; no offline play | No downloads |
| III.E.4.x | Stored API data must be refreshed or deleted within 30 days; user deletion requests within 7 days | Affects local history design |
| III.A.1–2 | Link to YouTube ToS; require agreement to a privacy policy that links to Google's Privacy Policy | Onboarding requirement |
| III.D.3 | Extra quota needs a compliance audit, approved per use case | Required before launch |
| III.B.1 | Mobile clients must be remotely updatable | Needs an in-app update mechanism |
| III.H | YouTube may audit and require test accounts for paid features | Plan for it |

**RMF (player)**
- Player viewport at least 200×200 px.
- No overlays or frames on top of the player.
- No touch events on the player that trigger your own actions.
- Only one autoplaying player, and only once it is more than 50% visible.
- **The client must identify itself via the HTTP Referer**, using your package name in reverse-DNS form. On Android, set it with `loadDataWithBaseURL` or as a `loadUrl` header. Without it you get "Error 153".

**Branding**
- You must never use "YouTube", "YT" or any variant in the app's name.
- No YouTube logo alongside your app's name.
- No logos confusingly similar to YouTube's.
- You may describe the app as "an app for YouTube".

### 3.2 Data API capability gaps
| Brief needs | API reality |
|---|---|
| Personalised recommendations | **None.** The home feed was deprecated in 2016 |
| Related videos | **Removed.** `relatedToVideoId` stopped working on 7 Aug 2023 |
| User's YouTube watch history / Watch Later | **Not accessible** |
| Search | `search.list` has its **own bucket of 100 calls per day** (June 2026 change), shared by all users |
| Video, channel and playlist details, trending | 1 unit each from a 10,000 units/day pool. Fine |
| User's subscriptions | `subscriptions.list?mine=true` with OAuth scope `youtube.readonly`. The scope is "sensitive", so Google must verify your OAuth app |
| Embedded playback | The IFrame Player API in a WebView. The old native "YouTube Android Player API" has been retired, and its documentation page now returns 404 |

---

## 4. Licensing report

| Component | Licence | Your obligations if you ship it |
|---|---|---|
| brave-core | MPL-2.0 (file-level copyleft) | Keep licence headers. Offer source for MPL files you distribute (including your changes to them) at no more than the cost of distribution. You may combine with proprietary code ("Larger Work"). No trademark rights |
| Chromium | BSD-3-Clause, plus hundreds of third-party licences (LGPL, MIT, Apache, ICU…) | Ship all notices (Chromium generates `about:credits`). LGPL parts carry relinking/source obligations |
| Filter lists (EasyList, uBO lists) | GPLv3 / CC BY-SA | Irrelevant under option D |
| **Option D stack** (AndroidX, Jetpack Compose, Kotlin, OkHttp/Retrofit, Coil) | Apache-2.0 | Include notices (an "Open-source licences" screen). Commercial use is fine |
| YouTube Data API / embedded player | API Terms (not open source) | Everything in §3 |

Option D makes licensing trivial. Option A makes it a real compliance task.

---

## 5. Feasibility: possible vs Brave-permitted vs YouTube-permitted vs commercially distributable

| Feature from brief | Technically possible | Brave permits | YouTube permits | Commercially distributable | Compliant alternative |
|---|---|---|---|---|---|
| Rebranded Brave locked to YouTube | Yes | Yes, if fully rebranded | No (alters or substitutes the Service) | **No** | Option D |
| Ad blocking | Yes | Yes | **No** | **No** | Show ads; tell users about YouTube Premium |
| Background or screen-off audio | Yes | Yes | **No** (III.I.9) | **No** | None. Picture-in-picture keeps the player visible and is *probably* fine; confirm in the audit |
| Downloads / offline | Yes | n/a | **No** (III.E.1) | **No** | "Watch later" list stored locally |
| Recommended feed | Only by scraping | n/a | **No** (no API; scraping is banned) | **No** | "New from your channels" + trending by region + your own curated collections |
| Related videos | Only by scraping | n/a | **No** | **No** | "More from this channel", same playlist, or a keyword search (uses search quota) |
| Recently watched / continue watching | Yes | n/a | Yes, stored locally (store video IDs and position; refresh metadata within 30 days) | Yes | Built locally |
| Search | Yes | n/a | Yes, within quota | Yes, *after* a quota audit | Cache trending/channel data; budget search per user |
| Subscriptions | Yes | n/a | Yes, via OAuth | Yes, after Google OAuth verification | v1: local "saved channels" with no sign-in |
| Full-screen, rotation, playback controls | Yes | n/a | Yes, using the player's own controls or JS API calls from *outside* the player | Yes | — |
| Volume/brightness swipe gestures **on the player** | Yes | n/a | **Risky** (RMF: no touch actions on the player; no overlays) | Doubtful | Hardware volume keys; gesture strip outside the player area when not in full screen |
| Description, metadata | Yes | n/a | Yes (`videos.list`) | Yes | — |
| Dark mode, offline/error states, preferences | Yes | n/a | Yes | Yes | — |
| Charging for the app | Yes | Yes | **Unclear** (G.2.1 allows; F.3.1 forbids charging to watch) | **Get written confirmation** | Free to watch, paid for *your* features (freemium) |
| Name containing "YouTube"/"YT" | Yes | n/a | **No** | **No** | An original name ("…for YouTube" only in descriptions) |

**Distributing outside the Play Store changes none of this.** Your API key, OAuth client and the embedded player's Referer all identify you to YouTube. YouTube can revoke them at any time (API ToS §24), which would break every installed copy. The 2022 shutdown of Vanced after a cease-and-desist shows YouTube enforces against third-party clients, even ones distributed only as APKs.

---

## 6. Privacy: what you *can* offer
- **No analytics or tracking SDKs** in your app. State this in the privacy policy.
- **Watch history, saved channels and preferences stay local** (on-device database). They are never sent to your server.
- **Sign-in is optional.** The app is fully usable without a Google account.
- **Privacy-enhanced embeds** (`youtube-nocookie.com`) where compatible with the Referer requirement. Verify this in Phase 2.
- **WebView hardening:** no third-party cookies, no file access, JS bridge limited to player events, and a "clear player data" button.
- **No autoplay by default.** The policies note that autoplay starts data collection on page load.

This is an honest privacy story and needs no Brave code.

---

## 7. Recommended architecture (option D)

```
app (single-activity, Jetpack Compose, Material 3)
├── ui/        Home · Search · Channels · Library · Settings · Watch screen
├── domain/    plain Kotlin models + use-cases (no Android deps)
├── data/
│   ├── youtube/   YouTubeDataSource (Retrofit + kotlinx.serialization, quota-aware cache)
│   ├── local/     Room DB: history, positions, saved channels, collections
│   └── prefs/     DataStore: theme, playback prefs
└── player/
    ├── VideoPlayer (interface: load, play, pause, seekTo, events)
    └── IFramePlayer (WebView + IFrame API + Referer = app package)
```

**Why these choices** (each dependency explained, nothing extra):
- **Kotlin + Jetpack Compose + Material 3:** Google's current standard UI toolkit. It handles dark mode and adaptive layouts, and has the most learning resources.
- **Single activity + Navigation Compose:** the simplest way to build a bottom-nav app.
- **ViewModel + StateFlow:** survives screen rotation and gives clean loading, error and success states.
- **Retrofit + OkHttp + kotlinx.serialization:** the standard HTTP stack. OkHttp's disk cache helps with quota.
- **Room:** local history and saved channels with type-safe queries.
- **DataStore:** small key-value settings store.
- **Coil:** thumbnail image loading.
- **No Hilt/Dagger at first:** manual dependency injection in one `AppContainer` class is easier to understand. Add Hilt later only if the app grows enough to need it.
- **Own thin IFrame wrapper (about 200 lines) rather than a third-party player library.** You control the Referer, the JS bridge and policy compliance. The `VideoPlayer` interface keeps the engine replaceable.

**Backend (later, only once licensing goes ahead):** a tiny server for licence validation and the update manifest. *No YouTube data passes through it*, which keeps you out of the 30-day storage rules and your secret API key off the device. A proxy is still an option if quota or key protection requires it.

---

## 8. Legal and distribution: India

| Area | What applies | Practical consequence |
|---|---|---|
| Contract | YouTube and API terms (California law) bind you wherever you distribute | Revocation of your API access kills the product |
| Trade Marks Act 1999 (s.29, passing off) | "YouTube" is a registered mark in India | Original name and icon; no confusing red play-button imagery |
| Copyright Act 1957 (s.65A anti-circumvention, s.63) | Circumventing technical protection measures can be an offence | Another reason: no ad-block or download circumvention |
| IT Act 2000 + **DPDP Act 2023 / DPDP Rules 2025** (phased in; most duties expected around 2027; *verify current dates*) | You are a "data fiduciary" for buyers' emails and payment records | Consent notice, purpose limitation, breach handling, grievance contact. Local-only app data keeps this small |
| Consumer Protection Act 2019 + **E-Commerce Rules 2020** + CCPA **Dark Patterns Guidelines 2023** | Selling digital goods online to consumers | Show seller identity and address, refund/cancellation policy, grievance officer; easy cancellation; no subscription traps or drip pricing |
| GST | Registration required above ₹20 lakh turnover for services (₹10 lakh in special-category states), or earlier if you choose to register | GST invoice per sale. Foreign buyers raise export/OIDAR questions |
| RBI e-mandate rules (recurring payments) | ₹49/month auto-debit needs an e-mandate via card or UPI AutoPay, with a pre-debit notice | Handled by the gateway (Razorpay, Cashfree, PayU). Expect merchant KYC and possible category review |
| Android sideloading: **developer verification** | Enforced in Brazil, Indonesia, Singapore and Thailand from 30 Sep 2026. **Global rollout planned for 2027.** Unverified apps go through an "advanced flow" with a 24-hour wait | Register as a verified developer (Android Developer Console) *before* launch, or Indian users will face scary warnings in 2027 |

---

## 9. Business model analysis

**Pricing hypotheses (₹49/month, ₹399/year, ₹699 lifetime)**
- Compare with YouTube Premium India, which costs about ₹149/month for an individual (*verify current price*). Premium removes ads and allows background play and downloads, none of which you can offer.
- Your price only makes sense if your features are things YouTube does not provide.
- **Lifetime pricing is dangerous.** Your API access can be revoked at any time, and you would owe refunds or support for a product that no longer works.

**Safer model:**
- **Free app:** watching, search and channels free. This avoids the III.F.3.1 conflict.
- **"Pro" tier** for your own features, for example:
  - Focus mode: no feed at all, daily time budget, schedule locks
  - Collections and courses: playlists with progress tracking and notes
  - Allowlist profiles (for kids or study)
  - Watch statistics from local data
  - Backup and sync of your *own* data
- Confirm this split in writing during the API compliance audit.

**Payments and entitlements (design only; do not implement yet)**
- **Gateway:** subscriptions plus a one-time product, with webhooks to your server.
- **Licence:**
  - Server issues an Ed25519-signed entitlement token (user, plan, expiry, device IDs).
  - The app verifies the token offline using the public key, with a 7-day grace period.
  - Device limit of 2–3 per licence, with self-service device reset.
- **Honest limitation:** a sideloaded APK can be patched to skip licence checks. Do not over-invest in DRM. Make paid features need the server where it is natural (sync, backup).
- **Updates:** a signed update manifest on your site, with in-app download and install via `PackageInstaller`. III.B.1 requires the app to be remotely updatable.
- **Refunds and support:** a written refund policy (consumer law), an email and grievance contact, and a cancellation flow in the app and on the website.

---

## 10. Development environment (option D, on your Windows 11 PC)
- **Android Studio** (latest stable). It bundles JDK 17/21, Gradle and the SDK manager. Allow about 15–25 GB of disk.
- **Android Emulator** with hardware acceleration via Windows Hypervisor Platform (enable it in Windows Features).
- **At least one real low-end phone** (2–3 GB RAM, Android 9–11) for Phase 5 testing.
- **Google Cloud project:**
  - YouTube Data API v3 enabled
  - API key restricted to your Android package and SHA-1 signing fingerprint
  - OAuth client added later, if you add sign-in
- **Git:** recommended. The folder is not a git repository yet.
- **No Linux machine needed.** You would only need one for option A.

## 11. Complexity estimate (solo developer, part-time, new to Android)
| Phase | Option D | Option A (for comparison) |
|---|---|---|
| Phase 2 prototype | 1–2 weeks | 2–4 weeks just to get a first build working |
| Phase 3 UI | 3–5 weeks | 4–8 weeks (Brave's Java UI codebase is huge) |
| Phase 4 player/API integration | 2–3 weeks | n/a |
| Phase 5 testing | 2 weeks | 3–4 weeks |
| Ongoing | Small: API/policy changes | **Continuous rebasing every 4 weeks** |
| YouTube compliance audit | Weeks to months (in parallel; outside your control) | Not applicable, since it wouldn't pass |

---

## 12. Decisions needed from you
1. **Architecture:** approve option D (native app + Data API + IFrame player), dropping the Brave fork.
2. **Product positioning:** accept a repositioning around *your own* value (e.g. intentional, algorithm-free viewing and learning) instead of "YouTube with fewer distractions". *(Free product: this still matters because of §III.I.1. It decides whether YouTube approves your quota audit.)*
3. **Scope of the Phase 2 prototype:** mock data only, or a real API key from the start (needs your Google Cloud project)?
4. **Working name:** "Project Y" is fine as a code name and is policy-safe. The final name must not contain "YouTube"/"YT".
