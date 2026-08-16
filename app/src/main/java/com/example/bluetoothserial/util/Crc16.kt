package com.example.bluetoothserial.util

/** CRC16 校验工具 */
object Crc16 {

    /**
     * Modbus RTU CRC16(多项式 0xA001,初始值 0xFFFF)。
     * 返回 16 位校验值,低字节在前,高字节在后(即 Modbus 报文尾部先发 CRC 低字节)。
     */
    fun modbus(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }
}
