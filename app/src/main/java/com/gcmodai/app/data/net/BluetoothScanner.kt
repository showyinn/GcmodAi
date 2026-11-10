package com.gcmodai.app.data.net

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** 扫描到的蓝牙设备。 */
data class BtDevice(
    val address: String,
    val name: String,
    val bonded: Boolean,
    /** 设备类型，用于猜它是电脑还是手机 */
    val majorClass: Int = 0,
) {
    val hint: String
        get() = when {
            bonded -> "已配对"
            // BluetoothClass.Device.Major.COMPUTER == 0x0100
            majorClass and 0x1F00 == 0x0100 -> "像是一台电脑"
            else -> "未配对"
        }
}

/**
 * 蓝牙设备扫描。
 *
 * ## 为什么用蓝牙而不是扫 Wi-Fi
 *
 * 扫 Wi-Fi 需要知道网段、部分机型还限制后台扫描，扫出来也常常一台都没有。
 * 蓝牙发现是系统级能力：附近设备会主动应答，配过对的直接就能看到，
 * 对本机和电脑在同一房间的场景更可靠。
 *
 * ## 权限
 *
 * - Android 12+：BLUETOOTH_SCAN + BLUETOOTH_CONNECT
 * - Android 11 及以下：BLUETOOTH + BLUETOOTH_ADMIN + 定位权限（系统要求）
 */
object BluetoothScanner {

    /** 需要的运行时权限，按系统版本区分。 */
    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    fun hasPermissions(context: Context): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isSupported(context: Context): Boolean = adapter(context) != null

    fun isEnabled(context: Context): Boolean =
        runCatching { adapter(context)?.isEnabled == true }.getOrDefault(false)

    /** 请求用户打开蓝牙的 Intent。蓝牙关闭时发现根本发不出去，必须先开。 */
    fun enableIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    /** 已配对的设备。没权限时返回空列表，不抛异常。 */
    fun bondedDevices(context: Context): List<BtDevice> {
        if (!hasPermissions(context)) return emptyList()
        return runCatching {
            adapter(context)?.bondedDevices.orEmpty().map { it.toBtDevice() }
        }.getOrDefault(emptyList())
    }

    /**
     * 开始发现附近设备。
     *
     * 发现过程是异步的，回调可能来多次；返回一个取消函数，
     * 界面关闭时要调用它，否则广播接收器会泄漏。
     */
    fun startDiscovery(
        context: Context,
        onFound: (BtDevice) -> Unit,
        onFinished: () -> Unit,
    ): () -> Unit {
        if (!hasPermissions(context)) {
            onFinished()
            return {}
        }
        val btAdapter = adapter(context) ?: run {
            onFinished()
            return {}
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let { onFound(runCatching { it.toBtDevice() }.getOrNull() ?: return) }
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onFinished()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        runCatching {
            if (btAdapter.isDiscovering) btAdapter.cancelDiscovery()
            btAdapter.startDiscovery()
        }.onFailure { onFinished() }

        return {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { btAdapter.cancelDiscovery() }
        }
    }

    /**
     * 尝试建立蓝牙串口（RFCOMM）连接。
     *
     * 说明：SSH 走的是网络，蓝牙通道本身不能承载 SSH 会话，
     * 这里提供的是"通道是否可达"的探测——对方电脑若没有开放
     * 蓝牙串口服务，会明确失败，我们就提示用户改走网络。
     *
     * @return Result.success 表示能连上；failure 里带中文原因
     */
    fun connect(context: Context, address: String, timeoutMs: Int = 8000): Result<Unit> {
        if (!hasPermissions(context)) {
            return Result.failure(IllegalStateException("没有蓝牙权限，请在系统设置里允许「附近的设备」"))
        }
        return runCatching {
            // 标准串口服务 UUID（SPP）
            val uuid = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            val device = adapter(context)?.getRemoteDevice(address)
                ?: return Result.failure(IllegalStateException("找不到这台设备"))
            val socket = device.createRfcommSocketToServiceRecord(uuid)
            try {
                runCatching { adapter(context)?.cancelDiscovery() }
                socket.connect()
                Unit
            } finally {
                runCatching { socket.close() }
            }
        }.recoverCatching { e ->
            throw IllegalStateException(
                "蓝牙通道连不上：${e.message ?: "对方没有开放蓝牙串口服务"}。" +
                    "蓝牙只能用来发现和配对电脑，真正的文件读写要走 SSH（网络），请填写电脑的 IP。"
            )
        }
    }

    /** 发起配对。系统会弹确认框。 */
    fun pair(context: Context, address: String): Boolean {
        if (!hasPermissions(context)) return false
        return runCatching {
            val device = adapter(context)?.getRemoteDevice(address) ?: return false
            device.createBond()
        }.getOrDefault(false)
    }

    private fun BluetoothDevice.toBtDevice(): BtDevice = BtDevice(
        address = address,
        name = runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "未知设备",
        bonded = runCatching {
            bondState == BluetoothDevice.BOND_BONDED
        }.getOrDefault(false),
        majorClass = runCatching { bluetoothClass?.majorDeviceClass ?: 0 }.getOrDefault(0),
    )
}
