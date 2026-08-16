package com.example.bluetoothserial.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
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
 * 调试页:连接状态、接收区(HEX/ASCII 下拉 + 接收编码 + 时间戳 + 收发颜色区分)、
 * 发送区(HEX/ASCII 下拉 + 发送编码 + 行尾 + 定时发送 + 历史记录)、BLE 设置(自定义 UUID + MTU)。
 *
 * 接收采用"行缓冲"渲染:BLE 分包到达的通知先合并进当前行,遇到换行(或超长)
 * 才提交为一行,避免一条回复被拆成多行;未暂停时始终自动滚动到最后一行。
 */
class ConsoleFragment : Fragment() {

    private var _binding: FragmentConsoleBinding? = null
    private val binding get() = _binding!!

    private val displayQueue = ArrayDeque<DisplayItem>()
    private val logBuilder = SpannableStringBuilder()

    // 接收行缓冲(合并分包)
    private val rxLineBuffer = ByteArrayOutputStream()
    private var rxLineStartTs = 0L

    private var rxBytesTotal = 0L
    private var txBytesTotal = 0L
    private var rxFormatHex = true
    private var txFormatHex = true
    private var paused = false
    private var loopJob: Job? = null
    private var rxCharset: TextCharset = TextCharset.UTF8
    private var txCharset: TextCharset = TextCharset.UTF8
    private lateinit var historyRepo: SendHistoryRepository

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

        // 接收/发送格式(HEX/ASCII 下拉,默认 HEX)
        rxFormatHex = setupFormatSpinner(binding.spRxFormat, KEY_FORMAT_RX, defaultHex = true) { hex ->
            rxFormatHex = hex
            renderFromQueue()
        }
        txFormatHex = setupFormatSpinner(binding.spTxFormat, KEY_FORMAT_TX, defaultHex = true) { hex ->
            txFormatHex = hex
        }

        // 接收编码 / 发送编码(独立)
        rxCharset = setupCharsetSpinner(binding.spCharset, KEY_CHARSET_RX)
        txCharset = setupCharsetSpinner(binding.spTxCharset, KEY_CHARSET_TX)

        binding.spLineEnd.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, lineEndingLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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

    /** 初始化格式下拉框(HEX/ASCII),返回当前是否为 HEX */
    private fun setupFormatSpinner(spinner: Spinner, prefsKey: String, defaultHex: Boolean, onSelect: (Boolean) -> Unit): Boolean {
        val options = arrayOf(getString(R.string.format_hex), getString(R.string.format_text))
        spinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, options
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val savedHex = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt(prefsKey, if (defaultHex) 0 else 1) == 0
        spinner.setSelection(if (savedHex) 0 else 1)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit().putInt(prefsKey, position).apply()
                onSelect(position == 0)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return savedHex
    }

    /** 初始化编码下拉框,返回当前选中的编码 */
    private fun setupCharsetSpinner(spinner: Spinner, prefsKey: String): TextCharset {
        val entries = TextCharset.entries
        spinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, entries.map { it.label }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val saved = TextCharset.fromName(
            requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(prefsKey, "UTF-8") ?: "UTF-8"
        )
        spinner.setSelection(entries.indexOfFirst { it == saved }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val cs = entries[position]
                requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit().putString(prefsKey, cs.javaName).apply()
                if (prefsKey == KEY_CHARSET_RX) {
                    rxCharset = cs
                    renderFromQueue()
                } else {
                    txCharset = cs
                }
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

    // ================= 接收(行缓冲合并分包) =================

    private fun appendReceived(rx: RxData) {
        rxBytesTotal += rx.bytes.size
        if (rxLineStartTs == 0L) rxLineStartTs = rx.timestamp
        rxLineBuffer.write(rx.bytes)

        val buf = rxLineBuffer.toByteArray()
        var lastNl = -1
        for (i in buf.indices) {
            if (buf[i] == 0x0A.toByte()) lastNl = i
        }
        if (lastNl >= 0 || buf.size >= MAX_LINE_BYTES) {
            val lineEnd = if (lastNl >= 0) lastNl else buf.size
            // 去掉行尾 \r\n 仅用于显示
            var displayEnd = lineEnd
            while (displayEnd > 0 && (buf[displayEnd - 1] == 0x0A.toByte() || buf[displayEnd - 1] == 0x0D.toByte())) {
                displayEnd--
            }
            commitRxLine(buf.copyOfRange(0, displayEnd), rxLineStartTs)

            val rest = buf.copyOfRange(if (lastNl >= 0) lastNl + 1 else buf.size, buf.size)
            rxLineBuffer.reset()
            if (rest.isNotEmpty()) rxLineBuffer.write(rest)
            rxLineStartTs = if (rest.isNotEmpty()) rx.timestamp else 0L
        }
        updateStats()
        autoScroll()
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
        val body = if (rxFormatHex) HexUtils.toHex(rx.bytes) else HexUtils.decode(rx.bytes, rxCharset)
        sb.append(head).append(body).append("\n")
        if (sb.length > 1) {
            sb.setSpan(ForegroundColorSpan(RX_COLOR), 0, sb.length - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** 切换格式/编码/时间戳后重建整个显示区 */
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

    // ================= 更多菜单(暂停/清屏/复制) =================

    private fun showMoreMenu() {
        val menu = PopupMenu(requireContext(), binding.btnMore)
        menu.menu.add(0, 1, 0, if (paused) R.string.resume else R.string.pause)
        menu.menu.add(0, 2, 1, R.string.clear)
        menu.menu.add(0, 3, 2, R.string.copy)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> paused = !paused
                2 -> clearReceive()
                3 -> copyReceive()
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
        val tx = DisplayItem.Tx(bytes, System.currentTimeMillis(), txFormatHex, txCharset.javaName)
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
        val base: ByteArray = if (txFormatHex) {
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
        private const val TX_COLOR = 0xFF1E88E5.toInt()
        private const val RX_COLOR = 0xFF43A047.toInt()
        private const val KEY_FORMAT_RX = "format_rx"
        private const val KEY_FORMAT_TX = "format_tx"
        private const val KEY_CHARSET_RX = "charset_rx"
        private const val KEY_CHARSET_TX = "charset_tx"
    }
}
