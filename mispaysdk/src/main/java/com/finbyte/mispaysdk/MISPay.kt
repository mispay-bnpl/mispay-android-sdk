package com.finbyte.mispaysdk

import android.content.Context
import android.content.Intent

class MISPay {
    companion object {
        fun checkout(context: Context, url: String, callback: (String?) -> Unit) {
            MISPayActivity.tokenCallback = object : TokenCallback {
                override fun onTokenReceived(token: String?) {
                    callback(token)
                }
            }
            val intent = Intent(context, MISPayActivity::class.java).apply {
                putExtra("URL", url)
            }
            context.startActivity(intent)
        }
    }
}
