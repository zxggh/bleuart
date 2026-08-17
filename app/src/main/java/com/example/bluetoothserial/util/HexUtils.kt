package com.example.bluetoothserial.util

import com.example.bluetoothserial.model.TextCharset
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * HEX 与文本转换工具
 */
object HexUtils {

    /** 字节数组 -> 大写 HEX 字符串,字节之间用空格分隔,如 "01 0A FF" */
    fun toHex(data: ByteArray): String =
        data.joinToString(" ") { "%02X".format(it) }

    /**
     * 解析 HEX 字符串为字节数组。
     * 支持任意分隔符(空格、逗号、0x 前缀等),会自动过滤非十六进制字符。
     * 如果字符总数为奇数,自动在最后一位前补零,如 "123" -> "1203"。
     */
    fun parseHex(input: String): ByteArray? {
        val clean = input.replace(Regex("[^0-9a-fA-F]"), "")
        if (clean.isEmpty()) return ByteArray(0)
        val normalized = if (clean.length % 2 == 1) {
            clean.substring(0, clean.length - 1) + "0" + clean.substring(clean.length - 1)
        } else {
            clean
        }
        return ByteArray(normalized.length / 2) { i ->
            normalized.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** 字节数组 -> 可读显示文本(非打印字节显示为 '.') */
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

    /**
     * 按指定字符集解码字节数组为文本。
     * 非法字节序列替换为 '�'(REPLACE 策略),不会抛异常。
     */
    fun decode(data: ByteArray, charset: TextCharset): String {
        return try {
            val decoder = java.nio.charset.Charset.forName(charset.javaName).newDecoder()
            decoder.onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(data)).toString()
        } catch (_: Exception) {
            toAsciiDisplay(data)
        }
    }

    /** 按指定字符集编码文本为字节数组 */
    fun encode(text: String, charset: TextCharset): ByteArray {
        return try {
            text.toByteArray(java.nio.charset.Charset.forName(charset.javaName))
        } catch (_: Exception) {
            text.toByteArray(Charsets.UTF_8)
        }
    }
}
