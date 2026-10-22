package com.example.hidpw

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Uygulama süreci yaşadığı sürece HID klavye kaydını canlı tutar.
 * Arka plan servisi ve arayüz aynı HidKeyboard örneğini kullanır.
 */
class HidApp : Application() {

    lateinit var hid: HidKeyboard
        private set

    /** Görünür etkinlik sayısı (uygulama ön planda mı?). Sadece ana iş parçacığında değişir. */
    @Volatile
    var visibleActivities = 0

    override fun onCreate() {
        super.onCreate()
        hid = HidKeyboard(this) { msg -> Status.post(msg) }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                visibleActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                visibleActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        if (hasBluetoothPermission()) hid.start()
    }

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
