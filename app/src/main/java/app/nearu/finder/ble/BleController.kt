package app.nearu.finder.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import app.nearu.finder.logic.AdvertisingParser
import java.util.UUID

/**
 * Real BLE only. This class never invents an RSSI.
 * Both phones advertise one session UUID and scan for it. No GATT connection.
 */
@SuppressLint("MissingPermission")
class BleController(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onLog(line: String)
        fun onAdvertiseStatus(status: String)
        fun onScanStatus(status: String)
        fun onSessionPacket(rssi: Int, atElapsedMs: Long)
        fun onOtherPacket(serviceUuid: UUID?)
        fun onRadioFailure(kind: RadioFailure, detail: String)
    }

    enum class RadioFailure {
        NO_BLUETOOTH,
        BLUETOOTH_OFF,
        CANNOT_ADVERTISE,
        PERMISSION,
        SCAN_FAILED,
        ADVERTISE_FAILED,
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var generation = 0
    private var scanRetries = 0
    private var sessionUuid: UUID? = null
    private var nonce: Byte = 0
    private var advertising = false
    private var scanning = false

    fun start(uuid: UUID, nonce: Byte) {
        stop()
        val gen = ++generation
        sessionUuid = uuid
        this.nonce = nonce
        scanRetries = 0
        advertising = false
        scanning = false
        val radio = adapter
        if (radio == null) {
            fail(RadioFailure.NO_BLUETOOTH, "This phone has no Bluetooth radio.")
            return
        }
        val enabled = try {
            radio.isEnabled
        } catch (error: SecurityException) {
            fail(RadioFailure.PERMISSION, error.message ?: "Bluetooth permission denied")
            return
        }
        if (!enabled) {
            fail(RadioFailure.BLUETOOTH_OFF, "Turn Bluetooth on and try again.")
            return
        }
        val nextAdvertiser = try {
            radio.bluetoothLeAdvertiser
        } catch (error: SecurityException) {
            fail(RadioFailure.PERMISSION, error.message ?: "Bluetooth permission denied")
            return
        }
        if (nextAdvertiser == null) {
            fail(
                RadioFailure.CANNOT_ADVERTISE,
                "This phone cannot advertise Bluetooth LE. NearU cannot run a session on it.",
            )
            return
        }
        val nextScanner = try {
            radio.bluetoothLeScanner
        } catch (error: SecurityException) {
            fail(RadioFailure.PERMISSION, error.message ?: "Bluetooth permission denied")
            return
        }
        if (nextScanner == null) {
            fail(RadioFailure.SCAN_FAILED, "Bluetooth scanner is unavailable.")
            return
        }
        advertiser = nextAdvertiser
        scanner = nextScanner
        val multiple = try {
            radio.isMultipleAdvertisementSupported
        } catch (_: SecurityException) {
            false
        }
        emit(gen, "multipleAdvertisementSupported=$multiple")
        startAdvertise(gen, includeNonce = true)
        startScan(gen)
    }

    fun stop() {
        generation++
        val currentAdvertiser = advertiser
        val currentAdvertiseCallback = advertiseCallback
        if (currentAdvertiser != null && currentAdvertiseCallback != null) {
            try {
                currentAdvertiser.stopAdvertising(currentAdvertiseCallback)
            } catch (error: SecurityException) {
                emitNow("stop advertise denied: ${error.message}")
            } catch (error: IllegalStateException) {
                emitNow("stop advertise failed: ${error.message}")
            }
        }
        val currentScanner = scanner
        val currentScanCallback = scanCallback
        if (currentScanner != null && currentScanCallback != null) {
            try {
                currentScanner.stopScan(currentScanCallback)
            } catch (error: SecurityException) {
                emitNow("stop scan denied: ${error.message}")
            } catch (error: IllegalStateException) {
                emitNow("stop scan failed: ${error.message}")
            }
        }
        advertising = false
        scanning = false
        advertiseCallback = null
        scanCallback = null
        main.post {
            listener.onAdvertiseStatus("Stopped")
            listener.onScanStatus("Stopped")
        }
    }

    private fun startAdvertise(gen: Int, includeNonce: Boolean) {
        val uuid = sessionUuid ?: return
        val current = advertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(uuid))
        if (includeNonce) {
            dataBuilder.addManufacturerData(
                AdvertisingParser.MANUFACTURER_ID,
                byteArrayOf(AdvertisingParser.MAGIC, nonce),
            )
        }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (gen != generation) return
                advertising = true
                val mode = if (includeNonce) "with session nonce" else "without nonce"
                emit(gen, "advertise started $mode")
                main.post {
                    if (gen == generation) listener.onAdvertiseStatus("Running")
                }
            }

            override fun onStartFailure(errorCode: Int) {
                if (gen != generation || advertising) return
                advertising = false
                val name = advertiseError(errorCode)
                emit(gen, "advertise failed: $name ($errorCode)")
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && includeNonce) {
                    emit(gen, "retrying advertise without nonce")
                    startAdvertise(gen, includeNonce = false)
                    return
                }
                main.post {
                    if (gen != generation) return@post
                    listener.onAdvertiseStatus("Failed: $name")
                    listener.onRadioFailure(RadioFailure.ADVERTISE_FAILED, name)
                }
            }
        }
        advertiseCallback = callback
        try {
            current.startAdvertising(settings, dataBuilder.build(), callback)
            main.post {
                if (gen == generation) listener.onAdvertiseStatus("Starting")
            }
        } catch (error: SecurityException) {
            fail(RadioFailure.PERMISSION, error.message ?: "Bluetooth advertise permission denied")
        } catch (error: IllegalStateException) {
            fail(RadioFailure.ADVERTISE_FAILED, error.message ?: "advertise failed")
        }
    }

    private fun startScan(gen: Int) {
        val current = scanner ?: return
        val uuid = sessionUuid ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleResult(gen, result, uuid)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handleResult(gen, it, uuid) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (gen != generation) return
                scanning = false
                val name = scanError(errorCode)
                emit(gen, "scan failed: $name ($errorCode)")
                val retry = (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ||
                    errorCode == SCAN_FAILED_SCANNING_TOO_FREQUENTLY) && scanRetries < 3
                if (retry) {
                    scanRetries++
                    val delayMs = 1000L * scanRetries
                    emit(gen, "retrying scan in ${delayMs}ms (attempt $scanRetries)")
                    main.postDelayed({
                        if (gen == generation) startScan(gen)
                    }, delayMs)
                    return
                }
                main.post {
                    if (gen != generation) return@post
                    listener.onScanStatus("Failed: $name")
                    listener.onRadioFailure(RadioFailure.SCAN_FAILED, name)
                }
            }
        }
        scanCallback = callback
        try {
            // Hardware filters for 128-bit UUIDs drop matches on many phones.
            // Match the session in software. Other devices are counted only.
            current.startScan(emptyList(), settings, callback)
            scanning = true
            main.post {
                if (gen == generation) listener.onScanStatus("Running")
            }
            emit(gen, "scan started (software session match, low latency)")
        } catch (error: SecurityException) {
            scanning = false
            fail(RadioFailure.PERMISSION, error.message ?: "Bluetooth scan permission denied")
        } catch (error: IllegalStateException) {
            scanning = false
            fail(RadioFailure.SCAN_FAILED, error.message ?: "scan failed")
        }
    }

    private fun handleResult(gen: Int, result: ScanResult, uuid: UUID) {
        if (gen != generation) return
        val record = result.scanRecord
        val parsed = record?.bytes?.let { AdvertisingParser.parse(it) }
        val fromApi = record?.serviceUuids?.map { it.uuid }.orEmpty()
        val uuids = (parsed?.serviceUuids.orEmpty() + fromApi).distinct()
        if (uuids.none { it == uuid }) {
            val noted = uuids.firstOrNull()
            main.post {
                if (gen == generation) listener.onOtherPacket(noted)
            }
            return
        }
        val payload = parsed?.manufacturerPayloads?.get(AdvertisingParser.MANUFACTURER_ID)
            ?: record?.getManufacturerSpecificData(AdvertisingParser.MANUFACTURER_ID)
        if (AdvertisingParser.isOwnNonce(payload, nonce)) return
        val rssi = result.rssi
        val at = SystemClock.elapsedRealtime()
        main.post {
            if (gen == generation) listener.onSessionPacket(rssi, at)
        }
    }

    private fun fail(kind: RadioFailure, detail: String) {
        emitNow(detail)
        main.post {
            when (kind) {
                RadioFailure.PERMISSION -> {
                    listener.onAdvertiseStatus("Permission denied")
                    listener.onScanStatus("Permission denied")
                }
                RadioFailure.SCAN_FAILED -> listener.onScanStatus("Failed: $detail")
                RadioFailure.ADVERTISE_FAILED, RadioFailure.CANNOT_ADVERTISE ->
                    listener.onAdvertiseStatus("Failed: $detail")
                else -> Unit
            }
            listener.onRadioFailure(kind, detail)
        }
    }

    private fun emit(gen: Int, line: String) {
        main.post {
            if (gen == generation) listener.onLog(line)
        }
    }

    private fun emitNow(line: String) {
        main.post { listener.onLog(line) }
    }

    private fun advertiseError(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertisement is too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Already advertising"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal Bluetooth error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Advertising is not supported"
        else -> "Unknown advertise error"
    }

    private fun scanError(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Scanner registration failed"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal scan error"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Scanning is not supported"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Scanner hardware is busy"
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Scanning too frequently"
        else -> "Unknown scan error"
    }
}
