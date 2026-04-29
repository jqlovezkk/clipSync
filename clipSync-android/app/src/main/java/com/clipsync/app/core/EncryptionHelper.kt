package com.clipsync.app.core

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CBC encryption/decryption helper.
 *
 * Key derivation: PBKDF2-SHA256(password, salt, 10000 iterations, 32 bytes)
 * IV: 16 bytes random
 * Padding: PKCS#7
 *
 * Encrypted payload format: base64(IV + ciphertext)
 */
object EncryptionHelper {

    private const val TAG = "EncryptionHelper"
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val KEY_FACTORY_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256
    private const val IV_LENGTH = 16
    private const val SALT_LENGTH = 16

    // Default encryption password - in production, this should be user-configurable
    private var encryptionPassword: String = "clipsync-default-password-change-me"

    /**
     * Set the encryption password.
     */
    fun setPassword(password: String) {
        encryptionPassword = password
    }

    /**
     * Encrypt plaintext content.
     * Returns string in format: base64(salt):base64(IV + ciphertext)
     * This format is compatible across all ClipSync clients.
     *
     * IMPORTANT: If encryption fails, throws an exception instead of returning plaintext.
     * Callers must handle the error appropriately.
     */
    fun encrypt(plaintext: String): String {
        val salt = generateSalt()
        val key = deriveKey(encryptionPassword, salt)
        val iv = generateIv()

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)

        val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivAndCiphertext = iv.iv + encryptedBytes

        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val encryptedB64 = Base64.encodeToString(ivAndCiphertext, Base64.NO_WRAP)
        return "$saltB64:$encryptedB64"
    }

    /**
     * Decrypt encrypted content.
     * Expects format: base64(salt):base64(IV + ciphertext)
     * Returns null if decryption fails (instead of returning raw data).
     */
    fun decrypt(encryptedData: String): String? {
        return try {
            val parts = encryptedData.split(":")
            if (parts.size != 2) {
                FileLogger.e(TAG, "Invalid encrypted data format: expected 'salt:content'")
                return null
            }

            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val ivAndCiphertext = Base64.decode(parts[1], Base64.NO_WRAP)

            if (ivAndCiphertext.size < IV_LENGTH) {
                FileLogger.e(TAG, "Ciphertext too short")
                return null
            }

            val ivBytes = ivAndCiphertext.copyOfRange(0, IV_LENGTH)
            val ciphertext = ivAndCiphertext.copyOfRange(IV_LENGTH, ivAndCiphertext.size)

            val key = deriveKey(encryptionPassword, salt)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(ivBytes))

            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Decryption failed", e)
            null
        }
    }

    /**
     * Calculate SHA-256 checksum of content for deduplication.
     */
    fun calculateChecksum(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Calculate SHA-256 checksum of raw bytes for image deduplication.
     */
    fun computeChecksum(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun generateIv(): IvParameterSpec {
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)
        return IvParameterSpec(iv)
    }

    /**
     * Derive an AES-256 key from password and salt using PBKDF2.
     */
    private fun deriveKey(password: String, salt: ByteArray): javax.crypto.spec.SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(KEY_FACTORY_ALGORITHM)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, ALGORITHM)
    }

    /**
     * Encrypt with salt included in the output for proper transmission.
     * Format: base64(salt):base64(IV + ciphertext)
     *
     * This is now the standard encryption method, same as encrypt().
     * Kept for backward compatibility with callers.
     */
    fun encryptWithSalt(plaintext: String): String {
        return encrypt(plaintext)
    }

    /**
     * Decrypt content that was encrypted with encryptWithSalt/encrypt.
     * Format: base64(salt):base64(IV + ciphertext)
     *
     * This is now the standard decryption method, same as decrypt().
     * Kept for backward compatibility with callers.
     */
    fun decryptWithSalt(encryptedData: String): String? {
        return decrypt(encryptedData)
    }
}
