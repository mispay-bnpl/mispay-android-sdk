package com.finbyte.mispaysdk.token

import com.finbyte.mispaysdk.crypto.TokenCipher
import com.finbyte.mispaysdk.model.ApiJson
import com.finbyte.mispaysdk.network.TokenDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class TokenManagerTest {

    private val secret = "0123456789abcdef0123456789abcdef"
    private val cipher = TokenCipher()

    private class FakeDataSource(var blob: String) : TokenDataSource {
        var fetchCount = 0
        var lastAppId: String? = null
        var lastAppSecret: String? = null

        override fun fetchEncryptedToken(appId: String, appSecret: String): String {
            fetchCount++
            lastAppId = appId
            lastAppSecret = appSecret
            return blob
        }
    }

    @Test
    fun `fetches, decrypts and caches a fresh token`() {
        val futureExpiration = ApiJson.formatExpiration(Date(System.currentTimeMillis() + 3_600_000))
        val source = FakeDataSource(encrypted("""{"token":"jwt-1","expiration":"$futureExpiration"}"""))
        val manager = TokenManager(source, cipher)

        val first = manager.getValidToken("app-id", secret)
        assertEquals("jwt-1", first.token)
        val second = manager.getValidToken("app-id", secret)

        assertEquals("cached token must be returned without a second fetch", 1, source.fetchCount)
        assertEquals("jwt-1", second.token)
        assertEquals("app-id", source.lastAppId)
        assertEquals(secret, source.lastAppSecret)
    }

    @Test
    fun `expired token is refetched`() {
        val source = FakeDataSource(
            encrypted("""{"token":"jwt-old","expiration":"2000-01-01T00:00:00Z"}""")
        )
        val manager = TokenManager(source, cipher)

        manager.getValidToken("app-id", secret)
        manager.getValidToken("app-id", secret)

        assertEquals("expired token must trigger a refetch", 2, source.fetchCount)
    }

    @Test
    fun `token without expiration is never cached`() {
        val source = FakeDataSource(encrypted("""{"token":"jwt-no-exp"}"""))
        val manager = TokenManager(source, cipher)

        manager.getValidToken("app-id", secret)
        manager.getValidToken("app-id", secret)

        assertEquals("null expiration must be treated as expired", 2, source.fetchCount)
    }

    @Test
    fun `invalidate forces a refetch even when cached token is fresh`() {
        val futureExpiration = ApiJson.formatExpiration(Date(System.currentTimeMillis() + 3_600_000))
        val source = FakeDataSource(encrypted("""{"token":"jwt-1","expiration":"$futureExpiration"}"""))
        val manager = TokenManager(source, cipher)

        manager.getValidToken("app-id", secret)
        manager.invalidate()
        val second = manager.getValidToken("app-id", secret)

        assertEquals(2, source.fetchCount)
        assertEquals("jwt-1", second.token)
    }

    /** Encrypts plain JSON exactly like the official MIS Pay SDKs (salt‖nonce‖ct‖tag, PBKDF2-40k). */
    private fun encrypted(plainJson: String): String {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(
                javax.crypto.spec.PBEKeySpec(secret.toCharArray(), salt, 40_000, 256)
            ).encoded
        val encryptor = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        encryptor.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, nonce)
        )
        val ctAndTag = encryptor.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        val ct = ctAndTag.copyOfRange(0, ctAndTag.size - 16)
        val tag = ctAndTag.copyOfRange(ctAndTag.size - 16, ctAndTag.size)
        return Base64.Default.encode(salt + nonce + ct + tag)
    }
}
