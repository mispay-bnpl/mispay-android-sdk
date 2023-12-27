package com.finbyte.mispaysdk

import android.webkit.JavascriptInterface

class JavaScriptInterface(private val receiveToken: (String) -> Unit) {

    @JavascriptInterface
    fun onTokenReceived(token: String) {
        receiveToken(token)
    }
}