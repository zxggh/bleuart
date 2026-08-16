package com.example.bluetoothserial.bt

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.bluetoothserial.data.BleSettings
import java.util.concurrent.Executors

/** 连接类型 */
enum class ConnType { NONE, CONNECTING, CLASSIC, BLE }

/** 连接状态 */
data class ConnState(
    val type: ConnType = ConnType.NONE,
    val deviceName: String? = null,
    val deviceAddress: String? = null
) {
    val isConnected: Boolean get() = type == ConnType.CLASSIC || type == ConnType.BLE
}

/** 接收到的数据(带时间戳) */
data class RxData(val bytes: ByteArray, val timestamp: Long) {
    override fun equals(other: Any?) =
        other is RxData && other.bytes.contentEquals(bytes) && other.timestamp == timestamp

    override fun hashCode() = bytes.contentHashCode() * 31 + timestamp.hashCode()
}

/**
 * 全局连接管理器:统一管理经典蓝牙 SPP 与 BLE UART 连接,
 * 向 UI 层广播连接状态、接收数据与专有模块模式。
 * 所有回调均发生在主线程。
 */
object ConnectionManager {

    private val main = Handler(Looper.getMainLooper())
    private val stateListeners = mutableListOf<(ConnState) -> Unit>()
    private val dataListeners = mutableListOf<(RxData) -> Unit>()
    private val moduleModeListeners = mutableListOf<(ModuleMode) -> Unit>()

    /** 最近接收数据缓冲,用于页面重建后回放,避免丢数据 */
    private val pending = ArrayDeque<RxData>()
    private const val MAX_PENDING = 2000

    /** 发送统一在单线程执行器上执行,避免阻塞主线程 */
    private val writeExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    var state = ConnState()
        private set

    /** 错误/断开提示回调(由发起连接的页面设置) */
    var errorCallback: ((String) -> Unit)? = null

    /** 当前连接的 BLE 设备是否为专有模块 */
    @Volatile
    var isProprietaryModule: Boolean = false
        private set

    // ---------------- 监听注册 ----------------

    fun addStateListener(listener: (ConnState) -> Unit) {
        stateListeners.add(listener)
        listener(state)
    }

    fun removeStateListener(listener: (ConnState) -> Unit) {
        stateListeners.remove(listener)
    }

    /**
     * @param replayPending 是否回放最近接收的数据(页面重建时使用)
     */
    fun addDataListener(listener: (RxData) -> Unit, replayPending: Boolean = false) {
        dataListeners.add(listener)
        if (replayPending) {
            val snapshot = pending.toList()
            main.post { snapshot.forEach { listener(it) } }
        }
    }

    fun removeDataListener(listener: (RxData) -> Unit) {
        dataListeners.remove(listener)
    }

    fun addModuleModeListener(listener: (ModuleMode) -> Unit) {
        moduleModeListeners.add(listener)
        listener(ble?.mode ?: ModuleMode.PASSTHROUGH)
    }

    fun removeModuleModeListener(listener: (ModuleMode) -> Unit) {
        moduleModeListeners.remove(listener)
    }

    // ---------------- 连接 ----------------

    fun connectClassic(device: BluetoothDevice) {
        disconnect()
        setState(ConnState(ConnType.CONNECTING, device.name ?: device.address, device.address))
        val client = ClassicSppClient(device)
        classic = client
        client.onConnected = {
            setState(ConnState(ConnType.CLASSIC, device.name ?: device.address, device.address))
        }
        client.onData = { data -> dispatchData(data) }
        client.onClosed = { msg ->
            val wasConnected = state.isConnected
            setState(ConnState())
            when {
                msg != null -> errorCallback?.invoke(msg)
                wasConnected -> errorCallback?.invoke("连接已断开")
            }
        }
        client.connect()
    }

    fun connectBle(context: Context, device: BluetoothDevice, settings: BleSettings) {
        disconnect()
        setState(ConnState(ConnType.CONNECTING, device.name ?: device.address, device.address))
        val client = BleUartClient(context.applicationContext, device, settings)
        ble = client
        client.onConnected = {
            isProprietaryModule = client.isProprietary
            setState(ConnState(ConnType.BLE, device.name ?: device.address, device.address))
        }
        client.onData = { data -> dispatchData(data) }
        client.onError = { msg -> errorCallback?.invoke(msg) }
        client.onClosed = {
            val wasConnected = state.isConnected
            setState(ConnState())
            isProprietaryModule = false
            if (wasConnected) errorCallback?.invoke("连接已断开")
        }
        client.onModeChanged = { mode ->
            main.post { moduleModeListeners.toList().forEach { it(mode) } }
        }
        client.connect()
    }

    fun disconnect() {
        val c = classic
        val b = ble
        classic = null
        ble = null
        c?.close()
        b?.close()
        isProprietaryModule = false
        if (state.type != ConnType.NONE) setState(ConnState())
    }

    fun isConnected(): Boolean = state.isConnected

    // ---------------- 专有模块模式切换 ----------------

    /** 进入设置模式(关闭 fff1/fff2,启用 fff3 双向) */
    fun enterModuleConfigMode() {
        ble?.setMode(ModuleMode.CONFIG)
    }

    /** 返回透传模式(启用 fff1 通知 + fff2 写入,关闭 fff3) */
    fun enterPassthroughMode() {
        ble?.setMode(ModuleMode.PASSTHROUGH)
    }

    /** 当前模块模式 */
    fun currentModuleMode(): ModuleMode = ble?.mode ?: ModuleMode.PASSTHROUGH

    // ---------------- 发送 / 接收 ----------------

    /** 发送字节;连接成功则入队发送并返回 true */
    fun send(data: ByteArray): Boolean {
        if (!isConnected()) return false
        val bytes = data.copyOf()
        writeExecutor.execute {
            when (state.type) {
                ConnType.CLASSIC -> classic?.send(bytes)
                ConnType.BLE -> ble?.send(bytes)
                else -> {}
            }
        }
        return true
    }

    private fun dispatchData(data: ByteArray) {
        val rx = RxData(data, System.currentTimeMillis())
        pending.addLast(rx)
        while (pending.size > MAX_PENDING) pending.removeFirst()
        dataListeners.toList().forEach { it(rx) }
    }

    private fun setState(newState: ConnState) {
        state = newState
        main.post { stateListeners.toList().forEach { it(newState) } }
    }

    private var classic: ClassicSppClient? = null
    private var ble: BleUartClient? = null
}
