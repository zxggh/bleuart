package com.example.bluetoothserial.bt

import android.content.Context
import java.util.UUID

/** BLE 自定义 UUID 设置(SharedPreferences 持久化) */
object BleUuidPrefs {

    private const val PREFS = "ble_uuids"

    /** 读取自定义 UUID,未配置或解析失败返回 null */
    fun read(context: Context): Triple<UUID?, UUID?, UUID?> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun parse(key: String): UUID? =
            p.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return Triple(parse("service"), parse("write"), parse("notify"))
    }

    fun save(context: Context, service: String, write: String, notify: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("service", service)
            .putString("write", write)
            .putString("notify", notify)
            .apply()
    }
}
