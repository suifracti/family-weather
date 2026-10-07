/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle.util

import java.security.MessageDigest

object Sha256Util {

    /**
     * Calculates the SHA-256 hash of a byte array and returns it as a lowercase hex string.
     */
    fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Calculates the SHA-256 hash of a UTF-8 string and returns it as a lowercase hex string.
     */
    fun calculateSha256(content: String): String {
        return calculateSha256(content.toByteArray(Charsets.UTF_8))
    }
}
