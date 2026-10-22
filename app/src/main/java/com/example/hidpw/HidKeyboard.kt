package com.example.hidpw

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.Executors

/**
 * Telefonu Bluetooth HID klavyesi olarak kaydeder ve metni tuş basışları olarak gönderir.
 * Not: BluetoothHidDevice klasik Bluetooth (BR/EDR) HID profilini kullanır, BLE değil.
 */
@SuppressLint("MissingPermission")
class HidKeyboard(
    private val context: Context,
    private val log: (String) -> Unit
) {
    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var hid: BluetoothHidDevice? = null

    @Volatile
    private var host: BluetoothDevice? = null

    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val senderExecutor = Executors.newSingleThreadExecutor()

    val isConnected: Boolean get() = host != null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            log(if (registered) "Klavye olarak kayıtlı. Bilgisayardan eşleştir veya listeden bağlan."
            else "Klavye kaydı kaldırıldı")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    log("Bağlı: ${device.name ?: device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    log("Bağlantı kesildi")
                }
            }
        }
    }

    fun start() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            log("Bluetooth kapalı veya yok")
            return
        }
        a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Telefon Klavye",
                    "Prototip",
                    "HidPw",
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                    DESCRIPTOR
                )
                val ok = h.registerApp(sdp, null, null, callbackExecutor, callback)
                if (!ok) log("registerApp başarısız (cihaz HID device profilini desteklemiyor olabilir)")
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun stop() {
        try {
            hid?.unregisterApp()
        } catch (_: Exception) {
        }
        hid = null
        host = null
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
        senderExecutor.execute {
            for (ch in text) {
                val k = KeyMapper.map(ch) ?: continue
                h.sendReport(d, REPORT_ID, report(k.first, k.second))
                Thread.sleep(12)
                h.sendReport(d, REPORT_ID, report(0, 0))
                Thread.sleep(12)
            }
        }
        return true
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
