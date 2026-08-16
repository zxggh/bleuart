package com.example.bluetoothserial.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * 经典蓝牙 SPP(串口)客户端。
 * 适用于 HC-05 / HC-06 / JDY-31 等经典蓝牙转串口(RS485)模块。
 */
class ClassicSppClient(private val device: BluetoothDevice) {

    companion object {
        /** 标准 SPP 服务 UUID */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val main = Handler(Looper.getMainLooper())
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var thread: Thread? = null
    private var closed = false

    var onConnected: (() -> Unit)? = null
    var onData: ((ByteArray) -> Unit)? = null
    var onClosed: ((String?) -> Unit)? = null

    /** 在后台线程连接(阻塞),结果通过回调回到主线程 */
    @SuppressLint("MissingPermission")
    fun connect() {
        thread = Thread({
            var s: BluetoothSocket? = null
            try {
                s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
            } catch (e: IOException) {
                // 部分设备需要走 channel=1 的方式建立 RFCOMM 连接
                try {
                    s?.close()
                    s = device.javaClass
                        .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                    s.connect()
                } catch (e2: Exception) {
                    try { s?.close() } catch (_: IOException) {}
                    main.post { onClosed?.invoke(e2.message ?: "连接失败") }
                    return@Thread
                }
            }
            socket = s
            input = s!!.inputStream
            output = s!!.outputStream
            main.post { onConnected?.invoke() }
            readLoop()
        }, "classic-spp-${device.address}").apply { start() }
    }

    /** 读取循环:持续读取对端数据并通过回调上报 */
    private fun readLoop() {
        val buffer = ByteArray(1024)
        try {
            while (!closed) {
                val n = input?.read(buffer) ?: -1
                if (n <= 0) break
                val data = buffer.copyOf(n)
                main.post { onData?.invoke(data) }
            }
        } catch (_: IOException) {
            // 连接断开
        } finally {
            main.post { onClosed?.invoke(null) }
        }
    }

    fun send(data: ByteArray): Boolean {
        return try {
            output?.write(data)
            output?.flush()
            true
        } catch (_: IOException) {
            false
        }
    }

    fun close() {
        closed = true
        try { socket?.close() } catch (_: IOException) {}
        thread?.interrupt()
    }
}
