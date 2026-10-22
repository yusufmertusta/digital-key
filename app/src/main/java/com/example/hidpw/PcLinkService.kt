package com.example.hidpw

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Arka plan servisi:
 *  1) BLE reklam yayını yapar: bilgisayar ajanı bu yayının sinyal gücüne bakıp uzaklaşınca kilitler.
 *  2) GATT sunucusu açar: tarayıcı eklentisinden gelen "şu siteye giriş yapılacak" komutunu
 *     (ajan üzerinden, HMAC ile imzalı) alır, kayıtla eşleştirir ve biyometrik doğrulamayı başlatır.
 */
@SuppressLint("MissingPermission")
class PcLinkService : Service() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7b3f6a10-5c1e-4d2a-9f60-2b7e4a1c9d01")
        val CHAR_UUID: UUID = UUID.fromString("7b3f6a11-5c1e-4d2a-9f60-2b7e4a1c9d01")
        private const val CH_SERVICE = "svc"
        private const val CH_LOGIN = "login"
        private const val NOTIF_SERVICE = 1

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PcLinkService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PcLinkService::class.java))
        }
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val buffers = HashMap<String, ByteArrayOutputStream>()

    @Volatile
    private var lastTs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        val n = NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Telefon-PC bağlantısı aktif")
            .setContentText("Otomatik giriş ve uzaklaşınca kilit için çalışıyor")
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIF_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            Status.post("Servis başlatılamadı: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (gattServer == null) setupGatt()
            startAdvertising()
        } catch (e: SecurityException) {
            Status.post("Bluetooth izni eksik, servis çalışamıyor")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            advertiser?.stopAdvertising(advCallback)
        } catch (_: Exception) {
        }
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        super.onDestroy()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Arka plan servisi", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LOGIN, "Giriş istekleri", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    // ---------- GATT sunucusu ----------

    private fun setupGatt() {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val server = mgr.openGattServer(this, gattCallback) ?: run {
            Status.post("GATT sunucusu açılamadı")
            return
        }
        gattServer = server
        val svc = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val ch = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        svc.addCharacteristic(ch)
        server.addService(svc)
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                buffers.remove(device.address)
                // Bağlantı sırasında reklam durabilir; bitince yeniden başlat.
                try {
                    startAdvertising()
                } catch (_: Exception) {
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic.uuid != CHAR_UUID || value == null) {
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_FAILURE, offset, null
                    )
                }
                return
            }
            if (preparedWrite) {
                // Uzun yazma: parçalar birikir, onExecuteWrite'ta işlenir.
                buffers.getOrPut(device.address) { ByteArrayOutputStream() }.write(value)
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                    )
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null
                    )
                }
                handleCommand(String(value, Charsets.UTF_8))
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buf = buffers.remove(device.address)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (execute && buf != null) {
                handleCommand(String(buf.toByteArray(), Charsets.UTF_8))
            }
        }
    }

    // ---------- BLE reklam ----------

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Status.post("BLE reklam başlatılamadı (kod $errorCode)")
        }
    }

    private fun startAdvertising() {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adv = mgr.adapter?.bluetoothLeAdvertiser
        if (adv == null) {
            Status.post("Bu telefon BLE reklam yayınını desteklemiyor")
            return
        }
        advertiser = adv
        try {
            adv.stopAdvertising(advCallback)
        } catch (_: Exception) {
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        adv.startAdvertising(settings, data, advCallback)
    }

    // ---------- Komut işleme ----------

    private fun handleCommand(raw: String) {
        val cmd = CommandAuth.verify(this, raw, lastTs)
        if (cmd == null) {
            Status.post("Geçersiz veya eski komut reddedildi")
            return
        }
        lastTs = cmd.ts

        val entry = HostMatcher.find(CredentialStore.list(this), cmd.host)
        if (entry == null) {
            Status.post("Bu site için kayıt yok: ${cmd.host}")
            return
        }
        val app = application as HidApp
        if (!app.hid.isConnected) {
            Status.post("Bilgisayara klavye olarak bağlı değil (yeniden bağlanıyor)")
            return
        }

        val cached = CredCache.get(entry.id)
        if (cached != null) {
            val ok = Typer.type(app.hid, cached.first, cached.second, cmd.stage, entry.enter)
            Status.post(if (ok) "Yazıldı: ${entry.label}" else "Yazılamadı")
            return
        }
        showPrompt(entry, cmd.host, cmd.stage)
    }

    private fun showPrompt(entry: Entry, host: String, stage: String) {
        val app = application as HidApp
        val intent = Intent(this, PromptActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("id", entry.id)
            .putExtra("host", host)
            .putExtra("stage", stage)

        if (app.visibleActivities > 0) {
            // Uygulama ekranda: doğrudan parmak izi istemini aç.
            startActivity(intent)
            return
        }

        val pi = PendingIntent.getActivity(
            this,
            entry.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CH_LOGIN)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("${entry.label} için giriş")
            .setContentText("$host — dokun ve parmak izini okut")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        getSystemService(NotificationManager::class.java).notify(entry.id.hashCode(), n)
    }
}
