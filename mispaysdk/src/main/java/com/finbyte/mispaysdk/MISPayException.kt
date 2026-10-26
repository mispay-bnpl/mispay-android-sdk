package com.finbyte.mispaysdk

/**
 * Typed failure surfaced to the merchant through [MISPayResult.Error].
 *
 * @property meCode MIS Pay error code when the API returned one
 *   (e.g. `ME01` credentials rejected, `ME04` session cache missing,
 *   `ME10` invalid authorization token, `ME11` session expired).
 * @property httpStatus HTTP status code of the failing response, or `-1` when the
 *   failure did not produce an HTTP response (network / crypto / validation errors).
 */
class MISPayException(
    val meCode: String? = null,
    val httpStatus: Int = -1,
    message: String? = null,
    cause: Throwable? = null
) : Exception(formatMessage(meCode, httpStatus, message), cause)

private fun formatMessage(meCode: String?, httpStatus: Int, message: String?): String {
    val parts = buildList {
        if (!message.isNullOrBlank()) add(message)
        if (meCode != null) add("ME code: $meCode")
        if (httpStatus >= 0) add("HTTP $httpStatus")
    }
    return if (parts.isEmpty()) "MISPay error" else parts.joinToString(" | ")
}
