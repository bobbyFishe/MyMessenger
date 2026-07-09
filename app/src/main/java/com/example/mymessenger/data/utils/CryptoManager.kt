package com.example.mymessenger.data.utils

import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import android.util.Base64

object CryptoManager {
    private const val ALGORITHM = "RSA"

    fun encrypt(text: String, publicKeyBase64: String): String {
        if (text.isEmpty() || publicKeyBase64.isEmpty()) return ""
        return try {
            val publicBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val keySpec = X509EncodedKeySpec(publicBytes)
            val kf = KeyFactory.getInstance(ALGORITHM)
            val publicKey = kf.generatePublic(keySpec)

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encryptedBytes = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("CRYPTO_ERR", "Ошибка шифрования: ${e.message}")
            ""
        }
    }

    fun decrypt(encryptedTextBase64: String, privateKeyBase64: String): String {
        if (encryptedTextBase64.isEmpty() || privateKeyBase64.isEmpty()) return ""
        return try {
            val privateBytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
            val keySpec = PKCS8EncodedKeySpec(privateBytes)
            val kf = KeyFactory.getInstance(ALGORITHM)
            val privateKey = kf.generatePrivate(keySpec)

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            val decryptedBytes = cipher.doFinal(Base64.decode(encryptedTextBase64, Base64.NO_WRAP))
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("CRYPTO_ERR", "Ошибка расшифровки: ${e.message}")
            "[Ошибка расшифровки сообщения]"
        }
    }

    fun generateKeyPair(): java.security.KeyPair {
        val keyPairGenerator = java.security.KeyPairGenerator.getInstance(ALGORITHM)
        keyPairGenerator.initialize(2048)
        return keyPairGenerator.generateKeyPair()
    }

    fun publicKeyToString(publicKey: java.security.PublicKey): String {
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    fun privateKeyToString(privateKey: java.security.PrivateKey): String {
        return Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP)
    }

}