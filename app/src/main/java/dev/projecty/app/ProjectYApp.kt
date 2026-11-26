package dev.projecty.app

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Holds the long-lived engine objects:
 *  - [runtime]: the Gecko engine itself. Android allows only ONE per app process.
 *  - [session]: the browsing "tab" (home, search, Shorts...).
 *  - [playerSession]: a second "tab" that plays videos, so a video can shrink to the mini player
 *    while you keep browsing in [session].
 * Kept here (not in the Activity) so they survive the Activity being recreated, e.g. when the
 * user changes system dark mode.
 *
 * Everything is created lazily: GeckoView starts helper processes for the same app, and those
 * must not create a runtime of their own.
 *
 * Also owns the "speed test" switches from Settings (stored in SharedPreferences "speed") and
 * the bridge to the page (see assets/extensions/bridge/bridge.js).
 */
class ProjectYApp : Application() {

    enum class PlayerMode { CLOSED, FULL, MINI }

    /** Survives Activity recreation, so the mini player stays mini after a theme change. */
    var playerMode = PlayerMode.CLOSED

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
            listOfNotNull(session, playerSession).forEach { it.settings.userAgentOverride = userAgentOverride() }
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
            installBuiltIn(it, BRIDGE_URI, BRIDGE_ID, "Bridge", onReady = ::onBridgeReady) { true }
        }
    }

    /** The browsing "tab". Null until [MainActivity] first creates it. */
    var session: GeckoSession? = null
        private set

    /** The video player "tab". Null until the first video is opened. */
    var playerSession: GeckoSession? = null
        private set

    fun obtainSession(): GeckoSession = session ?: newSession().also {
        session = it
        attachBridge(it, isPlayer = false)
    }

    fun obtainPlayerSession(): GeckoSession = playerSession ?: newSession().also {
        playerSession = it
        attachBridge(it, isPlayer = true)
    }

    private fun newSession() = GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(false) // keep cookies so users stay signed in to YouTube
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()
    ).also { it.settings.userAgentOverride = userAgentOverride() }

    private fun userAgentOverride(): String? = if (chromeUserAgent) CHROME_UA else null

    // ---- Bridge to the page (native messaging) -----------------------------------------------

    /** Receives messages from the page. [MainActivity] sets itself here while it's alive. */
    fun interface BridgeListener {
        fun onBridgeMessage(fromPlayer: Boolean, message: JSONObject)
    }

    var bridgeListener: BridgeListener? = null

    private var bridgeExtension: WebExtension? = null
    private var browsePort: WebExtension.Port? = null
    private var playerPort: WebExtension.Port? = null

    /** Sends a message to the page in the browse view or the player view (dropped if none). */
    fun sendToPage(toPlayer: Boolean, message: JSONObject) {
        (if (toPlayer) playerPort else browsePort)?.postMessage(message)
    }

    private fun onBridgeReady(extension: WebExtension) {
        bridgeExtension = extension
        session?.let { attachBridge(it, isPlayer = false) }
        playerSession?.let { attachBridge(it, isPlayer = true) }
    }

    private fun attachBridge(session: GeckoSession, isPlayer: Boolean) {
        val extension = bridgeExtension ?: return // attached later by onBridgeReady
        val portDelegate = object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                if (message is JSONObject) bridgeListener?.onBridgeMessage(isPlayer, message)
            }

            override fun onDisconnect(port: WebExtension.Port) {
                if (isPlayer && playerPort === port) playerPort = null
                if (!isPlayer && browsePort === port) browsePort = null
            }
        }
        session.webExtensionController.setMessageDelegate(extension, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                port.setDelegate(portDelegate)
                if (isPlayer) playerPort = port else browsePort = port
                port.postMessage(JSONObject().put("type", "role").put("role", if (isPlayer) "player" else "browse"))
                // A freshly loaded player page must also learn whether it's currently mini.
                if (isPlayer) port.postMessage(JSONObject().put("type", "mini").put("on", playerMode == PlayerMode.MINI))
            }
        }, NATIVE_APP)
    }

    // ---- Built-in extensions -----------------------------------------------------------------

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
        onReady: (WebExtension) -> Unit = {},
        wantEnabled: () -> Boolean,
    ) {
        runtime.webExtensionController
            .ensureBuiltIn(uri, id)
            .accept(
                { ext ->
                    Log.i(TAG, "$label ready (version ${ext?.metaData?.version})")
                    setExtensionEnabled(id, wantEnabled())
                    ext?.let(onReady)
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
        private const val BRIDGE_ID = "bridge@projecty.dev"
        private const val BRIDGE_URI = "resource://android/assets/extensions/bridge/"
        /** Must match browser.runtime.connectNative("projecty") in bridge.js. */
        private const val NATIVE_APP = "projecty"

        private const val KEY_ADBLOCK = "adblock"
        private const val KEY_H264 = "h264_only"
        private const val KEY_CHROME_UA = "chrome_ua"

        /** A current Chrome-on-Android user agent (Chrome's own reduced format). */
        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/142.0.0.0 Mobile Safari/537.36"
    }
}
