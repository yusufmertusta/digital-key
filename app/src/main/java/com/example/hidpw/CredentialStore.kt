package com.example.hidpw

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Kullanıcı adı/şifreyi Android Keystore'daki AES-GCM anahtarıyla şifreler.
 * Anahtar her kullanımda biyometrik doğrulama ister (CryptoObject ile).
 */
object CredentialStore {
    private const val ALIAS = "hidpw_key"
    private const val PREFS = "hidpw"
    private const val K_IV = "iv"
    private const val K_DATA = "data"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }

    fun hasSaved(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(K_DATA)

    /** Biyometrik istemine verilecek şifreleme cipher'ı. */
    fun encryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }

    /** Biyometrik istemine verilecek çözme cipher'ı. Kayıt yoksa veya anahtar geçersizse hata fırlatır. */
    fun decryptCipher(context: Context): Cipher {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = Base64.decode(prefs.getString(K_IV, null)!!, Base64.NO_WRAP)
        return Cipher.getInstance(TRANSFORM)
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
    }

    /** Doğrulanmış cipher ile şifreler ve kaydeder. */
    fun save(context: Context, cipher: Cipher, plain: ByteArray) {
        val ct = cipher.doFinal(plain)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(K_DATA, Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    /** Doğrulanmış cipher ile kayıtlı veriyi çözer. */
    fun decrypt(context: Context, cipher: Cipher): ByteArray {
        val data = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_DATA, null)!!
        return cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
