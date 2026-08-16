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
 * 专有模块模式(服务 FFF0,特征 fff1 接收通知 / fff2 写入发送 / fff3 双向配置):
 * - 透传模式:启用 fff1 通知 + fff2 写入,关闭 fff3;
 * - 设置模式:关闭 fff1/fff2,启用 fff3 写入 + 通知。
 *
 * 模式切换直接基于已发现的服务缓存立即重新绑定特征通道
 * (不依赖 discoverServices 的第二次回调,避免部分设备不触发回调导致通道未绑定),
 * discoverServices 仅作尽力重试;CCCD 描述符写入串行化排队,
 * 避免 fff1 关闭与 fff3 开启的并发写入导致模块无响应。
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

    /** CCCD 描述符写入队列(串行化,避免并发写入导致模块不响应) */
    private val descriptorQueue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var descriptorWritePending = false

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

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWritePending = false
            if (descriptorQueue.isNotEmpty()) descriptorQueue.removeFirst()
            processDescriptorQueue()
        }
    }

    // ================= 特征查找与绑定 =================

    /** 检测专有模块特征并绑定;否则走通用 UART 逻辑 */
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
            applyModeBindings()
        } else {
            cfgChar = null
            bindGeneric(gatt)
        }
    }

    /**
     * 按当前模式重新绑定特征通道(直接从已发现的服务缓存查找,不依赖 discoverServices 回调)。
     */
    @SuppressLint("MissingPermission")
    private fun applyModeBindings() {
        val g = gatt ?: return
        // 1) 解绑:关闭所有已启用通知,清空写入特征
        disableNotify(notifyChar)
        disableNotify(cfgChar)
        writeChar = null
        notifyChar = null

        // 2) 从已发现服务中重新查找特征
        val rxUuid = settings.parseNotify() ?: DEF_RX_UUID
        val txUuid = settings.parseWrite() ?: DEF_TX_UUID
        val cfgUuid = settings.parseCfg() ?: DEF_CFG_UUID
        val allChars = g.services.flatMap { it.characteristics }
        val rx = allChars.firstOrNull { it.uuid == rxUuid }
        val tx = allChars.firstOrNull { it.uuid == txUuid }
        val cfg = allChars.firstOrNull { it.uuid == cfgUuid }
        if (rx == null || tx == null || cfg == null) return

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
     * 直接基于已发现的服务缓存重新绑定通道(立即生效),
     * 再尽力 discoverServices 一次(部分固件需要,若回调不触发也不影响)。
     */
    @SuppressLint("MissingPermission")
    fun setMode(newMode: ModuleMode) {
        if (!isProprietary) return
        if (mode == newMode) return
        mode = newMode
        applyModeBindings()
        try { gatt?.discoverServices() } catch (_: Exception) {}
        main.post { onModeChanged?.invoke(newMode) }
    }

    // ================= 通知启停(CCCD 串行写入) =================

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

    /** 写 CCCD 描述符(入队,串行执行) */
    @SuppressLint("MissingPermission")
    private fun writeCccd(char: BluetoothGattCharacteristic, value: ByteArray) {
        descriptorQueue.addLast(char to value)
        processDescriptorQueue()
    }

    @SuppressLint("MissingPermission")
    private fun processDescriptorQueue() {
        if (descriptorWritePending) return
        val g = gatt ?: return
        val next = descriptorQueue.firstOrNull() ?: return
        val d = next.first.descriptors.firstOrNull { it.uuid == CCCD } ?: run {
            descriptorQueue.removeFirst()
            processDescriptorQueue()
            return
        }
        descriptorWritePending = true
        try {
            d.value = next.second
            g.writeDescriptor(d)
        } catch (_: Exception) {
            descriptorWritePending = false
            if (descriptorQueue.isNotEmpty()) descriptorQueue.removeFirst()
            processDescriptorQueue()
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
