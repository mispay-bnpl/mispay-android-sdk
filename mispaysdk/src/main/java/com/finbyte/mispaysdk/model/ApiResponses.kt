package com.finbyte.mispaysdk.model

import com.finbyte.mispaysdk.MISPayException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Result of a successful `POST /start-checkout`. */
internal data class StartCheckoutResult(
    val url: String,
    val trackId: String
)

/**
 * Decrypted content of the `/token` response payload.
 *
 * [expirationMillis] is null when the payload omits the expiration field or uses an
 * unparsable format; a null expiration is treated as expired so the token is always
 * refreshed rather than trusted forever.
 */
internal data class DecryptedToken(
    val token: String,
    val expirationMillis: Long?
) {
    fun isExpired(
        nowMillis: Long = System.currentTimeMillis(),
        marginMillis: Long = EXPIRY_MARGIN_MILLIS
    ): Boolean = expirationMillis == null || nowMillis >= expirationMillis - marginMillis

    companion object {
        /** Refresh one minute before the documented 8-hour expiry. */
        const val EXPIRY_MARGIN_MILLIS = 60_000L
    }
}

/**
 * Parsing for the MIS Pay API JSON envelopes. Success responses carry
 * `{"status":"success","result":…}`; error responses come in two shapes:
 * `{"status":"error : 400","result":"ExceptionType.ME01"}` and
 * `{"status":"error","result":{"code":401,"message":"ME10"}}`.
 */
internal object ApiJson {

    private val ME_CODE_REGEX = Regex("ME\\d{2}")

    /** Extracts the encrypted token blob from a 200 /token response. */
    fun parseEncryptedToken(body: String): String {
        val root = parseOrThrow(body)
        requireSuccess(root, body)
        val result = root.optJSONObject("result")
            ?: throw MISPayException(httpStatus = 200, message = "Malformed /token response: 'result' is not an object")
        val token = result.optString("token")
        if (token.isBlank()) {
            throw MISPayException(httpStatus = 200, message = "Malformed /token response: 'result.token' is missing")
        }
        return token
    }

    /** Parses the decrypted token JSON `{"token":"…","expiration":"…"}`. */
    fun parseDecryptedToken(decryptedJson: String): DecryptedToken {
        val root = parseOrThrow(decryptedJson)
        val token = root.optString("token")
        if (token.isBlank()) {
            throw MISPayException(message = "Decrypted token payload is missing 'token'")
        }
        return DecryptedToken(
            token = token,
            expirationMillis = parseExpiration(root.optString("expiration"))
        )
    }

    /** Extracts url + trackId from a 200 /start-checkout response. */
    fun parseStartCheckout(body: String): StartCheckoutResult {
        val root = parseOrThrow(body)
        requireSuccess(root, body)
        val result = root.optJSONObject("result")
            ?: throw MISPayException(httpStatus = 200, message = "Malformed /start-checkout response: 'result' is not an object")
        val url = result.optString("url")
        if (url.isBlank()) {
            throw MISPayException(httpStatus = 200, message = "Malformed /start-checkout response: 'result.url' is missing")
        }
        return StartCheckoutResult(url = url, trackId = result.optString("trackId"))
    }

    /** Maps an HTTP error body (either documented error shape) to a typed exception. */
    fun parseErrorBody(body: String, httpStatus: Int): MISPayException {
        val root = try {
            JSONObject(body)
        } catch (t: Exception) {
            return MISPayException(
                httpStatus = httpStatus,
                message = "Unexpected non-JSON error response (HTTP $httpStatus): ${body.take(120)}"
            )
        }
        val resultAny: Any? = if (root.has("result")) root.get("result") else null
        return when (resultAny) {
            is JSONObject -> MISPayException(
                meCode = resultAny.optString("message").ifBlank { null },
                httpStatus = httpStatus,
                message = resultAny.optString("message").ifBlank { "API error: ${root.optString("status")}" }
            )
            is String -> {
                val meCode = ME_CODE_REGEX.find(resultAny)?.value
                MISPayException(
                    meCode = meCode,
                    httpStatus = httpStatus,
                    message = resultAny.ifBlank { "API error: ${root.optString("status")}" }
                )
            }
            else -> MISPayException(
                meCode = ME_CODE_REGEX.find(root.optString("status"))?.value,
                httpStatus = httpStatus,
                message = "API error: ${root.optString("status")}"
            )
        }
    }

    private fun requireSuccess(root: JSONObject, body: String) {
        if (root.optString("status") != "success") {
            throw parseErrorBody(body, 200)
        }
    }

    private fun parseOrThrow(body: String): JSONObject {
        try {
            return JSONObject(body)
        } catch (t: Exception) {
            throw MISPayException(httpStatus = 200, message = "Response is not valid JSON: ${body.take(120)}", cause = t)
        }
    }

    private fun parseExpiration(value: String): Long? {
        if (value.isBlank()) return null
        // ISO-8601 UTC without offset, e.g. 2022-12-31T23:59:59Z (documented format).
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        utc.timeZone = TimeZone.getTimeZone("UTC")
        utc.isLenient = false
        try {
            return utc.parse(value)?.time
        } catch (_: Exception) {
            // fall through to offset-aware attempt
        }
        val offset = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        offset.isLenient = false
        return try {
            offset.parse(value)?.time
        } catch (_: Exception) {
            null
        }
    }

    /** Converts an epoch-millis value back to the documented expiration format (tests). */
    internal fun formatExpiration(date: Date): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(date)
    }
}
