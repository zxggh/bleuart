package com.example.bluetoothserial.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothserial.data.CustomCommand
import com.example.bluetoothserial.databinding.ItemCommandBinding
import com.example.bluetoothserial.model.DataFormat

/** 自定义命令列表适配器:点击发送,长按编辑 */
class CommandAdapter(
    private val items: List<CustomCommand>,
    private val onSend: (CustomCommand) -> Unit,
    private val onEdit: (CustomCommand) -> Unit
) : RecyclerView.Adapter<CommandAdapter.VH>() {

    class VH(val binding: ItemCommandBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCommandBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.binding.tvCmdName.text = c.name
        holder.binding.tvCmdFormat.text = if (c.format == DataFormat.HEX) "HEX" else "ASCII"
        holder.binding.tvCmdData.text = c.data
        holder.binding.root.setOnClickListener { onSend(c) }
        holder.binding.root.setOnLongClickListener {
            onEdit(c)
            true
        }
    }
}
