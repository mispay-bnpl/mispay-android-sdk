package com.finbyte.mispaysdk

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MISPayActivity : AppCompatActivity() {

    companion object {
        var tokenCallback: TokenCallback? = null
    }

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mispay)

        webView = findViewById(R.id.misPayWebView)
        val url = intent.getStringExtra("URL") ?: return

        setupWebView(url)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView(url: String) {
        webView.apply {
            settings.javaScriptEnabled = true
            settings.setGeolocationEnabled(true)
            val javaScriptInterface = JavaScriptInterface { token ->
                lifecycleScope.launch {
                    tokenCallback?.onTokenReceived(token)
                    finish()
                }
            }
            addJavascriptInterface(javaScriptInterface, "Android")

            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback
                ) {
                    callback.invoke(origin, true, false)
                }
            }

            loadUrl(url)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tokenCallback = null
    }
}
