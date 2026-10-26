package com.finbyte.mispaysdk.network

import com.finbyte.mispaysdk.MISPayException
import com.finbyte.mispaysdk.model.ApiJson
import com.finbyte.mispaysdk.model.StartCheckoutResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP client for the MIS Pay API, built on [HttpURLConnection] so the SDK
 * stays dependency-free for merchants. Never call from the main thread.
 */
internal interface TokenDataSource {
    /**
     * Calls `GET /token` and returns the encrypted token blob from `result.token`.
     * @throws MISPayException on any HTTP, transport or envelope error.
     */
    fun fetchEncryptedToken(appId: String, appSecret: String): String
}

internal class MISPayApi(private val baseUrl: String) : TokenDataSource {

    override fun fetchEncryptedToken(appId: String, appSecret: String): String {
        val connection = open(TOKEN_PATH)
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("x-app-id", appId)
            connection.setRequestProperty("x-app-secret", appSecret)
            connection.setRequestProperty("Accept", "application/json")
            return ApiJson.parseEncryptedToken(readBody(connection))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Calls `POST /start-checkout` with the bearer JWT and the serialized order body.
     * @throws MISPayException on any HTTP, transport or envelope error (incl. ME codes).
     */
    fun startCheckout(appId: String, bearerToken: String, bodyJson: String): StartCheckoutResult {
        val connection = open(START_CHECKOUT_PATH)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("authorization", "Bearer $bearerToken")
            connection.setRequestProperty("x-app-id", appId)
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
            return ApiJson.parseStartCheckout(readBody(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection {
        val url = URL(baseUrl.trimEnd('/') + "/" + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        return connection
    }

    private fun readBody(connection: HttpURLConnection): String {
        val status = try {
            connection.responseCode
        } catch (e: IOException) {
            throw MISPayException(message = "Network error: ${e.message}", cause = e)
        }
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (status !in 200..299) {
            throw ApiJson.parseErrorBody(body, status)
        }
        return body
    }

    companion object {
        internal const val TOKEN_PATH = "token"
        internal const val START_CHECKOUT_PATH = "start-checkout"
        private const val TIMEOUT_MILLIS = 30_000
    }
}
