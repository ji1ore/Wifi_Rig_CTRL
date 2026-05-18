package com.example.wifi_rig_ctrl

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.wifi_rig_ctrl.viewmodel.MainViewModel

class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()

    private var usbReceiver: BroadcastReceiver? = null

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.wifi_rig_ctrl.USB_PERMISSION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        registerUsbReceiver()
        // USB 接続状態で起動された場合に対応
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // アプリ起動時や復帰時に既に接続済みのUSBデバイスを検出する
        if (vm.cwUsbConnected.value != true) scanUsbDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        usbReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        usbReceiver = null
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = intent.usbDevice() ?: return
                        requestUsbPermissionIfNeeded(device)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        vm.disconnectCwUsb()
                    }
                    ACTION_USB_PERMISSION -> {
                        val device = intent.usbDevice() ?: return
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) vm.connectCwUsb(device)
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun handleUsbIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = intent.usbDevice() ?: return
            requestUsbPermissionIfNeeded(device)
        }
    }

    /** 対応デバイスかチェックしてからパーミッション要求 */
    fun requestUsbPermissionIfNeeded(device: UsbDevice) {
        val vid = "0x${device.vendorId.toString(16).uppercase()}"
        val pid = "0x${device.productId.toString(16).uppercase()}"
        val supported = vm.cwUsb.isSupportedDevice(device)
        if (!supported) {
            toast("USB detected: VID=$vid PID=$pid (unsupported)")
            return
        }
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            toast("USB granted: VID=$vid PID=$pid → connecting...")
            vm.connectCwUsb(device)
        } else {
            toast("USB permission request: VID=$vid PID=$pid")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) },
                PendingIntent.FLAG_UPDATE_CURRENT or flags
            )
            usbManager.requestPermission(device, pi)
        }
    }

    /** 現在接続中のすべての USB デバイスをスキャンして M5ATOM を探す */
    fun scanUsbDevices() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            toast("No USB devices")
            return
        }
        devices.values.forEach { device -> requestUsbPermissionIfNeeded(device) }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
