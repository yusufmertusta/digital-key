package com.example.hidpw

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * Saydam etkinlik: sadece parmak izi istemini gösterir, başarılı olunca
 * kaydı çözüp bilgisayara yazar ve kapanır.
 */
class PromptActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra("id")
        val host = intent.getStringExtra("host") ?: ""
        val stage = intent.getStringExtra("stage") ?: "both"

        val entry = CredentialStore.list(this).firstOrNull { it.id == id }
        if (entry == null) {
            finish()
            return
        }

        val cipher = try {
            CredentialStore.decryptCipher(entry)
        } catch (e: Exception) {
            Status.post("Anahtar geçersiz (parmak izi değişmiş olabilir). Kayıtları sıfırlayıp yeniden ekle.")
            finish()
            return
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Giriş: ${entry.label}")
            .setSubtitle(host)
            .setNegativeButtonText("İptal")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val c = result.cryptoObject?.cipher
                        if (c != null) {
                            val (u, p) = CredentialStore.decrypt(c, entry)
                            CredCache.put(entry.id, u, p)
                            val hid = (application as HidApp).hid
                            val ok = Typer.type(hid, u, p, stage, entry.enter)
                            Status.post(if (ok) "Yazıldı: ${entry.label}" else "Bilgisayara bağlı değil")
                        }
                    } catch (e: Exception) {
                        Status.post("Çözülemedi: ${e.message}")
                    }
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Status.post("Doğrulama tamamlanmadı: $errString")
                    finish()
                }
            }
        )
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
