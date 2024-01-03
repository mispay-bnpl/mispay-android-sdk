package com.finbyte.mispaysdk

interface TokenCallback {
    fun onTokenReceived(token: String?)
}