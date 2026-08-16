package com.example.bluetoothserial.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.bluetoothserial.R
import com.example.bluetoothserial.bt.ConnectionManager
import com.example.bluetoothserial.bt.ConnState
import com.example.bluetoothserial.bt.ConnType
import com.example.bluetoothserial.bt.ModuleMode
import com.example.bluetoothserial.data.BleSettingsPrefs
import com.example.bluetoothserial.databinding.FragmentModuleSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

/**
 * 专有模块设置页。
 *
 * 专有模块特征: fff1 接收通知 / fff2 写入发送 / fff3 双向配置。
 * - 透传模式: 启用 fff1 通知 + fff2 写入,关闭 fff3(连接后默认);
 * - 设置模式: 关闭 fff1/fff2,启用 fff3 写入 + 通知(进入前请开启硬件 SET 开关)。
 * 模式切换由 BleUartClient 通过重新 discoverServices + 动态绑定/解绑特征通道完成。
 */
class ModuleSettingsFragment : Fragment() {

    private var _binding: FragmentModuleSettingsBinding? = null
    private val binding get() = _binding!!

    private val stateListener: (ConnState) -> Unit = { refreshStatus() }
    private val modeListener: (ModuleMode) -> Unit = { refreshStatus() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentModuleSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val s = BleSettingsPrefs.load(requireContext())
        binding.etModuleService.setText(s.serviceUuid)
        binding.etModuleRx.setText(if (s.notifyUuid.isBlank()) "fff1" else s.notifyUuid)
        binding.etModuleTx.setText(if (s.writeUuid.isBlank()) "fff2" else s.writeUuid)
        binding.etModuleCfg.setText(if (s.cfgUuid.isBlank()) "fff3" else s.cfgUuid)

        binding.btnEnterConfig.setOnClickListener { confirmEnterConfig() }
        binding.btnExitConfig.setOnClickListener { ConnectionManager.enterPassthroughMode() }
        binding.btnSaveModuleUuid.setOnClickListener { saveUuidConfig() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        ConnectionManager.addStateListener(stateListener)
        ConnectionManager.addModuleModeListener(modeListener)
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.removeStateListener(stateListener)
        ConnectionManager.removeModuleModeListener(modeListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

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
        binding.tvModuleMode.text = getString(
            R.string.module_mode_label,
            if (mode == ModuleMode.CONFIG) getString(R.string.mode_config) else getString(R.string.mode_passthrough)
        )
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

    private fun saveUuidConfig() {
        val service = binding.etModuleService.text?.toString()?.trim().orEmpty()
        val rx = binding.etModuleRx.text?.toString()?.trim().orEmpty()
        val tx = binding.etModuleTx.text?.toString()?.trim().orEmpty()
        val cfg = binding.etModuleCfg.text?.toString()?.trim().orEmpty()

        fun valid(u: String): Boolean {
            val t = u.trim()
            if (t.isEmpty()) return true
            if (t.length == 4 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return true
            return runCatching { UUID.fromString(t) }.isSuccess
        }
        if (!valid(service) || !valid(rx) || !valid(tx) || !valid(cfg)) {
            Toast.makeText(requireContext(), R.string.uuid_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val cur = BleSettingsPrefs.load(requireContext())
        BleSettingsPrefs.save(
            requireContext(),
            cur.copy(serviceUuid = service, notifyUuid = rx, writeUuid = tx, cfgUuid = cfg)
        )
        Toast.makeText(requireContext(), R.string.module_saved, Toast.LENGTH_SHORT).show()
    }
}
