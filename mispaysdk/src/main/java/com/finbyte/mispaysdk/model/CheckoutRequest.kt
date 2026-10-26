package com.finbyte.mispaysdk.model

import com.finbyte.mispaysdk.MISPayLog
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Order information sent to `POST /start-checkout`.
 *
 * All monetary and numeric values are [String]s by contract with the MIS Pay API
 * (e.g. `"215"`, `"2"`). Optional fields are nullable and are omitted from the JSON
 * when null. [validate] must pass before the request is sent; [MISPay.startCheckout]
 * enforces this automatically.
 *
 * `version` is always serialized as `"v1.2"` and `isMobile` as `true` — do not try to
 * set them, they are fixed by the API contract.
 */
data class CheckoutRequest(
    /** Unique identifier for the order. Mandatory. */
    val orderId: String,
    /** Total amount for the product(s). Mandatory, numeric string. */
    val totalPrice: String,
    /** Shipping cost. Mandatory, numeric string. */
    val shippingAmount: String,
    /** Value-added tax amount. Mandatory, numeric string. */
    val vat: String,
    /**
     * Final calculated purchase amount:
     * `totalPrice + shippingAmount + vat - discount.amount`. Mandatory, numeric string.
     * A mismatch is logged but never rejected (rounding is the merchant's call).
     */
    val purchaseAmount: String,
    /** Currency for the transaction, e.g. `SAR`. Mandatory. */
    val purchaseCurrency: String,
    /** Language preference: `en` or `ar`. Mandatory. */
    val lang: String = "en",
    /** Invoice identifier. Optional. */
    val invoiceId: String? = null,
    /** Serial number associated with the transaction. Optional. */
    val serialNumber: String? = null,
    /** Applied discount details. Optional; omitted entirely when null. */
    val discount: Discount? = null,
    /** Customer details. Mandatory (at least [CustomerDetails.mobileNumber]). */
    val customerDetails: CustomerDetails,
    /** Ordered items. Mandatory, at least one item. */
    val orderDetails: OrderDetails,
    /** Shipping information. Optional; omitted entirely when null. */
    val shippingDetails: ShippingDetails? = null,
    /** Callback URL, required when your MIS Pay callback setting is Dynamic. Optional otherwise. */
    val callbackUri: String? = null
) {

    /** API version fixed by the MIS Pay contract. */
    val apiVersion: String = API_VERSION

    /** Throws [IllegalArgumentException] with a merchant-readable message on any violation. */
    fun validate() {
        require(orderId.isNotBlank()) { "orderId is mandatory" }
        requireNumeric("totalPrice", totalPrice)
        requireNumeric("shippingAmount", shippingAmount)
        requireNumeric("vat", vat)
        requireNumeric("purchaseAmount", purchaseAmount)
        require(purchaseCurrency.isNotBlank()) { "purchaseCurrency is mandatory" }
        require(lang == "en" || lang == "ar") { "lang must be 'en' or 'ar' (got '$lang')" }
        callbackUri?.let {
            require(it.startsWith("http://") || it.startsWith("https://")) {
                "callbackUri must be an http(s) URL (got '$it')"
            }
        }

        require(mobileNumberRegex.matches(customerDetails.mobileNumber)) {
            "customerDetails.mobileNumber format is invalid (got '${customerDetails.mobileNumber}'). " +
                "Accepted: 05XXXXXXXX, 9665XXXXXXXX, +9665XXXXXXXX"
        }

        require(orderDetails.items.isNotEmpty()) { "orderDetails.items must contain at least one item" }
        orderDetails.items.forEachIndexed { index, item ->
            val label = "orderDetails.items[$index]"
            requireNumeric("$label.quantity", item.quantity)
            requireNumeric("$label.unitPrice", item.unitPrice)
            require(item.nameArabic != null || item.nameEnglish != null) {
                "$label needs nameArabic or nameEnglish (at least one)"
            }
        }
    }

    /**
     * Builds the exact JSON body for /start-checkout. Null optional fields are omitted
     * entirely. Calls [validate] first. Logs (never rejects) a purchase-amount mismatch.
     */
    fun toJson(): JSONObject {
        validate()
        amountConsistencyIssue()?.let { MISPayLog.w(it) }

        val discountJson = discount?.let {
            JSONObject()
                .putOpt("percentage", it.percentage)
                .putOpt("amount", it.amount)
                .putOpt("voucherName", it.voucherName)
        }

        val customerJson = JSONObject()
            .putOpt("id", customerDetails.id)
            .putOpt("firstName", customerDetails.firstName)
            .putOpt("lastName", customerDetails.lastName)
            .put("mobileNumber", customerDetails.mobileNumber)
            .putOpt("email", customerDetails.email)

        val itemsJson = JSONArray()
        orderDetails.items.forEach { item ->
            itemsJson.put(
                JSONObject()
                    .putOpt("id", item.id)
                    .putOpt("sku", item.sku)
                    .putOpt("nameArabic", item.nameArabic)
                    .putOpt("nameEnglish", item.nameEnglish)
                    .put("quantity", item.quantity)
                    .put("unitPrice", item.unitPrice)
                    .putOpt("itemUrl", item.itemUrl)
                    .putOpt("imageUrl", item.imageUrl)
            )
        }

        val shippingJson = shippingDetails?.let {
            JSONObject()
                .putOpt("firstName", it.firstName)
                .putOpt("lastName", it.lastName)
                .putOpt("countryCode", it.countryCode)
                .putOpt("city", it.city)
                .putOpt("district", it.district)
                .putOpt("line1", it.line1)
                .putOpt("line2", it.line2)
                .putOpt("gpsCoordinates", it.gpsCoordinates)
        }

        val json = JSONObject()
            .put("orderId", orderId)
            .putOpt("invoiceId", invoiceId)
            .put("totalPrice", totalPrice)
            .put("shippingAmount", shippingAmount)
            .put("vat", vat)
            .putOpt("discount", discountJson)
            .put("purchaseAmount", purchaseAmount)
            .put("purchaseCurrency", purchaseCurrency)
            .put("lang", lang)
            .put("version", API_VERSION)
            .putOpt("serialNumber", serialNumber)
            .put("customerDetails", customerJson)
            .put("orderDetails", JSONObject().put("items", itemsJson))
            .putOpt("shippingDetails", shippingJson)
            .put("isMobile", true)
            .putOpt("callbackUri", callbackUri)
        return json
    }

    /**
     * Soft purchase-amount check: returns a warning string when
     * `totalPrice + shippingAmount + vat - discount.amount` differs from
     * [purchaseAmount] by more than 0.01, or null when consistent / undecidable.
     */
    fun amountConsistencyIssue(): String? {
        val expected = runCatching {
            totalPrice.toBigDecimal()
                .add(shippingAmount.toBigDecimal())
                .add(vat.toBigDecimal())
                .subtract(discount?.amount?.toBigDecimal() ?: BigDecimal.ZERO)
        }.getOrNull() ?: return null
        val actual = runCatching { purchaseAmount.toBigDecimal() }.getOrNull() ?: return null
        return if (expected.subtract(actual).abs() > BigDecimal("0.01")) {
            "purchaseAmount ($purchaseAmount) does not match totalPrice+shippingAmount+vat-discount ($expected)"
        } else {
            null
        }
    }

    private fun requireNumeric(name: String, value: String) {
        require(value.isNotBlank()) { "$name is mandatory" }
        require(
            runCatching { value.toBigDecimal() }.isSuccess
        ) { "$name must be a numeric string (got '$value')" }
    }

    companion object {
        /** Fixed API version required by the MIS Pay contract. */
        const val API_VERSION: String = "v1.2"

        private val mobileNumberRegex = Regex("""^(?:05\d{8}|9665\d{8}|\+9665\d{8})$""")
    }
}

/** Discount applied to the order. All fields optional; nulls are omitted. */
data class Discount(
    val percentage: String? = null,
    val amount: String? = null,
    val voucherName: String? = null
)

/** Customer details. [mobileNumber] is mandatory and format-checked. */
data class CustomerDetails(
    val mobileNumber: String,
    val id: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null
)

/** Ordered items; must contain at least one [OrderItem]. */
data class OrderDetails(
    val items: List<OrderItem>
)

/** A single ordered item. [quantity] and [unitPrice] are mandatory numeric strings;
 * at least one of [nameArabic]/[nameEnglish] is required. */
data class OrderItem(
    val quantity: String,
    val unitPrice: String,
    val id: String? = null,
    val sku: String? = null,
    val nameArabic: String? = null,
    val nameEnglish: String? = null,
    val itemUrl: String? = null,
    val imageUrl: String? = null
)

/** Shipping address details. All fields optional. */
data class ShippingDetails(
    val firstName: String? = null,
    val lastName: String? = null,
    val countryCode: String? = null,
    val city: String? = null,
    val district: String? = null,
    val line1: String? = null,
    val line2: String? = null,
    val gpsCoordinates: String? = null
)
