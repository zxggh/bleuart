package com.example.bluetoothserial.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bluetoothserial.MainActivity
import com.example.bluetoothserial.R
import com.example.bluetoothserial.bt.ConnectionManager
import com.example.bluetoothserial.data.BleSettingsPrefs
import com.example.bluetoothserial.databinding.FragmentDevicesBinding

/**
 * 设备页:经典蓝牙 / BLE 扫描与连接。
 * 扫描结果高频刷新时合并通知,避免点击丢失。
 */
class DeviceFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!

    private val deviceList = mutableListOf<BtDevice>()
    private lateinit var adapter: DeviceAdapter
    private var bleMode = true
    private var scanning = false
    private var classicReceiver: BroadcastReceiver? = null
    private var refreshScheduled = false

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            addDevice(
                BtDevice(
                    name = d.name?.takeIf { it.isNotBlank() } ?: "未知设备",
                    address = d.address,
                    isBle = true,
                    bonded = d.bondState == BluetoothDevice.BOND_BONDED,
                    rssi = result.rssi
                )
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            updateScanButtons()
            Toast.makeText(
                requireContext(),
                getString(R.string.ble_scan_failed, errorCode),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = DeviceAdapter(deviceList) { onDeviceClicked(it) }
        binding.rvDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDevices.adapter = adapter

        binding.toggleBtType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            bleMode = checkedId == R.id.btBle
            stopScan()
            refreshDevices()
        }
        binding.toggleBtType.check(R.id.btBle)

        binding.btnScan.setOnClickListener { startScan() }
        binding.btnStop.setOnClickListener { stopScan() }
        refreshDevices()
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopScan()
        _binding = null
    }

    // ================= 扫描 =================

    private fun startScan() {
        val activity = activity as? MainActivity ?: return
        activity.ensureBluetoothPermissions {
            activity.ensureBluetoothOn {
                doStartScan()
            }
        }
    }

    private fun doStartScan() {
        deviceList.clear()
        adapter.notifyDataSetChanged()

        if (bleMode) {
            val scanner = try { bluetoothAdapter()?.bluetoothLeScanner } catch (_: Exception) { null }
            if (scanner == null) {
                Toast.makeText(requireContext(), R.string.bt_not_supported, Toast.LENGTH_LONG).show()
                return
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, leScanCallback)
            scanning = true
        } else {
            val bt = bluetoothAdapter() ?: return
            try { if (bt.isDiscovering) bt.cancelDiscovery() } catch (_: Exception) {}
            classicReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val d = getDevice(intent) ?: return
                            addDevice(
                                BtDevice(
                                    name = d.name?.takeIf { it.isNotBlank() } ?: "未知设备",
                                    address = d.address,
                                    isBle = false,
                                    bonded = d.bondState == BluetoothDevice.BOND_BONDED
                                )
                            )
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            scanning = false
                            updateScanButtons()
                            unregisterClassicReceiver()
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(
                requireContext(),
                classicReceiver!!,
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            try {
                bt.startDiscovery()
            } catch (e: SecurityException) {
                Toast.makeText(requireContext(), R.string.permission_denied, Toast.LENGTH_LONG).show()
                unregisterClassicReceiver()
                return
            }
            scanning = true
        }
        updateScanButtons()
    }

    private fun stopScan() {
        if (!scanning) return
        if (bleMode) {
            try { bluetoothAdapter()?.bluetoothLeScanner?.stopScan(leScanCallback) } catch (_: Exception) {}
        } else {
            try { bluetoothAdapter()?.cancelDiscovery() } catch (_: Exception) {}
            unregisterClassicReceiver()
        }
        scanning = false
        updateScanButtons()
    }

    private fun updateScanButtons() {
        binding.btnScan.visibility = if (scanning) View.GONE else View.VISIBLE
        binding.btnStop.visibility = if (scanning) View.VISIBLE else View.GONE
    }

    private fun unregisterClassicReceiver() {
        classicReceiver?.let { r ->
            try { requireContext().unregisterReceiver(r) } catch (_: Exception) {}
        }
        classicReceiver = null
    }

    @Suppress("DEPRECATION")
    private fun getDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    /** 列出已配对设备(经典模式)与已配对 BLE 设备 */
    private fun refreshDevices() {
        deviceList.clear()
        val bt = try { bluetoothAdapter() } catch (_: Exception) { null }
        if (bt != null && bt.isEnabled) {
            val bonded = try { bt.bondedDevices } catch (_: Exception) { emptySet<BluetoothDevice>() }
            bonded.forEach { d ->
                val isLe = d.type == BluetoothDevice.DEVICE_TYPE_LE ||
                    d.type == BluetoothDevice.DEVICE_TYPE_DUAL
                if (bleMode) {
                    if (isLe) addDevice(BtDevice(d.name?.takeIf { it.isNotBlank() } ?: "未知设备", d.address, true, true))
                } else {
                    if (d.type == BluetoothDevice.DEVICE_TYPE_CLASSIC || d.type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                        addDevice(BtDevice(d.name?.takeIf { it.isNotBlank() } ?: "未知设备", d.address, false, true))
                    }
                }
            }
        }
        adapter.notifyDataSetChanged()
        updateScanButtons()
    }

    /** 新增设备立即刷新;已有设备(RSSI 更新)合并刷新,避免高频重建导致点击丢失 */
    private fun addDevice(d: BtDevice) {
        val idx = deviceList.indexOfFirst { it.address == d.address && it.isBle == d.isBle }
        if (idx >= 0) {
            deviceList[idx] = d
        } else {
            deviceList.add(d)
        }
        // 专有模块(E104-BT5005A)排到最前,方便连接
        deviceList.sortWith(devicePriorityComparator)
        adapter.notifyDataSetChanged()
    }

    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        view?.postDelayed({
            refreshScheduled = false
            deviceList.sortWith(devicePriorityComparator)
            adapter.notifyDataSetChanged()
        }, 300)
    }

    /** 名称含 E104-BT5005A 的专有模块排最前 */
    private val devicePriorityComparator = Comparator<BtDevice> { a, b ->
        val pa = a.name.contains(PRIORITY_NAME)
        val pb = b.name.contains(PRIORITY_NAME)
        when {
            pa && !pb -> -1
            !pa && pb -> 1
            else -> 0
        }
    }

    // ================= 连接 =================

    private fun onDeviceClicked(device: BtDevice) {
        val activity = activity as? MainActivity ?: return
        activity.ensureBluetoothPermissions {
            val bt = try { bluetoothAdapter() } catch (_: Exception) { null }
            if (bt == null) {
                Toast.makeText(requireContext(), R.string.bt_not_supported, Toast.LENGTH_LONG).show()
                return@ensureBluetoothPermissions
            }
            val dev = try { bt.getRemoteDevice(device.address) } catch (_: Exception) { null }
            if (dev == null) {
                Toast.makeText(requireContext(), R.string.device_invalid, Toast.LENGTH_SHORT).show()
                return@ensureBluetoothPermissions
            }
            stopScan()
            ConnectionManager.errorCallback = { msg ->
                Toast.makeText(requireContext(), msg ?: getString(R.string.conn_failed), Toast.LENGTH_LONG).show()
            }
            if (device.isBle) {
                val settings = BleSettingsPrefs.load(requireContext())
                ConnectionManager.connectBle(requireContext(), dev, settings)
            } else {
                ConnectionManager.connectClassic(dev)
            }
            Toast.makeText(
                requireContext(),
                getString(R.string.connecting_to, device.name),
                Toast.LENGTH_SHORT
            ).show()
            activity.switchToConsole()
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? = try {
        val bm = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bm.adapter
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val PRIORITY_NAME = "E104-BT5005A"
    }
}
