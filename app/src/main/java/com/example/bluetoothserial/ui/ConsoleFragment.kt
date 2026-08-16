package com.example.bluetoothserial.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.bluetoothserial.MainActivity
import com.example.bluetoothserial.R
import com.example.bluetoothserial.bt.ConnectionManager
import com.example.bluetoothserial.bt.ConnState
import com.example.bluetoothserial.bt.ConnType
import com.example.bluetoothserial.bt.RxData
import com.example.bluetoothserial.data.BleSettings
import com.example.bluetoothserial.data.BleSettingsPrefs
import com.example.bluetoothserial.data.SendHistoryRepository
import com.example.bluetoothserial.databinding.DialogUuidBinding
import com.example.bluetoothserial.databinding.FragmentConsoleBinding
import com.example.bluetoothserial.model.TextCharset
import com.example.bluetoothserial.util.HexUtils
import com.example.bluetoothserial.util.TimeFormat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID

/** 接收区显示条目:区分发送(TX)与接收(RX) */
sealed class DisplayItem {
    data class Rx(val rx: RxData) : DisplayItem()
    data class Tx(val bytes: ByteArray, val ts: Long, val hex: Boolean, val charset: String) : DisplayItem()
}

/**
 * 调试页。
 * 格式与编码合并为一个下拉: [HEX][UTF-8][GBK][GB2312][GB18030],默认 HEX;
 * 接收采用"行缓冲"渲染(合并 BLE 分包),未暂停时始终滚动到最后一行;
 * 收发分颜色显示(TX 蓝 / RX 绿);收发计数上下两行显示。
 */
class ConsoleFragment : Fragment() {

    private var _binding: FragmentConsoleBinding? = null
    private val binding get() = _binding!!

    private val displayQueue = ArrayDeque<DisplayItem>()
    private val logBuilder = SpannableStringBuilder()

    // 接收行缓冲(合并分包)
    private val rxLineBuffer = ByteArrayOutputStream()
    private var rxLineStartTs = 0L

    /** 数据停顿后把未换行的部分提交为一行,保证无换行数据也能实时显示 */
    private val flushHandler = Handler(Looper.getMainLooper())
    private val flushRunnable = Runnable { flushPendingRxLine() }

    private var rxBytesTotal = 0L
    private var txBytesTotal = 0L

    /** 0=HEX, 1..N=文本+对应编码 */
    private var rxModeIndex = 0
    private var txModeIndex = 0

    private var paused = false
    private var loopJob: Job? = null
    private lateinit var historyRepo: SendHistoryRepository

    private val rxIsHex get() = rxModeIndex == 0
    private val rxCharset: TextCharset
        get() = if (rxModeIndex == 0) TextCharset.UTF8 else TextCharset.entries[rxModeIndex - 1]
    private val txIsHex get() = txModeIndex == 0
    private val txCharset: TextCharset
        get() = if (txModeIndex == 0) TextCharset.UTF8 else TextCharset.entries[txModeIndex - 1]

    private val lineEndingLabels = arrayOf("无", "\\r", "\\n", "\\r\\n")
    private val lineEndingValues = arrayOf("", "\r", "\n", "\r\n")

    private val stateListener: (ConnState) -> Unit = { updateConnectionUi(it) }
    private val dataListener: (RxData) -> Unit = { appendReceived(it) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConsoleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        historyRepo = SendHistoryRepository(requireContext())

        // 接收/发送格式+编码(合并下拉,默认 HEX)
        rxModeIndex = setupModeSpinner(binding.spRxFormat, KEY_MODE_RX) { renderFromQueue() }
        txModeIndex = setupModeSpinner(binding.spTxFormat, KEY_MODE_TX) {}

        // 行尾
        binding.spLineEnd.adapter = ArrayAdapter(
            requireContext(), R.layout.spinner_item, lineEndingLabels
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

        binding.btnSend.setOnClickListener { sendInput() }
        binding.btnHistory.setOnClickListener { showHistoryDialog() }
        binding.btnMore.setOnClickListener { showMoreMenu() }
        binding.btnDisconnect.setOnClickListener { ConnectionManager.disconnect() }
        binding.btnConnect.setOnClickListener { (activity as? MainActivity)?.switchToDevices() }
        binding.btnUuidSetting.setOnClickListener { showBleSettingsDialog() }

        binding.cbLoop.setOnCheckedChangeListener { _, checked ->
            if (checked) restartLoop() else stopLoop()
        }
        binding.etInterval.doAfterTextChanged { if (binding.cbLoop.isChecked) restartLoop() }

        updateConnectionUi(ConnectionManager.state)
    }

    /**
     * 初始化合并格式下拉: [HEX][UTF-8][GBK][GB2312][GB18030]。
     * 返回当前选中的索引(0=HEX)。
     */
    private fun setupModeSpinner(spinner: Spinner, prefsKey: String, onSelect: (Int) -> Unit): Int {
        val labels = listOf("HEX") + TextCharset.entries.map { it.label }
        spinner.adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, labels).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        val saved = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt(prefsKey, 0).coerceIn(0, labels.size - 1)
        spinner.setSelection(saved)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit().putInt(prefsKey, position).apply()
                onSelect(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return saved
    }

    override fun onResume() {
        super.onResume()
        ConnectionManager.addStateListener(stateListener)
        ConnectionManager.addDataListener(dataListener, replayPending = displayQueue.isEmpty())
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.removeStateListener(stateListener)
        ConnectionManager.removeDataListener(dataListener)
        stopLoop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLoop()
        flushHandler.removeCallbacks(flushRunnable)
        _binding = null
    }

    // ================= 连接状态 =================

    private fun updateConnectionUi(state: ConnState) {
        val dotColor = when (state.type) {
            ConnType.NONE -> R.color.status_disconnected
            ConnType.CONNECTING -> R.color.status_connecting
            else -> R.color.status_connected
        }
        binding.tvStatusDot.setTextColor(ContextCompat.getColor(requireContext(), dotColor))

        when (state.type) {
            ConnType.NONE -> {
                binding.tvDeviceName.text = getString(R.string.not_connected)
                binding.tvConnInfo.text = getString(R.string.conn_info_default)
                binding.btnDisconnect.isEnabled = false
                stopLoop()
            }
            ConnType.CONNECTING -> {
                binding.tvDeviceName.text = state.deviceName ?: "?"
                binding.tvConnInfo.text = getString(R.string.connecting)
                binding.btnDisconnect.isEnabled = true
            }
            ConnType.CLASSIC -> {
                binding.tvDeviceName.text = state.deviceName ?: "?"
                binding.tvConnInfo.text = getString(R.string.conn_classic, state.deviceAddress ?: "")
                binding.btnDisconnect.isEnabled = true
            }
            ConnType.BLE -> {
                binding.tvDeviceName.text = state.deviceName ?: "?"
                binding.tvConnInfo.text = getString(R.string.conn_ble, state.deviceAddress ?: "")
                binding.btnDisconnect.isEnabled = true
            }
        }
    }

    // ================= 接收(行缓冲合并分包 + 空闲刷行) =================

    private fun appendReceived(rx: RxData) {
        rxBytesTotal += rx.bytes.size
        if (rxLineStartTs == 0L) rxLineStartTs = rx.timestamp
        rxLineBuffer.write(rx.bytes)
        processRxBuffer()
        // 数据停顿后提交未换行的部分,保证无换行数据(二进制/Modbus)也能实时显示
        flushHandler.removeCallbacks(flushRunnable)
        flushHandler.postDelayed(flushRunnable, RX_LINE_IDLE_MS)
        updateStats()
        autoScroll()
    }

    /** 处理缓冲中的完整行(按 \n 切分);剩余未换行部分保留在缓冲 */
    private fun processRxBuffer() {
        if (rxLineBuffer.size() == 0) return
        val buf = rxLineBuffer.toByteArray()
        var lastNl = -1
        for (i in buf.indices) {
            if (buf[i] == 0x0A.toByte()) lastNl = i
        }
        if (lastNl >= 0) {
            // 去掉行尾 \r\n 仅用于显示
            var displayEnd = lastNl
            while (displayEnd > 0 && (buf[displayEnd - 1] == 0x0A.toByte() || buf[displayEnd - 1] == 0x0D.toByte())) {
                displayEnd--
            }
            commitRxLine(buf.copyOfRange(0, displayEnd), rxLineStartTs)

            val rest = buf.copyOfRange(lastNl + 1, buf.size)
            rxLineBuffer.reset()
            if (rest.isNotEmpty()) rxLineBuffer.write(rest)
            rxLineStartTs = if (rest.isNotEmpty()) rx.timestamp else 0L
            // 剩余部分可能还包含多行,继续处理
            if (rest.isNotEmpty()) processRxBuffer()
        } else if (buf.size >= MAX_LINE_BYTES) {
            // 超长无换行数据:直接提交为一行
            flushPendingRxLine()
        }
    }

    /** 把缓冲中未换行的数据提交为一行 */
    private fun flushPendingRxLine() {
        if (rxLineBuffer.size() == 0) return
        val buf = rxLineBuffer.toByteArray()
        var displayEnd = buf.size
        while (displayEnd > 0 && (buf[displayEnd - 1] == 0x0A.toByte() || buf[displayEnd - 1] == 0x0D.toByte())) {
            displayEnd--
        }
        commitRxLine(buf.copyOfRange(0, displayEnd), rxLineStartTs)
        rxLineBuffer.reset()
        rxLineStartTs = 0L
    }

    private fun commitRxLine(bytes: ByteArray, ts: Long) {
        if (bytes.isEmpty() && ts == 0L) return
        val line = RxData(bytes, ts)
        displayQueue.addLast(DisplayItem.Rx(line))
        while (displayQueue.size > 5000) displayQueue.removeFirst()
        logBuilder.append(renderRx(line))
        trimLog()
        binding.tvReceive.text = logBuilder
    }

    /** 发送回显(TX 蓝色) */
    private fun renderTx(tx: DisplayItem.Tx): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val head = if (binding.cbTimestamp.isChecked) "[${TimeFormat.stamp(tx.ts)}] TX> " else "TX> "
        val body = if (tx.hex) HexUtils.toHex(tx.bytes) else HexUtils.decode(tx.bytes, TextCharset.fromName(tx.charset))
        sb.append(head).append(body).append("\n")
        if (sb.length > 1) {
            sb.setSpan(ForegroundColorSpan(TX_COLOR), 0, sb.length - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** 接收行(RX 绿色) */
    private fun renderRx(rx: RxData): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val head = if (binding.cbTimestamp.isChecked) "[${TimeFormat.stamp(rx.timestamp)}] RX< " else "RX< "
        val body = if (rxIsHex) HexUtils.toHex(rx.bytes) else HexUtils.decode(rx.bytes, rxCharset)
        sb.append(head).append(body).append("\n")
        if (sb.length > 1) {
            sb.setSpan(ForegroundColorSpan(RX_COLOR), 0, sb.length - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** 切换格式/时间戳后重建整个显示区 */
    private fun renderFromQueue() {
        logBuilder.delete(0, logBuilder.length)
        displayQueue.forEach {
            when (it) {
                is DisplayItem.Rx -> logBuilder.append(renderRx(it.rx))
                is DisplayItem.Tx -> logBuilder.append(renderTx(it))
            }
        }
        trimLog()
        binding.tvReceive.text = if (logBuilder.isEmpty()) getString(R.string.receive_placeholder) else logBuilder
        updateStats()
        autoScroll(force = true)
    }

    private fun updateStats() {
        binding.tvRxStats.text = getString(R.string.stats_rxtx, rxBytesTotal, txBytesTotal)
    }

    private fun trimLog() {
        if (logBuilder.length > MAX_LOG_CHARS) {
            logBuilder.delete(0, logBuilder.length - MAX_LOG_CHARS)
        }
    }

    /** 未暂停时始终滚动到最后一行 */
    private fun autoScroll(force: Boolean = false) {
        if (paused && !force) return
        binding.scrollReceive.post {
            binding.scrollReceive.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun clearReceive() {
        displayQueue.clear()
        logBuilder.delete(0, logBuilder.length)
        flushHandler.removeCallbacks(flushRunnable)
        rxLineBuffer.reset()
        rxLineStartTs = 0L
        rxBytesTotal = 0
        txBytesTotal = 0
        binding.tvReceive.text = getString(R.string.receive_placeholder)
        updateStats()
    }

    private fun copyReceive() {
        val text = binding.tvReceive.text?.toString().orEmpty()
        if (text.isEmpty() || text == getString(R.string.receive_placeholder)) {
            Toast.makeText(requireContext(), R.string.input_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("rx", text))
        Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // ================= 更多菜单(暂停/清屏/复制/更新) =================

    private fun showMoreMenu() {
        val menu = PopupMenu(requireContext(), binding.btnMore)
        menu.menu.add(0, 1, 0, if (paused) R.string.resume else R.string.pause)
        menu.menu.add(0, 2, 1, R.string.clear)
        menu.menu.add(0, 3, 2, R.string.copy)
        menu.menu.add(0, 4, 3, R.string.update_menu)
        menu.menu.add(0, 5, 4, R.string.update_url_setting)
        menu.menu.add(0, 6, 5, R.string.about_menu)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> paused = !paused
                2 -> clearReceive()
                3 -> copyReceive()
                4 -> (activity as? MainActivity)?.checkForUpdates(true)
                5 -> (activity as? MainActivity)?.showUpdateUrlDialog()
                6 -> (activity as? MainActivity)?.showAboutDialog()
            }
            true
        }
        menu.show()
    }

    // ================= 发送 =================

    private fun sendInput() {
        val input = binding.etSend.text?.toString().orEmpty()
        if (input.isBlank()) {
            Toast.makeText(requireContext(), R.string.input_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val data = buildSendBytes(input)
        if (data == null) {
            Toast.makeText(requireContext(), R.string.hex_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (ConnectionManager.send(data)) {
            appendTxSent(data)
            historyRepo.add(input)
            // 成功不弹 Toast,发送内容以蓝色 TX 行回显在接收区
        } else {
            Toast.makeText(requireContext(), R.string.not_connected_msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** 定时发送使用,静默失败 */
    private fun sendNow(): Boolean {
        val input = binding.etSend.text?.toString().orEmpty()
        if (input.isBlank()) return false
        val data = buildSendBytes(input) ?: return false
        return ConnectionManager.send(data)
    }

    private fun appendTxSent(bytes: ByteArray) {
        val tx = DisplayItem.Tx(bytes, System.currentTimeMillis(), txIsHex, txCharset.javaName)
        displayQueue.addLast(tx)
        while (displayQueue.size > 5000) displayQueue.removeFirst()
        txBytesTotal += bytes.size
        logBuilder.append(renderTx(tx))
        trimLog()
        binding.tvReceive.text = logBuilder
        updateStats()
        autoScroll()
    }

    private fun buildSendBytes(input: String): ByteArray? {
        val base: ByteArray = if (txIsHex) {
            HexUtils.parseHex(input) ?: return null
        } else {
            HexUtils.encode(input, txCharset)
        }
        val ending = lineEndingValues[binding.spLineEnd.selectedItemPosition.coerceIn(0, lineEndingValues.size - 1)]
        return if (ending.isEmpty()) base else base + ending.toByteArray(Charsets.UTF_8)
    }

    private fun restartLoop() {
        stopLoop()
        if (!binding.cbLoop.isChecked) return
        val ms = binding.etInterval.text?.toString()?.toLongOrNull()?.coerceAtLeast(20L) ?: 1000L
        loopJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                sendNow()
                delay(ms)
            }
        }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    // ================= 发送历史 =================

    private fun showHistoryDialog() {
        val history = historyRepo.load()
        if (history.isEmpty()) {
            Toast.makeText(requireContext(), R.string.history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.history_title)
            .setItems(history.toTypedArray()) { _, which ->
                val text = history[which]
                binding.etSend.setText(text)
                binding.etSend.setSelection(text.length)
            }
            .setPositiveButton(R.string.cancel, null)
            .setNegativeButton(R.string.clear_history) { _, _ ->
                historyRepo.clear()
                Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ================= BLE 设置(自定义 UUID + MTU) =================

    private fun showBleSettingsDialog() {
        val dlg = DialogUuidBinding.inflate(layoutInflater)
        val s = BleSettingsPrefs.load(requireContext())
        dlg.etUuidService.setText(s.serviceUuid)
        dlg.etUuidWrite.setText(s.writeUuid)
        dlg.etUuidNotify.setText(s.notifyUuid)
        dlg.etUuidCfg.setText(s.cfgUuid)
        dlg.etMtu.setText(s.mtu.toString())

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ble_setting)
            .setView(dlg.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val service = dlg.etUuidService.text?.toString()?.trim().orEmpty()
                val write = dlg.etUuidWrite.text?.toString()?.trim().orEmpty()
                val notify = dlg.etUuidNotify.text?.toString()?.trim().orEmpty()
                val cfg = dlg.etUuidCfg.text?.toString()?.trim().orEmpty()

                fun valid(u: String): Boolean = u.isEmpty() || isUuidOrHex4(u)
                if (!valid(service) || !valid(write) || !valid(notify) || !valid(cfg)) {
                    Toast.makeText(requireContext(), R.string.uuid_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val mtu = dlg.etMtu.text?.toString()?.toIntOrNull()?.coerceIn(23, 517) ?: 247
                BleSettingsPrefs.save(requireContext(), BleSettings(service, write, notify, cfg, mtu))
                Toast.makeText(requireContext(), R.string.uuid_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun isUuidOrHex4(u: String): Boolean {
        val t = u.trim()
        if (t.length == 4 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return true
        return runCatching { UUID.fromString(t) }.isSuccess
    }

    companion object {
        private const val MAX_LOG_CHARS = 400_000
        private const val MAX_LINE_BYTES = 4096
        private const val RX_LINE_IDLE_MS = 150L
        private const val TX_COLOR = 0xFF1E88E5.toInt()
        private const val RX_COLOR = 0xFF43A047.toInt()
        private const val KEY_MODE_RX = "mode_rx"
        private const val KEY_MODE_TX = "mode_tx"
    }
}
