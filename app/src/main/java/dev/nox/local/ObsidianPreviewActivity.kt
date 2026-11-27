package dev.nox.local

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout

/** An isolated, bundled design reference. No bridge to NoxService or agent permissions. */
class ObsidianPreviewActivity : Activity() {
    private var preview: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.rgb(17, 17, 19)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setDecorFitsSystemWindows(true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(Button(this).apply {
            text = "‹  N.O.X. · Выйти из демо"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2))
        val view = WebView(this).apply {
            setBackgroundColor(bg)
            settings.javaScriptEnabled = true // bundled, reviewed design only
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
        }
        preview = view
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        val html = assets.open("obsidian/index-matrix.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        view.loadDataWithBaseURL("https://obsidian.invalid/", html, "text/html", "UTF-8", null)
    }

    override fun onPause() {
        preview?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        preview?.onResume()
    }

    override fun onDestroy() {
        preview?.let {
            (it.parent as? LinearLayout)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        preview = null
        super.onDestroy()
    }
}
