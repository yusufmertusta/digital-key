package com.example.hidpw

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * Kart biçiminde açılır pencere (popup). İki tür:
 *  - login : tarayıcıdaki giriş formu için kullanıcı adı/şifre yazar
 *  - unlock: kilitli Windows için PIN yazar (önce bilgisayarın hâlâ kilitli olduğu doğrulanır)
 * Açılınca parmak izi istemi kendiliğinden başlar; iptal edilirse kart kalır, "Tekrar dene" ile açılır.
 */
class PromptActivity : AppCompatActivity() {

    companion object {
        const val ACTION_CLOSE_UNLOCK = "com.example.hidpw.CLOSE_UNLOCK"
    }

    private var promptShowing = false
    private lateinit var infoText: TextView
    private lateinit var retryBtn: Button

    private var kind = "login"
    private var entryId: String? = null
    private var host = ""
    private var stage = "both"

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (kind == "unlock") finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ContextCompat.registerReceiver(
            this, closeReceiver, IntentFilter(ACTION_CLOSE_UNLOCK), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        buzz()
        setup(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setup(intent)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(closeReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun buzz() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
        } catch (_: Exception) {
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun setup(i: Intent) {
        kind = i.getStringExtra("kind") ?: "login"
        entryId = i.getStringExtra("id")
        host = i.getStringExtra("host") ?: ""
        stage = i.getStringExtra("stage") ?: "both"

        val entry = findEntry()
        if (entry == null) {
            Status.post(if (kind == "unlock") "Windows PIN kaydı yok" else "Kayıt bulunamadı")
            finish()
            return
        }

        val title = if (kind == "unlock") "Bilgisayarın kilidini aç" else "Giriş: ${entry.label}"
        val sub = if (kind == "unlock") {
            (if (PcState.pcName.isNotEmpty()) PcState.pcName + " kilitli" else "Bilgisayar kilitli")
        } else host

        buildCard(title, sub)
        startBiometric(entry)
    }

    private fun findEntry(): Entry? {
        val list = CredentialStore.list(this)
        return if (kind == "unlock") list.firstOrNull { it.host == PcLinkService.WINDOWS_HOST }
        else list.firstOrNull { it.id == entryId }
    }

    private fun buildCard(title: String, sub: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setOnClickListener { } // arka plana dokunmak kapatmasın
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(24).toFloat()
            }
        }
        val icon = TextView(this).apply {
            text = if (kind == "unlock") "🔓" else "🔑"
            textSize = 44f
            gravity = Gravity.CENTER
        }
        val t = TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        val s = TextView(this).apply {
            text = sub
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }
        infoText = TextView(this).apply {
            text = "Parmak izini okut"
            textSize = 15f
            setTextColor(Color.parseColor("#1565C0"))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(10))
        }
        retryBtn = Button(this).apply {
            text = "Tekrar dene"
            textSize = 16f
            setOnClickListener {
                val e = findEntry()
                if (e != null) startBiometric(e)
            }
        }
        val cancel = Button(this).apply {
            text = "İptal"
            textSize = 16f
            setOnClickListener { cancelAndClose() }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        card.addView(icon)
        card.addView(t)
        card.addView(s)
        card.addView(infoText)
        card.addView(retryBtn, lp)
        card.addView(cancel, lp)
        root.addView(card, lp)
        setContentView(root)
    }

    private fun cancelAndClose() {
        if (kind == "unlock") {
            PcState.dismissed = true
            getSystemService(NotificationManager::class.java).cancel(PcLinkService.NOTIF_UNLOCK)
        }
        finish()
    }

    private fun startBiometric(entry: Entry) {
        if (promptShowing) return

        if (kind == "unlock") {
            // Parmak izinden önce ön kontroller: gereksiz yere PIN yazılmasın.
            if (!UnlockGuard.allowed()) {
                infoText.text = "PIN iki kez işe yaramadı: 5 dakika otomatik açma kapalı."
                retryBtn.isEnabled = false
                return
            }
            if (!PcState.isLockFresh()) {
                infoText.text = "Bilgisayar artık kilitli görünmüyor."
                retryBtn.isEnabled = false
                return
            }
        }

        val cipher = try {
            CredentialStore.decryptCipher(entry)
        } catch (e: Exception) {
            Status.post("Anahtar geçersiz (parmak izi değişmiş olabilir). Kayıtları sıfırlayıp yeniden ekle.")
            infoText.text = "Anahtar geçersiz. Uygulamadan kayıtları sıfırla."
            retryBtn.isEnabled = false
            return
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (kind == "unlock") "Kilidi aç" else "Giriş: ${entry.label}")
            .setSubtitle(if (kind == "unlock") PcState.pcName else host)
            .setNegativeButtonText("İptal")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptShowing = false
                    try {
                        val c = result.cryptoObject?.cipher
                        if (c != null) {
                            val (u, p) = CredentialStore.decrypt(c, entry)
                            if (kind == "unlock") doUnlock(entry, p) else doLogin(entry, u, p)
                        }
                    } catch (e: Exception) {
                        Status.post("Çözülemedi: ${e.message}")
                    }
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptShowing = false
                    Status.post("Doğrulama tamamlanmadı: $errString")
                    infoText.text = "Doğrulama tamamlanmadı"
                }
            }
        )
        promptShowing = true
        infoText.text = "Parmak izini okut"
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun doLogin(entry: Entry, u: String, p: String) {
        CredCache.put(entry.id, u, p)
        val hid = (application as HidApp).hid
        val ok = Typer.type(hid, u, p, stage, entry.enter)
        Status.post(if (ok) "Yazıldı: ${entry.label}" else "Bilgisayara bağlı değil")
    }

    private fun doUnlock(entry: Entry, pin: String) {
        val hid = (application as HidApp).hid
        // Güvenlik kontrolü: parmak izi sonrası da bilgisayarın hâlâ kilitli olduğunu doğrula.
        if (!PcState.isLockFresh()) {
            Status.post("Bilgisayar kilitli görünmüyor, PIN yazılmadı")
            return
        }
        if (!UnlockGuard.allowed()) {
            Status.post("PIN iki kez işe yaramadı, otomatik açma geçici olarak kapalı")
            return
        }
        val ok = hid.unlockSequence(
            pin, entry.enter, AppSettings.wakeKey(this), AppSettings.wakeDelayMs(this)
        )
        if (!ok) {
            Status.post("Bilgisayara klavye olarak bağlı değil, PIN yazılmadı")
            return
        }
        UnlockGuard.recordAttempt()
        // Otomatik yeniden deneme yok: bu kilit için bir daha sorma (yeni yaklaşmada sıfırlanır).
        PcState.dismissed = true
        getSystemService(NotificationManager::class.java).cancel(PcLinkService.NOTIF_UNLOCK)
        Status.post("PIN yazıldı")
    }
}
