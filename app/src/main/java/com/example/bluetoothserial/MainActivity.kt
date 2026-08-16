package com.example.bluetoothserial

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.bluetoothserial.databinding.ActivityMainBinding
import com.example.bluetoothserial.ui.CommandFragment
import com.example.bluetoothserial.ui.ConsoleFragment
import com.example.bluetoothserial.ui.DeviceFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var consoleFragment: ConsoleFragment? = null
    private var commandFragment: CommandFragment? = null
    private var deviceFragment: DeviceFragment? = null

    private var pendingPermissionCallback: (() -> Unit)? = null
    private var pendingBluetoothOnCallback: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                pendingPermissionCallback?.invoke()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
            pendingPermissionCallback = null
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            if (bluetoothAdapter()?.isEnabled == true) {
                pendingBluetoothOnCallback?.invoke()
            }
            pendingBluetoothOnCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            consoleFragment = ConsoleFragment()
            commandFragment = CommandFragment()
            deviceFragment = DeviceFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.container, consoleFragment!!, TAG_CONSOLE)
                .add(R.id.container, commandFragment!!, TAG_COMMANDS)
                .add(R.id.container, deviceFragment!!, TAG_DEVICES)
                .hide(commandFragment!!)
                .hide(deviceFragment!!)
                .commit()
        } else {
            consoleFragment = supportFragmentManager.findFragmentByTag(TAG_CONSOLE) as? ConsoleFragment
            commandFragment = supportFragmentManager.findFragmentByTag(TAG_COMMANDS) as? CommandFragment
            deviceFragment = supportFragmentManager.findFragmentByTag(TAG_DEVICES) as? DeviceFragment
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_console -> showFragment(consoleFragment)
                R.id.nav_commands -> showFragment(commandFragment)
                R.id.nav_devices -> showFragment(deviceFragment)
            }
            true
        }
    }

    private fun showFragment(target: Fragment?) {
        if (target == null) return
        val others = listOfNotNull(consoleFragment, commandFragment, deviceFragment)
            .filter { it !== target }
        supportFragmentManager.beginTransaction().apply {
            others.forEach { hide(it) }
            show(target)
        }.commit()
    }

    /** 切换到调试页 */
    fun switchToConsole() {
        binding.bottomNav.selectedItemId = R.id.nav_console
    }

    /** 切换到设备页 */
    fun switchToDevices() {
        binding.bottomNav.selectedItemId = R.id.nav_devices
    }

    /**
     * 按系统版本请求蓝牙运行时权限:
     * Android 12+ 需要 BLUETOOTH_SCAN / BLUETOOTH_CONNECT;
     * Android 11 及以下需要 ACCESS_FINE_LOCATION(BLE 扫描)。
     */
    fun ensureBluetoothPermissions(onGranted: () -> Unit) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                needed += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                needed += Manifest.permission.BLUETOOTH_SCAN
            }
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }
        if (needed.isEmpty()) {
            onGranted()
        } else {
            pendingPermissionCallback = onGranted
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /** 确保蓝牙已开启 */
    fun ensureBluetoothOn(onGranted: () -> Unit) {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            Toast.makeText(this, R.string.bt_not_supported, Toast.LENGTH_LONG).show()
            return
        }
        if (adapter.isEnabled) {
            onGranted()
        } else {
            pendingBluetoothOnCallback = onGranted
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** 获取蓝牙适配器(需要 BLUETOOTH_CONNECT 权限,失败返回 null) */
    fun bluetoothAdapter(): BluetoothAdapter? = try {
        getSystemService(BluetoothManager::class.java).adapter
    } catch (_: SecurityException) {
        null
    }

    companion object {
        private const val TAG_CONSOLE = "console"
        private const val TAG_COMMANDS = "commands"
        private const val TAG_DEVICES = "devices"
    }
}
