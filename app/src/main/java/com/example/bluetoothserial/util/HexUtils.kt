package com.example.bluetoothserial.util

/**
 * HEX 与 ASCII 转换工具
 */
object HexUtils {

    /** 字节数组 -> 大写 HEX 字符串,字节之间用空格分隔,如 "01 0A FF" */
    fun toHex(data: ByteArray): String =
        data.joinToString(" ") { "%02X".format(it) }

    /**
     * 解析 HEX 字符串为字节数组。
     * 支持任意分隔符(空格、逗号、0x 前缀等),会自动过滤非十六进制字符。
     * 如果字符总数为奇数则返回 null(格式错误)。
     */
    fun parseHex(input: String): ByteArray? {
        val clean = input.replace(Regex("[^0-9a-fA-F]"), "")
        if (clean.isEmpty()) return ByteArray(0)
        if (clean.length % 2 != 0) return null
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * 字节数组 -> 可读 ASCII 显示文本。
     * 可打印字符原样显示,\t \r \n 保留,其余不可见字符显示为 '.'。
     */
    fun toAsciiDisplay(data: ByteArray): String = buildString {
        for (b in data) {
            val c = b.toInt() and 0xFF
            when (c) {
                9, 10, 13 -> append(c.toChar())
                in 32..126 -> append(c.toChar())
                else -> append('.')
            }
        }
    }
}
