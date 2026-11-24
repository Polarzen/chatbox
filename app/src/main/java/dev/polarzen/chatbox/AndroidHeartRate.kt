package dev.polarzen.chatbox

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.location.LocationManagerCompat
import java.util.UUID

private val heartService = ParcelUuid.fromString("0000180d-0000-1000-8000-00805f9b34fb")
private val heartMeasurement = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
private val clientConfiguration = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

internal data class AndroidHeartDevice(
    val address: String,
    val name: String,
    internal val peripheral: BluetoothDevice
) {
    val label: String get() = "${name.ifBlank { "心率设备" }} · $address"
}

@SuppressLint("MissingPermission") // Permissions are checked at start and may be revoked mid-session.
internal class AndroidHeartRate(context: Context, val live: LiveHeartRate) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val discovered = mutableStateListOf<AndroidHeartDevice>()
    val devices: List<AndroidHeartDevice> get() = discovered
    var selected by mutableStateOf<AndroidHeartDevice?>(null)
        private set
    var active by mutableStateOf(false)
        private set
    var bpm by mutableStateOf<Int?>(null)
        private set
    var status by mutableStateOf("请在手环上开启心率广播。")
        private set

    private var closed = false
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var radioReceiver: BroadcastReceiver? = null
    private var timeout: Runnable? = null
    private var deadlineElapsedMs = 0L

    @MainThread
    fun startScan() {
        if (closed) return
        discovered.clear()
        begin(scanning = true, "正在扫描附近的心率广播…") { adapter ->
            val scanner = adapter.bluetoothLeScanner ?: return@begin finish("蓝牙扫描不可用，请检查手机蓝牙。")
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            if (adapter.isLeExtendedAdvertisingSupported) settings.setLegacy(false)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    accept(listOf(result))
                }

                override fun onBatchScanResults(results: List<ScanResult>) {
                    accept(results)
                }

                private fun accept(results: List<ScanResult>) {
                    main.post {
                        if (scanCallback !== this) return@post
                        bluetooth("扫描失败，请重新扫描。") {
                            for (result in results) {
                                val device = result.device
                                val address = device.address
                                val index = discovered.indexOfFirst { it.address == address }
                                val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                                    ?: discovered.getOrNull(index)?.name?.takeIf { it.isNotBlank() }
                                    ?: device.name.orEmpty()
                                val item = AndroidHeartDevice(address, name, device)
                                if (index < 0) discovered.add(item) else discovered[index] = item
                                if (selected?.address == address) selected = item
                            }
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    main.post { if (scanCallback === this) finish("蓝牙扫描失败（$errorCode），请稍后重新扫描。") }
                }
            }
            this.scanner = scanner
            scanCallback = callback
            val filter = ScanFilter.Builder().setServiceUuid(heartService).build()
            scanner.startScan(listOf(filter), settings.build(), callback)
            armTimeout(8_000) {
                if (devices.isEmpty()) "未发现心率广播，请检查手环和手机蓝牙。" else "请选择心率设备。"
            }
        }
    }

    @MainThread
    fun connect(device: AndroidHeartDevice) {
        if (closed) return
        selected = device
        begin(scanning = false, "正在连接 ${device.label}…") {
            // Deliver GATT callbacks on the same looper as start, stop and all observable state.
            gatt = device.peripheral.connectGatt(
                app, false, connectionCallback(), BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, main
            ) ?: return@begin finish("无法连接设备，请靠近手环并确认心率广播已开启。")
            armTimeout(30_000) { "连接超时，请靠近手环并检查心率广播后重新连接。" }
        }
    }

    @MainThread
    fun stop() {
        if (!closed) finish("已停止。")
    }

    @MainThread
    fun refreshAfterResume() {
        val task = timeout ?: return
        main.removeCallbacks(task)
        // Handler delays use uptime. Account for deep sleep since the last phase or notification.
        val remaining = deadlineElapsedMs - SystemClock.elapsedRealtime()
        if (remaining <= 0L) task.run() else main.postDelayed(task, remaining)
    }

    @MainThread
    fun permissionDenied() {
        if (!closed) finish(permissionMessage())
    }

    @MainThread
    fun close() {
        if (closed) return
        closed = true
        finish("已停止。")
    }

    private fun begin(scanning: Boolean, message: String, action: (BluetoothAdapter) -> Unit) {
        finish(message)
        bluetooth(if (scanning) "扫描失败，请检查手机蓝牙。" else "连接失败，请检查手机蓝牙。") {
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                return@bluetooth finish("手机不支持低功耗蓝牙。")
            }
            fun granted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!granted(Manifest.permission.BLUETOOTH_CONNECT) ||
                    (scanning && !granted(Manifest.permission.BLUETOOTH_SCAN))) return@bluetooth permissionDenied()
            } else if (scanning && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                return@bluetooth permissionDenied()
            }
            if (!adapter.isEnabled) return@bluetooth finish("手机蓝牙已关闭，请开启后重试。")
            if (scanning && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                val location = app.getSystemService(LocationManager::class.java)
                if (location != null && !LocationManagerCompat.isLocationEnabled(location)) {
                    return@bluetooth finish("请开启手机定位后重新扫描心率设备。")
                }
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (radioReceiver !== this || intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> finish("手机蓝牙已关闭，请开启后重试。")
                    }
                }
            }
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            radioReceiver = receiver
            active = true
            action(adapter)
        }
    }

    private fun connectionCallback() = object : BluetoothGattCallback() {
        private var measurement: BluetoothGattCharacteristic? = null
        private var received = false

        override fun onConnectionStateChange(client: BluetoothGatt, code: Int, newState: Int) {
            if (gatt !== client) {
                // Older Android versions can finish registering the client after an early close().
                release { client.close() }
                return
            }
            when {
                code != BluetoothGatt.GATT_SUCCESS -> finish("蓝牙连接异常（$code），请靠近手环后重新连接。")
                newState == BluetoothProfile.STATE_DISCONNECTED -> finish("设备已断开，请检查手环后重新连接。")
                newState == BluetoothProfile.STATE_CONNECTED -> bluetooth("无法查找心率服务，请重新连接。") {
                    status = "已连接，正在查找心率服务…"
                    if (!client.discoverServices()) finish("无法查找心率服务，请重新连接。")
                }
            }
        }

        override fun onServicesDiscovered(client: BluetoothGatt, code: Int) {
            if (gatt !== client) return
            if (code != BluetoothGatt.GATT_SUCCESS) return finish("查找心率服务失败（$code），请重新连接。")
            val service = client.getService(heartService.uuid) ?: return finish("未找到标准心率服务，请确认手环已开启心率广播。")
            val characteristic = service.getCharacteristic(heartMeasurement) ?: return finish("未找到心率测量特征。")
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                return finish("设备的心率测量特征不支持通知。")
            }
            val descriptor = characteristic.getDescriptor(clientConfiguration) ?: return finish("设备缺少心率通知配置。")
            bluetooth("无法订阅心率通知，请重新连接。") {
                measurement = characteristic
                status = "正在订阅心率通知…"
                if (!client.setCharacteristicNotification(characteristic, true) || !enableNotifications(client, descriptor)) {
                    finish("无法订阅心率通知，请断开其他连接手环的应用后重试。")
                }
            }
        }

        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, code: Int) {
            if (gatt !== client || descriptor.uuid != clientConfiguration || descriptor.characteristic !== measurement) return
            if (code != BluetoothGatt.GATT_SUCCESS) return finish("订阅心率通知失败（$code），请重新连接。")
            if (!received) {
                status = "已订阅心率通知，等待首个读数…"
                armTimeout(10_000) { "未收到首个心率读数，请检查手环佩戴和广播后重新连接。" }
            }
        }

        @Deprecated("Android 12 and earlier callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt === client && characteristic === measurement) onMeasurement(characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (gatt === client && characteristic === measurement) onMeasurement(value)
        }

        private fun onMeasurement(value: ByteArray) {
            received = true
            publish(androidHeartBpm(value))
            status = if (bpm == null) "暂无有效读数，请检查佩戴状态。" else "实时心率"
            armTimeout(10_000) { "心率数据已中断，请检查手环距离和佩戴状态后重新连接。" }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(client: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            client.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.setValue(value) && client.writeDescriptor(descriptor)
        }
    }

    private fun permissionMessage() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        "请允许「附近的设备」权限后重试。"
    } else {
        "请允许定位权限后扫描附近的心率设备。"
    }

    private inline fun bluetooth(failure: String, action: () -> Unit) {
        try {
            action()
        } catch (_: SecurityException) {
            finish(permissionMessage())
        } catch (_: IllegalStateException) {
            finish(failure)
        }
    }

    private fun publish(value: Int?) {
        live.update(value)
        bpm = value
    }

    private fun armTimeout(delay: Long, message: () -> String) {
        timeout?.let(main::removeCallbacks)
        val task = Runnable { finish(message()) }
        timeout = task
        deadlineElapsedMs = SystemClock.elapsedRealtime() + delay
        main.postDelayed(task, delay)
    }

    private fun finish(message: String) {
        val oldScan = scanCallback
        val oldGatt = gatt
        scanCallback = null
        gatt = null
        publish(null)
        active = false
        status = message
        timeout?.let(main::removeCallbacks)
        timeout = null
        radioReceiver?.let(app::unregisterReceiver)
        radioReceiver = null
        if (oldScan != null) release { scanner?.stopScan(oldScan) }
        scanner = null
        if (oldGatt != null) {
            release { oldGatt.disconnect() }
            release { oldGatt.close() }
        }
    }

    // Bluetooth can be switched off or permission revoked while a scan/GATT is open.
    private inline fun release(action: () -> Unit) {
        try {
            action()
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }
}
