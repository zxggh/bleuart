package com.example.bluetoothserial.data

import android.content.Context
import com.example.bluetoothserial.model.DataFormat
import org.json.JSONArray
import org.json.JSONObject

/** 自定义命令的本地持久化(SharedPreferences + JSON) */
class CommandRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): MutableList<CustomCommand> {
        val list = mutableListOf<CustomCommand>()
        val raw = prefs.getString(KEY_COMMANDS, null) ?: return list
        return try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    CustomCommand(
                        id = o.optLong("id", System.currentTimeMillis() + i),
                        name = o.optString("name", "命令"),
                        data = o.optString("data", ""),
                        format = if (o.optString("format", "HEX") == "ASCII") DataFormat.ASCII else DataFormat.HEX
                    )
                )
            }
            list
        } catch (_: Exception) {
            list
        }
    }

    fun save(list: List<CustomCommand>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("data", c.data)
                    put("format", c.format.name)
                }
            )
        }
        prefs.edit().putString(KEY_COMMANDS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "command_lib"
        private const val KEY_COMMANDS = "commands"
    }
}
