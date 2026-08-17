package com.example.bluetoothserial.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bluetoothserial.R
import com.example.bluetoothserial.bt.ConnectionManager
import com.example.bluetoothserial.data.CommandRepository
import com.example.bluetoothserial.data.CustomCommand
import com.example.bluetoothserial.databinding.DialogCommandEditBinding
import com.example.bluetoothserial.databinding.FragmentCommandsBinding
import com.example.bluetoothserial.model.DataFormat
import com.example.bluetoothserial.model.TextCharset
import com.example.bluetoothserial.util.HexUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 自定义命令页:点击发送、长按编辑/删除、FAB 新增,本地持久化
 */
class CommandFragment : Fragment() {

    private var _binding: FragmentCommandsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: CommandRepository
    private val commands = mutableListOf<CustomCommand>()
    private lateinit var adapter: CommandAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCommandsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = CommandRepository(requireContext())
        commands.clear()
        commands.addAll(repo.load())

        adapter = CommandAdapter(
            commands,
            onSend = { sendCommand(it) },
            onEdit = { showEditDialog(it) }
        )
        binding.rvCommands.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCommands.adapter = adapter
        binding.fabAdd.setOnClickListener { showEditDialog(null) }
    }

    // ================= 发送 =================

    private fun sendCommand(cmd: CustomCommand) {
        val bytes = buildBytes(cmd)
        if (bytes == null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.cmd_invalid_format, cmd.name),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (bytes.isEmpty()) {
            Toast.makeText(requireContext(), R.string.input_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (ConnectionManager.send(bytes)) {
            Toast.makeText(
                requireContext(),
                getString(R.string.cmd_sent, cmd.name),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(requireContext(), R.string.not_connected_msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildBytes(cmd: CustomCommand): ByteArray? = when (cmd.format) {
        DataFormat.HEX -> HexUtils.parseHex(cmd.data)
        DataFormat.ASCII -> HexUtils.encode(cmd.data, currentCharset())
    }

    /** 读取调试页选择的发送编码 */
    private fun currentCharset(): TextCharset {
        val name = requireContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getString("charset_tx", "UTF-8") ?: "UTF-8"
        return TextCharset.fromName(name)
    }

    // ================= 编辑 / 新增 =================

    private fun showEditDialog(existing: CustomCommand?) {
        val dlg = DialogCommandEditBinding.inflate(layoutInflater)

        // 提示由 TextInputLayout 标签承担,不再设置输入框 hint,避免重叠

        if (existing != null) {
            dlg.etCmdName.setText(existing.name)
            dlg.etCmdData.setText(existing.data)
            dlg.toggleCmdFormat.check(if (existing.format == DataFormat.HEX) R.id.cmdHex else R.id.cmdAscii)
        } else {
            dlg.toggleCmdFormat.check(R.id.cmdHex)
        }

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.cmd_add else R.string.cmd_edit)
            .setView(dlg.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = dlg.etCmdName.text?.toString()?.trim().orEmpty()
                val data = dlg.etCmdData.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || data.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.cmd_name_data_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val format = if (dlg.toggleCmdFormat.checkedButtonId == R.id.cmdHex) {
                    DataFormat.HEX
                } else {
                    DataFormat.ASCII
                }
                if (existing != null) {
                    val idx = commands.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) commands[idx] = existing.copy(name = name, data = data, format = format)
                } else {
                    val newId = (commands.maxOfOrNull { it.id } ?: 0L) + 1
                    commands.add(CustomCommand(newId, name, data, format))
                }
                repo.save(commands)
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton(R.string.cancel, null)

        if (existing != null) {
            builder.setNeutralButton(R.string.delete) { _, _ ->
                commands.removeAll { it.id == existing.id }
                repo.save(commands)
                adapter.notifyDataSetChanged()
            }
        }
        builder.show()
    }
}
