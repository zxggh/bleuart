package com.example.bluetoothserial.model

/** 文本字符集 */
enum class TextCharset(val label: String, val javaName: String) {
    ASCII("ASCII", "US-ASCII"),
    UTF8("UTF-8", "UTF-8"),
    GBK("GBK", "GBK"),
    GB2312("GB2312", "GB2312"),
    GB18030("GB18030", "GB18030");

    companion object {
        fun fromName(name: String): TextCharset =
            entries.firstOrNull { it.javaName.equals(name, ignoreCase = true) } ?: UTF8
    }
}
