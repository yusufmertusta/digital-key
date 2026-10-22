package com.example.hidpw

import java.util.concurrent.ConcurrentHashMap

/** Arayüze ve servise ortak durum mesajı kanalı. */
object Status {
    @Volatile
    var last: String = ""

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun post(msg: String) {
        last = msg
        listener?.invoke(msg)
    }
}

/**
 * Biyometrik doğrulamadan sonra çözülen bilgiyi kısa süre bellekte tutar.
 * İki adımlı girişlerde (önce kullanıcı adı, sonra şifre) ikinci parmak izi istememek için.
 */
object CredCache {
    private const val TTL_MS = 45_000L

    private class Item(val user: String, val pass: String, val expires: Long)

    private val map = ConcurrentHashMap<String, Item>()

    fun put(id: String, user: String, pass: String) {
        map[id] = Item(user, pass, System.currentTimeMillis() + TTL_MS)
    }

    fun get(id: String): Pair<String, String>? {
        val item = map[id] ?: return null
        if (System.currentTimeMillis() > item.expires) {
            map.remove(id)
            return null
        }
        return item.user to item.pass
    }

    fun clear() = map.clear()
}

/** Sayfanın durumuna (hangi alanlar görünüyor) göre ne yazılacağını belirler. */
object Typer {
    fun type(hid: HidKeyboard, user: String, pass: String, stage: String, enter: Boolean): Boolean {
        val sb = StringBuilder()
        when (stage) {
            "user" -> {
                sb.append(user)
                if (enter) sb.append('\n')
            }
            "pass" -> {
                sb.append(pass)
                if (enter) sb.append('\n')
            }
            else -> {
                if (user.isNotEmpty()) sb.append(user).append('\t')
                sb.append(pass)
                if (enter) sb.append('\n')
            }
        }
        return hid.typeText(sb.toString())
    }
}

/** Sayfa alan adı ile kayıtlı site adresini eşleştirir. */
object HostMatcher {
    fun normalize(input: String): String {
        var s = input.trim().lowercase()
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('/').substringBefore('?').substringBefore(':')
        return s.removePrefix("www.")
    }

    /** Tam eşleşme veya alt alan adı. "evilgoogle.com" ve "google.com.evil.com" eşleşmez. */
    fun find(entries: List<Entry>, pageHost: String): Entry? {
        val h = normalize(pageHost)
        return entries
            .filter { h == it.host || h.endsWith("." + it.host) }
            .maxByOrNull { it.host.length }
    }
}
