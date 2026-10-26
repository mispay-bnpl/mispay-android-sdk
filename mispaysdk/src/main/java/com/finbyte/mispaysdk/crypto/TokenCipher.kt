package com.finbyte.mispaysdk.crypto

import com.finbyte.mispaysdk.MISPayException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Decrypts the encrypted token blob returned by `GET /token` (`result.token` field).
 *
 * CONFIRMED wire format (verified against the official MIS Pay JS and PHP SDKs,
 * doc.mispay.co/docs/sdks — both use identical parameters):
 *
 *   blob = Base64( salt[16] ‖ nonce[12] ‖ ciphertext ‖ tag[16] )
 *   key  = PBKDF2-HMAC-SHA256(password = appSecret UTF-8 bytes,
 *                             salt = salt, iterations = 40 000, dkLen = 32)
 *   AES-256-GCM with the 12-byte nonce and a detached 16-byte tag at the end.
 *
 * PBKDF2 is implemented with [Mac] directly because SecretKeyFactory's
 * "PBKDF2WithHmacSHA256" requires Android API 26+ while the SDK supports API 24+.
 * The implementation is cross-verified against the JDK SecretKeyFactory in unit tests.
 */
internal class TokenCipher {

    /**
     * @param encryptedToken the Base64 blob from the /token response.
     * @param appSecret the merchant application secret (`x-app-secret`).
     * @return the decrypted JSON payload `{"token":"<JWT>","expiration":"…"}`.
     * @throws MISPayException when the blob is not decodable Base64, is too short,
     * or fails GCM authentication (tampered data or wrong secret).
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun decrypt(encryptedToken: String, appSecret: String): String {
        val raw = try {
            Base64.Default.decode(encryptedToken)
        } catch (t: IllegalArgumentException) {
            throw MISPayException(message = "Encrypted token is not valid Base64", cause = t)
        }

        if (raw.size < SALT_LENGTH + NONCE_LENGTH + TAG_LENGTH + 1) {
            throw MISPayException(
                message = "Encrypted token is too short (${raw.size} bytes; " +
                    "expected salt16 + nonce12 + ciphertext + tag16)"
            )
        }

        val salt = raw.copyOfRange(0, SALT_LENGTH)
        val nonce = raw.copyOfRange(SALT_LENGTH, SALT_LENGTH + NONCE_LENGTH)
        val tag = raw.copyOfRange(raw.size - TAG_LENGTH, raw.size)
        val ciphertext = raw.copyOfRange(SALT_LENGTH + NONCE_LENGTH, raw.size - TAG_LENGTH)

        val key = pbkdf2HmacSha256(
            password = appSecret.toByteArray(Charsets.UTF_8),
            salt = salt,
            iterations = PBKDF2_ITERATIONS,
            keyLengthBytes = KEY_LENGTH_BYTES
        )

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_LENGTH_BITS, nonce)
            )
            String(cipher.doFinal(ciphertext + tag), Charsets.UTF_8)
        } catch (e: Exception) {
            val prefix = raw.take(8).joinToString("") { "%02x".format(it) }
            throw MISPayException(
                message = "AES-256-GCM decryption failed " +
                    "(blob ${raw.size} bytes, first bytes $prefix…)",
                cause = e
            )
        }
    }

    /**
     * PBKDF2 (RFC 2898) with HMAC-SHA256, implemented over [Mac] for API-24
     * compatibility. Visible for the cross-verification unit test only.
     */
    internal fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int
    ): ByteArray {
        require(iterations >= 1) { "iterations must be >= 1" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hashLength = mac.macLength // 32 for SHA-256
        val blockCount = (keyLengthBytes + hashLength - 1) / hashLength
        val derived = ByteArray(blockCount * hashLength)

        for (block in 1..blockCount) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (block ushr 24).toByte(),
                    (block ushr 16).toByte(),
                    (block ushr 8).toByte(),
                    block.toByte()
                )
            )
            var u = mac.doFinal()
            val accumulator = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in accumulator.indices) {
                    accumulator[i] = (accumulator[i].toInt() xor u[i].toInt()).toByte()
                }
            }
            accumulator.copyInto(derived, (block - 1) * hashLength)
        }
        return derived.copyOf(keyLengthBytes)
    }

    companion object {
        internal const val PBKDF2_ITERATIONS = 40_000
        private const val SALT_LENGTH = 16
        private const val NONCE_LENGTH = 12
        private const val TAG_LENGTH = 16
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_LENGTH_BYTES = 32
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
