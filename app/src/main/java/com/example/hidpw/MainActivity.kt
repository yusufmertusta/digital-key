package com.example.hidpw

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
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

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var hid: HidKeyboard
    private lateinit var status: TextView
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var enterCheck: CheckBox

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) hid.start() else status.text = "Bluetooth izni gerekli"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        hid = HidKeyboard(this) { msg -> runOnUiThread { status.text = msg } }
        startHid()
    }

    override fun onDestroy() {
        super.onDestroy()
        hid.stop()
    }

    private fun startHid() {
        val needsPerm = Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPerm) permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) else hid.start()
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { text = "Hazırlanıyor..." }
        userInput = EditText(this).apply {
            hint = "Kullanıcı adı (isteğe bağlı)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        passInput = EditText(this).apply {
            hint = "Şifre"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        enterCheck = CheckBox(this).apply {
            text = "Sonunda Enter bas"
            isChecked = true
        }

        fun btn(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

        root.addView(status)
        root.addView(btn("1) Telefonu görünür yap (eşleştirme)") { makeDiscoverable() })
        root.addView(btn("2) Eşleşmiş bilgisayara bağlan") { pickHost() })
        root.addView(userInput)
        root.addView(passInput)
        root.addView(btn("3) Kaydet (biyometrik)") { saveCredentials() })
        root.addView(enterCheck)
        root.addView(btn("4) Bilgisayara yaz (biyometrik)") { typeSaved() })
        root.addView(btn("Kaydı sil") {
            CredentialStore.clear(this)
            status.text = "Kayıt silindi"
        })

        setContentView(ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        })
    }

    private fun makeDiscoverable() {
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        startActivity(i)
    }

    private fun pickHost() {
        val devices = hid.bondedDevices()
        if (devices.isEmpty()) {
            status.text = "Eşleşmiş cihaz yok. Önce bilgisayardan eşleştir."
            return
        }
        val names = devices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bilgisayarı seç")
            .setItems(names) { _, which -> hid.connect(devices[which]) }
            .show()
    }

    private fun authenticate(cipher: Cipher, title: String, onOk: (Cipher) -> Unit) {
        val can = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            status.text = "Güçlü biyometrik doğrulama kullanılamıyor (kod: $can). Parmak izi/yüz kaydı ekle."
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

    private fun saveCredentials() {
        val pass = passInput.text.toString()
        if (pass.isEmpty()) {
            status.text = "Şifre boş"
            return
        }
        val payload = userInput.text.toString() + "\t" + pass
        val cipher = try {
            CredentialStore.encryptCipher()
        } catch (e: Exception) {
            status.text = "Anahtar hazırlanamadı: ${e.message}"
            return
        }
        authenticate(cipher, "Kaydetmek için doğrula") { c ->
            try {
                CredentialStore.save(this, c, payload.toByteArray(Charsets.UTF_8))
                userInput.setText("")
                passInput.setText("")
                status.text = "Şifrelenip kaydedildi"
            } catch (e: Exception) {
                status.text = "Kaydedilemedi: ${e.message}"
            }
        }
    }

    private fun typeSaved() {
        if (!CredentialStore.hasSaved(this)) {
            status.text = "Önce bir şifre kaydet"
            return
        }
        if (!hid.isConnected) {
            status.text = "Bilgisayara bağlı değil"
            return
        }
        val cipher = try {
            CredentialStore.decryptCipher(this)
        } catch (e: Exception) {
            status.text = "Anahtar geçersiz (biyometrik değişmiş olabilir). Kaydı silip yeniden kaydet."
            return
        }
        authenticate(cipher, "Şifreyi yazmak için doğrula") { c ->
            try {
                val parts = String(CredentialStore.decrypt(this, c), Charsets.UTF_8)
                    .split('\t', limit = 2)
                val user = parts[0]
                val pass = parts.getOrElse(1) { "" }
                val sb = StringBuilder()
                if (user.isNotEmpty()) sb.append(user).append('\t')
                sb.append(pass)
                if (enterCheck.isChecked) sb.append('\n')
                val sent = hid.typeText(sb.toString())
                status.text = if (sent) "Yazıldı" else "Bağlantı yok"
            } catch (e: Exception) {
                status.text = "Çözülemedi: ${e.message}"
            }
        }
    }
}
