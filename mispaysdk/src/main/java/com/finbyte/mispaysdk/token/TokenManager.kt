package com.finbyte.mispaysdk.token

import com.finbyte.mispaysdk.crypto.TokenCipher
import com.finbyte.mispaysdk.model.ApiJson
import com.finbyte.mispaysdk.model.DecryptedToken
import com.finbyte.mispaysdk.network.TokenDataSource

/**
 * Fetches, decrypts and caches the /token JWT. Tokens are valid for ~8 hours per the
 * MIS Pay documentation; this manager refreshes proactively (60s before expiry) and
 * on demand after [invalidate] (called by the SDK when the API answers ME10/ME11).
 *
 * The cache is memory-only on purpose: no token or secret is ever persisted to disk.
 */
internal class TokenManager(
    private val dataSource: TokenDataSource,
    private val cipher: TokenCipher = TokenCipher()
) {

    @Volatile
    private var cached: DecryptedToken? = null

    /**
     * Returns a non-expired decrypted token, fetching and decrypting when needed.
     * @throws com.finbyte.mispaysdk.MISPayException on fetch, decrypt or parse failure.
     */
    @Synchronized
    fun getValidToken(appId: String, appSecret: String): DecryptedToken {
        cached?.let { if (!it.isExpired()) return it }
        val encrypted = dataSource.fetchEncryptedToken(appId, appSecret)
        val decryptedJson = cipher.decrypt(encrypted, appSecret)
        val token = ApiJson.parseDecryptedToken(decryptedJson)
        cached = token
        return token
    }

    /** Drops the cached token; the next [getValidToken] call refetches. */
    @Synchronized
    fun invalidate() {
        cached = null
    }
}
