package com.example.bluetoothserial.data

import com.example.bluetoothserial.model.DataFormat

/** 自定义命令 */
data class CustomCommand(
    val id: Long,
    val name: String,
    val data: String,
    val format: DataFormat
)
