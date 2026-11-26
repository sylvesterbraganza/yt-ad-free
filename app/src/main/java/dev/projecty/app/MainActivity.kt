package dev.projecty.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.projecty.app.ProjectYApp.PlayerMode
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.OrientationController

/**
 * The whole visible app: YouTube's mobile site with no browser UI, uBlock Origin underneath.
 *
 * Two GeckoViews, one per session:
 *  - browse view: home, search, subscriptions, Shorts...
 *  - player view: videos open here. Back shrinks it to a floating mini player so you can keep
 *    browsing; tapping the mini player expands it again (like the official app).
 */
class MainActivity : ComponentActivity(), ProjectYApp.BridgeListener {

    private val app get() = application as ProjectYApp

    private lateinit var root: FrameLayout
    private lateinit var browseView: GeckoView
    private lateinit var playerBox: FrameLayout
    private lateinit var playerView: GeckoView
    private lateinit var miniTouch: View
    private lateinit var miniPlay: ImageButton
    private lateinit var miniClose: ImageButton

    private lateinit var browse: GeckoSession
    private lateinit var player: GeckoSession

    private var browseCanGoBack = false
    private var playerUrl: String? = null
    /** The session currently showing a video full screen, if any. */
    private var fullScreenSession: GeckoSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the status/nav bars; their icons switch light/dark with the system theme.
        enableEdgeToEdge()
        requestHighestRefreshRate()
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        browseView = findViewById(R.id.gecko_view)
        playerBox = findViewById(R.id.player_box)
        playerView = findViewById(R.id.player_view)
        miniTouch = findViewById(R.id.mini_touch)
        miniPlay = findViewById(R.id.mini_play)
        miniClose = findViewById(R.id.mini_close)

        val background = ContextCompat.getColor(this, R.color.page_background)
        browseView.coverUntilFirstPaint(background)
        // TextureView: the player is drawn over the browse view and gets rounded corners as the
        // mini player. The default SurfaceView can't reliably be layered or clipped like that.
        playerView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        playerBox.clipToOutline = true
        applySystemBarInsets()

        val runtime = app.runtime
        browse = app.obtainSession()
        player = app.obtainPlayerSession()
        for (s in listOf(browse, player)) {
            s.navigationDelegate = navigationDelegate
            s.contentDelegate = contentDelegate
            s.permissionDelegate = permissionDelegate
        }
        runtime.orientationController.delegate = orientationDelegate
        app.bridgeListener = this

        val isFirstOpen = !browse.isOpen
        if (isFirstOpen) browse.open(runtime)
        if (!player.isOpen) player.open(runtime)
        browseView.setSession(browse)
        playerView.setSession(player)

        miniTouch.setOnClickListener { setPlayerMode(PlayerMode.FULL) }
        miniPlay.setOnClickListener { app.sendToPage(toPlayer = true, JSONObject().put("type", "togglePlay")) }
        miniClose.setOnClickListener { setPlayerMode(PlayerMode.CLOSED) }

        val linkFromIntent = debugUrlFrom(intent) ?: youtubeUrlFrom(intent)
        when {
            linkFromIntent != null -> openLink(linkFromIntent, loadHomeBehind = isFirstOpen)
            isFirstOpen -> browse.loadUri(SitePolicy.HOME_URL)
        }
        setPlayerMode(app.playerMode, reload = false)

        onBackPressedDispatcher.addCallback(this) {
            val fullScreen = fullScreenSession
            when {
                fullScreen != null -> fullScreen.exitFullScreen()
                // Like the official app: Back on a video shrinks it to the mini player.
                app.playerMode == PlayerMode.FULL -> setPlayerMode(PlayerMode.MINI)
                browseCanGoBack -> browse.goBack()
                // Back on the first page sends the app to the background instead of destroying
                // it, so the page/video state is kept.
                else -> moveTaskToBack(true)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        (debugUrlFrom(intent) ?: youtubeUrlFrom(intent))?.let { openLink(it, loadHomeBehind = false) }
    }

    override fun onDestroy() {
        // Detach (but do not close) the sessions so they survive Activity recreation.
        browseView.releaseSession()
        playerView.releaseSession()
        // Only clear delegates that are still ours: a newer Activity may already have set its own.
        for (s in listOf(browse, player)) {
            if (s.navigationDelegate === navigationDelegate) s.navigationDelegate = null
            if (s.contentDelegate === contentDelegate) s.contentDelegate = null
            if (s.permissionDelegate === permissionDelegate) s.permissionDelegate = null
        }
        if (app.runtime.orientationController.delegate === orientationDelegate) {
            app.runtime.orientationController.delegate = null
        }
        if (app.bridgeListener === this) app.bridgeListener = null
        super.onDestroy()
    }

    /** Videos go to the player, everything else to the browse view. */
    private fun openLink(url: String, loadHomeBehind: Boolean) {
        if (isVideoUrl(url)) {
            if (loadHomeBehind) browse.loadUri(SitePolicy.HOME_URL)
            openVideo(url)
        } else {
            browse.loadUri(url)
        }
    }

    // ---- Player / mini player --------------------------------------------------------------

    private fun openVideo(url: String) {
        player.loadUri(url)
        playerUrl = url
        setPlayerMode(PlayerMode.FULL)
    }

    /**
     * @param reload false when only restoring the on-screen state after Activity recreation
     *   (don't stop a video that's still loaded).
     */
    private fun setPlayerMode(mode: PlayerMode, reload: Boolean = true) {
        app.playerMode = mode
        when (mode) {
            PlayerMode.CLOSED -> {
                playerBox.visibility = View.GONE
                if (reload) player.loadUri("about:blank")
                playerUrl = null
                browse.setActive(true)
            }

            PlayerMode.FULL -> {
                playerBox.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                playerBox.elevation = 0f
                playerBox.outlineProvider = ViewOutlineProvider.BOUNDS
                playerBox.visibility = View.VISIBLE
                setMiniControlsVisible(false)
                app.sendToPage(toPlayer = true, JSONObject().put("type", "mini").put("on", false))
                player.setActive(true)
                // The browse view is fully covered: pause its rendering to save battery.
                browse.setActive(false)
            }

            PlayerMode.MINI -> {
                val density = resources.displayMetrics.density
                val width = (root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) * MINI_WIDTH_FRACTION
                playerBox.layoutParams = FrameLayout.LayoutParams(width.toInt(), (width * 9 / 16).toInt()).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    marginEnd = (8 * density).toInt()
                    // Sit above YouTube's own bottom navigation bar (Home / Shorts / ...).
                    bottomMargin = (MINI_BOTTOM_MARGIN_DP * density).toInt()
                }
                playerBox.elevation = 8 * density
                val radius = 12 * density
                playerBox.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                playerBox.visibility = View.VISIBLE
                setMiniControlsVisible(true)
                app.sendToPage(toPlayer = true, JSONObject().put("type", "mini").put("on", true))
                browse.setActive(true)
            }
        }
    }

    private fun setMiniControlsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        miniTouch.visibility = v
        miniPlay.visibility = v
        miniClose.visibility = v
    }

    /** Messages from bridge.js in either view. */
    override fun onBridgeMessage(fromPlayer: Boolean, message: JSONObject) {
        when (message.optString("type")) {
            "openVideo" -> if (!fromPlayer) {
                val url = message.optString("url")
                if (SitePolicy.decide(url) == SitePolicy.Decision.ALLOW) openVideo(url)
            }

            "playing" -> if (fromPlayer) {
                miniPlay.setImageResource(if (message.optBoolean("playing")) R.drawable.ic_pause else R.drawable.ic_play)
            }
        }
    }

    // ---- Navigation lock + routing between the two views ----------------------------------

    private val navigationDelegate = object : NavigationDelegate {
        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            if (session === browse) browseCanGoBack = canGoBack
        }

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            if (url == null) return
            if (session === browse) onBrowseLocation(url) else onPlayerLocation(url)
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny> = if (isDebugUrl(request.uri)) GeckoResult.allow()
        else when (SitePolicy.decide(request.uri)) {
            SitePolicy.Decision.ALLOW ->
                if (request.target == NavigationDelegate.TARGET_WINDOW_NEW) {
                    // "Open in new tab" links: we have no tabs, so load in place.
                    session.loadUri(request.uri)
                    GeckoResult.deny()
                } else {
                    GeckoResult.allow()
                }

            SitePolicy.Decision.OPEN_EXTERNALLY -> {
                openInOtherApp(request.uri)
                GeckoResult.deny()
            }

            SitePolicy.Decision.BLOCK -> {
                Log.d(TAG, "Blocked navigation to ${request.uri.take(80)}")
                GeckoResult.deny()
            }
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            // Already handled in onLoadRequest (TARGET_WINDOW_NEW); never open extra sessions.
            return null
        }
    }

    private fun onBrowseLocation(url: String) {
        when {
            // bridge.js normally catches video taps first. This is the fallback for videos that
            // YouTube opened some other way: step the browse view back and play it in the player.
            isVideoUrl(url) -> {
                browse.goBack()
                openVideo(url)
            }
            // Shorts play in the browse view; don't play two things at once.
            isShortsUrl(url) && app.playerMode == PlayerMode.MINI ->
                app.sendToPage(toPlayer = true, JSONObject().put("type", "pause"))
        }
    }

    private fun onPlayerLocation(url: String) {
        if (url == "about:blank") return
        if (isVideoUrl(url)) {
            playerUrl = url // e.g. tapped a related video: stays in the player
            return
        }
        // Left the video (channel, home logo, search...): continue in the browse view.
        if (app.playerMode != PlayerMode.CLOSED) {
            browse.loadUri(url)
            setPlayerMode(PlayerMode.CLOSED)
        }
    }

    private fun openInOtherApp(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app can open $uri")
        }
    }

    // ---- Autoplay ----------------------------------------------------------------------------

    /**
     * GeckoView blocks video with sound from starting unless the app allows it. A video opened in
     * the player view starts there without a tap on that page, and the mini player's play button
     * is a native button, so both need autoplay allowed. Every other permission (camera,
     * location, notifications...) keeps GeckoView's default: denied.
     */
    private val permissionDelegate = object : PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? = when (perm.permission) {
            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_ALLOW)

            else -> null
        }
    }

    // ---- Full screen video ----------------------------------------------------------------

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            Log.d(TAG, "onFullScreen($fullScreen)")
            fullScreenSession = if (fullScreen) session else null
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (fullScreen) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            ViewCompat.requestApplyInsets(root)
        }
    }

    /** YouTube's player asks to lock landscape when entering full screen; we honour it. */
    private val orientationDelegate = object : OrientationController.OrientationDelegate {
        override fun onOrientationLock(aOrientation: Int): GeckoResult<AllowOrDeny> {
            requestedOrientation = aOrientation
            return GeckoResult.allow()
        }

        override fun onOrientationUnlock() {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // ---- Smoothness: 120 Hz ------------------------------------------------------------------

    /**
     * Phones like the Galaxy S23 have 120 Hz screens, but the system often runs apps it doesn't
     * know at 60 Hz, which makes scrolling feel laggy next to the official YouTube app. Ask for
     * the fastest refresh rate available at the current resolution.
     */
    private fun requestHighestRefreshRate() {
        val display = ContextCompat.getDisplayOrDefault(this)
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        Log.i(TAG, "Display: requested ${best.refreshRate} Hz (was ${current.refreshRate} Hz)")
    }

    // ---- Edge-to-edge layout ----------------------------------------------------------------

    /**
     * Keep YouTube's page out from under the status/navigation bars, except in full screen.
     * The padding goes on the root container, never on a GeckoView (see activity_main.xml).
     */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            if (fullScreenSession != null) {
                view.setPadding(0, 0, 0, 0)
            } else {
                // ime() = the on-screen keyboard, so the search box isn't hidden behind it.
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
    }

    // ---- Incoming links ("Open with" / "Share to") -------------------------------------------

    private fun youtubeUrlFrom(intent: Intent?): String? {
        val candidate = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { text -> URL_IN_TEXT.find(text)?.value }
            else -> null
        } ?: return null
        return candidate.takeIf { SitePolicy.decide(it) == SitePolicy.Decision.ALLOW }
    }

    // ---- Debug-only test pages (app/src/debug/assets/debug/, absent from release builds) ------

    private val isDebuggable get() =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun isDebugUrl(uri: String) = isDebuggable && uri.startsWith(DEBUG_PAGES)

    /**
     * adb shell am start -n dev.projecty.app/.MainActivity --es debug_uri resource://android/assets/debug/x.html
     * adb shell am start -n dev.projecty.app/.MainActivity --es debug_ublock off|on
     */
    private fun debugUrlFrom(intent: Intent?): String? {
        if (!isDebuggable) return null
        intent?.getStringExtra("debug_ublock")?.let { app.setAdBlockerEnabledForDebug(it == "on") }
        return intent?.getStringExtra("debug_uri")?.takeIf { isDebugUrl(it) }
    }

    companion object {
        private const val TAG = "ProjectY"
        private const val DEBUG_PAGES = "resource://android/assets/debug/"
        private val URL_IN_TEXT = Regex("""https?://\S+""")
        private const val MINI_WIDTH_FRACTION = 0.55f
        private const val MINI_BOTTOM_MARGIN_DP = 64

        /** A YouTube video page (youtube.com/watch?v=... or a youtu.be/ID short link). */
        fun isVideoUrl(url: String): Boolean {
            val host = SitePolicy.hostOf(url)?.lowercase() ?: return false
            if (host == "youtu.be") return url.substringAfter("youtu.be/", "").isNotEmpty()
            if (!SitePolicy.isAllowedHost(host) || !host.endsWith("youtube.com")) return false
            val path = url.substringAfter(host).substringBefore('?').substringBefore('#')
            return path == "/watch" && url.contains("v=")
        }

        fun isShortsUrl(url: String) = url.contains("youtube.com/shorts/")
    }
}
