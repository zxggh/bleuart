package com.example.bluetoothserial

import android.Manifest
import android.app.DownloadManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.bluetoothserial.data.AppUpdate
import com.example.bluetoothserial.data.UpdateChecker
import com.example.bluetoothserial.databinding.ActivityMainBinding
import com.example.bluetoothserial.ui.CommandFragment
import com.example.bluetoothserial.ui.ConsoleFragment
import com.example.bluetoothserial.ui.DeviceFragment
import com.example.bluetoothserial.ui.ModbusFragment
import com.example.bluetoothserial.ui.ModuleSettingsFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var consoleFragment: ConsoleFragment? = null
    private var commandFragment: CommandFragment? = null
    private var deviceFragment: DeviceFragment? = null
    private var modbusFragment: ModbusFragment? = null
    private var moduleSettingsFragment: ModuleSettingsFragment? = null

    private var pendingPermissionCallback: (() -> Unit)? = null
    private var pendingBluetoothOnCallback: (() -> Unit)? = null

    // ---------------- 更新下载 ----------------
    private var downloadId: Long = -1L
    private var pendingApkUri: Uri? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != downloadId) return
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = dm.getUriForDownloadedFile(id)
            if (uri != null) {
                installApk(uri)
            } else {
                Toast.makeText(this@MainActivity, R.string.update_fail, Toast.LENGTH_LONG).show()
            }
        }
    }

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
            modbusFragment = ModbusFragment()
            moduleSettingsFragment = ModuleSettingsFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.container, consoleFragment!!, TAG_CONSOLE)
                .add(R.id.container, commandFragment!!, TAG_COMMANDS)
                .add(R.id.container, deviceFragment!!, TAG_DEVICES)
                .add(R.id.container, modbusFragment!!, TAG_MODBUS)
                .add(R.id.container, moduleSettingsFragment!!, TAG_SETTINGS)
                .hide(commandFragment!!)
                .hide(deviceFragment!!)
                .hide(modbusFragment!!)
                .hide(moduleSettingsFragment!!)
                .commit()
        } else {
            consoleFragment = supportFragmentManager.findFragmentByTag(TAG_CONSOLE) as? ConsoleFragment
            commandFragment = supportFragmentManager.findFragmentByTag(TAG_COMMANDS) as? CommandFragment
            deviceFragment = supportFragmentManager.findFragmentByTag(TAG_DEVICES) as? DeviceFragment
            modbusFragment = supportFragmentManager.findFragmentByTag(TAG_MODBUS) as? ModbusFragment
            moduleSettingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as? ModuleSettingsFragment
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_console -> showFragment(consoleFragment)
                R.id.nav_commands -> showFragment(commandFragment)
                R.id.nav_devices -> showFragment(deviceFragment)
                R.id.nav_modbus -> showFragment(modbusFragment)
                R.id.nav_settings -> showFragment(moduleSettingsFragment)
            }
            true
        }

        // 首次启动主动请求蓝牙权限,避免扫描/连接时再弹
        binding.root.post { ensureBluetoothPermissions { } }

        // 注册下载完成监听
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 自动检查更新(每 24 小时静默检查一次,发现新版本才提示)
        autoCheckUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
    }

    private fun showFragment(target: Fragment?) {
        if (target == null) return
        val others = listOfNotNull(
            consoleFragment, commandFragment, deviceFragment, modbusFragment, moduleSettingsFragment
        ).filter { it !== target }
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

    // ---------------- 更新检查与安装 ----------------

    /**
     * 检查更新。
     * @param manual true=用户手动触发(有过程提示); false=自动检查(仅发现新版本才提示)
     */
    fun checkForUpdates(manual: Boolean) {
        if (manual) {
            Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        }
        val current = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
        UpdateChecker.check(current) { ok, update ->
            runOnUiThread {
                if (!ok) {
                    if (manual) Toast.makeText(this, R.string.update_fail, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val u = update
                if (u == null) {
                    if (manual) Toast.makeText(this, R.string.update_latest, Toast.LENGTH_SHORT).show()
                } else {
                    showUpdateDialog(u)
                }
            }
        }
    }

    private fun autoCheckUpdate() {
        val prefs = getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_check", 0L)
        val now = System.currentTimeMillis()
        if (now - last < 24 * 3600 * 1000L) return
        prefs.edit().putLong("last_check", now).apply()
        checkForUpdates(false)
    }

    private fun showUpdateDialog(update: AppUpdate) {
        val notes = update.notes.ifEmpty { getString(R.string.update_no_notes) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_found_title, update.versionName))
            .setMessage(notes)
            .setPositiveButton(R.string.update_download) { _, _ -> downloadAndInstall(update.apkUrl) }
            .setNegativeButton(R.string.update_later, null)
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstall(apkUrl: String) {
        // Android 8.0+ 安装未知应用需授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.update_permission_msg)
                .setPositiveButton(R.string.update_permission_goto) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle(getString(R.string.app_name) + " 更新")
                .setDescription(getString(R.string.update_checking))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalFilesDir(this, null, "zxg-update.apk")
            downloadId = dm.enqueue(req)
            Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.update_fail, Toast.LENGTH_LONG).show()
        }
    }

    private fun installApk(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.update_fail, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- 权限 ----------------

    /**
     * 按系统版本请求蓝牙运行时权限:
     * Android 12+ 需要 BLUETOOTH_SCAN / BLUETOOTH_CONNECT;
     * 定位权限全版本请求(部分国产 ROM 在 12+ 仍要求定位才能扫描 BLE)。
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
        }
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
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
        private const val TAG_MODBUS = "modbus"
        private const val TAG_SETTINGS = "settings"
    }
}
