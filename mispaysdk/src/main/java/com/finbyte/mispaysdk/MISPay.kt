package com.finbyte.mispaysdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.finbyte.mispaysdk.model.CheckoutRequest
import com.finbyte.mispaysdk.network.MISPayApi
import com.finbyte.mispaysdk.token.TokenManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * MIS Pay checkout SDK entry point.
 *
 * Typical usage:
 * ```kotlin
 * MISPay.initialize(appId, appSecret, MISPayEnvironment.SANDBOX)
 * MISPay.startCheckout(activity, checkoutRequest) { result ->
 *     when (result) {
 *         is MISPayResult.Success  -> verifyTokenServerSide(result.token)
 *         is MISPayResult.Cancelled -> handleCancellation()
 *         is MISPayResult.Error     -> showError(result.exception)
 *     }
 * }
 * ```
 *
 * Delivery guarantees:
 *  - [startCheckout]'s callback is invoked on the **main thread**, exactly **once**
 *    per call — [MISPayResult.Success] on completion, [MISPayResult.Cancelled] when
 *    the customer backs out, [MISPayResult.Error] for every other failure
 *    (validation, network, API, decryption, page load).
 *  - Only **one checkout can be active at a time**; a second call while one is in
 *    flight fails fast with [MISPayResult.Error].
 *  - If the host process is killed while the customer is on the MIS Pay pages, no
 *    in-app result can be delivered — the authoritative fallbacks are your
 *    `callbackUri` webhook and `GET /track-checkout/{trackId}` polling (both
 *    server-side, independent of the app process).
 */
class MISPay private constructor() {

    companion object {

        @Volatile
        private var config: MISPayConfig? = null

        @Volatile
        private var api: MISPayApi? = null

        @Volatile
        private var tokenManager: TokenManager? = null

        private val executor: ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "MISPay-API").apply { isDaemon = true }
            }

        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * Configures the SDK with the credentials provided by the MIS Pay Technical Team.
         * Call once during app start, before [startCheckout]. Calling it again replaces
         * the configuration and drops any cached token.
         *
         * @throws IllegalArgumentException when appId or appSecret is blank.
         */
        @JvmStatic
        fun initialize(appId: String, appSecret: String, environment: MISPayEnvironment) {
            require(appId.isNotBlank()) { "appId must not be blank" }
            require(appSecret.isNotBlank()) { "appSecret must not be blank" }
            val environmentApi = MISPayApi(environment.baseUrl)
            config = MISPayConfig(appId, appSecret, environment)
            api = environmentApi
            tokenManager = TokenManager(environmentApi)
        }

        /**
         * Legacy SDK 1.x entry point: opens a raw checkout URL in the MIS Pay WebView
         * and delivers the final token to [callback]. No /token or /start-checkout
         * calls are made; no Cancelled/Error delivery — behaviour is unchanged from
         * SDK 1.0.
         *
         * @deprecated Use [startCheckout], which performs the documented
         * token → start-checkout → WebView flow with typed results.
         */
        @JvmStatic
        @Deprecated(
            message = "Use startCheckout(activity, request) { result -> ... }: full token + " +
                "start-checkout flow with typed Success/Cancelled/Error results"
        )
        fun checkout(context: Context, url: String, callback: (String?) -> Unit) {
            MISPayActivity.tokenCallback = object : TokenCallback {
                override fun onTokenReceived(token: String?) {
                    callback(token)
                }
            }
            MISPayActivity.deliveryMode = MISPayActivity.DELIVERY_LEGACY
            val intent = Intent(context, MISPayActivity::class.java).apply {
                putExtra(MISPayActivity.EXTRA_URL, url)
            }
            context.startActivity(intent)
        }

        /**
         * Runs the full checkout flow:
         *  1. fetch + AES-256-GCM decrypt the /token JWT (cached ~8h);
         *  2. POST the order to /start-checkout (one automatic retry on ME10/ME11);
         *  3. open the returned checkout URL in the MIS Pay WebView;
         *  4. deliver [MISPayResult.Success] when the page reports the token,
         *     [MISPayResult.Cancelled] when the user backs out, or
         *     [MISPayResult.Error] when any step fails.
         *
         * [callback] and [onCheckoutStarted] are invoked on the main thread. Exactly one
         * terminal [MISPayResult] is delivered per call.
         *
         * Example:
         * ```kotlin
         * MISPay.startCheckout(activity, request) { result -> ... }
         * // with trackId:
         * MISPay.startCheckout(activity, request, onCheckoutStarted = { url, trackId -> ... }) { result -> ... }
         * ```
         *
         * @throws IllegalStateException when [initialize] has not been called.
         *
         * Validation failures of [request] are NOT thrown — they are delivered to
         * [callback] as [MISPayResult.Error] on the main thread, so every caller
         * has a single error path.
         */
        @JvmStatic
        fun startCheckout(
            activity: Activity,
            request: CheckoutRequest,
            onCheckoutStarted: ((url: String, trackId: String) -> Unit)? = null,
            callback: (MISPayResult) -> Unit
        ) {
            val activeConfig = config ?: throw IllegalStateException(
                "Call MISPay.initialize(appId, appSecret, environment) before startCheckout()"
            )
            val activeApi = api ?: throw IllegalStateException("MISPay is not initialized")
            val manager = tokenManager ?: throw IllegalStateException("MISPay is not initialized")

            try {
                request.validate()
            } catch (e: IllegalArgumentException) {
                mainHandler.post {
                    callback(MISPayResult.Error(MISPayException(message = e.message, cause = e)))
                }
                return
            }

            executor.execute {
                val outcome = runCheckout(activeConfig, activeApi, manager, request)
                mainHandler.post {
                    when (outcome) {
                        is Outcome.Started -> launchCheckoutActivity(
                            activity, callback, onCheckoutStarted, outcome
                        )
                        is Outcome.Failed ->
                            callback(MISPayResult.Error(outcome.error))
                    }
                }
            }
        }

        /**
         * Opens the checkout activity on the main thread. Fallback guards:
         *  - refuses to start when another checkout still owns the delivery slot
         *    (its result would be mis-delivered to the newer callback);
         *  - any failure while starting the activity is converted into an
         *    [MISPayResult.Error] instead of crashing the host app.
         */
        private fun launchCheckoutActivity(
            activity: Activity,
            callback: (MISPayResult) -> Unit,
            onCheckoutStarted: ((url: String, trackId: String) -> Unit)?,
            outcome: Outcome.Started
        ) {
            if (MISPayActivity.typedCallback != null) {
                callback(
                    MISPayResult.Error(
                        MISPayException(
                            message = "A checkout is already in progress. Wait for its result " +
                                "before starting another one."
                        )
                    )
                )
                return
            }
            try {
                MISPayActivity.typedCallback = callback
                MISPayActivity.deliveryMode = MISPayActivity.DELIVERY_TYPED
                onCheckoutStarted?.invoke(outcome.url, outcome.trackId)
                val intent = Intent(activity, MISPayActivity::class.java).apply {
                    putExtra(MISPayActivity.EXTRA_URL, outcome.url)
                }
                activity.startActivity(intent)
            } catch (t: Throwable) {
                // Activity was finishing/destroyed or the launch was rejected —
                // release the slot and surface the failure to the merchant.
                MISPayActivity.clearDeliverySlots()
                callback(
                    MISPayResult.Error(
                        MISPayException(message = "Could not open the checkout page: ${t.message}", cause = t)
                    )
                )
            }
        }

        private sealed class Outcome {
            data class Started(val url: String, val trackId: String) : Outcome()
            data class Failed(val error: MISPayException) : Outcome()
        }

        private fun runCheckout(
            config: MISPayConfig,
            api: MISPayApi,
            manager: TokenManager,
            request: CheckoutRequest
        ): Outcome {
            return try {
                attempt(config, api, manager, request)
            } catch (e: MISPayException) {
                if (e.meCode == ME_INVALID_TOKEN || e.meCode == ME_SESSION_EXPIRED) {
                    // Token rejected (ME10/ME11): drop cache and retry exactly once.
                    manager.invalidate()
                    try {
                        attempt(config, api, manager, request)
                    } catch (retryError: MISPayException) {
                        Outcome.Failed(retryError)
                    }
                } else {
                    Outcome.Failed(e)
                }
            } catch (t: Throwable) {
                Outcome.Failed(MISPayException(message = "Unexpected checkout failure: ${t.message}", cause = t))
            }
        }

        private fun attempt(
            config: MISPayConfig,
            api: MISPayApi,
            manager: TokenManager,
            request: CheckoutRequest
        ): Outcome {
            val jwt = manager.getValidToken(config.appId, config.appSecret).token
            val started = api.startCheckout(config.appId, jwt, request.toJson().toString())
            return Outcome.Started(started.url, started.trackId)
        }

        private const val ME_INVALID_TOKEN = "ME10"
        private const val ME_SESSION_EXPIRED = "ME11"
    }
}
