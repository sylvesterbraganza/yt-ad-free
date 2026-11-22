package dev.projecty.app

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtensionController

/**
 * Holds the two long-lived engine objects:
 *  - [runtime]: the Gecko engine itself. Android allows only ONE per app process.
 *  - [session]: the single "tab" showing YouTube. Kept here (not in the Activity) so it survives
 *    the Activity being recreated, e.g. when the user changes system dark mode.
 *
 * Both are created lazily: GeckoView starts helper processes for the same app, and those must
 * not create a runtime of their own.
 *
 * Also owns the "speed test" switches from Settings (stored in SharedPreferences "speed").
 */
class ProjectYApp : Application() {

    private val prefs by lazy { getSharedPreferences("speed", Context.MODE_PRIVATE) }

    /** uBlock Origin on (default) or off. Off is only meant for measuring its cost. */
    var adBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ADBLOCK, value).apply()
            setExtensionEnabled(UBLOCK_ID, value)
        }

    /** Ask YouTube for H.264 only (lightest to decode), instead of VP9. Default off. */
    var h264Only: Boolean
        get() = prefs.getBoolean(KEY_H264, false)
        set(value) {
            prefs.edit().putBoolean(KEY_H264, value).apply()
            setExtensionEnabled(H264_ID, value)
        }

    /** Tell YouTube we're Chrome, so it serves the code it optimises most. Default off. */
    var chromeUserAgent: Boolean
        get() = prefs.getBoolean(KEY_CHROME_UA, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CHROME_UA, value).apply()
            session?.settings?.userAgentOverride = userAgentOverride()
        }

    val runtime: GeckoRuntime by lazy {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val settings = GeckoRuntimeSettings.Builder()
            .consoleOutput(debuggable)
            .remoteDebuggingEnabled(debuggable)
            .aboutConfigEnabled(false)
            .build()
        GeckoRuntime.create(this, settings).also {
            installBuiltIn(it, UBLOCK_URI, UBLOCK_ID, "uBlock Origin") { adBlockEnabled }
            installBuiltIn(it, TWEAKS_URI, TWEAKS_ID, "Tweaks") { true }
            installBuiltIn(it, H264_URI, H264_ID, "H.264-only") { h264Only }
        }
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
    ).also {
        it.settings.userAgentOverride = userAgentOverride()
        session = it
    }

    private fun userAgentOverride(): String? = if (chromeUserAgent) CHROME_UA else null

    /**
     * Loads one bundled extension (app/src/main/assets/extensions/...). `ensureBuiltIn` is
     * idempotent: it installs on first run and updates when the bundled version changes.
     * Then switches it on or off to match Settings.
     */
    private fun installBuiltIn(
        runtime: GeckoRuntime,
        uri: String,
        id: String,
        label: String,
        wantEnabled: () -> Boolean,
    ) {
        runtime.webExtensionController
            .ensureBuiltIn(uri, id)
            .accept(
                { ext ->
                    Log.i(TAG, "$label ready (version ${ext?.metaData?.version})")
                    setExtensionEnabled(id, wantEnabled())
                },
                { error -> Log.e(TAG, "$label failed to load", error) },
            )
    }

    /** Switches an installed extension on or off (no-op if it's already in that state). */
    fun setExtensionEnabled(id: String, enabled: Boolean) {
        val controller = runtime.webExtensionController
        controller.list().accept({ extensions ->
            val ext = extensions?.firstOrNull { it.id == id } ?: return@accept
            if (ext.metaData.enabled == enabled) return@accept
            val source = WebExtensionController.EnableSource.USER
            val result = if (enabled) controller.enable(ext, source) else controller.disable(ext, source)
            result.accept({ Log.i(TAG, "$id enabled=$enabled") }, { Log.e(TAG, "toggle $id failed", it) })
        }, { Log.e(TAG, "list failed", it) })
    }

    /** Debug builds only: switch uBlock Origin off/on without saving it as a setting. */
    fun setAdBlockerEnabledForDebug(enabled: Boolean) = setExtensionEnabled(UBLOCK_ID, enabled)

    companion object {
        private const val TAG = "ProjectY"
        private const val UBLOCK_ID = "uBlock0@raymondhill.net"
        private const val UBLOCK_URI = "resource://android/assets/extensions/ublock/"
        private const val TWEAKS_ID = "tweaks@projecty.dev"
        private const val TWEAKS_URI = "resource://android/assets/extensions/tweaks/"
        private const val H264_ID = "h264@projecty.dev"
        private const val H264_URI = "resource://android/assets/extensions/h264/"

        private const val KEY_ADBLOCK = "adblock"
        private const val KEY_H264 = "h264_only"
        private const val KEY_CHROME_UA = "chrome_ua"

        /** A current Chrome-on-Android user agent (Chrome's own reduced format). */
        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/142.0.0.0 Mobile Safari/537.36"
    }
}
