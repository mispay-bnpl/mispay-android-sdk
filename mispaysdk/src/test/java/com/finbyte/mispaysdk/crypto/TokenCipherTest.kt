package com.finbyte.mispaysdk.crypto

import com.finbyte.mispaysdk.MISPayException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class TokenCipherTest {

    private val cipher = TokenCipher()

    // Realistic merchant secret: 64 hex chars.
    private val secret64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val arbitrarySecret = "some-arbitrary-passphrase"

    @Test
    fun `pbkdf2 implementation matches JDK SecretKeyFactory`() {
        val random = Random(42)
        repeat(3) {
            val salt = ByteArray(16).also { random.nextBytes(it) }
            val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(secret64.toCharArray(), salt, 40000, 256))
                .encoded
            val actual = cipher.pbkdf2HmacSha256(
                secret64.toByteArray(Charsets.UTF_8), salt, 40000, 32
            )
            assertArrayEquals("PBKDF2 divergence on salt #$it", expected, actual)
        }
    }

    @Test
    fun `decrypts blob in the confirmed salt-nonce-ct-tag format with 64-hex secret`() {
        val plain = """{"token":"jwt-abc","expiration":"2999-01-01T00:00:00Z"}"""
        val blob = encryptLikeOfficialSdk(plain, secret64)
        assertEquals(plain, cipher.decrypt(blob, secret64))
    }

    @Test
    fun `decrypts blob with an arbitrary passphrase secret`() {
        val plain = """{"token":"jwt-xyz"}"""
        val blob = encryptLikeOfficialSdk(plain, arbitrarySecret)
        assertEquals(plain, cipher.decrypt(blob, arbitrarySecret))
    }

    @Test
    fun `wrong secret fails authentication`() {
        val blob = encryptLikeOfficialSdk("""{"token":"jwt"}""", secret64)
        try {
            cipher.decrypt(blob, "wrong-secret-entirely")
            fail("expected MISPayException")
        } catch (e: MISPayException) {
            assertTrue(e.message!!.contains("decryption failed"))
        }
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val raw = Base64.Default.decode(
            encryptLikeOfficialSdk("""{"token":"jwt"}""", secret64)
        )
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        val tampered = Base64.Default.encode(raw)
        try {
            cipher.decrypt(tampered, secret64)
            fail("expected MISPayException")
        } catch (e: MISPayException) {
            assertTrue(e.message!!.contains("decryption failed"))
        }
    }

    @Test
    fun `rejects blob shorter than salt plus nonce plus tag`() {
        val short = Base64.Default.encode(ByteArray(40) { it.toByte() })
        try {
            cipher.decrypt(short, secret64)
            fail("expected MISPayException")
        } catch (e: MISPayException) {
            assertTrue(e.message!!.contains("too short"))
        }
    }

    @Test
    fun `rejects non-base64 input with clear message`() {
        try {
            cipher.decrypt("!!!not-base64!!!", secret64)
            fail("expected MISPayException")
        } catch (e: MISPayException) {
            assertTrue(e.message!!.contains("Base64"))
        }
    }

    /**
     * Encrypts exactly like the official MIS Pay SDKs:
     * Base64( salt[16] ‖ nonce[12] ‖ ciphertext ‖ tag[16] ) with
     * PBKDF2-HMAC-SHA256(secret, salt, 40 000, 32) as the AES-256-GCM key.
     * The PBKDF2 key here comes from the JDK SecretKeyFactory (not the SDK's own
     * implementation), so a successful decrypt cross-verifies the SDK's PBKDF2 too.
     */
    private fun encryptLikeOfficialSdk(plain: String, secret: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(secret.toCharArray(), salt, 40000, 256))
            .encoded
        val encryptor = Cipher.getInstance("AES/GCM/NoPadding")
        encryptor.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ctAndTag = encryptor.doFinal(plain.toByteArray(Charsets.UTF_8))
        val ct = ctAndTag.copyOfRange(0, ctAndTag.size - 16)
        val tag = ctAndTag.copyOfRange(ctAndTag.size - 16, ctAndTag.size)
        return Base64.Default.encode(salt + nonce + ct + tag)
    }
}
