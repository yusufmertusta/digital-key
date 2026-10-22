package com.example.hidpw

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Bilgisayar ajanı ile paylaşılan 32 baytlık anahtar.
 * Arka planda biyometrik istemeden okunabilmesi için, kullanıcı doğrulaması gerektirmeyen
 * ayrı bir Keystore anahtarıyla sarılıp saklanır.
 */
object PcKey {
    private const val ALIAS = "hidpw_pckey"
    private const val PREFS = "hidpw_pc"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }

    fun get(context: Context): ByteArray {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = p.getString("iv", null)
        val ct = p.getString("ct", null)
        if (iv != null && ct != null) {
            try {
                val c = Cipher.getInstance(TRANSFORM).apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        wrapKey(),
                        GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
                    )
                }
                return c.doFinal(Base64.decode(ct, Base64.NO_WRAP))
            } catch (_: Exception) {
                // bozulmuşsa aşağıda yeniden üretilir
            }
        }
        return regenerate(context)
    }

    fun regenerate(context: Context): ByteArray {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        val ct = c.doFinal(raw)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
        return raw
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Ajandan gelen komut biçimi: "zamanDamgasıMs|host|aşama|mac"
 * mac = HMAC-SHA256(anahtar, "zamanDamgasıMs|host|aşama") hex'inin ilk 32 karakteri.
 */
object CommandAuth {
    data class Command(val ts: Long, val host: String, val stage: String)

    private val STAGES = setOf("user", "pass", "both")

    fun verify(context: Context, raw: String, lastTs: Long): Command? {
        val parts = raw.trim().split('|')
        if (parts.size != 4) return null
        val ts = parts[0].toLongOrNull() ?: return null
        val host = parts[1]
        val stage = parts[2]
        val mac = parts[3]
        if (stage !in STAGES) return null
        if (host.isEmpty() || host.length > 253) return null

        val now = System.currentTimeMillis()
        if (abs(now - ts) > 60_000L) return null   // saat farkı / eski komut
        if (ts <= lastTs) return null              // tekrar saldırısı

        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(PcKey.get(context), "HmacSHA256"))
        val expected = PcKey.hex(m.doFinal("$ts|$host|$stage".toByteArray(Charsets.UTF_8)))
            .substring(0, 32)
        if (!MessageDigest.isEqual(expected.toByteArray(), mac.toByteArray())) return null
        return Command(ts, host, stage)
    }
}
