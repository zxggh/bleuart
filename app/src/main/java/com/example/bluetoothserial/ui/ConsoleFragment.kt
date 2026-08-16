package com.example.bluetoothserial.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.bluetoothserial.MainActivity
import com.example.bluetoothserial.R
import com.example.bluetoothserial.bt.BleUuidPrefs
import com.example.bluetoothserial.bt.ConnectionManager
import com.example.bluetoothserial.bt.ConnState
import com.example.bluetoothserial.bt.ConnType
import com.example.bluetoothserial.bt.RxData
import com.example.bluetoothserial.databinding.DialogUuidBinding
import com.example.bluetoothserial.databinding.FragmentConsoleBinding
import com.example.bluetoothserial.util.HexUtils
import com.example.bluetoothserial.util.TimeFormat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 调试页:连接状态、接收区(HEX/ASCII + 时间戳)、发送区(HEX/ASCII + 行尾 + 定时发送)
 */
class ConsoleFragment : Fragment() {

    private var _binding: FragmentConsoleBinding? = null
    private val binding get() = _binding!!

    // 接收缓冲
    private val rxQueue = ArrayDeque<RxData>()
    private val logBuffer = StringBuilder()
    private var rxBytesTotal = 0L
    private var rxChunks = 0L
    private var rxFormatHex = true
    private var txFormatHex = true
    private var paused = false
    private var loopJob: Job? = null

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

        binding.toggleRxFormat.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            rxFormatHex = checkedId == R.id.rxHex
            renderFromQueue()
        }
        binding.toggleRxFormat.check(R.id.rxHex)

        binding.toggleTxFormat.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            txFormatHex = checkedId == R.id.txHex
        }
        binding.toggleTxFormat.check(R.id.txHex)

        binding.spLineEnd.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, lineEndingLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        binding.btnSend.setOnClickListener { sendInput() }
        binding.btnClear.setOnClickListener { clearReceive() }
        binding.btnCopy.setOnClickListener { copyReceive() }
        binding.btnPause.setOnClickListener {
            paused = !paused
            binding.btnPause.text = if (paused) getString(R.string.resume) else getString(R.string.pause)
        }
        binding.btnDisconnect.setOnClickListener { ConnectionManager.disconnect() }
        binding.btnConnect.setOnClickListener { (activity as? MainActivity)?.switchToDevices() }
        binding.btnUuidSetting.setOnClickListener { showUuidDialog() }

        binding.cbLoop.setOnCheckedChangeListener { _, checked ->
            if (checked) restartLoop() else stopLoop()
        }
        binding.etInterval.doAfterTextChanged { if (binding.cbLoop.isChecked) restartLoop() }

        updateConnectionUi(ConnectionManager.state)
    }

    override fun onResume() {
        super.onResume()
        ConnectionManager.addStateListener(stateListener)
        // 页面重建(如旋转屏幕)时回放最近接收的数据
        ConnectionManager.addDataListener(dataListener, replayPending = rxQueue.isEmpty())
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

    // ================= 接收 =================

    private fun appendReceived(rx: RxData) {
        rxQueue.addLast(rx)
        rxChunks++
        rxBytesTotal += rx.bytes.size
        while (rxQueue.size > 5000) rxQueue.removeFirst()

        logBuffer.append(formatLine(rx))
        trimLog()
        binding.tvReceive.text = logBuffer.toString()
        binding.tvRxStats.text = getString(R.string.rx_stats, rxBytesTotal, rxChunks)
        autoScroll()
    }

    private fun formatLine(rx: RxData): String {
        val head = if (binding.cbTimestamp.isChecked) "[${TimeFormat.stamp(rx.timestamp)}] " else ""
        val body = if (rxFormatHex) HexUtils.toHex(rx.bytes) else HexUtils.toAsciiDisplay(rx.bytes)
        return head + body + "\n"
    }

    /** 切换显示格式 / 时间戳后,按当前设置重建整个接收区 */
    private fun renderFromQueue() {
        logBuffer.setLength(0)
        rxQueue.forEach { logBuffer.append(formatLine(it)) }
        trimLog()
        binding.tvReceive.text =
            if (logBuffer.isEmpty()) getString(R.string.receive_placeholder) else logBuffer.toString()
        binding.tvRxStats.text = getString(R.string.rx_stats, rxBytesTotal, rxChunks)
        autoScroll(force = true)
    }

    private fun trimLog() {
        if (logBuffer.length > MAX_LOG_CHARS) {
            logBuffer.delete(0, logBuffer.length - MAX_LOG_CHARS)
        }
    }

    private fun autoScroll(force: Boolean = false) {
        binding.scrollReceive.post {
            val sv = binding.scrollReceive
            val child = sv.getChildAt(0) ?: return@post
            val atBottom = force ||
                (!paused && (sv.scrollY + sv.height >= child.height - 200 || child.height <= sv.height))
            if (atBottom) sv.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun clearReceive() {
        rxQueue.clear()
        logBuffer.setLength(0)
        rxBytesTotal = 0
        rxChunks = 0
        binding.tvReceive.text = getString(R.string.receive_placeholder)
        binding.tvRxStats.text = getString(R.string.rx_stats, 0L, 0L)
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
        val sent = ConnectionManager.send(data)
        Toast.makeText(
            requireContext(),
            if (sent) R.string.send_ok else R.string.not_connected_msg,
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 定时发送使用,静默失败 */
    private fun sendNow(): Boolean {
        val input = binding.etSend.text?.toString().orEmpty()
        if (input.isBlank()) return false
        val data = buildSendBytes(input) ?: return false
        return ConnectionManager.send(data)
    }

    private fun buildSendBytes(input: String): ByteArray? {
        val base: ByteArray = if (txFormatHex) {
            HexUtils.parseHex(input) ?: return null
        } else {
            input.toByteArray(Charsets.UTF_8)
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

    // ================= UUID 设置 =================

    private fun showUuidDialog() {
        val dlg = DialogUuidBinding.inflate(layoutInflater)
        val (s, w, n) = BleUuidPrefs.read(requireContext())
        dlg.etUuidService.setText(s?.toString() ?: "")
        dlg.etUuidWrite.setText(w?.toString() ?: "")
        dlg.etUuidNotify.setText(n?.toString() ?: "")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.uuid_setting)
            .setView(dlg.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val sv = dlg.etUuidService.text?.toString()?.trim().orEmpty()
                val wv = dlg.etUuidWrite.text?.toString()?.trim().orEmpty()
                val nv = dlg.etUuidNotify.text?.toString()?.trim().orEmpty()

                fun valid(u: String): Boolean = u.isEmpty() || runCatching { UUID.fromString(u) }.isSuccess
                if (!valid(sv) || !valid(wv) || !valid(nv)) {
                    Toast.makeText(requireContext(), R.string.uuid_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                BleUuidPrefs.save(requireContext(), sv, wv, nv)
                Toast.makeText(requireContext(), R.string.uuid_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val MAX_LOG_CHARS = 400_000
    }
}
