package com.finbyte.mispaysdk

/**
 * Outcome of a checkout flow started with [MISPay.startCheckout].
 */
sealed class MISPayResult {

    /**
     * The customer finished the checkout on the MIS Pay pages. [token] is the value
     * delivered by the checkout page; per the MIS Pay documentation it encodes the
     * success or failure of the payment and must be verified server-side.
     */
    data class Success(val token: String?) : MISPayResult()

    /** The customer left the checkout before completing it (back navigation). */
    object Cancelled : MISPayResult()

    /** The flow failed before or during the checkout pages (network, API, crypto or validation). */
    data class Error(val exception: MISPayException) : MISPayResult()
}
