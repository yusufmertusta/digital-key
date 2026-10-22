package com.example.hidpw

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import javax.crypto.Cipher

class MainActivity : AppCompatActivity() {

    private val app: HidApp get() = application as HidApp
    private lateinit var status: TextView
    private lateinit var listBox: LinearLayout
    private val ui by lazy { getSharedPreferences("hidpw_ui", Context.MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            afterPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val missing = missingPermissions()
        if (missing.isEmpty()) afterPermissions() else permLauncher.launch(missing)
    }

    override fun onResume() {
        super.onResume()
        Status.listener = { msg -> runOnUiThread { status.text = msg } }
        status.text = Status.last.ifEmpty { "Hazır" }
        refreshList()
        if (app.hasBluetoothPermission()) app.hid.start()
    }

    override fun onPause() {
        super.onPause()
        Status.listener = null
    }

    // ---------- İzinler ----------

    private fun missingPermissions(): Array<String> {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_CONNECT
            l += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        return l.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private fun afterPermissions() {
        if (!app.hasBluetoothPermission()) {
            status.text = "Bluetooth izni gerekli"
            return
        }
        app.hid.start()
        if (ui.getBoolean("svc", true)) PcLinkService.start(this)
    }

    // ---------- Arayüz ----------

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun btn(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 17f
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun buildUi() {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply {
            text = Status.last.ifEmpty { "Hazırlanıyor..." }
            textSize = 15f
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val svcCheck = CheckBox(this).apply {
            text = "Arka plan servisi (otomatik giriş + uzaklaşınca kilit)"
            isChecked = ui.getBoolean("svc", true)
            setOnCheckedChangeListener { _, on ->
                ui.edit().putBoolean("svc", on).apply()
                if (on) {
                    if (app.hasBluetoothPermission()) PcLinkService.start(this@MainActivity)
                } else {
                    PcLinkService.stop(this@MainActivity)
                }
            }
        }

        root.addView(status)
        root.addView(section("Bilgisayar bağlantısı"))
        root.addView(btn("1) Telefonu görünür yap (ilk eşleştirme)") { makeDiscoverable() })
        root.addView(btn("2) Eşleşmiş bilgisayara bağlan") { pickHost() })
        root.addView(svcCheck)
        root.addView(section("Kayıtlı siteler"))
        root.addView(listBox)
        root.addView(btn("+ Kayıt ekle") { showAddDialog() })
        root.addView(section("Gelişmiş"))
        root.addView(btn("PC eşleştirme anahtarını göster") {
            confirmUser("Anahtarı göstermek için doğrula") { showPcKey() }
        })
        root.addView(btn("Tüm kayıtları sıfırla") { confirmReset() })

        setContentView(
            ScrollView(this).apply {
                addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        )
    }

    private fun refreshList() {
        listBox.removeAllViews()
        val items = CredentialStore.list(this)
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "Henüz kayıt yok" })
            return
        }
        for (e in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tv = TextView(this).apply {
                text = "${e.label}  (${e.host})"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = btn("Sil") {
                AlertDialog.Builder(this)
                    .setTitle("Silinsin mi?")
                    .setMessage("${e.label} (${e.host}) kaydı silinecek.")
                    .setPositiveButton("Sil") { _, _ ->
                        CredentialStore.delete(this, e.id)
                        refreshList()
                    }
                    .setNegativeButton("İptal", null)
                    .show()
            }
            row.addView(tv)
            row.addView(del)
            listBox.addView(row)
        }
    }

    // ---------- Bluetooth ----------

    private fun makeDiscoverable() {
        startActivity(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        )
    }

    private fun pickHost() {
        val devices = try {
            app.hid.bondedDevices()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (devices.isEmpty()) {
            status.text = "Eşleşmiş cihaz yok. Önce bilgisayardan eşleştir."
            return
        }
        val names = devices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bilgisayarı seç")
            .setItems(names) { _, which -> app.hid.connect(devices[which]) }
            .show()
    }

    // ---------- Kayıt ekleme ----------

    private fun showAddDialog() {
        val pad = dp(20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        val label = EditText(this).apply { hint = "Ad (örn. Gmail)" }
        val host = EditText(this).apply {
            hint = "Site (örn. google.com, alt alan adlarını da kapsar)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val user = EditText(this).apply {
            hint = "Kullanıcı adı / e-posta"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val pass = EditText(this).apply {
            hint = "Şifre"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val enter = CheckBox(this).apply {
            text = "Sonunda Enter bas"
            isChecked = true
        }
        box.addView(label)
        box.addView(host)
        box.addView(user)
        box.addView(pass)
        box.addView(enter)

        AlertDialog.Builder(this)
            .setTitle("Kayıt ekle")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                saveEntry(
                    label.text.toString(),
                    host.text.toString(),
                    user.text.toString(),
                    pass.text.toString(),
                    enter.isChecked
                )
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun saveEntry(label: String, hostIn: String, user: String, pass: String, enter: Boolean) {
        val host = HostMatcher.normalize(hostIn)
        if (host.isEmpty() || !host.contains('.')) {
            status.text = "Geçerli bir site adresi gir (örn. google.com)"
            return
        }
        if (pass.isEmpty()) {
            status.text = "Şifre boş"
            return
        }
        val name = label.trim().ifEmpty { host }
        val cipher = try {
            CredentialStore.encryptCipher()
        } catch (e: Exception) {
            status.text = "Anahtar hazırlanamadı: ${e.message}. Gerekirse 'Tüm kayıtları sıfırla'."
            return
        }
        authenticateCipher(cipher, "Kaydetmek için doğrula") { c ->
            try {
                CredentialStore.add(this, c, name, host, enter, user, pass)
                status.text = "Kaydedildi: $name ($host)"
                refreshList()
            } catch (e: Exception) {
                status.text = "Kaydedilemedi: ${e.message}"
            }
        }
    }

    // ---------- PC anahtarı ----------

    private fun showPcKey() {
        val hex = PcKey.hex(PcKey.get(this))
        AlertDialog.Builder(this)
            .setTitle("PC eşleştirme anahtarı")
            .setMessage(
                "Bu anahtarı bilgisayardaki agent_config.json dosyasında phone_key_hex alanına yapıştır. " +
                    "Kimseyle paylaşma.\n\n$hex"
            )
            .setPositiveButton("Kopyala") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("pc-key", hex)
                clip.description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                cm.setPrimaryClip(clip)
                status.text = "Anahtar panoya kopyalandı"
            }
            .setNeutralButton("Yenile") { _, _ ->
                PcKey.regenerate(this)
                status.text = "Yeni anahtar üretildi, bilgisayardaki ayarı da güncelle"
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Hepsi silinsin mi?")
            .setMessage("Tüm kayıtlar ve şifreleme anahtarı silinir. Yeni parmak izi ekledikten sonra anahtar geçersiz olduysa bunu kullan.")
            .setPositiveButton("Sıfırla") { _, _ ->
                CredentialStore.resetAll(this)
                refreshList()
                status.text = "Sıfırlandı"
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ---------- Biyometrik ----------

    private fun authenticateCipher(cipher: Cipher, title: String, onOk: (Cipher) -> Unit) {
        val can = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            status.text = "Güçlü biyometrik doğrulama kullanılamıyor (kod: $can). Parmak izi ekle."
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("İptal")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(onOk)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    status.text = "Doğrulama tamamlanmadı: $errString"
                }
            }
        )
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    /** Şifreleme gerektirmeyen basit kullanıcı doğrulaması (parmak izi veya ekran kilidi). */
    private fun confirmUser(title: String, onOk: () -> Unit) {
        val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(auth)
            .build()
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onOk()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    status.text = "Doğrulama tamamlanmadı: $errString"
                }
            }
        )
        prompt.authenticate(info)
    }
}
