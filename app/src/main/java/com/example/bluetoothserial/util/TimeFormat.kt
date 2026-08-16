package com.example.bluetoothserial.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时间戳格式化工具 */
object TimeFormat {

    private val threadLocal = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }

    /** 毫秒时间戳 -> "HH:mm:ss.SSS" */
    fun stamp(ts: Long): String = threadLocal.get().format(Date(ts))
}
