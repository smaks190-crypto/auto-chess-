package dev.nox.local

/** No request can fall back to the network. Only the bundled document and local JPEG previews. */
internal object ObsidianResources {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val URL = "$ORIGIN/obsidian/index.html"
    enum class Kind { DOCUMENT, INLINE_IMAGE, REJECT }
    fun route(url: String, method: String, mainFrame: Boolean): Kind = when {
        method != "GET" -> Kind.REJECT
        mainFrame && url == URL -> Kind.DOCUMENT
        !mainFrame && url.startsWith("data:image/jpeg;base64,") -> Kind.INLINE_IMAGE
        else -> Kind.REJECT
    }
}
