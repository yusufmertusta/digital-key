package com.example.hidpw

/** ABD (US) klavye düzenine göre karakter -> (modifier, HID usage kodu). */
object KeyMapper {
    private const val SHIFT = 0x02

    fun map(c: Char): Pair<Int, Int>? {
        return when (c) {
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
    }
}
