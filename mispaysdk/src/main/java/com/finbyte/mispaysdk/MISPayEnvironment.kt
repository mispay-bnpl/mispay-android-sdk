package com.finbyte.mispaysdk

/**
 * MIS Pay API environment. Endpoints `token` (GET) and `start-checkout` (POST) are
 * resolved relative to [baseUrl].
 *
 * Use the built-in [SANDBOX] / [PRODUCTION] environments, or [Custom] against a private
 * deployment (HTTPS required).
 */
class MISPayEnvironment private constructor(val baseUrl: String) {

    override fun toString(): String = baseUrl

    companion object {
        /** Sandbox environment provided by the MIS Pay team. */
        @JvmField
        val SANDBOX = MISPayEnvironment("https://api-sandbox.mispay.dev/v1/api/")

        /** Production environment. */
        @JvmField
        val PRODUCTION = MISPayEnvironment("https://api.mispay.co/v1/api/")

        /**
         * Builds a custom environment. HTTPS is enforced; plain HTTP is rejected because
         * the requests carry application credentials.
         */
        @JvmStatic
        fun Custom(baseUrl: String): MISPayEnvironment {
            val trimmed = baseUrl.trim()
            require(trimmed.startsWith("https://")) {
                "Custom MISPayEnvironment must use HTTPS, got: $trimmed"
            }
            return MISPayEnvironment(trimmed)
        }
    }
}

/** Internal SDK configuration captured by [MISPay.initialize]. */
internal data class MISPayConfig(
    val appId: String,
    val appSecret: String,
    val environment: MISPayEnvironment
)
