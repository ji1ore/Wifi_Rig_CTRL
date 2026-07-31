package com.ji1ore.wifi_rig_ctrl

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ji1ore.wifi_rig_ctrl.viewmodel.MainViewModel

class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()

    private var usbReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // Suppress PiP auto-enter while a USB permission dialog is pending
    private var suppressPip = false

    companion object {
        private const val ACTION_USB_PERMISSION = "com.ji1ore.wifi_rig_ctrl.USB_PERMISSION"
    }

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) connectCwBtInternal()
        else toast("Bluetooth permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()  // must be called before setContentView
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)
        setContentView(R.layout.activity_main)
        // Apply window insets to the root view so content avoids status/nav bars
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        registerUsbReceiver()
        registerNetworkCallback()
        // Handle incoming intent when started from a USB connection state
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        suppressPip = false
        updatePipParams()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode) {
            // Post deferred relayout so the window has fully expanded before GridLayout remeasures
            val content = findViewById<android.view.View>(android.R.id.content)
            content.post { content.requestLayout() }
        }
    }

    // ───────── Picture-in-Picture ─────────

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipIfActive()
        }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Auto-enter PiP only when actively transmitting or keying CW (not just SPK on)
        val active = vm.txEnabled.value == true || vm.cwKeyOn.value == true
        try {
            setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(active)
                    .build()
            )
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun enterPipIfActive() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (suppressPip) return
        // Only enter PiP for active transmit/keying, not for receive-only (SPK on)
        val active = vm.txEnabled.value == true || vm.cwKeyOn.value == true
        if (!active) return
        try {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
            enterPictureInPictureMode(builder.build())
        } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (vm.cwUsbConnected.value != true) {
            // App started or resumed with USB already connected: scan silently
            scanUsbDevices(silent = true)
        } else if (vm.cwUsb.isConnected) {
            // USB physically connected; refresh Pi-side connection (handles VPN→LAN switch)
            vm.refreshCwPiConnection()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        usbReceiver = null
        networkCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
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

    /** Check if the device is supported, then request permission */
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
            suppressPip = true  // prevent PiP entry while permission dialog is showing
            usbManager.requestPermission(device, pi)
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Network switched (VPN↔LAN etc.): refresh Pi-side CW connection if USB is live
                if (vm.cwUsb.isConnected) {
                    android.util.Log.i("Network", "network available → refreshing CW Pi connection")
                    vm.refreshCwPiConnection()
                }
            }
        }
        try { cm.registerNetworkCallback(req, networkCallback!!) } catch (_: Exception) {}
    }

    // ───────── Bluetooth / BLE ─────────

    fun connectCwBt() {
        if (vm.cwBle.isConnected) { vm.disconnectCwBle(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        connectCwBtInternal()
    }

    @SuppressLint("MissingPermission")
    private fun connectCwBtInternal() {
        val adapter: BluetoothAdapter? =
            (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Bluetooth is not enabled")
            return
        }
        val bonded = adapter.bondedDevices ?: emptySet()
        val bleDevices = bonded.filter {
            it.name?.startsWith("DualKey") == true || it.name?.startsWith("RemoteKe") == true
        }

        when (bleDevices.size) {
            0 -> toast("No paired CW device found.\nPair \"RemoteKeyer-BLE\" or \"DualKey-BLE\" in Android BT settings.")
            1 -> {
                toast("Connecting BLE: ${bleDevices[0].name}…")
                vm.connectCwBle(bleDevices[0])
            }
            else -> {
                AlertDialog.Builder(this)
                    .setTitle("CW Device")
                    .setItems(bleDevices.map { it.name ?: "Unknown" }.toTypedArray()) { _, which ->
                        toast("Connecting BLE: ${bleDevices[which].name}…")
                        vm.connectCwBle(bleDevices[which])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /** Scan all currently connected USB devices looking for M5ATOM */
    fun scanUsbDevices(silent: Boolean = false) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            if (!silent) toast("No USB devices")
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
