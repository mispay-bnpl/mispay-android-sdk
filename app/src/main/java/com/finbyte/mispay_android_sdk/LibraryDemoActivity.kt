package com.finbyte.mispay_android_sdk

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.finbyte.mispaysdk.MISPay
import com.finbyte.mispaysdk.MISPayEnvironment
import com.finbyte.mispaysdk.MISPayResult
import com.finbyte.mispaysdk.model.CheckoutRequest
import com.finbyte.mispaysdk.model.CustomerDetails
import com.finbyte.mispaysdk.model.Discount
import com.finbyte.mispaysdk.model.OrderDetails
import com.finbyte.mispaysdk.model.OrderItem
import com.finbyte.mispaysdk.model.ShippingDetails

/**
 * One-screen demo anyone can run: initialize the SDK with sandbox credentials,
 * start a checkout with the docs sample order, and show every result
 * (started / success / cancelled / error).
 *
 * Credentials come from app/credentials.properties (gitignored) via BuildConfig.
 * Create that file with your sandbox MISPAY_APP_ID / MISPAY_APP_SECRET to run.
 */
class LibraryDemoActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        button = findViewById(R.id.button)

        if (BuildConfig.MISPAY_APP_ID != "REPLACE_ME") {
            MISPay.initialize(BuildConfig.MISPAY_APP_ID, BuildConfig.MISPAY_APP_SECRET, MISPayEnvironment.SANDBOX)
            statusText.text = getString(R.string.demo_ready)
        } else {
            button.isEnabled = false
            statusText.text = """
                Credentials missing.
                Create app/credentials.properties with MISPAY_APP_ID and
                MISPAY_APP_SECRET from the MIS Pay team.
            """.trimIndent()
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 3)
        }

        button.setOnClickListener { startSampleCheckout() }
    }

    private fun startSampleCheckout() {
        statusText.text = "Starting checkout…\n(GET /token → decrypt → POST /start-checkout)"
        button.isEnabled = false

        MISPay.startCheckout(
            activity = this,
            request = sampleRequest(),
            onCheckoutStarted = { _, trackId ->
                statusText.text = "Checkout opened.\ntrackId: $trackId"
            }
        ) { result ->
            button.isEnabled = true
            statusText.text = when (result) {
                is MISPayResult.Success ->
                    "SUCCESS ✔\ntoken prefix: ${result.token?.take(24)}…\n(verify server-side)"
                is MISPayResult.Cancelled ->
                    "CANCELLED — customer left the checkout."
                is MISPayResult.Error -> {
                    val meCode = result.exception.meCode?.let { " [$it]" } ?: ""
                    "ERROR$meCode\n${result.exception.message}"
                }
            }
        }
    }

    /** Sample order from the MIS Pay API documentation, with a per-run unique orderId. */
    private fun sampleRequest() = CheckoutRequest(
        orderId = "DEMO-ANDROID-${System.currentTimeMillis()}",
        invoiceId = "1234567890",
        totalPrice = "215",
        shippingAmount = "0",
        vat = "15",
        // totalPrice + shippingAmount + vat - discount.amount = 215+0+15-30 = 200.
        // The API validates this formula and rejects mismatches with ME18.
        discount = Discount(percentage = "15", amount = "30", voucherName = "MIS-30"),
        purchaseAmount = "200",
        purchaseCurrency = "SAR",
        lang = "en",
        customerDetails = CustomerDetails(
            id = "customer ID",
            firstName = "first name",
            lastName = "last name",
            mobileNumber = "+966520006780",
            email = "testemail@gmail.com"
        ),
        orderDetails = OrderDetails(
            items = listOf(
                OrderItem(
                    id = "OR12345",
                    sku = "1234568abcd",
                    nameArabic = "item name in arabic",
                    nameEnglish = "item name in english",
                    quantity = "2",
                    unitPrice = "100",
                    itemUrl = "https://itemurl.com",
                    imageUrl = "https://imagurle.com"
                )
            )
        ),
        shippingDetails = ShippingDetails(
            firstName = "first name",
            lastName = "last name",
            countryCode = "96",
            city = "riyadh",
            district = "riyadh",
            line1 = "first line",
            line2 = "second line",
            gpsCoordinates = "41.40338, 2.17403"
        ),
        // Mandatory for merchant accounts whose MIS Pay callback URL setting is
        // configured as "Dynamic" — this sandbox account requires it (ME97 without it).
        callbackUri = "https://example.com/mispay-callback"
    )
}
