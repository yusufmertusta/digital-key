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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Arka plan servisi:
 *  1) BLE reklam yayını (hızlı, güçlü): bilgisayar ajanı sinyal gücüne bakıp uzaklaşınca kilitler.
 *     Hareket algısı açıksa, telefon hareket ederken farklı UUID yayınlar (ajan daha hızlı kilitler).
 *  2) GATT sunucusu: ajandan gelen imzalı komutları alır:
 *     - user/pass/both : tarayıcıda giriş formu (kayıtla eşleştir, parmak izi, yaz)
 *     - approach       : bilgisayar kilitli ve telefon yaklaştı (ilk yaklaşma) -> açılış penceresi
 *     - locked         : bilgisayar kilitli (kalp atışı, ~8 sn) -> kilit durumu tazele
 *     - unlocked       : bilgisayarın kilidi açıldı -> bildirimleri temizle
 */
@SuppressLint("MissingPermission")
class PcLinkService : Service(), SensorEventListener {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7b3f6a10-5c1e-4d2a-9f60-2b7e4a1c9d01")
        val CHAR_UUID: UUID = UUID.fromString("7b3f6a11-5c1e-4d2a-9f60-2b7e4a1c9d01")
        /** Telefon hareket halindeyken yayınlanan UUID. */
        val MOVING_UUID: UUID = UUID.fromString("7b3f6a12-5c1e-4d2a-9f60-2b7e4a1c9d01")

        /** Windows PIN kaydının "site" alanı (gerçek bir site adıyla eşleşemez). */
        const val WINDOWS_HOST = "@windows"

        const val ACTION_DISMISS = "com.example.hidpw.DISMISS_UNLOCK"
        const val ACTION_REFRESH = "com.example.hidpw.REFRESH"

        private const val CH_SERVICE = "svc"
        private const val CH_LOGIN = "login"
        private const val CH_UNLOCK = "unlock"
        private const val CH_UNLOCK_QUIET = "unlock_quiet"
        private const val NOTIF_SERVICE = 1
        const val NOTIF_UNLOCK = 2

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PcLinkService::class.java))
        }

        /** Ayar değişince (hareket algısı vb.) servise haber ver. */
        fun refresh(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, PcLinkService::class.java).setAction(ACTION_REFRESH)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PcLinkService::class.java))
        }
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val buffers = HashMap<String, ByteArrayOutputStream>()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastTs = 0L

    // Hareket algısı
    private var sensorManager: SensorManager? = null
    private var motionOn = false
    private var moving = false
    private val stopMoving = Runnable {
        if (moving) {
            moving = false
            restartAdvertisingQuiet()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        val n = NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Telefon-PC bağlantısı aktif")
            .setContentText("Otomatik giriş, kilit ve oturum açma için çalışıyor")
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIF_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            Status.post("Servis başlatılamadı: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_DISMISS) {
            // Kullanıcı kilit açma bildirimini sildi: bu kilit için bir daha sorma.
            PcState.dismissed = true
            return START_STICKY
        }

        try {
            if (gattServer == null) setupGatt()
            startAdvertising()
        } catch (e: SecurityException) {
            Status.post("Bluetooth izni eksik, servis çalışamıyor")
        }
        updateMotionSensor()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopMoving)
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {
        }
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
            NotificationChannel(CH_LOGIN, "Giriş istekleri", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_UNLOCK, "Kilit açma (yaklaşınca)", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_UNLOCK_QUIET, "Kilit açma (sessiz)", NotificationManager.IMPORTANCE_LOW)
        )
    }

    // ---------- Hareket algısı (isteğe bağlı) ----------

    private fun updateMotionSensor() {
        val want = AppSettings.motion(this)
        if (want == motionOn) return
        motionOn = want
        if (sensorManager == null) sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sm = sensorManager ?: return
        if (want) {
            // Uyandırma (wake-up) sensörü: ekran kapalıyken de olay iletir.
            val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
                ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (s == null) {
                Status.post("Bu telefonda ivmeölçer yok, hareket algısı kapalı")
                motionOn = false
                return
            }
            sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            sm.unregisterListener(this)
            if (moving) {
                moving = false
                restartAdvertisingQuiet()
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val g = sqrt(x * x + y * y + z * z)
        // Yerçekimi (9.8) civarından sapma: telefon yürürken/kaldırılırken artar.
        if (abs(g - 9.81f) > 1.8f) {
            handler.removeCallbacks(stopMoving)
            handler.postDelayed(stopMoving, 4_000L)
            if (!moving) {
                moving = true
                restartAdvertisingQuiet()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
                val s = String(value, Charsets.UTF_8)
                handler.post { handleCommand(s) }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buf = buffers.remove(device.address)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (execute && buf != null) {
                val s = String(buf.toByteArray(), Charsets.UTF_8)
                handler.post { handleCommand(s) }
            }
        }
    }

    // ---------- BLE reklam ----------

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Status.post("BLE reklam başlatılamadı (kod $errorCode)")
        }
    }

    private fun restartAdvertisingQuiet() {
        try {
            startAdvertising()
        } catch (_: Exception) {
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
        // Hızlı kilit için: en sık yayın (~100 ms) ve yüksek güç.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(if (moving) MOVING_UUID else SERVICE_UUID))
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

        when (cmd.stage) {
            "locked" -> onLocked(cmd.host, approach = false)
            "approach" -> onLocked(cmd.host, approach = true)
            "unlocked" -> onUnlocked()
            else -> onLoginRequest(cmd.host, cmd.stage)
        }
    }

    private fun onUnlocked() {
        PcState.clear()
        UnlockGuard.onUnlocked()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_UNLOCK)
        // Açık kalmış açma penceresi varsa kapat.
        sendBroadcast(Intent(PromptActivity.ACTION_CLOSE_UNLOCK).setPackage(packageName))
        Status.post("Bilgisayarın kilidi açıldı")
    }

    private fun onLocked(pcName: String, approach: Boolean) {
        PcState.markLocked(pcName)
        UnlockGuard.onLockedReport()
        if (approach) PcState.dismissed = false
        if (PcState.dismissed) return
        if (windowsEntry() == null) {
            if (approach) Status.post("Bilgisayar kilitli ama Windows PIN kaydı yok (Ayarlar bölümünden ekle)")
            return
        }
        if (!UnlockGuard.allowed()) return
        if (approach) {
            showUnlockPopup(loud = true)
        } else {
            // Elle kilitleme (yaklaşma yok): sessiz, kalıcı küçük bildirim.
            if (!unlockNotificationShown()) showUnlockPopup(loud = false)
        }
    }

    private fun windowsEntry(): Entry? =
        CredentialStore.list(this).firstOrNull { it.host == WINDOWS_HOST }

    private fun unlockNotificationShown(): Boolean =
        getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == NOTIF_UNLOCK }

    private fun unlockIntent(): Intent =
        Intent(this, PromptActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("kind", "unlock")

    private fun showUnlockPopup(loud: Boolean) {
        val app = application as HidApp
        val intent = unlockIntent()
        val canPopup = app.visibleActivities > 0 || Settings.canDrawOverlays(this)
        if (loud && canPopup) {
            try {
                startActivity(intent)
            } catch (_: Exception) {
            }
        }

        val pi = PendingIntent.getActivity(
            this, 2001, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismiss = PendingIntent.getService(
            this, 2002,
            Intent(this, PcLinkService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, if (loud) CH_UNLOCK else CH_UNLOCK_QUIET)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(if (loud) "Bilgisayarın kilidini aç" else "Kilidi aç")
            .setContentText(
                (if (PcState.pcName.isNotEmpty()) "${PcState.pcName} kilitli — " else "") +
                    "dokun ve parmak izini okut"
            )
            .setContentIntent(pi)
            .setDeleteIntent(dismiss)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(0, "Kilidi aç", pi)
        if (loud) {
            b.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
        } else {
            b.setPriority(NotificationCompat.PRIORITY_LOW)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_UNLOCK, b.build())
    }

    private fun onLoginRequest(host: String, stage: String) {
        val entry = HostMatcher.find(CredentialStore.list(this), host)
        if (entry == null) {
            Status.post("Bu site için kayıt yok: $host")
            return
        }
        val app = application as HidApp
        if (!app.hid.isConnected) {
            Status.post("Bilgisayara klavye olarak bağlı değil (yeniden bağlanıyor)")
            return
        }

        val cached = CredCache.get(entry.id)
        if (cached != null) {
            val ok = Typer.type(app.hid, cached.first, cached.second, stage, entry.enter)
            Status.post(if (ok) "Yazıldı: ${entry.label}" else "Yazılamadı")
            return
        }
        showLoginPrompt(entry, host, stage)
    }

    private fun showLoginPrompt(entry: Entry, host: String, stage: String) {
        val app = application as HidApp
        val intent = Intent(this, PromptActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("kind", "login")
            .putExtra("id", entry.id)
            .putExtra("host", host)
            .putExtra("stage", stage)

        // Uygulama ekranda veya "diğer uygulamaların üzerinde göster" izni varsa doğrudan pencere aç.
        if (app.visibleActivities > 0 || Settings.canDrawOverlays(this)) {
            try {
                startActivity(intent)
            } catch (_: Exception) {
            }
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
