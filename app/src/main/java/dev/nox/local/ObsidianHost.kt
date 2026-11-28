package dev.nox.local

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.*
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** A private message channel transferred only to the bundled top-level document.
 * No JavascriptInterface, network, file access, arbitrary intents or shell commands. */
internal class ObsidianHost(
    private val activity: Activity,
    private val dispatch: (String) -> Unit
) {
    companion object {
        private const val ORIGIN = ObsidianResources.ORIGIN
        private const val URL = ObsidianResources.URL
    }
    val view = WebView(activity)
    private var port: WebMessagePort? = null
    private var closed = false
    private var documentLoaded = false
    private var mainDocumentFailed = false
    var ready = false
        private set

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        view.setBackgroundColor(0xFF111113.toInt())
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // Every HTTP(S) resource is resolved locally or rejected by the interceptor.
            blockNetworkLoads = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            domStorageEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                when (ObsidianResources.route(request.url.toString(), request.method, request.isForMainFrame)) {
                    ObsidianResources.Kind.DOCUMENT -> WebResourceResponse(
                        "text/html", "UTF-8", 200, "OK",
                        mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
                        activity.assets.open("obsidian/index.html"))
                    ObsidianResources.Kind.INLINE_IMAGE -> null // data URL only; never a network request
                    ObsidianResources.Kind.REJECT -> WebResourceResponse("text/plain", "UTF-8", 403,
                        "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) mainDocumentFailed = true
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) mainDocumentFailed = true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (closed || documentLoaded || mainDocumentFailed || url != URL) return
                documentLoaded = true
                val channels = view.createWebMessageChannel()
                port = channels[0]
                channels[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                    override fun onMessage(port: WebMessagePort, message: WebMessage) {
                        if (closed || this@ObsidianHost.port !== port) return
                        val value = message.data ?: return
                        if (value.length > 20000) return
                        if (!ready) {
                            if (ObsidianCommand.parse(value)?.op != "ready") return
                            ready = true
                        }
                        dispatch(value)
                    }
                }, Handler(Looper.getMainLooper()))
                view.postWebMessage(WebMessage("nox-init", arrayOf(channels[1])), Uri.parse(ORIGIN))
            }
        }
        view.loadUrl(URL)
    }

    fun send(value: JSONObject) {
        if (ready && !closed) runCatching { port?.postMessage(WebMessage(value.toString())) }
    }

    fun back(handled: (Boolean) -> Unit) {
        if (!ready || closed) { handled(false); return }
        view.evaluateJavascript("window.noxBack ? window.noxBack() : false") { handled(it == "true") }
    }

    fun destroy() {
        closed = true
        ready = false
        runCatching { port?.close() }
        port = null
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.destroy()
    }
}
