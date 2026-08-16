package com.example.bluetoothserial.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.bluetoothserial.R
import com.example.bluetoothserial.bt.ConnectionManager
import com.example.bluetoothserial.bt.ConnState
import com.example.bluetoothserial.bt.ConnType
import com.example.bluetoothserial.bt.ModuleMode
import com.example.bluetoothserial.bt.RxData
import com.example.bluetoothserial.databinding.FragmentModuleSettingsBinding
import com.example.bluetoothserial.model.TextCharset
import com.example.bluetoothserial.util.HexUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 专有模块调试器设置页(E104-BT5005A 等)。
 *
 * 专有模块特征(服务 FFF0): fff1 接收通知 / fff2 写入发送 / fff3 双向配置。
 * - 透传模式: 启用 fff1 通知 + fff2 写入,关闭 fff3(连接后默认);
 * - 设置模式: 关闭 fff1/fff2,启用 fff3 写入 + 通知(空中配置通道)。
 * 进入设置模式后 App 自动发送认证指令 at+auth=123456,认证成功后自动查询
 * at+baud? / at+pari? 并显示当前串口信息(如 115200,8,1,无校验)。
 * 波特率设置成功后: 立即用所选值刷新显示,再自动回读模块确认并更新。
 */
class ModuleSettingsFragment : Fragment() {

    private var _binding: FragmentModuleSettingsBinding? = null
    private val binding get() = _binding!!

    /** 波特率表(与数据手册 AT+BAUD 参数一致) */
    private data class Baud(val index: Int, val bps: String)

    private val baudList = listOf(
        Baud(0, "1200"), Baud(1, "2400"), Baud(2, "4800"), Baud(3, "9600"),
        Baud(4, "14400"), Baud(5, "19200"), Baud(6, "28800"), Baud(7, "38400"),
        Baud(8, "57600"), Baud(9, "76800"), Baud(10, "115200"), Baud(11, "230400"),
        Baud(12, "250000"), Baud(13, "460800")
    )

    /** 待确认的命令类型 */
    private enum class Pending { NONE, AUTH, BAUD_QUERY, PARI_QUERY, BAUD_SET, CMD }

    private var pending = Pending.NONE
    private val replyBuffer = StringBuilder()
    private var timeoutJob: Job? = null
    private var currentParity = "无校验"

    private val stateListener: (ConnState) -> Unit = { refreshStatus() }
    private val modeListener: (ModuleMode) -> Unit = { onModeChanged(it) }
    private val dataListener: (RxData) -> Unit = { onModuleData(it.bytes) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentModuleSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.spBaud.adapter = ArrayAdapter(
            requireContext(), R.layout.spinner_item, baudList.map { it.bps }
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        binding.spBaud.setSelection(10) // 默认 115200

        binding.btnEnterConfig.setOnClickListener { confirmEnterConfig() }
        binding.btnExitConfig.setOnClickListener {
            ConnectionManager.enterPassthroughMode()
            Toast.makeText(requireContext(), R.string.module_exit_prompt, Toast.LENGTH_LONG).show()
        }
        binding.btnSetBaud.setOnClickListener {
            val idx = binding.spBaud.selectedItemPosition.coerceIn(0, baudList.size - 1)
            sendAt("at+baud=${baudList[idx].index}", Pending.BAUD_SET)
        }
        binding.btnSetCmd.setOnClickListener {
            val cmd = binding.etModuleCmd.text?.toString()?.trim().orEmpty()
            if (cmd.isEmpty()) {
                Toast.makeText(requireContext(), R.string.input_empty, Toast.LENGTH_SHORT).show()
            } else {
                sendAt(cmd, Pending.CMD)
            }
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        ConnectionManager.addStateListener(stateListener)
        ConnectionManager.addModuleModeListener(modeListener)
        ConnectionManager.addDataListener(dataListener)
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.removeStateListener(stateListener)
        ConnectionManager.removeModuleModeListener(modeListener)
        ConnectionManager.removeDataListener(dataListener)
        timeoutJob?.cancel()
        pending = Pending.NONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timeoutJob?.cancel()
        _binding = null
    }

    // ================= 模式与自动流程 =================

    private fun onModeChanged(mode: ModuleMode) {
        refreshStatus()
        // 进入设置模式后,App 自动发送空中配置认证指令(默认密码 123456)
        if (mode == ModuleMode.CONFIG && pending == Pending.NONE) {
            autoAuth()
        }
    }

    private fun autoAuth() {
        // 等待特征通道重新绑定完成后再发送
        viewLifecycleOwner.lifecycleScope.launch {
            delay(800)
            if (pending == Pending.NONE && ConnectionManager.state.type == ConnType.BLE) {
                sendAt("at+auth=123456", Pending.AUTH)
            }
        }
    }

    private fun confirmEnterConfig() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.nav_settings)
            .setMessage(R.string.module_set_sw_hint)
            .setPositiveButton(R.string.module_enter_confirm) { _, _ ->
                ConnectionManager.enterModuleConfigMode()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ================= 指令发送与回复解析 =================

    private fun sendAt(cmd: String, type: Pending) {
        val bytes = HexUtils.encode(cmd, TextCharset.UTF8)
        if (!ConnectionManager.send(bytes)) {
            Toast.makeText(requireContext(), R.string.not_connected_msg, Toast.LENGTH_SHORT).show()
            return
        }
        pending = type
        replyBuffer.setLength(0)
        startTimeout(type)
    }

    private fun startTimeout(type: Pending) {
        timeoutJob?.cancel()
        timeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(REPLY_TIMEOUT_MS)
            if (pending == type) {
                pending = Pending.NONE
                // 回读步骤超时时也刷新一次显示,避免信息停留在旧值
                if (type == Pending.BAUD_QUERY || type == Pending.PARI_QUERY) {
                    showUartInfo(currentParity, toast = false)
                }
                Toast.makeText(requireContext(), R.string.module_reply_timeout, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onModuleData(bytes: ByteArray) {
        if (pending == Pending.NONE) return
        replyBuffer.append(HexUtils.decode(bytes, TextCharset.UTF8))
        var text = replyBuffer.toString()
        while (pending != Pending.NONE) {
            val idx = text.indexOf('\n')
            if (idx < 0) break
            val line = text.substring(0, idx).trim('\r', '\n', ' ')
            text = text.substring(idx + 1)
            if (line.isNotEmpty()) processReply(line)
        }
        replyBuffer.setLength(0)
        replyBuffer.append(text)
    }

    private fun processReply(line: String) {
        if (line.startsWith("STA:")) return // 状态打印,忽略
        when {
            line.startsWith("+OK") -> onOk(line)
            line.startsWith("+ERR=") -> {
                val num = line.removePrefix("+ERR=").trim().toIntOrNull() ?: -1
                onErr(num)
            }
            else -> {
                pending = Pending.NONE
                Toast.makeText(
                    requireContext(),
                    getString(R.string.module_reply_unknown, line),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun onOk(line: String) {
        when (pending) {
            Pending.AUTH -> {
                pending = Pending.NONE
                Toast.makeText(requireContext(), R.string.module_auth_ok, Toast.LENGTH_SHORT).show()
                Toast.makeText(requireContext(), R.string.module_querying, Toast.LENGTH_SHORT).show()
                // 认证成功 -> 自动读取串口参数
                sendAt("at+baud?", Pending.BAUD_QUERY)
            }
            Pending.BAUD_QUERY -> {
                val value = line.substringAfter("=", "").trim()
                val idx = value.toIntOrNull()
                if (idx != null && idx in 0 until baudList.size) {
                    binding.spBaud.setSelection(idx)
                }
                pending = Pending.NONE
                sendAt("at+pari?", Pending.PARI_QUERY)
            }
            Pending.PARI_QUERY -> {
                val value = line.substringAfter("=", "").trim()
                val parity = if (value == "1") "偶校验" else "无校验"
                pending = Pending.NONE
                showUartInfo(parity)
            }
            Pending.BAUD_SET -> {
                pending = Pending.NONE
                Toast.makeText(requireContext(), R.string.module_baud_set_ok, Toast.LENGTH_SHORT).show()
                // 立即用所选值刷新显示(无需等待模块),再自动回读确认
                showUartInfo(currentParity, toast = false)
                sendAt("at+baud?", Pending.BAUD_QUERY)
            }
            Pending.CMD -> {
                val t = line
                pending = Pending.NONE
                Toast.makeText(requireContext(), getString(R.string.module_reply_ok, t), Toast.LENGTH_SHORT).show()
            }
            Pending.NONE -> {}
        }
    }

    private fun onErr(num: Int) {
        val msg = errText(num)
        val t = pending
        pending = Pending.NONE
        if (t == Pending.AUTH) {
            Toast.makeText(
                requireContext(),
                getString(R.string.module_auth_fail, num, msg),
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.module_reply_err, num, msg),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showUartInfo(parity: String, toast: Boolean = true) {
        currentParity = parity
        val sel = binding.spBaud.selectedItemPosition.coerceIn(0, baudList.size - 1)
        val info = "${baudList[sel].bps},8,1,$parity"
        val text = getString(R.string.module_uart_info_label) + "：" + info
        binding.tvModuleUartInfo.text = text
        if (toast) {
            Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
        }
    }

    /** 错误代码 -> 含义(数据手册 6.2 错误代码表) */
    private fun errText(num: Int): String {
        val arr = resources.getStringArray(R.array.err_codes)
        return if (num in arr.indices) arr[num] else "未知错误($num)"
    }

    // ================= 状态刷新 =================

    private fun refreshStatus() {
        val s = ConnectionManager.state
        val connectedBle = s.type == ConnType.BLE
        val pro = connectedBle && ConnectionManager.isProprietaryModule
        binding.btnEnterConfig.isEnabled = pro
        binding.btnExitConfig.isEnabled = pro

        val status = when {
            !connectedBle -> getString(R.string.module_status_none)
            !pro -> getString(R.string.module_status_normal)
            else -> getString(R.string.module_status_proprietary)
        }
        binding.tvModuleStatus.text = getString(R.string.module_status, status)

        val mode = ConnectionManager.currentModuleMode()
        val inConfig = mode == ModuleMode.CONFIG
        binding.tvModuleMode.text = getString(
            R.string.module_mode_label,
            if (inConfig) getString(R.string.mode_config) else getString(R.string.mode_passthrough)
        )
        // 按钮颜色跟随模式:「进入设置模式」仅在设置模式下高亮,「返回透传模式」始终无选中色
        updateModeButtonAppearance(inConfig)
    }

    private fun updateModeButtonAppearance(inConfig: Boolean) {
        val primary = ContextCompat.getColor(requireContext(), R.color.primary)
        binding.btnEnterConfig.setBackgroundTintList(
            if (inConfig) ColorStateList.valueOf(primary) else ColorStateList.valueOf(Color.TRANSPARENT)
        )
        binding.btnEnterConfig.setTextColor(if (inConfig) Color.WHITE else primary)
    }

    companion object {
        private const val REPLY_TIMEOUT_MS = 3000L
    }
}
