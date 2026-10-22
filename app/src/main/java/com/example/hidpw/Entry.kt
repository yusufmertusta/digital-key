package com.example.hidpw

/** Bir site kaydı. Kullanıcı adı ve şifre `data` içinde Keystore ile şifreli durur. */
data class Entry(
    val id: String,
    val label: String,
    val host: String,
    val iv: String,
    val data: String,
    val enter: Boolean
)
