package com.finbyte.mispaysdk.model

import com.finbyte.mispaysdk.MISPayLog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CheckoutRequestTest {

    @Before
    fun setUp() {
        // Keep android.util.Log out of local JVM tests.
        MISPayLog.enabled = false
    }

    @Test
    fun `toJson matches the documented example field for field`() {
        val json = docsExampleRequest().toJson()

        assertEquals("1233-ABCD", json.getString("orderId"))
        assertEquals("1234567890", json.getString("invoiceId"))
        assertEquals("215", json.getString("totalPrice"))
        assertEquals("0", json.getString("shippingAmount"))
        assertEquals("15", json.getString("vat"))

        val discount = json.getJSONObject("discount")
        assertEquals("15", discount.getString("percentage"))
        assertEquals("30", discount.getString("amount"))
        assertEquals("MIS-30", discount.getString("voucherName"))

        assertEquals("200", json.getString("purchaseAmount"))
        assertEquals("SAR", json.getString("purchaseCurrency"))
        assertEquals("en", json.getString("lang"))
        assertEquals("v1.2", json.getString("version"))

        val customer = json.getJSONObject("customerDetails")
        assertEquals("customer ID", customer.getString("id"))
        assertEquals("first name", customer.getString("firstName"))
        assertEquals("last name", customer.getString("lastName"))
        assertEquals("+966512345678", customer.getString("mobileNumber"))
        assertEquals("testemail@gmail.com", customer.getString("email"))

        val item = json.getJSONObject("orderDetails").getJSONArray("items").getJSONObject(0)
        assertEquals("OR12345", item.getString("id"))
        assertEquals("1234568abcd", item.getString("sku"))
        assertEquals("item name in arabic", item.getString("nameArabic"))
        assertEquals("item name in english", item.getString("nameEnglish"))
        assertEquals("2", item.getString("quantity"))
        assertEquals("100", item.getString("unitPrice"))
        assertEquals("https://itemurl.com", item.getString("itemUrl"))
        assertEquals("https://imagurle.com", item.getString("imageUrl"))

        val shipping = json.getJSONObject("shippingDetails")
        assertEquals("first name", shipping.getString("firstName"))
        assertEquals("last name", shipping.getString("lastName"))
        assertEquals("96", shipping.getString("countryCode"))
        assertEquals("riyadh", shipping.getString("city"))
        assertEquals("riyadh", shipping.getString("district"))
        assertEquals("first line", shipping.getString("line1"))
        assertEquals("second line", shipping.getString("line2"))
        assertEquals("41.40338, 2.17403", shipping.getString("gpsCoordinates"))

        assertTrue(json.getBoolean("isMobile"))
    }

    @Test
    fun `null optional fields are omitted entirely`() {
        val json = minimalRequest().toJson()

        assertFalse(json.has("discount"))
        assertFalse(json.has("shippingDetails"))
        assertFalse(json.has("invoiceId"))
        assertFalse(json.has("serialNumber"))
        assertFalse(json.has("callbackUri"))

        val item = json.getJSONObject("orderDetails").getJSONArray("items").getJSONObject(0)
        assertFalse(item.has("id"))
        assertFalse(item.has("sku"))
        assertFalse(item.has("nameArabic"))
        assertFalse(item.has("itemUrl"))
        assertFalse(item.has("imageUrl"))
    }

    @Test
    fun `accepted mobile number formats validate`() {
        listOf("0512345678", "966512345678", "+966512345678").forEach { mobile ->
            minimalRequest(mobileNumber = mobile).validate()
        }
    }

    @Test
    fun `malformed mobile number is rejected with accepted formats in message`() {
        try {
            minimalRequest(mobileNumber = "12345").validate()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("mobileNumber"))
        }
    }

    @Test
    fun `blank orderId is rejected`() {
        try {
            docsExampleRequest().copy(orderId = " ").validate()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("orderId"))
        }
    }

    @Test
    fun `non-numeric amount is rejected`() {
        try {
            docsExampleRequest().copy(totalPrice = "12a").validate()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("totalPrice"))
        }
    }

    @Test
    fun `unsupported language is rejected`() {
        try {
            docsExampleRequest().copy(lang = "fr").validate()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("lang"))
        }
    }

    @Test
    fun `item without any name is rejected`() {
        val request = docsExampleRequest().copy(
            orderDetails = OrderDetails(
                items = listOf(
                    OrderItem(quantity = "1", unitPrice = "10", nameArabic = null, nameEnglish = null)
                )
            )
        )
        try {
            request.validate()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("items[0]"))
        }
    }

    @Test
    fun `empty item list is rejected`() {
        try {
            docsExampleRequest().copy(orderDetails = OrderDetails(items = emptyList())).validate()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("items"))
        }
    }

    @Test
    fun `non-http callbackUri is rejected`() {
        try {
            docsExampleRequest().copy(callbackUri = "ftp://example.com/cb").validate()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("callbackUri"))
        }
    }

    @Test
    fun `documented example amounts are consistent`() {
        assertNull(docsExampleRequest().amountConsistencyIssue())
    }

    @Test
    fun `inconsistent purchaseAmount warns but never rejects`() {
        val request = docsExampleRequest().copy(purchaseAmount = "999")
        assertNotNull(request.amountConsistencyIssue())
        // Still serializes — the mismatch is the merchant's call.
        assertEquals("999", request.toJson().getString("purchaseAmount"))
    }

    // ------------------------------------------------------------------ fixtures

    private fun docsExampleRequest() = CheckoutRequest(
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
        )
    )

    private fun minimalRequest(mobileNumber: String = "0512345678") = CheckoutRequest(
        orderId = "ORD-1",
        totalPrice = "10",
        shippingAmount = "0",
        vat = "0",
        purchaseAmount = "10",
        purchaseCurrency = "SAR",
        lang = "en",
        customerDetails = CustomerDetails(mobileNumber = mobileNumber),
        orderDetails = OrderDetails(
            items = listOf(OrderItem(quantity = "1", unitPrice = "10", nameEnglish = "widget"))
        )
    )
}
