package com.example.hidpw

/**
 * Karakter -> (modifier, HID usage kodu).
 * HID kodları fiziksel tuş konumudur; bilgisayardaki klavye düzeni (ABD veya Türkçe Q)
 * bunu karaktere çevirir. Bu yüzden telefondaki seçim, bilgisayarın giriş yöntemiyle aynı olmalı.
 */
object KeyMapper {
    const val SHIFT = 0x02
    const val ALTGR = 0x40   // Sağ Alt

    enum class Layout { US, TR }

    /** Türkçe Q'da ^ ve ~ genelde ölü tuştur: karakter için ardından Boşluk gerekir. */
    val DEAD_CHARS = setOf('^', '~')

    fun map(c: Char, layout: Layout): Pair<Int, Int>? =
        if (layout == Layout.TR) trMap(c) else usMap(c)

    /** Seçili düzende yazılamayan karakterler (kayıt sırasında uyarmak için). */
    fun unsupported(text: String, layout: Layout): List<Char> =
        text.toList().filter { map(it, layout) == null }.distinct()

    private fun usMap(c: Char): Pair<Int, Int>? = when (c) {
        in 'a'..'z' -> 0 to (0x04 + (c - 'a'))
        in 'A'..'Z' -> SHIFT to (0x04 + (c - 'A'))
        in '1'..'9' -> 0 to (0x1E + (c - '1'))
        '0' -> 0 to 0x27
        '!' -> SHIFT to 0x1E
        '@' -> SHIFT to 0x1F
        '#' -> SHIFT to 0x20
        '$' -> SHIFT to 0x21
        '%' -> SHIFT to 0x22
        '^' -> SHIFT to 0x23
        '&' -> SHIFT to 0x24
        '*' -> SHIFT to 0x25
        '(' -> SHIFT to 0x26
        ')' -> SHIFT to 0x27
        '\n' -> 0 to 0x28
        '\t' -> 0 to 0x2B
        ' ' -> 0 to 0x2C
        '-' -> 0 to 0x2D
        '_' -> SHIFT to 0x2D
        '=' -> 0 to 0x2E
        '+' -> SHIFT to 0x2E
        '[' -> 0 to 0x2F
        '{' -> SHIFT to 0x2F
        ']' -> 0 to 0x30
        '}' -> SHIFT to 0x30
        '\\' -> 0 to 0x31
        '|' -> SHIFT to 0x31
        ';' -> 0 to 0x33
        ':' -> SHIFT to 0x33
        '\'' -> 0 to 0x34
        '"' -> SHIFT to 0x34
        '`' -> 0 to 0x35
        '~' -> SHIFT to 0x35
        ',' -> 0 to 0x36
        '<' -> SHIFT to 0x36
        '.' -> 0 to 0x37
        '>' -> SHIFT to 0x37
        '/' -> 0 to 0x38
        '?' -> SHIFT to 0x38
        else -> null
    }

    /** Türkçe Q düzeni (Windows "Türkçe Q"). */
    private fun trMap(c: Char): Pair<Int, Int>? = when (c) {
        // i / ı ayrımı: noktasız ı, ABD'deki "i" tuşunda; noktalı i, ABD'deki "'" tuşunda
        'ı' -> 0 to 0x0C
        'I' -> SHIFT to 0x0C
        'i' -> 0 to 0x34
        'İ' -> SHIFT to 0x34
        in 'a'..'z' -> 0 to (0x04 + (c - 'a'))
        in 'A'..'Z' -> SHIFT to (0x04 + (c - 'A'))
        'ğ' -> 0 to 0x2F
        'Ğ' -> SHIFT to 0x2F
        'ü' -> 0 to 0x30
        'Ü' -> SHIFT to 0x30
        'ş' -> 0 to 0x33
        'Ş' -> SHIFT to 0x33
        'ö' -> 0 to 0x36
        'Ö' -> SHIFT to 0x36
        'ç' -> 0 to 0x37
        'Ç' -> SHIFT to 0x37
        in '1'..'9' -> 0 to (0x1E + (c - '1'))
        '0' -> 0 to 0x27
        '!' -> SHIFT to 0x1E
        '\'' -> SHIFT to 0x1F
        '^' -> SHIFT to 0x20
        '+' -> SHIFT to 0x21
        '%' -> SHIFT to 0x22
        '&' -> SHIFT to 0x23
        '/' -> SHIFT to 0x24
        '(' -> SHIFT to 0x25
        ')' -> SHIFT to 0x26
        '=' -> SHIFT to 0x27
        '"' -> 0 to 0x35
        'é' -> SHIFT to 0x35
        '*' -> 0 to 0x2D
        '?' -> SHIFT to 0x2D
        '-' -> 0 to 0x2E
        '_' -> SHIFT to 0x2E
        ',' -> 0 to 0x31
        ';' -> SHIFT to 0x31
        '.' -> 0 to 0x38
        ':' -> SHIFT to 0x38
        '<' -> 0 to 0x64
        '>' -> SHIFT to 0x64
        '|' -> ALTGR to 0x64
        '@' -> ALTGR to 0x14
        '€' -> ALTGR to 0x08
        '₺' -> ALTGR to 0x17
        '#' -> ALTGR to 0x20
        '$' -> ALTGR to 0x21
        '{' -> ALTGR to 0x24
        '[' -> ALTGR to 0x25
        ']' -> ALTGR to 0x26
        '}' -> ALTGR to 0x27
        '\\' -> ALTGR to 0x2D
        '~' -> ALTGR to 0x30
        '\n' -> 0 to 0x28
        '\t' -> 0 to 0x2B
        ' ' -> 0 to 0x2C
        else -> null
    }
}
