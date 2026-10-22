package com.example.hidpw

import android.content.Context

/** Kullanıcının seçtiği ayarlar. */
object AppSettings {
    private fun p(c: Context) = c.getSharedPreferences("hidpw_ui", Context.MODE_PRIVATE)

    fun layout(c: Context): KeyMapper.Layout =
        if (p(c).getString("layout", "tr") == "us") KeyMapper.Layout.US else KeyMapper.Layout.TR

    fun setLayout(c: Context, l: KeyMapper.Layout) {
        p(c).edit().putString("layout", if (l == KeyMapper.Layout.US) "us" else "tr").apply()
    }

    /** ^ ve ~ gibi ölü tuş karakterlerinden sonra Boşluk gönder. */
    fun deadSpace(c: Context): Boolean = p(c).getBoolean("dead_space", true)
    fun setDeadSpace(c: Context, v: Boolean) = p(c).edit().putBoolean("dead_space", v).apply()

    /** Hareket algısı (pil harcar): yayına "yürüyorum" bilgisi ekler, ajan daha hızlı kilitler. */
    fun motion(c: Context): Boolean = p(c).getBoolean("motion", false)
    fun setMotion(c: Context, v: Boolean) = p(c).edit().putBoolean("motion", v).apply()

    /** Kilit ekranını uyandırmak için gönderilen tuş: shift, ctrl, esc, space, none. */
    fun wakeKey(c: Context): String = p(c).getString("wake", "shift") ?: "shift"
    fun setWakeKey(c: Context, v: String) = p(c).edit().putString("wake", v).apply()

    fun wakeDelayMs(c: Context): Long = p(c).getLong("wake_delay", 1000L)
}

/** Ajanın bildirdiği bilgisayar kilit durumu. */
object PcState {
    /** Kilit bilgisi bu kadar süre içinde tazelenmediyse PIN yazılmaz. */
    private const val FRESH_MS = 25_000L

    @Volatile
    var lockedAt = 0L

    /** Kullanıcı bildirimi sildi / İptal'e bastı: bir sonraki yaklaşmaya kadar tekrar sorma. */
    @Volatile
    var dismissed = false

    @Volatile
    var pcName = ""

    fun markLocked(name: String) {
        lockedAt = System.currentTimeMillis()
        if (name.isNotEmpty()) pcName = name
    }

    fun isLockFresh(): Boolean =
        lockedAt != 0L && System.currentTimeMillis() - lockedAt <= FRESH_MS

    fun clear() {
        lockedAt = 0L
        dismissed = false
    }
}

/** Yanlış PIN yüzünden Windows hesabının kilitlenmesini önler. */
object UnlockGuard {
    private const val MAX_FAILS = 2
    private const val BLOCK_MS = 5 * 60_000L

    private var fails = 0
    private var blockedUntil = 0L
    private var lastTypedAt = 0L

    @Synchronized
    fun allowed(): Boolean = System.currentTimeMillis() >= blockedUntil

    @Synchronized
    fun recordAttempt() {
        lastTypedAt = System.currentTimeMillis()
    }

    /** Ajan "hâlâ kilitli" dediğinde çağrılır; PIN yazıldıktan sonra kilit sürüyorsa başarısızlık sayar. */
    @Synchronized
    fun onLockedReport() {
        val now = System.currentTimeMillis()
        if (lastTypedAt != 0L && now - lastTypedAt in 6_000L..90_000L) {
            lastTypedAt = 0L
            fails++
            if (fails >= MAX_FAILS) {
                fails = 0
                blockedUntil = now + BLOCK_MS
                Status.post("PIN iki kez işe yaramadı: 5 dakika otomatik açma kapalı. PIN'i kontrol et.")
            } else {
                Status.post("PIN kabul edilmedi gibi görünüyor, PIN'i kontrol et")
            }
        }
    }

    @Synchronized
    fun onUnlocked() {
        fails = 0
        lastTypedAt = 0L
    }
}
