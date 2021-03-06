/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.sessions

import io.ktor.util.*
import org.slf4j.*
import java.security.*
import javax.crypto.*
import javax.crypto.spec.*

/**
 * Session transformer that encrypts/decrypts the input.
 *
 * Where the input is either a session contents or a previous transformation.
 *
 * It encrypts/decrypts the input with an [encryptAlgorithm] and an [encryptionKeySpec]
 * and includes an authenticated MAC (Message Authentication Code) hash with [signAlgorithm] and a [signKeySpec]
 * and includes an IV (Initialization Vector) that is generated by an [ivGenerator] by default secure random bytes.
 *
 * By default it uses AES for encryption and HmacSHA256 for authenticating.
 *
 * You have to provide keys of compatible sizes: 16, 24 and 32 for AES encryption.
 * For HmacSHA256 it is recommended a key of 32 bytes.
 *
 * @property encryptionKeySpec is a secret key that is used for encryption
 * @property signKeySpec is a secret key that is used for signing
 * @property ivGenerator is a function that generates input vectors
 * @property encryptAlgorithm is an encryption algorithm name
 * @property signAlgorithm is a signing algorithm name
 */
class SessionTransportTransformerEncrypt(
    val encryptionKeySpec: SecretKeySpec,
    val signKeySpec: SecretKeySpec,
    val ivGenerator: (size: Int) -> ByteArray = { size -> SecureRandom().generateSeed(size) },
    val encryptAlgorithm: String = encryptionKeySpec.algorithm,
    val signAlgorithm: String = signKeySpec.algorithm
) : SessionTransportTransformer {
    companion object {
        private val log = LoggerFactory.getLogger(SessionTransportTransformerEncrypt::class.qualifiedName)
    }

    private val charset = Charsets.UTF_8

    /**
     * Encryption key size in bytes
     */
    val encryptionKeySize: Int get() = encryptionKeySpec.encoded.size

    // Check that input keys are right
    init {
        encrypt(ivGenerator(encryptionKeySize), byteArrayOf())
        mac(byteArrayOf())
    }

    constructor(
        encryptionKey: ByteArray,
        signKey: ByteArray,
        ivGenerator: (size: Int) -> ByteArray = { size -> SecureRandom().generateSeed(size) },
        encryptAlgorithm: String = "AES",
        signAlgorithm: String = "HmacSHA256"
    ) : this(
        SecretKeySpec(encryptionKey, encryptAlgorithm),
        SecretKeySpec(signKey, signAlgorithm),
        ivGenerator
    )

    override fun transformRead(transportValue: String): String? {
        try {
            val encrypedMac = transportValue.substringAfterLast('/', "")
            val iv = hex(transportValue.substringBeforeLast('/'))
            val encrypted = hex(encrypedMac.substringBeforeLast(':'))
            val macHex = encrypedMac.substringAfterLast(':', "")
            val decrypted = decrypt(iv, encrypted)

            if (hex(mac(decrypted)) != macHex) {
                return null
            }

            return decrypted.toString(charset)
        } catch (e: Throwable) {
            // NumberFormatException // Invalid hex
            // InvalidAlgorithmParameterException // Invalid data
            if (log.isDebugEnabled) {
                log.debug(e.toString())
            }
            return null
        }
    }

    override fun transformWrite(transportValue: String): String {
        val iv = ivGenerator(encryptionKeySize)
        val decrypted = transportValue.toByteArray(charset)
        val encrypted = encrypt(iv, decrypted)
        val mac = mac(decrypted)
        return "${hex(iv)}/${hex(encrypted)}:${hex(mac)}"
    }

    private fun encrypt(initVector: ByteArray, decrypted: ByteArray): ByteArray {
        return encryptDecrypt(Cipher.ENCRYPT_MODE, initVector, decrypted)
    }

    private fun decrypt(initVector: ByteArray, encrypted: ByteArray): ByteArray {
        return encryptDecrypt(Cipher.DECRYPT_MODE, initVector, encrypted)
    }

    private fun encryptDecrypt(mode: Int, initVector: ByteArray, input: ByteArray): ByteArray {
        val iv = IvParameterSpec(initVector)
        val cipher = Cipher.getInstance("$encryptAlgorithm/CBC/PKCS5PADDING")
        cipher.init(mode, encryptionKeySpec, iv)
        return cipher.doFinal(input)
    }

    private fun mac(value: ByteArray): ByteArray = Mac.getInstance(signAlgorithm).run {
        init(signKeySpec)
        doFinal(value)
    }
}
