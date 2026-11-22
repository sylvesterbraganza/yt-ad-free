package dev.projecty.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.OrientationController

/**
 * The whole visible app: one full-screen GeckoView showing YouTube's mobile site.
 * No address bar, no tabs, no menus - just YouTube, with uBlock Origin running underneath.
 */
class MainActivity : ComponentActivity() {

    private val app get() = application as ProjectYApp
    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession

    private var canGoBack = false
    private var isFullScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the status/nav bars; their icons switch light/dark with the system theme.
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        geckoView = findViewById(R.id.gecko_view)
        geckoView.coverUntilFirstPaint(ContextCompat.getColor(this, R.color.page_background))
        applySystemBarInsets()

        val runtime = app.runtime
        session = app.obtainSession()
        session.navigationDelegate = navigationDelegate
        session.contentDelegate = contentDelegate
        runtime.orientationController.delegate = orientationDelegate

        val isFirstOpen = !session.isOpen
        if (isFirstOpen) session.open(runtime)
        geckoView.setSession(session)

        val linkFromIntent = debugUrlFrom(intent) ?: youtubeUrlFrom(intent)
        when {
            linkFromIntent != null -> session.loadUri(linkFromIntent)
            isFirstOpen -> session.loadUri(SitePolicy.HOME_URL)
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                isFullScreen -> session.exitFullScreen()
                canGoBack -> session.goBack()
                // Like the official app: Back on the first page sends the app to the background
                // instead of destroying it, so the page/video state is kept.
                else -> moveTaskToBack(true)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        (debugUrlFrom(intent) ?: youtubeUrlFrom(intent))?.let { session.loadUri(it) }
    }

    override fun onDestroy() {
        // Detach (but do not close) the session so it survives Activity recreation.
        geckoView.releaseSession()
        // Only clear delegates that are still ours: a newer Activity may already have set its own.
        if (session.navigationDelegate === navigationDelegate) session.navigationDelegate = null
        if (session.contentDelegate === contentDelegate) session.contentDelegate = null
        if (app.runtime.orientationController.delegate === orientationDelegate) {
            app.runtime.orientationController.delegate = null
        }
        super.onDestroy()
    }

    // ---- Navigation lock -------------------------------------------------------------------

    private val navigationDelegate = object : NavigationDelegate {
        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@MainActivity.canGoBack = canGoBack
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

    private fun openInOtherApp(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app can open $uri")
        }
    }

    // ---- Full screen video ----------------------------------------------------------------

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            Log.d(TAG, "onFullScreen($fullScreen)")
            isFullScreen = fullScreen
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (fullScreen) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            ViewCompat.requestApplyInsets(geckoView)
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

    // ---- Edge-to-edge layout ----------------------------------------------------------------

    /** Keep YouTube's page out from under the status/navigation bars, except in full screen. */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(geckoView) { view, insets ->
            if (isFullScreen) {
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
    }
}
