package com.example.bluetoothserial.data

import android.content.Context
import java.util.UUID

/**
 * BLE 连接设置:自定义 UUID 与 MTU。
 * 支持 4 位缩写(如 fff1 自动展开为 0000fff1-0000-1000-8000-00805f9b34fb)。
 */
data class BleSettings(
    val serviceUuid: String = "",
    val writeUuid: String = "",
    val notifyUuid: String = "",
    val cfgUuid: String = "",
    val mtu: Int = 247
) {
    fun parseService(): UUID? = parse(serviceUuid)
    fun parseWrite(): UUID? = parse(writeUuid)
    fun parseNotify(): UUID? = parse(notifyUuid)
    fun parseCfg(): UUID? = parse(cfgUuid)

    private fun parse(raw: String): UUID? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val norm = if (s.length == 4 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "0000${s.lowercase()}-0000-1000-8000-00805f9b34fb"
        } else s
        return runCatching { UUID.fromString(norm) }.getOrNull()
    }
}

object BleSettingsPrefs {
    private const val PREFS = "ble_settings"

    fun load(context: Context): BleSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BleSettings(
            serviceUuid = p.getString("service", "") ?: "",
            writeUuid = p.getString("write", "") ?: "",
            notifyUuid = p.getString("notify", "") ?: "",
            cfgUuid = p.getString("cfg", "") ?: "",
            mtu = p.getInt("mtu", 247).coerceIn(23, 517)
        )
    }

    fun save(context: Context, s: BleSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("service", s.serviceUuid)
            .putString("write", s.writeUuid)
            .putString("notify", s.notifyUuid)
            .putString("cfg", s.cfgUuid)
            .putInt("mtu", s.mtu.coerceIn(23, 517))
            .apply()
    }
}
