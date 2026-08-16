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
import com.example.bluetoothserial.data.BleSettings
import java.util.UUID

/** 专有模块工作模式 */
enum class ModuleMode { PASSTHROUGH, CONFIG }

/**
 * BLE UART 客户端。
 *
 * 通用模式:自动识别常见 UART 服务(NUS / HM-10 FFE0 / JDY-08 FFE5),
 * 也支持通过 BleSettings 手动指定服务 / 写特征 / 通知特征 UUID。
 *
 * 专有模块模式(特征 fff1 接收通知 / fff2 写入发送 / fff3 双向配置):
 * - 透传模式:启用 fff1 通知 + fff2 写入,关闭 fff3;
 * - 设置模式:关闭 fff1/fff2,启用 fff3 写入 + 通知。
 * 模式切换时重新 discoverServices 并按模式动态绑定/解绑特征通道。
 */
class BleUartClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val settings: BleSettings,
    private val initialMode: ModuleMode = ModuleMode.PASSTHROUGH
) {

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val HM10_SERVICE: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val JDY08_SERVICE: UUID = UUID.fromString("0000FFE5-0000-1000-8000-00805F9B34FB")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // 专有模块默认特征
        val DEF_RX_UUID: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
        val DEF_TX_UUID: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
        val DEF_CFG_UUID: UUID = UUID.fromString("0000FFF3-0000-1000-8000-00805F9B34FB")

        val AUTO_SERVICES = listOf(NUS_SERVICE, HM10_SERVICE, JDY08_SERVICE)
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null

    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var cfgChar: BluetoothGattCharacteristic? = null

    /** 是否为专有模块(检测到 fff1/fff2/fff3 特征) */
    var isProprietary: Boolean = false
        private set

    /** 当前模式 */
    var mode: ModuleMode = initialMode
        private set

    var onConnected: (() -> Unit)? = null
    var onData: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    var onModeChanged: ((ModuleMode) -> Unit)? = null

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
            detectAndBind(gatt)
            try { gatt.requestMtu(settings.mtu.coerceIn(23, 517)) } catch (_: Exception) {}
            main.post { onConnected?.invoke() }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: ByteArray(0)
            main.post { onData?.invoke(value) }
        }
    }

    /** 检测专有模块特征并按其 UUID 绑定;否则走通用 UART 逻辑 */
    private fun detectAndBind(gatt: BluetoothGatt) {
        val rxUuid = settings.parseNotify() ?: DEF_RX_UUID
        val txUuid = settings.parseWrite() ?: DEF_TX_UUID
        val cfgUuid = settings.parseCfg() ?: DEF_CFG_UUID

        val allChars = gatt.services.flatMap { it.characteristics }
        val rx = allChars.firstOrNull { it.uuid == rxUuid }
        val tx = allChars.firstOrNull { it.uuid == txUuid }
        val cfg = allChars.firstOrNull { it.uuid == cfgUuid }

        isProprietary = rx != null && tx != null && cfg != null

        if (isProprietary) {
            cfgChar = cfg
            when (mode) {
                ModuleMode.PASSTHROUGH -> {
                    // 透传:fff1 通知 + fff2 写入,关闭 fff3
                    notifyChar = rx
                    writeChar = tx
                    enableNotify(rx)
                    disableNotify(cfg)
                }
                ModuleMode.CONFIG -> {
                    // 设置:关闭 fff1,fff3 双向
                    disableNotify(rx)
                    notifyChar = cfg
                    writeChar = cfg
                    enableNotify(cfg)
                }
            }
        } else {
            cfgChar = null
            bindGeneric(gatt)
        }
    }

    /** 通用 UART 服务绑定(自动识别或按自定义 UUID) */
    private fun bindGeneric(gatt: BluetoothGatt) {
        val service = settings.parseService()?.let { gatt.getService(it) }
            ?: AUTO_SERVICES.firstNotNullOfOrNull { gatt.getService(it) }
            ?: gatt.services.firstOrNull { svc ->
                val hasWrite = svc.characteristics.any {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }
                val hasNotify = svc.characteristics.any {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                }
                hasWrite && hasNotify
            }
        if (service == null) {
            main.post { onError?.invoke("未找到 UART 服务,请在「BLE设置」中配置自定义 UUID") }
            closeGatt()
            return
        }
        val write = settings.parseWrite()?.let { service.getCharacteristic(it) }
            ?: service.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
        val notify = settings.parseNotify()?.let { service.getCharacteristic(it) }
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
        notify?.let { enableNotify(it) }
    }

    // ================= 模式切换 =================

    /**
     * 切换专有模块工作模式。
     * 先解绑当前通道(关闭全部通知、清空写入特征),
     * 再重新 discoverServices 并按新模式重新绑定。
     */
    @SuppressLint("MissingPermission")
    fun setMode(newMode: ModuleMode) {
        if (!isProprietary) return
        if (mode == newMode) return
        mode = newMode
        // 1) 解绑:关闭所有通知,清空写入特征
        disableNotify(notifyChar)
        disableNotify(cfgChar)
        writeChar = null
        notifyChar = null
        // 2) 重新发现服务,onServicesDiscovered 中按 mode 重新绑定
        try { gatt?.discoverServices() } catch (_: Exception) {}
        main.post { onModeChanged?.invoke(newMode) }
    }

    /** 启用特征通知(写 CCCD 0x0001) */
    @SuppressLint("MissingPermission")
    private fun enableNotify(char: BluetoothGattCharacteristic?) {
        if (char == null) return
        val g = gatt ?: return
        try {
            g.setCharacteristicNotification(char, true)
            writeCccd(char, byteArrayOf(0x01, 0x00))
        } catch (_: Exception) {}
    }

    /** 关闭特征通知(写 CCCD 0x0000) */
    @SuppressLint("MissingPermission")
    private fun disableNotify(char: BluetoothGattCharacteristic?) {
        if (char == null) return
        val g = gatt ?: return
        try {
            g.setCharacteristicNotification(char, false)
            writeCccd(char, byteArrayOf(0x00, 0x00))
        } catch (_: Exception) {}
    }

    /** 写 CCCD 描述符(标准 UART 特征都自带 CCCD) */
    @SuppressLint("MissingPermission")
    private fun writeCccd(char: BluetoothGattCharacteristic, value: ByteArray) {
        val g = gatt ?: return
        val d = char.descriptors.firstOrNull { it.uuid == CCCD } ?: return
        try {
            d.value = value
            g.writeDescriptor(d)
        } catch (_: Exception) {}
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
