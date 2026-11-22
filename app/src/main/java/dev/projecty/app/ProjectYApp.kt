package dev.projecty.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * Holds the two long-lived engine objects:
 *  - [runtime]: the Gecko engine itself. Android allows only ONE per app process.
 *  - [session]: the single "tab" showing YouTube. Kept here (not in the Activity) so it survives
 *    the Activity being recreated, e.g. when the user changes system dark mode.
 *
 * Both are created lazily: GeckoView starts helper processes for the same app, and those must
 * not create a runtime of their own.
 */
class ProjectYApp : Application() {

    val runtime: GeckoRuntime by lazy {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val settings = GeckoRuntimeSettings.Builder()
            .consoleOutput(debuggable)
            .remoteDebuggingEnabled(debuggable)
            .aboutConfigEnabled(false)
            .build()
        GeckoRuntime.create(this, settings).also { installAdBlocker(it) }
    }

    /** The YouTube "tab". Null until [MainActivity] first creates it. */
    var session: GeckoSession? = null
        private set

    fun obtainSession(): GeckoSession = session ?: GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(false) // keep cookies so users stay signed in to YouTube
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()
    ).also { session = it }

    /**
     * Loads the bundled uBlock Origin (app/src/main/assets/extensions/ublock) as a built-in
     * extension. `ensureBuiltIn` is idempotent: it installs on first run and updates it when the
     * bundled version changes.
     */
    private fun installAdBlocker(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn(UBLOCK_URI, UBLOCK_ID)
            .accept(
                { ext -> Log.i(TAG, "uBlock Origin ready (version ${ext?.metaData?.version})") },
                { error -> Log.e(TAG, "uBlock Origin failed to load", error) },
            )
    }

    /** Debug builds only: switch uBlock Origin off/on to tell its effects apart from the app's. */
    fun setAdBlockerEnabledForDebug(enabled: Boolean) {
        val controller = runtime.webExtensionController
        controller.list().accept({ extensions ->
            val ublock = extensions?.firstOrNull { it.id == UBLOCK_ID } ?: return@accept
            val source = org.mozilla.geckoview.WebExtensionController.EnableSource.USER
            val result = if (enabled) controller.enable(ublock, source) else controller.disable(ublock, source)
            result.accept({ Log.i(TAG, "uBlock Origin enabled=$enabled") }, { Log.e(TAG, "toggle failed", it) })
        }, { Log.e(TAG, "list failed", it) })
    }

    companion object {
        private const val TAG = "ProjectY"
        private const val UBLOCK_ID = "uBlock0@raymondhill.net"
        private const val UBLOCK_URI = "resource://android/assets/extensions/ublock/"
    }
}
