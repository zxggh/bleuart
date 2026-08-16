package com.example.bluetoothserial.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * BLE UART 客户端。
 * 自动识别常见 UART 服务:Nordic UART Service (NUS)、HM-10 (FFE0)、JDY-08 (FFE5),
 * 也支持在「UUID设置」中手动指定服务 / 写特征 / 通知特征 UUID。
 */
class BleUartClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val customServiceUuid: UUID?,
    private val customWriteUuid: UUID?,
    private val customNotifyUuid: UUID?
) {

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val HM10_SERVICE: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val JDY08_SERVICE: UUID = UUID.fromString("0000FFE5-0000-1000-8000-00805F9B34FB")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        val AUTO_SERVICES = listOf(NUS_SERVICE, HM10_SERVICE, JDY08_SERVICE)
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    var onConnected: (() -> Unit)? = null
    var onData: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun connect() {
        gatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    main.post { onClosed?.invoke() }
                    closeGatt()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                main.post { onError?.invoke("服务发现失败 (status=$status)") }
                closeGatt()
                return
            }
            val service = resolveService(gatt)
            if (service == null) {
                main.post { onError?.invoke("未找到 UART 服务,可在「UUID设置」中配置自定义服务 UUID") }
                closeGatt()
                return
            }
            val write = customWriteUuid?.let { service.getCharacteristic(it) }
                ?: service.characteristics.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }
            val notify = customNotifyUuid?.let { service.getCharacteristic(it) }
                ?: service.characteristics.firstOrNull {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                }
            if (write == null && notify == null) {
                main.post { onError?.invoke("服务中没有可用的读写特征") }
                closeGatt()
                return
            }
            writeChar = write
            notifyChar = notify

            notify?.let { ch ->
                gatt.setCharacteristicNotification(ch, true)
                val cccd = ch.descriptors.firstOrNull { it.uuid == CCCD }
                    ?: BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                gatt.writeDescriptor(cccd)
            }
            try { gatt.requestMtu(247) } catch (_: Exception) {}
            main.post { onConnected?.invoke() }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: ByteArray(0)
            main.post { onData?.invoke(value) }
        }
    }

    /** 解析 UART 服务:优先自定义,其次常见服务,最后兜底找同时有写和通知特征的服务 */
    private fun resolveService(gatt: BluetoothGatt): android.bluetooth.BluetoothGattService? {
        if (customServiceUuid != null) {
            gatt.getService(customServiceUuid)?.let { return it }
        }
        for (uuid in AUTO_SERVICES) {
            gatt.getService(uuid)?.let { return it }
        }
        return gatt.services.firstOrNull { svc ->
            val hasWrite = svc.characteristics.any {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            val hasNotify = svc.characteristics.any {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            }
            hasWrite && hasNotify
        }
    }

    @SuppressLint("MissingPermission")
    fun send(data: ByteArray): Boolean {
        val ch = writeChar ?: return false
        val g = gatt ?: return false
        return try {
            ch.value = data
            ch.writeType = if (
                (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 &&
                (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
            ) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            g.writeCharacteristic(ch)
        } catch (_: Exception) {
            false
        }
    }

    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    fun close() {
        closeGatt()
    }
}
