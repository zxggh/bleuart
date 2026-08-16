package com.example.bluetoothserial.data

import android.content.Context

/** 发送历史记录(SharedPreferences 持久化,最近 50 条) */
class SendHistoryRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<String> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 新增一条记录(去重,新的在最前) */
    fun add(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val list = load().filterNot { it == t }.toMutableList()
        list.add(0, t)
        while (list.size > MAX_ITEMS) list.removeAt(list.size - 1)
        save(list)
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun save(list: List<String>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "send_history"
        private const val KEY_HISTORY = "history"
        private const val MAX_ITEMS = 50
    }
}
