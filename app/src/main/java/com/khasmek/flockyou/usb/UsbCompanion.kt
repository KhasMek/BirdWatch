package com.khasmek.flockyou.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.khasmek.flockyou.detection.DetectedDevice
import com.khasmek.flockyou.detection.DetectionTable
import com.khasmek.flockyou.location.GeoFix
import com.khasmek.flockyou.usb.FirmwareLineParser.mergeInto
import com.khasmek.flockyou.usb.FirmwareLineParser.toDetectedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

enum class UsbStatus(val label: String) {
    NO_DEVICE("No ESP32 attached"),
    PERMISSION_NEEDED("USB permission needed"),
    CONNECTING("Connecting…"),
    CONNECTED("ESP32 connected"),
    DISCONNECTED("ESP32 disconnected"),
    ERROR("USB error"),
}

data class UsbState(
    val status: UsbStatus = UsbStatus.NO_DEVICE,
    /** e.g. "Espressif 303a:1001". */
    val deviceDescription: String? = null,
    val error: String? = null,
    val linesReceived: Long = 0,
    val detectionsReceived: Long = 0,
    /** Last `config` line from the firmware (beep mask, OUI count, tiers). */
    val config: FirmwareMessage.Config? = null,
    /** Last `[flockyou] ...` banner, useful as a heartbeat ("scanning (ch=6 ...)"). */
    val lastText: String? = null,
) {
    val isConnected: Boolean get() = status == UsbStatus.CONNECTED
}

/**
 * Talks to a XIAO ESP32-S3 running the flock-you WiFi promiscuous firmware over USB CDC serial
 * (115200 8N1). Reads newline-delimited JSON, parses each line with [FirmwareLineParser], and
 * folds live detections into a [DetectionTable] with the same session/GPS hooks as the BLE scanner.
 *
 * Lifecycle: [connect] finds the first supported device, asks for USB permission if needed, opens
 * the port and starts a reader coroutine. Attach/detach broadcasts keep [state] accurate and
 * auto-reconnect while [wantConnection] is true (i.e. during a scan session).
 */
class UsbCompanion(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)

    private val prober: UsbSerialProber = UsbSerialProber(
        UsbSerialProber.getDefaultProbeTable().apply {
            // Espressif native USB (ESP32-S3 "USB JTAG/serial" and TinyUSB CDC PIDs).
            ESPRESSIF_PIDS.forEach { addProduct(ESPRESSIF_VID, it, CdcAcmSerialDriver::class.java) }
        }
    )

    val table = DetectionTable()

    private val _state = MutableStateFlow(UsbState())
    val state: StateFlow<UsbState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<FirmwareMessage>(extraBufferCapacity = 256)
    /** Every parsed line, for logging / a raw console view. */
    val messages: SharedFlow<FirmwareMessage> = _messages.asSharedFlow()

    val devices: StateFlow<List<DetectedDevice>> get() = table.devices
    val newDetections: SharedFlow<DetectedDevice> get() = table.newDetections

    @Volatile var locationSource: (() -> GeoFix?)? = null
    @Volatile var sessionId: String = ""

    /** While true, an attached device is opened automatically (set by SessionManager). */
    @Volatile var wantConnection: Boolean = false

    private var port: UsbSerialPort? = null
    private var readJob: Job? = null
    private val permissionAction = "${appContext.packageName}.USB_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB permission ${if (granted) "granted" else "denied"}")
                    if (granted) connect()
                    else _state.update { it.copy(status = UsbStatus.PERMISSION_NEEDED, error = "USB permission denied") }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device attached")
                    if (wantConnection) connect() else refreshPresence()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB device detached")
                    closePort()
                    _state.update { it.copy(status = UsbStatus.NO_DEVICE, deviceDescription = null) }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshPresence()
    }

    /** Update [state] to reflect whether a supported device is plugged in, without opening it. */
    fun refreshPresence() {
        if (port != null) return
        val driver = findDriver()
        _state.update {
            it.copy(
                status = if (driver == null) UsbStatus.NO_DEVICE else UsbStatus.DISCONNECTED,
                deviceDescription = driver?.device?.describe(),
            )
        }
    }

    /** Open the first supported device, prompting for permission if necessary. Safe to call repeatedly. */
    fun connect() {
        if (port != null) return
        val driver = findDriver()
        if (driver == null) {
            _state.update { it.copy(status = UsbStatus.NO_DEVICE, deviceDescription = null, error = null) }
            return
        }
        val device = driver.device
        _state.update { it.copy(deviceDescription = device.describe(), error = null) }

        if (!usbManager.hasPermission(device)) {
            _state.update { it.copy(status = UsbStatus.PERMISSION_NEEDED) }
            requestPermission(device)
            return
        }
        open(driver)
    }

    fun disconnect() {
        closePort()
        refreshPresence()
        Log.i(TAG, "Disconnected")
    }

    /** Send one command line to the firmware, e.g. `{"cmd":"get_config"}`. */
    fun send(line: String): Boolean {
        val p = port ?: return false
        return try {
            p.write((line.trimEnd('\n') + "\n").toByteArray(Charsets.UTF_8), WRITE_TIMEOUT_MS)
            true
        } catch (e: IOException) {
            Log.w(TAG, "write failed", e)
            false
        }
    }

    fun requestConfig() = send("""{"cmd":"get_config"}""")

    fun clear() = table.clear()

    // ------------------------------------------------------------------

    private fun findDriver(): UsbSerialDriver? =
        prober.findAllDrivers(usbManager).firstOrNull()

    private fun requestPermission(device: UsbDevice) {
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_MUTABLE
        val pi = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    private fun open(driver: UsbSerialDriver) {
        _state.update { it.copy(status = UsbStatus.CONNECTING, error = null) }
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            _state.update { it.copy(status = UsbStatus.ERROR, error = "Could not open USB device") }
            return
        }
        val p = driver.ports.firstOrNull()
        if (p == null) {
            connection.close()
            _state.update { it.copy(status = UsbStatus.ERROR, error = "USB device has no serial port") }
            return
        }
        try {
            p.open(connection)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Native ESP32-S3 CDC only streams once the host asserts DTR.
            p.dtr = true
            p.rts = true
        } catch (e: IOException) {
            Log.e(TAG, "open failed", e)
            try { p.close() } catch (_: IOException) {}
            _state.update { it.copy(status = UsbStatus.ERROR, error = "Open failed: ${e.message}") }
            return
        }
        port = p
        _state.update { it.copy(status = UsbStatus.CONNECTED, linesReceived = 0, detectionsReceived = 0) }
        Log.i(TAG, "Connected to ${driver.device.describe()} @ $BAUD")
        readJob = scope.launch(Dispatchers.IO) { readLoop(p) }
        requestConfig()
    }

    private fun closePort() {
        readJob?.cancel()
        readJob = null
        val p = port ?: return
        port = null
        try { p.close() } catch (_: IOException) {}
    }

    private suspend fun readLoop(p: UsbSerialPort) {
        val buf = ByteArray(READ_BUFFER_BYTES)
        val pending = StringBuilder()
        while (scope.isActive && port === p) {
            val n = try {
                p.read(buf, READ_TIMEOUT_MS)
            } catch (e: IOException) {
                if (port === p) {
                    Log.w(TAG, "read failed: ${e.message}")
                    closePort()
                    _state.update { it.copy(status = UsbStatus.DISCONNECTED, error = "Read failed: ${e.message}") }
                }
                return
            }
            if (n <= 0) continue
            pending.append(String(buf, 0, n, Charsets.UTF_8))

            var nl = pending.indexOf('\n')
            while (nl >= 0) {
                val line = pending.substring(0, nl)
                pending.delete(0, nl + 1)
                handleLine(line)
                nl = pending.indexOf('\n')
            }
            // Guard against a runaway line with no newline.
            if (pending.length > MAX_LINE_CHARS) pending.setLength(0)
        }
    }

    private fun handleLine(line: String) {
        val msg = FirmwareLineParser.parse(line) ?: return
        _state.update { it.copy(linesReceived = it.linesReceived + 1) }
        _messages.tryEmit(msg)

        when (msg) {
            is FirmwareMessage.Detection -> onDetection(msg)
            is FirmwareMessage.Config -> {
                Log.i(TAG, "config: beepMask=${msg.beepMask} ouiCount=${msg.ouiCount}")
                _state.update { it.copy(config = msg) }
            }
            is FirmwareMessage.Text -> _state.update { it.copy(lastText = msg.line) }
            is FirmwareMessage.Unknown -> Log.d(TAG, "unknown line: ${msg.line}")
            else -> Unit // session dump messages are handled by a future import feature
        }
    }

    private fun onDetection(d: FirmwareMessage.Detection) {
        val now = System.currentTimeMillis()
        val fix = locationSource?.invoke()
        val (stored, isNew) = table.upsert(
            macAddress = d.macAddress,
            create = { d.toDetectedDevice(sessionId, fix, now) },
            merge = { existing -> d.mergeInto(existing, fix, now) },
        )
        _state.update { it.copy(detectionsReceived = it.detectionsReceived + 1) }
        if (isNew) {
            Log.i(TAG, "DETECTED ${stored.macAddress} tier=${d.tier} [${d.method}] rssi=${d.rssi} ch=${d.channel}")
        }
    }

    private fun UsbDevice.describe(): String {
        val vendor = manufacturerName ?: "USB"
        val product = productName?.let { " $it" } ?: ""
        return "$vendor$product (%04x:%04x)".format(vendorId, productId)
    }

    companion object {
        private const val TAG = "FlockYou/Usb"
        private const val BAUD = 115200
        private const val READ_TIMEOUT_MS = 250
        private const val WRITE_TIMEOUT_MS = 500
        private const val READ_BUFFER_BYTES = 4096
        private const val MAX_LINE_CHARS = 16_384

        const val ESPRESSIF_VID = 0x303A
        /** 0x1001 = ESP32-S3 USB JTAG/serial (Arduino default), 0x0002 = TinyUSB CDC. */
        val ESPRESSIF_PIDS = listOf(0x1001, 0x0002)
    }
}
