package com.finbyte.mispaysdk

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Hosts the MIS Pay checkout pages in a WebView.
 *
 * Delivery modes (set by the entry point that launched this activity):
 *  - [DELIVERY_LEGACY]: legacy [MISPay.checkout] — token only, no result on cancel
 *    (behaviour unchanged from SDK 1.0);
 *  - [DELIVERY_TYPED]: [MISPay.startCheckout] — typed [MISPayResult]: [MISPayResult.Success]
 *    when the page reports the token, [MISPayResult.Cancelled] on back navigation,
 *    [MISPayResult.Error] when the checkout page fails to load.
 *
 * Exactly one result is delivered per launch (guarded by [resultDelivered]); the
 * static delivery slots are cleared as soon as a result is delivered so a new
 * checkout can start immediately afterwards.
 */
class MISPayActivity : AppCompatActivity() {

    companion object {
        internal const val DELIVERY_LEGACY = 0
        internal const val DELIVERY_TYPED = 1

        /** Intent extra carrying the checkout URL. */
        internal const val EXTRA_URL = "URL"

        /** Legacy [MISPay.checkout] callback slot. */
        var tokenCallback: TokenCallback? = null

        /** Typed [MISPay.startCheckout] callback slot. */
        internal var typedCallback: ((MISPayResult) -> Unit)? = null

        /** Which delivery mode the launching entry point selected. */
        internal var deliveryMode: Int = DELIVERY_LEGACY

        /** Clears both delivery slots (main thread). */
        internal fun clearDeliverySlots() {
            tokenCallback = null
            typedCallback = null
        }
    }

    private lateinit var webView: WebView
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mispay)

        webView = findViewById(R.id.misPayWebView)

        // Registered before anything else so back handling works in every state,
        // including the missing-URL edge case below.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    deliverResult(MISPayResult.Cancelled, fromUserCancel = true)
                    finish()
                }
            }
        })

        // Fallback guard: a launch without a usable URL delivers Error (typed mode)
        // and finishes instead of stranding the user on a blank screen.
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            deliverResult(
                MISPayResult.Error(MISPayException(message = "Checkout URL is missing or blank")),
                fromUserCancel = false
            )
            finish()
            return
        }

        setupWebView(url)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView(url: String) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.apply {
            settings.javaScriptEnabled = true
            settings.setGeolocationEnabled(true)
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)


            val javaScriptInterface = JavaScriptInterface { token ->
                lifecycleScope.launch {
                    deliverResult(MISPayResult.Success(token), fromUserCancel = false)
                    finish()
                }
            }
            addJavascriptInterface(javaScriptInterface, "Android")

            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    // Sub-frame failures (images, scripts) must not kill the checkout;
                    // only a failed MAIN document load is terminal.
                    if (request?.isForMainFrame == true) {
                        val failingUrl = request.url?.toString().orEmpty()
                        val description = error?.description?.toString().orEmpty()
                        deliverResult(
                            MISPayResult.Error(
                                MISPayException(
                                    message = "Checkout page failed to load" +
                                        (if (description.isNotBlank()) ": $description" else "") +
                                        (if (failingUrl.isNotBlank()) " [$failingUrl]" else "")
                                )
                            ),
                            fromUserCancel = false
                        )
                        finish()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    if (request?.isForMainFrame == true) {
                        val status = errorResponse?.statusCode ?: -1
                        deliverResult(
                            MISPayResult.Error(
                                MISPayException(
                                    httpStatus = status,
                                    message = "Checkout page returned HTTP $status"
                                )
                            ),
                            fromUserCancel = false
                        )
                        finish()
                    }
                }
            }
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

    /**
     * Delivers the terminal result exactly once and releases the delivery slots so
     * the next checkout can start immediately. Main-thread only (the JS bridge path
     * via lifecycleScope, the back dispatcher and WebView callbacks all run on main).
     */
    private fun deliverResult(result: MISPayResult, fromUserCancel: Boolean) {
        if (resultDelivered) return
        resultDelivered = true
        try {
            when (deliveryMode) {
                DELIVERY_TYPED -> typedCallback?.invoke(result)
                DELIVERY_LEGACY -> if (!fromUserCancel && result is MISPayResult.Success) {
                    tokenCallback?.onTokenReceived(result.token)
                }
            }
        } finally {
            clearDeliverySlots()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Safety net: release slots even when no result was ever delivered
        // (e.g. the system killed the flow).
        clearDeliverySlots()
    }
}
