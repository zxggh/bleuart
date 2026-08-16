package com.example.bluetoothserial.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.bluetoothserial.databinding.ItemDeviceBinding

/** 设备列表项数据 */
data class BtDevice(
    val name: String,
    val address: String,
    val isBle: Boolean,
    val bonded: Boolean = false,
    val rssi: Int = 0
)

/** 设备列表适配器 */
class DeviceAdapter(
    private val items: List<BtDevice>,
    private val onClick: (BtDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    class VH(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.binding.tvDeviceName.text = d.name
        holder.binding.tvDeviceAddress.text = d.address
        holder.binding.tvDeviceExtra.text = when {
            d.isBle && d.rssi != 0 -> "RSSI ${d.rssi} dBm"
            d.bonded -> "已配对"
            else -> ""
        }
        holder.binding.root.setOnClickListener { onClick(d) }
    }
}
