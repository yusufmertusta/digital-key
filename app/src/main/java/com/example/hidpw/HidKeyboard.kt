package com.example.hidpw

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Telefonu Bluetooth HID klavyesi olarak kaydeder, metni tuş basışları olarak gönderir
 * ve son bağlanılan bilgisayara otomatik yeniden bağlanır.
 * Not: BluetoothHidDevice klasik Bluetooth (BR/EDR) HID profilini kullanır, BLE değil.
 */
@SuppressLint("MissingPermission")
class HidKeyboard(
    private val context: Context,
    private val log: (String) -> Unit
) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val prefs = context.getSharedPreferences("hidpw_hid", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val senderExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var hid: BluetoothHidDevice? = null

    @Volatile
    private var host: BluetoothDevice? = null

    @Volatile
    private var registered = false

    @Volatile
    private var starting = false

    private var failedAttempts = 0

    /** Tuş basışı ve bırakışı arasındaki bekleme (ms). Karakter kaçarsa artır. */
    @Volatile
    var keyDelayMs: Long = 8

    val isConnected: Boolean get() = host != null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidKeyboard.registered = registered
            log(
                if (registered) "Klavye olarak kayıtlı"
                else "Klavye kaydı kaldırıldı"
            )
            if (registered) startReconnectLoop()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    failedAttempts = 0
                    prefs.edit().putString("last_host", device.address).apply()
                    log("Bağlı: ${device.name ?: device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    log("Bağlantı kesildi, yeniden bağlanılacak")
                }
            }
        }
    }

    private val reconnectTask = object : Runnable {
        override fun run() {
            var delay = 8_000L
            try {
                val h = hid
                val a = adapter
                val addr = prefs.getString("last_host", null)
                if (h != null && a != null && registered && host == null && a.isEnabled &&
                    addr != null && BluetoothAdapter.checkBluetoothAddress(addr)
                ) {
                    h.connect(a.getRemoteDevice(addr))
                    failedAttempts++
                    if (failedAttempts > 10) delay = 30_000L
                }
            } catch (_: Exception) {
            }
            handler.postDelayed(this, delay)
        }
    }

    private fun startReconnectLoop() {
        handler.removeCallbacks(reconnectTask)
        handler.postDelayed(reconnectTask, 1_500L)
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            starting = false
            val h = proxy as BluetoothHidDevice
            hid = h
            try {
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Telefon Klavye",
                    "Prototip",
                    "HidPw",
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                    DESCRIPTOR
                )
                val ok = h.registerApp(sdp, null, null, callbackExecutor, callback)
                if (!ok) log("registerApp başarısız (cihaz HID device profilini desteklemiyor olabilir)")
            } catch (e: SecurityException) {
                log("Bluetooth izni eksik")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            registered = false
            host = null
        }
    }

    /** Birden fazla kez çağrılabilir. */
    @Synchronized
    fun start() {
        if (hid != null || starting) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            log("Bluetooth kapalı veya yok")
            return
        }
        starting = true
        try {
            val ok = a.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)
            if (!ok) {
                starting = false
                log("HID profili alınamadı")
            }
        } catch (e: SecurityException) {
            starting = false
            log("Bluetooth izni eksik")
        }
    }

    fun bondedDevices(): List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()

    fun connect(device: BluetoothDevice) {
        val ok = hid?.connect(device) ?: false
        log(if (ok) "Bağlanılıyor: ${device.name ?: device.address}" else "Bağlantı isteği başarısız")
    }

    /** Metni arka planda tuş tuş gönderir. Bağlı değilse false döner. */
    fun typeText(text: String): Boolean {
        val h = hid
        val d = host
        if (h == null || d == null) return false
        val layout = AppSettings.layout(context)
        val dead = AppSettings.deadSpace(context)
        senderExecutor.execute { typeChars(h, d, text, layout, dead) }
        return true
    }

    /**
     * Kilit ekranı için: önce uyandırma tuşu, bekleme, sonra PIN (ve isteğe bağlı Enter).
     * wake: shift, ctrl, esc, space, none.
     */
    fun unlockSequence(pin: String, enter: Boolean, wake: String, wakeDelayMs: Long): Boolean {
        val h = hid
        val d = host
        if (h == null || d == null) return false
        val layout = AppSettings.layout(context)
        val dead = AppSettings.deadSpace(context)
        senderExecutor.execute {
            when (wake) {
                "shift" -> tap(h, d, 0x02, 0)
                "ctrl" -> tap(h, d, 0x01, 0)
                "esc" -> tap(h, d, 0, 0x29)
                "space" -> tap(h, d, 0, 0x2C)
                else -> {}
            }
            if (wake != "none") Thread.sleep(wakeDelayMs)
            typeChars(h, d, pin, layout, dead)
            if (enter) tap(h, d, 0, 0x28)
        }
        return true
    }

    private fun typeChars(
        h: BluetoothHidDevice,
        d: BluetoothDevice,
        text: String,
        layout: KeyMapper.Layout,
        deadSpace: Boolean
    ) {
        for (ch in text) {
            val k = KeyMapper.map(ch, layout) ?: continue
            tap(h, d, k.first, k.second)
            if (deadSpace && layout == KeyMapper.Layout.TR && ch in KeyMapper.DEAD_CHARS) {
                tap(h, d, 0, 0x2C)
            }
        }
    }

    private fun tap(h: BluetoothHidDevice, d: BluetoothDevice, mod: Int, key: Int) {
        if ((mod and KeyMapper.ALTGR) != 0) {
            // AltGr için önce değiştirici, sonra tuş: bazı sistemler aynı raporda kaçırır.
            h.sendReport(d, REPORT_ID, report(mod, 0))
            Thread.sleep(keyDelayMs)
            h.sendReport(d, REPORT_ID, report(mod, key))
            Thread.sleep(keyDelayMs)
            h.sendReport(d, REPORT_ID, report(mod, 0))
            Thread.sleep(keyDelayMs)
            h.sendReport(d, REPORT_ID, report(0, 0))
            Thread.sleep(keyDelayMs)
        } else {
            h.sendReport(d, REPORT_ID, report(mod, key))
            Thread.sleep(keyDelayMs)
            h.sendReport(d, REPORT_ID, report(0, 0))
            Thread.sleep(keyDelayMs)
        }
    }

    private fun report(modifier: Int, key: Int) =
        byteArrayOf(modifier.toByte(), 0, key.toByte(), 0, 0, 0, 0, 0)

    companion object {
        private const val REPORT_ID = 1

        private val DESCRIPTOR = byteArrayOf(
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x06,             // Usage (Keyboard)
            0xA1.toByte(), 0x01,    // Collection (Application)
            0x85.toByte(), 0x01,    //   Report ID (1)
            0x05, 0x07,             //   Usage Page (Key Codes)
            0x19, 0xE0.toByte(),    //   Usage Minimum (224)
            0x29, 0xE7.toByte(),    //   Usage Maximum (231)
            0x15, 0x00,             //   Logical Minimum (0)
            0x25, 0x01,             //   Logical Maximum (1)
            0x75, 0x01,             //   Report Size (1)
            0x95.toByte(), 0x08,    //   Report Count (8)
            0x81.toByte(), 0x02,    //   Input (Data, Variable, Absolute) - modifiers
            0x95.toByte(), 0x01,    //   Report Count (1)
            0x75, 0x08,             //   Report Size (8)
            0x81.toByte(), 0x01,    //   Input (Constant) - reserved
            0x95.toByte(), 0x05,    //   Report Count (5)
            0x75, 0x01,             //   Report Size (1)
            0x05, 0x08,             //   Usage Page (LEDs)
            0x19, 0x01,             //   Usage Minimum (1)
            0x29, 0x05,             //   Usage Maximum (5)
            0x91.toByte(), 0x02,    //   Output (Data, Variable, Absolute) - LEDs
            0x95.toByte(), 0x01,    //   Report Count (1)
            0x75, 0x03,             //   Report Size (3)
            0x91.toByte(), 0x01,    //   Output (Constant) - padding
            0x95.toByte(), 0x06,    //   Report Count (6)
            0x75, 0x08,             //   Report Size (8)
            0x15, 0x00,             //   Logical Minimum (0)
            0x25, 0x65,             //   Logical Maximum (101)
            0x05, 0x07,             //   Usage Page (Key Codes)
            0x19, 0x00,             //   Usage Minimum (0)
            0x29, 0x65,             //   Usage Maximum (101)
            0x81.toByte(), 0x00,    //   Input (Data, Array) - key codes
            0xC0.toByte()           // End Collection
        )
    }
}
