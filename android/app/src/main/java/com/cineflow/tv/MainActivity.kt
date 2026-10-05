package com.cineflow.tv

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Main entry: fullscreen WebView hosting the CineFlow web app.
 * The JS bridge (CineflowBridge) is exposed as `CineflowNative`; the shim
 * (assets/bridge-shim.js) is injected into index.html by WwwServer before
 * any page script runs.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var wwwServer: WwwServer? = null

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        val store = Store(this)
        val tmdb = TmdbClient(store)
        val resources = ResourceClient(store)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // D-pad / remote friendly focus.
            isFocusable = true
            isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {
                // Keep navigation inside the WebView.
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false
            }
        }
        val bridge = CineflowBridge(this, webView, store, tmdb, resources)
        webView.addJavascriptInterface(bridge, "CineflowNative")
        setContentView(webView)

        val shim = assets.open("bridge-shim.js").bufferedReader().readText()
        val server = WwwServer(this, shim)
        server.start()
        wwwServer = server
        val port = server.listeningPort
        webView.loadUrl("http://127.0.0.1:$port/index.html")
    }

    override fun onDestroy() {
        try {
            wwwServer?.stop()
        } catch (e: Exception) {
            // ignore
        }
        webView.destroy()
        super.onDestroy()
    }

    // TV remote "back": go back in history first, otherwise exit.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
