package com.example.bluetoothserial.ui

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.example.bluetoothserial.R
import com.example.bluetoothserial.bt.ConnectionManager
import com.example.bluetoothserial.bt.RxData
import com.example.bluetoothserial.databinding.FragmentModbusBinding
import com.example.bluetoothserial.util.Crc16
import com.example.bluetoothserial.util.HexUtils
import com.example.bluetoothserial.util.TimeFormat
import java.io.ByteArrayOutputStream

/**
 * Modbus RTU 调试页:常规功能码 + 自动 CRC16 校验。
 * 本页独立显示发送与接收:发送(TX 蓝色)与接收(RX 绿色)分行显示,
 * 仅显示本页可见期间的实时数据(不显示调试页的历史接收)。
 */
class ModbusFragment : Fragment() {

    private var _binding: FragmentModbusBinding? = null
    private val binding get() = _binding!!

    private val modbusLog = SpannableStringBuilder()
    private val dataListener: (RxData) -> Unit = { appendRx(it) }

    /** 功能码定义: read=读类, multi=写多类 */
    private data class Func(val code: Int, val label: String, val read: Boolean, val multi: Boolean)

    private val functions = listOf(
        Func(0x01, "01 读线圈", true, false),
        Func(0x02, "02 读离散输入", true, false),
        Func(0x03, "03 读保持寄存器", true, false),
        Func(0x04, "04 读输入寄存器", true, false),
        Func(0x05, "05 写单个线圈", false, false),
        Func(0x06, "06 写单个寄存器", false, false),
        Func(0x0F, "0F 写多个线圈", false, true),
        Func(0x10, "10 写多个寄存器", false, true)
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentModbusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.spFunction.adapter = ArrayAdapter(
            requireContext(), R.layout.spinner_item, functions.map { it.label }
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        binding.spFunction.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val f = functions[position]
                binding.etValue.hint = when {
                    f.multi -> getString(R.string.modbus_value_hint_multi)
                    !f.read -> getString(R.string.modbus_value_hint_write)
                    else -> getString(R.string.modbus_value_hint_read)
                }
                updatePreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spFunction.setSelection(2) // 默认 03 读保持寄存器

        binding.etSlave.doAfterTextChanged { updatePreview() }
        binding.etStart.doAfterTextChanged { updatePreview() }
        binding.etValue.doAfterTextChanged { updatePreview() }
        binding.btnSendModbus.setOnClickListener { sendFrame() }
        binding.btnModbusClear.setOnClickListener { clearRx() }

        updatePreview()
    }

    override fun onResume() {
        super.onResume()
        // 仅页面可见时监听,避免未打开本页时也接收显示数据
        if (!isHidden) setListening(true)
        isPageVisible = !isHidden
    }

    override fun onPause() {
        super.onPause()
        setListening(false)
        isPageVisible = false
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        isPageVisible = !hidden
        if (hidden) setListening(false) else if (isResumed) setListening(true)
    }

    private var listening = false

    private fun setListening(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) ConnectionManager.addDataListener(dataListener)
        else ConnectionManager.removeDataListener(dataListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        setListening(false)
        flushHandler.removeCallbacks(flushRunnable)
        _binding = null
    }

    // ================= 本页收发显示(分颜色/方向,按 50ms 间隔合并一条响应的分片) =================

    private val rxLineBuffer = ByteArrayOutputStream()
    private var rxLineStartTs = 0L
    private val flushHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val flushRunnable = Runnable { flushPendingRxLine() }

    private fun appendRx(rx: RxData) {
        if (rxLineStartTs == 0L) rxLineStartTs = rx.timestamp
        rxLineBuffer.write(rx.bytes)
        processRxBuffer(rx.timestamp)
        flushHandler.removeCallbacks(flushRunnable)
        flushHandler.postDelayed(flushRunnable, RX_LINE_IDLE_MS)
    }

    private fun processRxBuffer(ts: Long) {
        if (rxLineBuffer.size() == 0) return
        val buf = rxLineBuffer.toByteArray()

        // 1) 换行切分(文本兜底)
        var lastNl = -1
        for (i in buf.indices) {
            if (buf[i] == 0x0A.toByte()) lastNl = i
        }
        if (lastNl >= 0) {
            var displayEnd = lastNl
            while (displayEnd > 0 && (buf[displayEnd - 1] == 0x0A.toByte() || buf[displayEnd - 1] == 0x0D.toByte())) {
                displayEnd--
            }
            commitRxLine(buf.copyOfRange(0, displayEnd), rxLineStartTs)
            val rest = buf.copyOfRange(lastNl + 1, buf.size)
            rxLineBuffer.reset()
            if (rest.isNotEmpty()) rxLineBuffer.write(rest)
            rxLineStartTs = if (rest.isNotEmpty()) ts else 0L
            if (rest.isNotEmpty()) processRxBuffer(ts)
            return
        }

        // 2) Modbus 帧长判断:按帧头(从站+功能码+长度)计算整帧长度,收够即整帧一行
        val expected = expectedFrameLen(buf)
        if (expected != null && buf.size >= expected) {
            commitRxLine(buf.copyOfRange(0, expected), rxLineStartTs)
            val rest = buf.copyOfRange(expected, buf.size)
            rxLineBuffer.reset()
            if (rest.isNotEmpty()) rxLineBuffer.write(rest)
            rxLineStartTs = if (rest.isNotEmpty()) ts else 0L
            if (rest.isNotEmpty()) processRxBuffer(ts)
            return
        }

        // 3) 超长保护
        if (buf.size >= MAX_LINE_BYTES) {
            flushPendingRxLine()
        }
    }

    /**
     * 根据 Modbus RTU 帧头计算整帧长度:
     * 读类(01-04): 3 + 字节数 + 2(CRC);写单类(05/06/0F/10): 8;异常响应(功能码>=0x80): 5。
     */
    private fun expectedFrameLen(buf: ByteArray): Int? {
        if (buf.size < 2) return null
        val func = buf[1].toInt() and 0xFF
        val isError = func and 0x80 != 0
        val f = func and 0x7F
        return when {
            isError -> 5
            f in 1..4 -> if (buf.size < 3) null else 3 + (buf[2].toInt() and 0xFF) + 2
            f == 5 || f == 6 || f == 15 || f == 16 -> 8
            else -> null
        }
    }

    private fun flushPendingRxLine() {
        if (rxLineBuffer.size() == 0) return
        val buf = rxLineBuffer.toByteArray()
        // 已知整帧长度但未收完时,等待帧收完整(不提前截断)
        val expected = expectedFrameLen(buf)
        if (expected != null && buf.size < expected) return
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
        val sb = SpannableStringBuilder()
        sb.append("[").append(TimeFormat.stamp(ts)).append("] RX< ")
            .append(HexUtils.toHex(bytes)).append("\n")
        if (sb.length > 1) {
            sb.setSpan(ForegroundColorSpan(RX_COLOR), 0, sb.length - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        appendDisplay(sb)
    }

    private fun appendTx(bytes: ByteArray) {
        // 发送前先刷新待显示的接收,让 TX/RX 交替
        flushPendingRxLine()
        val sb = SpannableStringBuilder()
        sb.append("[").append(TimeFormat.stamp(System.currentTimeMillis())).append("] TX> ")
            .append(HexUtils.toHex(bytes)).append("\n")
        if (sb.length > 1) {
            sb.setSpan(ForegroundColorSpan(TX_COLOR), 0, sb.length - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        appendDisplay(sb)
    }

    private fun appendDisplay(sb: SpannableStringBuilder) {
        modbusLog.append(sb)
        if (modbusLog.length > MAX_RX_CHARS) {
            modbusLog.delete(0, modbusLog.length - MAX_RX_CHARS)
        }
        binding.tvModbusRx.text = modbusLog
        binding.scrollModbusRx.post { binding.scrollModbusRx.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearRx() {
        flushHandler.removeCallbacks(flushRunnable)
        rxLineBuffer.reset()
        rxLineStartTs = 0L
        modbusLog.delete(0, modbusLog.length)
        binding.tvModbusRx.text = ""
    }

    // ================= 帧生成与发送 =================

    private fun currentFunc(): Func = functions[binding.spFunction.selectedItemPosition.coerceIn(0, functions.size - 1)]

    /** 构建 Modbus RTU 帧(含 CRC16),参数无效返回 null */
    private fun buildFrame(): ByteArray? {
        val slave = binding.etSlave.text?.toString()?.toIntOrNull() ?: return null
        if (slave !in 1..247) return null
        val start = binding.etStart.text?.toString()?.toIntOrNull() ?: return null
        if (start !in 0..0xFFFF) return null

        val f = currentFunc()
        val body = ByteArrayOutputStream()
        body.write(slave and 0xFF)
        body.write(f.code)
        body.write((start shr 8) and 0xFF)
        body.write(start and 0xFF)

        when {
            f.read -> {
                val count = binding.etValue.text?.toString()?.toIntOrNull() ?: return null
                if (count !in 1..2000) return null
                body.write((count shr 8) and 0xFF)
                body.write(count and 0xFF)
            }
            f.code == 0x05 -> {
                val v = binding.etValue.text?.toString()?.toIntOrNull() ?: return null
                body.write(if (v != 0) 0xFF else 0x00)
                body.write(0x00)
            }
            f.code == 0x06 -> {
                val v = binding.etValue.text?.toString()?.toIntOrNull() ?: return null
                if (v !in 0..0xFFFF) return null
                body.write((v shr 8) and 0xFF)
                body.write(v and 0xFF)
            }
            f.multi -> {
                val parts = binding.etValue.text?.toString()
                    ?.split(',', '，')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
                val values = parts.mapNotNull { it.toIntOrNull() }
                if (values.size != parts.size || values.isEmpty()) return null

                val count = values.size
                body.write((count shr 8) and 0xFF)
                body.write(count and 0xFF)

                if (f.code == 0x10) {
                    // 写多寄存器:每值 2 字节
                    body.write(values.size * 2)
                    values.forEach { v ->
                        if (v !in 0..0xFFFF) return null
                        body.write((v shr 8) and 0xFF)
                        body.write(v and 0xFF)
                    }
                } else {
                    // 写多线圈:位打包
                    val byteCount = (values.size + 7) / 8
                    body.write(byteCount)
                    var i = 0
                    while (i < values.size) {
                        var b = 0
                        for (bit in 0 until 8) {
                            if (i < values.size && values[i] != 0) b = b or (1 shl bit)
                            i++
                        }
                        body.write(b)
                    }
                }
            }
        }

        val data = body.toByteArray()
        val crc = Crc16.modbus(data)
        return data + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    private fun updatePreview() {
        val frame = buildFrame()
        binding.tvFramePreview.text = if (frame == null) {
            getString(R.string.modbus_err_value)
        } else {
            HexUtils.toHex(frame)
        }
    }

    private fun sendFrame() {
        val frame = buildFrame()
        if (frame == null) {
            Toast.makeText(requireContext(), R.string.modbus_err_value, Toast.LENGTH_SHORT).show()
            return
        }
        if (ConnectionManager.send(frame)) {
            appendTx(frame)
            Toast.makeText(requireContext(), "已发送 ${frame.size} 字节", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), R.string.not_connected_msg, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** 本页是否可见:可见期间调试窗口暂停显示接收,数据只在本页展示 */
        @Volatile
        var isPageVisible: Boolean = false

        private const val MAX_RX_CHARS = 300_000
        private const val MAX_LINE_BYTES = 4096
        private const val RX_LINE_IDLE_MS = 100L
        private const val TX_COLOR = 0xFF1E88E5.toInt()
        private const val RX_COLOR = 0xFF43A047.toInt()
    }
}
