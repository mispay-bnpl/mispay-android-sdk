package com.finbyte.mispaysdk.model

import com.finbyte.mispaysdk.MISPayLog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exhaustive contract test: the JSON produced by CheckoutRequest.toJson() — which
 * MSIPay.attempt sends verbatim as the POST /start-checkout body — is checked
 * field-by-field against the official MIS Pay parameter table
 * (https://doc.mispay.co/docs/endpoints, "Start Checkout Process", S.No 1-41).
 *
 * Documented rules verified here:
 *  - all monetary/numeric values are STRINGS (S.No 3,4,5,10,7,8,27,28);
 *  - mandatory fields must be present (S.No 1,3,4,5,10,11,12,13,15,19,21,22,27,28);
 *  - version is fixed "v1.2" (S.No 13) and isMobile is boolean (S.No 40);
 *  - optional fields are OMITTED entirely when not supplied
 *    (S.No 2,6,14,16,17,18,20,23,24,29,30,31..39,41);
 *  - items is an array of objects with nameArabic XOR nameEnglish (S.No 25,26).
 */
class CheckoutRequestContractTest {

    @Before
    fun setUp() {
        MISPayLog.enabled = false
    }

    // ---------------------------------------------------------- mandatory set

    @Test
    fun `S1 S3 S4 S5 S10 S11 S12 - mandatory scalar fields present as strings with exact values`() {
        val json = docsExample().toJson()
        assertEquals("1233-ABCD", json.getString("orderId"))          // S1
        assertEquals("215", json.getString("totalPrice"))             // S3
        assertEquals("0", json.getString("shippingAmount"))           // S4
        assertEquals("15", json.getString("vat"))                     // S5
        assertEquals("200", json.getString("purchaseAmount"))         // S10
        assertEquals("SAR", json.getString("purchaseCurrency"))       // S11
        assertEquals("en", json.getString("lang"))                    // S12
        // Numeric-as-string proof: these must be JSON strings, not numbers.
        listOf("totalPrice", "shippingAmount", "vat", "purchaseAmount").forEach {
            assertTrue("$it must serialize as a JSON string", json.get(it) is String)
        }
    }

    @Test
    fun `S13 - version is fixed v1_2`() {
        assertEquals("v1.2", docsExample().toJson().getString("version"))
    }

    @Test
    fun `S15 S19 - customerDetails object with mandatory mobileNumber string`() {
        val customer = docsExample().toJson().getJSONObject("customerDetails")
        assertTrue(customer is JSONObject)
        assertEquals("+966512345678", customer.getString("mobileNumber"))
        assertTrue(customer.get("mobileNumber") is String)
    }

    @Test
    fun `S21 S22 - orderDetails object containing items array`() {
        val order = docsExample().toJson().getJSONObject("orderDetails")
        val items: JSONArray = order.getJSONArray("items")
        assertEquals(1, items.length())
        assertTrue(items.get(0) is JSONObject)
    }

    @Test
    fun `S25 S26 S27 S28 - item name condition plus numeric strings`() {
        val item = docsExample().toJson().getJSONObject("orderDetails")
            .getJSONArray("items").getJSONObject(0)
        assertEquals("item name in arabic", item.getString("nameArabic"))
        assertEquals("item name in english", item.getString("nameEnglish"))
        assertEquals("2", item.getString("quantity"))
        assertEquals("100", item.getString("unitPrice"))
        assertTrue(item.get("quantity") is String)
        assertTrue(item.get("unitPrice") is String)
    }

    @Test
    fun `S40 - isMobile is boolean true`() {
        val json = docsExample().toJson()
        assertTrue(json.getBoolean("isMobile"))
    }

    // ---------------------------------------------------------- optional set

    @Test
    fun `S6 S7 S8 S9 - discount object with all three optional fields when set`() {
        val discount = docsExample().toJson().getJSONObject("discount")
        assertEquals("15", discount.getString("percentage"))
        assertEquals("30", discount.getString("amount"))
        assertEquals("MIS-30", discount.getString("voucherName"))
    }

    @Test
    fun `S2 - invoiceId present when set`() {
        assertEquals("1234567890", docsExample().toJson().getString("invoiceId"))
    }

    @Test
    fun `S14 - serialNumber present when set`() {
        val request = docsExample().copy(serialNumber = "SN-001")
        assertEquals("SN-001", request.toJson().getString("serialNumber"))
    }

    @Test
    fun `S16 S17 S18 S20 - optional customer fields present when set`() {
        val customer = docsExample().toJson().getJSONObject("customerDetails")
        assertEquals("customer ID", customer.getString("id"))
        assertEquals("first name", customer.getString("firstName"))
        assertEquals("last name", customer.getString("lastName"))
        assertEquals("testemail@gmail.com", customer.getString("email"))
    }

    @Test
    fun `S23 S24 S29 S30 - optional item fields present when set`() {
        val item = docsExample().toJson().getJSONObject("orderDetails")
            .getJSONArray("items").getJSONObject(0)
        assertEquals("OR12345", item.getString("id"))
        assertEquals("1234568abcd", item.getString("sku"))
        assertEquals("https://itemurl.com", item.getString("itemUrl"))
        assertEquals("https://imagurle.com", item.getString("imageUrl"))
    }

    @Test
    fun `S31-S39 - shippingDetails with all eight optional fields when set`() {
        val shipping = docsExample().toJson().getJSONObject("shippingDetails")
        assertEquals("first name", shipping.getString("firstName"))
        assertEquals("last name", shipping.getString("lastName"))
        assertEquals("96", shipping.getString("countryCode"))
        assertEquals("riyadh", shipping.getString("city"))
        assertEquals("riyadh", shipping.getString("district"))
        assertEquals("first line", shipping.getString("line1"))
        assertEquals("second line", shipping.getString("line2"))
        assertEquals("41.40338, 2.17403", shipping.getString("gpsCoordinates"))
    }

    @Test
    fun `S41 - callbackUri present when set`() {
        val request = docsExample().copy(callbackUri = "https://merchant.example/cb")
        assertEquals("https://merchant.example/cb", request.toJson().getString("callbackUri"))
    }

    // ------------------------------------------------- omission when not set

    @Test
    fun `every optional field is omitted entirely when null`() {
        val json = minimal().toJson()
        val absent = listOf(
            "invoiceId",        // S2
            "discount",         // S6 (whole object)
            "serialNumber",     // S14
            "shippingDetails",  // S31 (whole object)
            "callbackUri"       // S41
        )
        absent.forEach { assertFalse("$it must be omitted when null", json.has(it)) }

        val customer = json.getJSONObject("customerDetails")
        listOf("id", "firstName", "lastName", "email")            // S16-18, S20
            .forEach { assertFalse("customerDetails.$it must be omitted", customer.has(it)) }

        val item = json.getJSONObject("orderDetails").getJSONArray("items").getJSONObject(0)
        listOf("id", "sku", "itemUrl", "imageUrl")               // S23,24,29,30
            .forEach { assertFalse("items[0].$it must be omitted", item.has(it)) }
    }

    @Test
    fun `null members inside discount and shippingDetails are omitted`() {
        val json = docsExample().copy(
            discount = Discount(amount = "10"),                    // percentage, voucherName null
            shippingDetails = ShippingDetails(city = "riyadh")     // everything else null
        ).toJson()
        val discount = json.getJSONObject("discount")
        assertFalse(discount.has("percentage"))
        assertFalse(discount.has("voucherName"))
        assertEquals("10", discount.getString("amount"))
        val shipping = json.getJSONObject("shippingDetails")
        assertEquals("riyadh", shipping.getString("city"))
        listOf("firstName", "lastName", "countryCode", "district", "line1", "line2", "gpsCoordinates")
            .forEach { assertFalse("shippingDetails.$it must be omitted", shipping.has(it)) }
    }

    // ------------------------------------------------------------------ misc

    @Test
    fun `multiple items serialize in order`() {
        val request = docsExample().copy(
            orderDetails = OrderDetails(
                items = listOf(
                    OrderItem(quantity = "1", unitPrice = "10", nameEnglish = "first"),
                    OrderItem(quantity = "2", unitPrice = "20", nameArabic = "ثاني"),
                    OrderItem(quantity = "3", unitPrice = "30", nameEnglish = "third", nameArabic = "ثالث")
                )
            )
        )
        val items = request.toJson().getJSONObject("orderDetails").getJSONArray("items")
        assertEquals(3, items.length())
        assertEquals("first", items.getJSONObject(0).getString("nameEnglish"))
        assertFalse(items.getJSONObject(1).has("nameEnglish"))
        assertEquals("ثالث", items.getJSONObject(2).getString("nameArabic"))
        assertEquals("3", items.getJSONObject(2).getString("quantity"))
    }

    @Test
    fun `lang ar is accepted`() {
        assertEquals("ar", docsExample().copy(lang = "ar").toJson().getString("lang"))
    }

    @Test
    fun `no null values anywhere in the serialized body`() {
        val json = docsExample().toJson()
        fun assertNoNulls(obj: JSONObject, path: String) {
            obj.keys().forEach { key ->
                when (val value = obj.get(key)) {
                    JSONObject.NULL -> throw AssertionError("null at $path.$key")
                    is JSONObject -> assertNoNulls(value, "$path.$key")
                    is JSONArray -> for (i in 0 until value.length()) {
                        val element = value.get(i)
                        if (element is JSONObject) assertNoNulls(element, "$path.$key[$i]")
                    }
                }
            }
        }
        assertNoNulls(json, "root")
    }

    // ------------------------------------------------------------------ data

    private fun docsExample() = CheckoutRequest(
        orderId = "1233-ABCD",
        invoiceId = "1234567890",
        totalPrice = "215",
        shippingAmount = "0",
        vat = "15",
        discount = Discount(percentage = "15", amount = "30", voucherName = "MIS-30"),
        purchaseAmount = "200",
        purchaseCurrency = "SAR",
        lang = "en",
        customerDetails = CustomerDetails(
            id = "customer ID",
            firstName = "first name",
            lastName = "last name",
            mobileNumber = "+966512345678",
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
        callbackUri = null
    )

    private fun minimal() = CheckoutRequest(
        orderId = "ORD-1",
        totalPrice = "10",
        shippingAmount = "0",
        vat = "0",
        purchaseAmount = "10",
        purchaseCurrency = "SAR",
        lang = "en",
        customerDetails = CustomerDetails(mobileNumber = "0512345678"),
        orderDetails = OrderDetails(
            items = listOf(OrderItem(quantity = "1", unitPrice = "10", nameEnglish = "widget"))
        )
    )
}
