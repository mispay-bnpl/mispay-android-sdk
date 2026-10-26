package com.finbyte.mispaysdk

import android.util.Log

/**
 * Internal logging seam. Merchants can silence the SDK entirely with
 * `MISPayLog.enabled = false`. Kept behind a seam so local JVM unit tests never touch
 * android.util.Log (which is not mocked in plain unit tests).
 */
internal object MISPayLog {

    @Volatile
    var enabled: Boolean = true

    private const val TAG = "MISPaySDK"

    fun w(message: String) {
        if (enabled) Log.w(TAG, message)
    }

    fun e(message: String, error: Throwable? = null) {
        if (enabled) Log.e(TAG, message, error)
    }
}
