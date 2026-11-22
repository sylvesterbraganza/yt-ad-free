package dev.projecty.app

import dev.projecty.app.SitePolicy.Decision.ALLOW
import dev.projecty.app.SitePolicy.Decision.BLOCK
import dev.projecty.app.SitePolicy.Decision.OPEN_EXTERNALLY
import org.junit.Assert.assertEquals
import org.junit.Test

class SitePolicyTest {

    private fun check(expected: SitePolicy.Decision, url: String) =
        assertEquals(url, expected, SitePolicy.decide(url))

    @Test fun youtubePagesLoadInApp() {
        check(ALLOW, "https://m.youtube.com/")
        check(ALLOW, "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s")
        check(ALLOW, "https://youtube.com/shorts/abc")
        check(ALLOW, "https://youtu.be/dQw4w9WgXcQ")
        check(ALLOW, "https://M.YOUTUBE.COM/feed/subscriptions")
        check(ALLOW, "https://m.youtube.com./")
        check(ALLOW, "https://m.youtube.com:443/results?search_query=a|b")
        check(ALLOW, "about:blank")
    }

    @Test fun googleSignInLoadsInApp() {
        check(ALLOW, "https://accounts.google.com/ServiceLogin?service=youtube")
        check(ALLOW, "https://accounts.google.co.in/accounts/SetSID")
        check(ALLOW, "https://consent.youtube.com/m?continue=x")
        check(ALLOW, "https://myaccount.google.com/")
    }

    @Test fun otherSitesOpenInNormalBrowser() {
        check(OPEN_EXTERNALLY, "https://www.google.com/search?q=hi")
        check(OPEN_EXTERNALLY, "https://example.com/")
        check(OPEN_EXTERNALLY, "http://instagram.com/someone")
        check(OPEN_EXTERNALLY, "mailto:someone@example.com")
    }

    @Test fun lookalikeAndTrickHostsAreNotTrusted() {
        check(OPEN_EXTERNALLY, "https://notyoutube.com/")
        check(OPEN_EXTERNALLY, "https://youtube.com.evil.example/")
        check(OPEN_EXTERNALLY, "https://youtube.com@evil.example/")
        check(OPEN_EXTERNALLY, "https://accounts.google.com.evil.example/")
    }

    @Test fun appSchemesAreBlocked() {
        check(BLOCK, "intent://watch?v=x#Intent;package=com.google.android.youtube;end")
        check(BLOCK, "vnd.youtube:dQw4w9WgXcQ")
        check(BLOCK, "javascript:alert(1)")
        check(BLOCK, "file:///sdcard/x")
        check(BLOCK, "about:config")
        check(BLOCK, "https://")
    }
}
