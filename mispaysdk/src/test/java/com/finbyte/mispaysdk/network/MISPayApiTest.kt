package com.finbyte.mispaysdk.network

import com.finbyte.mispaysdk.MISPayException
import com.finbyte.mispaysdk.model.CheckoutRequest
import com.finbyte.mispaysdk.model.CustomerDetails
import com.finbyte.mispaysdk.model.OrderDetails
import com.finbyte.mispaysdk.model.OrderItem
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class MISPayApiTest {

    private lateinit var server: MiniHttpServer
    private lateinit var api: MISPayApi

    private val headerFailures = mutableListOf<String>()

    @Before
    fun setUp() {
        com.finbyte.mispaysdk.MISPayLog.enabled = false
        server = MiniHttpServer().also { it.start() }
        api = MISPayApi("http://localhost:${server.port}/v1/api/")
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ------------------------------------------------------------------ /token

    @Test
    fun `fetchEncryptedToken sends GET with credential headers and returns the blob`() {
        server.handler = { method, path, headers, _ ->
            expectHeader(method, "GET")
            expectHeader(path, "/v1/api/token")
            expectHeader(headers["x-app-id"], "app-id-1")
            expectHeader(headers["x-app-secret"], "secret-1")
            MiniHttpServer.Response(
                200,
                """{"status":"success","result":{"token":"BLOB123"}}"""
            )
        }

        val blob = api.fetchEncryptedToken("app-id-1", "secret-1")

        assertEquals("BLOB123", blob)
        assertTrue("request assertions failed: $headerFailures", headerFailures.isEmpty())
    }

    @Test
    fun `token error with string result maps to ME code and http status`() {
        server.handler = { _, _, _, _ ->
            MiniHttpServer.Response(
                400,
                """{"status":"error : 400","result":"ExceptionType.ME01"}"""
            )
        }

        try {
            api.fetchEncryptedToken("app-id-1", "secret-1")
            fail("expected MISPayException")
        } catch (e: MISPayException) {
            assertEquals("ME01", e.meCode)
            assertEquals(400, e.httpStatus)
            assertTrue(e.message!!.contains("ME01"))
        }
    }

    @Test
    fun `success status with missing token field is a typed error`() {
        server.handler = { _, _, _, _ ->
            MiniHttpServer.Response(200, """{"status":"success","result":{}}""")
        }

        try {
            api.fetchEncryptedToken("app-id-1", "secret-1")
            fail("expected MISPayException")
        } catch (e: MISPayException) {
            assertTrue(e.message!!.contains("result.token"))
        }
    }

    // ---------------------------------------------------------- /start-checkout

    @Test
    fun `startCheckout sends POST with bearer and x-app-id and documented body`() {
        server.handler = { method, path, headers, body ->
            expectHeader(method, "POST")
            expectHeader(path, "/v1/api/start-checkout")
            expectHeader(headers["authorization"], "Bearer jwt-abc")
            expectHeader(headers["x-app-id"], "app-id-1")

            val parsed = JSONObject(body)
            expectHeader(parsed.getString("orderId"), "ORD-1")
            expectHeader(parsed.getString("version"), "v1.2")
            expectHeader(parsed.getBoolean("isMobile"), true)
            expectHeader(
                parsed.getJSONObject("customerDetails").getString("mobileNumber"),
                "+966512345678"
            )
            expectHeader(
                parsed.getJSONObject("orderDetails").getJSONArray("items")
                    .getJSONObject(0).getString("quantity"),
                "2"
            )

            MiniHttpServer.Response(
                200,
                """{"status":"success","result":{"url":"https://checkout.example/x","trackId":"tr-1"}}"""
            )
        }

        val result = api.startCheckout("app-id-1", "jwt-abc", sampleRequest().toJson().toString())

        assertEquals("https://checkout.example/x", result.url)
        assertEquals("tr-1", result.trackId)
        assertTrue("request assertions failed: $headerFailures", headerFailures.isEmpty())
    }

    @Test
    fun `start-checkout ME10 object error maps to typed exception`() {
        server.handler = { _, _, _, _ ->
            MiniHttpServer.Response(
                401,
                """{"status":"error","result":{"code":401,"message":"ME10"}}"""
            )
        }

        try {
            api.startCheckout("app-id-1", "jwt-abc", sampleRequest().toJson().toString())
            fail("expected MISPayException")
        } catch (e: MISPayException) {
            assertEquals("ME10", e.meCode)
            assertEquals(401, e.httpStatus)
        }
    }

    @Test
    fun `non-JSON error body maps to typed exception with status and snippet`() {
        server.handler = { _, _, _, _ ->
            MiniHttpServer.Response(502, "<html>Bad gateway</html>")
        }

        try {
            api.fetchEncryptedToken("app-id-1", "secret-1")
            fail("expected MISPayException")
        } catch (e: MISPayException) {
            assertEquals(502, e.httpStatus)
            assertEquals(null, e.meCode)
            assertTrue(e.message!!.contains("Bad gateway"))
        }
    }

    @Test
    fun `connection failure wraps into MISPayException`() {
        // Start and immediately stop a server so the port refuses connections.
        val deadServer = MiniHttpServer().also { it.start() }
        val port = deadServer.port
        deadServer.stop()
        val deadApi = MISPayApi("http://localhost:$port/v1/api/")

        try {
            deadApi.fetchEncryptedToken("app-id-1", "secret-1")
            fail("expected MISPayException")
        } catch (e: MISPayException) {
            assertTrue(e.message!!.contains("Network error"))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun expectHeader(actual: Any?, expected: Any?) {
        if (actual != expected) {
            headerFailures.add("expected '$expected', got '$actual'")
        }
    }

    private fun sampleRequest() = CheckoutRequest(
        orderId = "ORD-1",
        totalPrice = "215",
        shippingAmount = "0",
        vat = "15",
        purchaseAmount = "230", // consistent with totalPrice+shippingAmount+vat (no discount)
        purchaseCurrency = "SAR",
        lang = "en",
        customerDetails = CustomerDetails(mobileNumber = "+966512345678"),
        orderDetails = OrderDetails(
            items = listOf(
                OrderItem(quantity = "2", unitPrice = "100", nameEnglish = "item name in english")
            )
        )
    )

    /**
     * Minimal dependency-free HTTP/1.1 mock server for local unit tests.
     * (com.sun.net.httpserver is not on the AGP unit-test compile classpath.)
     * Serves one request per connection; responds with Connection: close.
     */
    private class MiniHttpServer {

        data class Response(val status: Int, val body: String)

        var handler: (method: String, path: String, headers: Map<String, String>, body: String) -> Response =
            { _, _, _, _ -> Response(404, "{}") }

        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("localhost"))
        val port: Int get() = serverSocket.localPort

        @Volatile
        private var running = true

        private val acceptThread = Thread({ loop() }, "MiniHttpServer").apply { isDaemon = true }

        fun start() {
            acceptThread.start()
        }

        fun stop() {
            running = false
            runCatching { serverSocket.close() }
        }

        private fun loop() {
            while (running) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                handle(socket)
            }
        }

        private fun handle(socket: Socket) {
            socket.use { s ->
                try {
                    s.soTimeout = 10_000
                    val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    val requestLine = input.readLine() ?: return
                    val parts = requestLine.split(" ")
                    val method = parts.getOrNull(0).orEmpty()
                    val path = parts.getOrNull(1).orEmpty()

                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) {
                            headers[line.substring(0, separator).trim().lowercase()] =
                                line.substring(separator + 1).trim()
                        }
                    }

                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyChars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(bodyChars, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    val body = String(bodyChars, 0, read)

                    val response = handler(method, path, headers.toMap(), body)
                    val bytes = response.body.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 ${response.status} ${reason(response.status)}\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    val output = s.getOutputStream()
                    output.write(head.toByteArray(Charsets.UTF_8))
                    output.write(bytes)
                    output.flush()
                } catch (_: Exception) {
                    // Connection-level failures end the exchange silently.
                }
            }
        }

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            else -> "Status"
        }
    }
}
