package com.example.hidpw

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Site kayıtlarını saklar. Kullanıcı adı/şifre, Android Keystore'daki AES-GCM anahtarıyla
 * şifrelenir; anahtar her kullanımda biyometrik doğrulama ister (CryptoObject ile).
 * Etiket ve site adresi (şifre değil) açık tutulur ki servis site eşleştirebilsin.
 */
object CredentialStore {
    private const val ALIAS = "hidpw_key"
    private const val PREFS = "hidpw_entries"
    private const val K_LIST = "list"
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

    fun list(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    host = o.getString("host"),
                    iv = o.getString("iv"),
                    data = o.getString("data"),
                    enter = o.optBoolean("enter", true)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveList(context: Context, items: List<Entry>) {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("label", e.label)
                    .put("host", e.host)
                    .put("iv", e.iv)
                    .put("data", e.data)
                    .put("enter", e.enter)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_LIST, arr.toString())
            .apply()
    }

    /** Biyometrik istemine verilecek şifreleme cipher'ı. */
    fun encryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }

    /** Biyometrik istemine verilecek çözme cipher'ı. Anahtar geçersizse hata fırlatır. */
    fun decryptCipher(entry: Entry): Cipher {
        val iv = Base64.decode(entry.iv, Base64.NO_WRAP)
        return Cipher.getInstance(TRANSFORM)
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
    }

    /** Doğrulanmış cipher ile şifreleyip ekler. Aynı site için eski kayıt değiştirilir. */
    fun add(
        context: Context,
        cipher: Cipher,
        label: String,
        host: String,
        enter: Boolean,
        user: String,
        pass: String
    ) {
        val ct = cipher.doFinal("$user\t$pass".toByteArray(Charsets.UTF_8))
        val e = Entry(
            id = UUID.randomUUID().toString(),
            label = label,
            host = host,
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            data = Base64.encodeToString(ct, Base64.NO_WRAP),
            enter = enter
        )
        saveList(context, list(context).filterNot { it.host == host } + e)
    }

    fun delete(context: Context, id: String) {
        saveList(context, list(context).filterNot { it.id == id })
        CredCache.clear()
    }

    /** Doğrulanmış cipher ile (kullanıcı adı, şifre) çözer. */
    fun decrypt(cipher: Cipher, entry: Entry): Pair<String, String> {
        val plain = String(
            cipher.doFinal(Base64.decode(entry.data, Base64.NO_WRAP)),
            Charsets.UTF_8
        )
        val p = plain.split('\t', limit = 2)
        return p[0] to p.getOrElse(1) { "" }
    }

    /** Anahtar geçersiz olduysa (ör. yeni parmak izi eklendi) her şeyi sıfırlar. */
    fun resetAll(context: Context) {
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        } catch (_: Exception) {
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        CredCache.clear()
    }
}
