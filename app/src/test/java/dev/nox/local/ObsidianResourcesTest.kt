package dev.nox.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ObsidianResourcesTest {
    @Test fun bundledMainFrameIsServed() {
        assertEquals(ObsidianResources.Kind.DOCUMENT, ObsidianResources.route(ObsidianResources.URL, "GET", true))
    }
    @Test fun onlyInlineJpegSubresourceMayPass() {
        assertEquals(ObsidianResources.Kind.INLINE_IMAGE, ObsidianResources.route("data:image/jpeg;base64,AAAA", "GET", false))
        assertEquals(ObsidianResources.Kind.REJECT, ObsidianResources.route("data:image/jpeg;base64,AAAA", "GET", true))
        assertEquals(ObsidianResources.Kind.REJECT, ObsidianResources.route("data:text/html,hello", "GET", false))
    }
    @Test fun noNetworkOrOtherPaths() {
        for (url in listOf("https://example.com", "file:///sdcard/file", ObsidianResources.URL+"?x=1", ObsidianResources.ORIGIN+"/other", "https://appassets.androidplatform.net.evil.example/obsidian/index.html")) {
            assertEquals(ObsidianResources.Kind.REJECT, ObsidianResources.route(url,"GET",true))
            assertEquals(ObsidianResources.Kind.REJECT, ObsidianResources.route(url,"GET",false))
        }
        assertEquals(ObsidianResources.Kind.REJECT, ObsidianResources.route(ObsidianResources.URL, "POST", true))
        assertEquals(ObsidianResources.Kind.REJECT, ObsidianResources.route(ObsidianResources.URL, "GET", false))
    }
}
