package com.example.wavechat

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

object CryptoUtils {

    private const val ALGORITHM = "RSA"
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"
    private const val PREFS_NAME = "wavechat_crypto"
    private const val KEY_PUBLIC = "public_key"
    private const val KEY_PRIVATE = "private_key"

    private var keyPair: KeyPair? = null

    /**
     * Initializes the key pair. Loads from SharedPreferences or generates a new one.
     */
    fun initKeys(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pubBase64 = prefs.getString(KEY_PUBLIC, null)
        val privBase64 = prefs.getString(KEY_PRIVATE, null)

        if (pubBase64 != null && privBase64 != null) {
            try {
                val pubKey = stringToPublicKey(pubBase64)
                val privKey = stringToPrivateKey(privBase64)
                keyPair = KeyPair(pubKey, privKey)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Generate new keys
        val generator = KeyPairGenerator.getInstance(ALGORITHM)
        generator.initialize(2048)
        keyPair = generator.generateKeyPair()

        // Save to SharedPreferences
        prefs.edit()
            .putString(KEY_PUBLIC, publicKeyToString(keyPair!!.public))
            .putString(KEY_PRIVATE, privateKeyToString(keyPair!!.private))
            .apply()
    }

    /** Returns the device's public key as a Base64 string to be broadcasted. */
    fun getMyPublicKeyString(): String? {
        return keyPair?.public?.let { publicKeyToString(it) }
    }

    /** Encrypts a plain text message using the recipient's public key (Base64 string). */
    fun encrypt(plainText: String, recipientPublicKeyBase64: String): String {
        try {
            val pubKey = stringToPublicKey(recipientPublicKeyBase64)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, pubKey)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            throw IllegalArgumentException("Encryption failed", e)
        }
    }

    /** Decrypts a Base64 encrypted message using our private key. */
    fun decrypt(encryptedTextBase64: String): String {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyPair!!.private)
            val decodedBytes = Base64.decode(encryptedTextBase64, Base64.NO_WRAP)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            throw IllegalArgumentException("Decryption failed", e)
        }
    }

    fun publicKeyToString(publicKey: PublicKey): String {
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    fun privateKeyToString(privateKey: PrivateKey): String {
        return Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP)
    }

    fun stringToPublicKey(keyStr: String): PublicKey {
        val keyBytes = Base64.decode(keyStr, Base64.NO_WRAP)
        val spec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance(ALGORITHM)
        return keyFactory.generatePublic(spec)
    }

    private fun stringToPrivateKey(keyStr: String): PrivateKey {
        val keyBytes = Base64.decode(keyStr, Base64.NO_WRAP)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance(ALGORITHM)
        return keyFactory.generatePrivate(spec)
    }
}
