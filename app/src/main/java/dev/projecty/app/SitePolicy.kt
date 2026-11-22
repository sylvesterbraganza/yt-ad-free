package dev.projecty.app

/**
 * Decides which pages the app is allowed to show.
 *
 * The app is "locked" to YouTube: YouTube pages and the Google sign-in pages needed to log in
 * load inside the app. Every other web link is handed to the phone's normal browser, and
 * app-specific links (intent:, vnd.youtube:, ...) are ignored so YouTube's own app never hijacks us.
 *
 * Pure Kotlin with no Android dependencies, so it is covered by plain JVM unit tests.
 */
object SitePolicy {

    const val HOME_URL = "https://m.youtube.com/"

    enum class Decision { ALLOW, OPEN_EXTERNALLY, BLOCK }

    /** Sites (and all their subdomains) that open inside the app. */
    private val allowedDomains = listOf(
        "youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
    )

    /** Exact Google hosts needed for signing in / account consent. Not google.com as a whole. */
    private val allowedGoogleHosts = setOf(
        "accounts.google.com",
        "myaccount.google.com",
        "consent.google.com",
        "gds.google.com",
    )

    /** Country-specific sign-in hosts such as accounts.google.co.in. */
    private val countryAccountsHost = Regex("""^accounts\.google\.[a-z]{2,3}(\.[a-z]{2})?$""")

    /** Non-web schemes that are reasonable to hand to another app. */
    private val externalSchemes = setOf("mailto", "tel", "sms", "market")

    fun decide(uri: String): Decision {
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
        return when (scheme) {
            "about" -> if (uri.equals("about:blank", ignoreCase = true)) Decision.ALLOW else Decision.BLOCK
            "http", "https" -> {
                val host = hostOf(uri) ?: return Decision.BLOCK
                if (isAllowedHost(host)) Decision.ALLOW else Decision.OPEN_EXTERNALLY
            }
            in externalSchemes -> Decision.OPEN_EXTERNALLY
            else -> Decision.BLOCK // intent:, vnd.youtube:, data:, javascript:, file:, ...
        }
    }

    fun isAllowedHost(rawHost: String): Boolean {
        val host = rawHost.lowercase().trimEnd('.')
        if (allowedDomains.any { host == it || host.endsWith(".$it") }) return true
        if (host in allowedGoogleHosts) return true
        return countryAccountsHost.matches(host)
    }

    /**
     * Extracts the host from an http(s) URL without java.net.URI, which rejects some characters
     * that real YouTube URLs contain.
     */
    internal fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        val hostAndPort = authority.substringAfterLast('@')
        val host = if (hostAndPort.startsWith("[")) {
            hostAndPort.substringBefore(']') + "]" // IPv6 literal - never allowed, but parse it sanely
        } else {
            hostAndPort.substringBefore(':')
        }
        return host.ifEmpty { null }
    }
}
