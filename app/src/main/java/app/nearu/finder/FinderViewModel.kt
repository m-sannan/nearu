package app.nearu.finder

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nearu.finder.ble.BleController
import app.nearu.finder.ble.Permissions
import app.nearu.finder.logic.ProximityReading
import app.nearu.finder.logic.ProximityState
import app.nearu.finder.logic.Sample
import app.nearu.finder.logic.SessionCodes
import app.nearu.finder.logic.SessionIdentity
import app.nearu.finder.logic.SignalProcessor
import app.nearu.finder.logic.Trend
import app.nearu.finder.logic.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen { HOME, JOIN, FINDING, ENDED, CHECKLIST }

data class UiState(
    val screen: Screen = Screen.HOME,
    val hasRadio: Boolean = true,
    val bluetoothOn: Boolean = false,
    val canAdvertise: Boolean = false,
    val codeInput: String = "",
    val sessionCode: String = "",
    val sessionUuid: String = "",
    val nonce: Int = 0,
    val advertiseStatus: String = "Stopped",
    val scanStatus: String = "Stopped",
    val reading: ProximityReading = ProximityReading.searching(),
    val sessionPackets: Int = 0,
    val otherPackets: Int = 0,
    val audioOn: Boolean = false,
    val hapticsOn: Boolean = false,
    val audioNote: String? = null,
    val banner: String? = null,
    val headline: String = "NearU",
    val trendText: String = "Signal trend: not enough yet",
    val copied: Boolean = false,
    val backgroundPaused: Boolean = false,
    val needsResume: Boolean = false,
    val model: String = "",
    val androidVersion: String = "",
    val sdk: Int = 0,
    val nearbyUuids: List<String> = emptyList(),
    val logText: String = "",
    val locationOn: Boolean = true,
    val hasLocationPermission: Boolean = false,
    val elapsedSec: Long = 0,
    val versionName: String = BuildConfig.VERSION_NAME,
)

class FinderViewModel(app: Application) : AndroidViewModel(app) {
    private val processor = SignalProcessor()
    private val logLines = ArrayDeque<String>()
    private val recentSamples = ArrayDeque<Sample>()
    private val nearbySet = LinkedHashSet<String>()
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var tone: ToneGenerator? = null
    private var nextClickAt = 0L
    private var playedFound = false
    private var hapticState: ProximityState? = null
    private var hapticCandidate: ProximityState? = null
    private var hapticCandidateSince = 0L
    private var lastRssiLogAt = 0L
    private var sessionStartedAt = 0L
    private var copiedAt = 0L
    private var lastRadioRefresh = 0L
    private var active = false
    private var backgroundPaused = false
    private var radioHeld = false
    private var sawFirstPacket = false
    private var warnedSelf = false
    private var sessionPackets = 0
    private var otherPackets = 0
    private var liveUuid: UUID? = null
    private var liveNonce: Byte = 0
    private var liveCode: String = ""

    private val controller = BleController(
        app,
        object : BleController.Listener {
            override fun onLog(line: String) = log(line)

            override fun onAdvertiseStatus(status: String) {
                _ui.update { it.copy(advertiseStatus = status) }
            }

            override fun onScanStatus(status: String) {
                _ui.update { it.copy(scanStatus = status) }
            }

            override fun onSessionPacket(rssi: Int, atElapsedMs: Long) {
                if (!active || backgroundPaused || radioHeld) return
                processor.add(Sample(atElapsedMs, rssi))
                sessionPackets += 1
                recentSamples.addLast(Sample(atElapsedMs, rssi))
                while (recentSamples.size > 40) recentSamples.removeFirst()
                if (!sawFirstPacket) {
                    sawFirstPacket = true
                    val seconds = (atElapsedMs - sessionStartedAt) / 1000.0
                    log("first session packet rssi=$rssi after ${"%.1f".format(seconds)}s")
                }
                if (rssi >= -25 && !warnedSelf) {
                    warnedSelf = true
                    log("very strong rssi=$rssi. If the phones are not touching, this phone may be hearing itself.")
                }
            }

            override fun onOtherPacket(serviceUuid: UUID?) {
                if (!active || backgroundPaused) return
                otherPackets += 1
                if (serviceUuid != null && nearbySet.size < 8) {
                    nearbySet.add(serviceUuid.toString())
                }
            }

            override fun onRadioFailure(kind: BleController.RadioFailure, detail: String) {
                val headline = when (kind) {
                    BleController.RadioFailure.NO_BLUETOOTH,
                    BleController.RadioFailure.BLUETOOTH_OFF,
                    -> "Bluetooth unavailable"
                    BleController.RadioFailure.CANNOT_ADVERTISE -> "Bluetooth advertising unavailable"
                    BleController.RadioFailure.PERMISSION -> "Permission denied"
                    BleController.RadioFailure.SCAN_FAILED -> "Scan failed"
                    BleController.RadioFailure.ADVERTISE_FAILED -> "Advertise failed"
                }
                stopAudioHardware()
                _ui.update {
                    it.copy(
                        headline = headline,
                        banner = detail,
                        needsResume = kind == BleController.RadioFailure.BLUETOOTH_OFF ||
                            kind == BleController.RadioFailure.PERMISSION,
                    )
                }
            }
        },
    )

    init {
        refreshRadio()
        viewModelScope.launch {
            while (isActive) {
                delay(100)
                tick()
            }
        }
    }

    fun refreshRadio() {
        val app = getApplication<Application>()
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val on = try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            _ui.value.bluetoothOn
        }
        val can = try {
            on && adapter?.bluetoothLeAdvertiser != null
        } catch (_: SecurityException) {
            false
        }
        _ui.update {
            it.copy(
                hasRadio = adapter != null,
                bluetoothOn = on,
                canAdvertise = can,
                locationOn = Permissions.locationServicesEnabled(app),
                hasLocationPermission = Permissions.hasFineLocation(app),
                model = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = Build.VERSION.RELEASE ?: "",
                sdk = Build.VERSION.SDK_INT,
            )
        }
    }

    fun showJoin() {
        _ui.update { it.copy(screen = Screen.JOIN, codeInput = "", banner = null, headline = "Join a session") }
    }

    fun showChecklist() {
        _ui.update { it.copy(screen = Screen.CHECKLIST, headline = "Test checklist") }
    }

    fun home() {
        if (active) endSession("left session")
        _ui.update { it.copy(screen = Screen.HOME, headline = "NearU", banner = null, codeInput = "") }
    }

    fun setCodeInput(raw: String) {
        _ui.update { it.copy(codeInput = SessionCodes.normalize(raw), banner = null) }
    }

    fun rejectScan(message: String) {
        _ui.update { it.copy(screen = Screen.JOIN, banner = message) }
    }

    fun host() {
        begin(SessionCodes.generate())
    }

    fun joinCurrent() {
        val code = SessionCodes.normalize(_ui.value.codeInput)
        if (code.length != 8) return
        begin(code)
    }

    fun onBluetoothMissing() {
        _ui.update {
            it.copy(
                screen = Screen.HOME,
                hasRadio = false,
                headline = "Bluetooth unavailable",
                banner = "This phone has no Bluetooth radio.",
            )
        }
    }

    fun onBluetoothOff() {
        _ui.update {
            it.copy(
                headline = "Bluetooth unavailable",
                banner = "Bluetooth is off. Turn it on, then try again.",
                bluetoothOn = false,
            )
        }
    }

    fun onPermissionDenied() {
        _ui.update {
            it.copy(
                headline = "Permission denied",
                banner = "NearU needs Bluetooth permission to discover your friend. Nothing was scanned.",
            )
        }
    }

    fun setAudio(on: Boolean) {
        _ui.update { it.copy(audioOn = on, audioNote = null) }
        if (!on) stopAudioHardware()
    }

    fun setHaptics(on: Boolean) {
        _ui.update { it.copy(hapticsOn = on) }
    }

    fun pauseForBackground() {
        if (!active || backgroundPaused) return
        backgroundPaused = true
        controller.stop()
        stopAudioHardware()
        processor.reset()
        resetHeadlineLatch()
        log("paused: app left the foreground. Discovery stopped.")
        _ui.update {
            it.copy(
                backgroundPaused = true,
                headline = "Paused",
                banner = "Discovery stops when NearU is not on screen. It does not track in the background.",
                reading = ProximityReading.searching(),
                advertiseStatus = "Stopped",
                scanStatus = "Stopped",
                trendText = "Signal trend: paused",
            )
        }
    }

    fun resumeFromBackground() {
        if (!active || !backgroundPaused) return
        backgroundPaused = false
        radioHeld = false
        processor.reset()
        resetHeadlineLatch()
        sessionPackets = 0
        otherPackets = 0
        nearbySet.clear()
        sawFirstPacket = false
        playedFound = false
        hapticState = null
        hapticCandidate = null
        sessionStartedAt = SystemClock.elapsedRealtime()
        val uuid = liveUuid ?: return
        log("resumed discovery")
        _ui.update {
            it.copy(
                backgroundPaused = false,
                needsResume = false,
                headline = "Initialising",
                banner = null,
                sessionPackets = 0,
                otherPackets = 0,
                nearbyUuids = emptyList(),
                reading = ProximityReading.searching(),
            )
        }
        controller.start(uuid, liveNonce)
    }

    fun resumeRadio() {
        if (!active) return
        radioHeld = false
        backgroundPaused = false
        processor.reset()
        resetHeadlineLatch()
        sessionPackets = 0
        otherPackets = 0
        sawFirstPacket = false
        playedFound = false
        hapticState = null
        hapticCandidate = null
        sessionStartedAt = SystemClock.elapsedRealtime()
        val uuid = liveUuid ?: return
        log("resume after radio recovery")
        _ui.update {
            it.copy(
                needsResume = false,
                backgroundPaused = false,
                headline = "Initialising",
                banner = null,
                reading = ProximityReading.searching(),
                sessionPackets = 0,
                otherPackets = 0,
            )
        }
        controller.start(uuid, liveNonce)
    }

    fun endSession(reason: String) {
        if (!active && _ui.value.screen != Screen.FINDING) {
            _ui.update { it.copy(screen = Screen.ENDED, headline = "Session ended") }
            return
        }
        active = false
        backgroundPaused = false
        radioHeld = false
        controller.stop()
        stopAudioHardware()
        processor.reset()
        resetHeadlineLatch()
        log("session ended: $reason")
        _ui.update {
            it.copy(
                screen = Screen.ENDED,
                headline = "Session ended",
                banner = "Discovery has stopped. Nothing was saved. There is no location history.",
                backgroundPaused = false,
                needsResume = false,
                reading = ProximityReading.searching(),
                advertiseStatus = "Stopped",
                scanStatus = "Stopped",
                audioOn = false,
                trendText = "Signal trend: stopped",
            )
        }
    }

    fun copyDiagnostics() {
        val app = getApplication<Application>()
        val state = _ui.value
        val offsets = recentSamples.joinToString("\n") { sample ->
            val rel = sample.tMillis - sessionStartedAt
            "$rel,${sample.rssi}"
        }
        val text = buildString {
            appendLine("NearU POC ${state.versionName}")
            appendLine("device: ${state.model}")
            appendLine("android: ${state.androidVersion} (sdk ${state.sdk})")
            appendLine("code: ${state.sessionCode}")
            appendLine("uuid: ${state.sessionUuid}")
            appendLine("nonce: ${state.nonce}")
            appendLine("advertise: ${state.advertiseStatus}")
            appendLine("scan: ${state.scanStatus}")
            appendLine("headline: ${state.headline}")
            appendLine("trend: ${state.trendText}")
            appendLine("smoothed: ${state.reading.smoothed ?: "none"}")
            appendLine("raw: ${state.reading.raw ?: "none"}")
            appendLine("sessionPackets: ${state.sessionPackets}")
            appendLine("otherPackets: ${state.otherPackets}")
            appendLine(
                "thresholds: veryClose>=${processor.veryCloseRssi} strong>=${processor.veryCloseStrongRssi} spread<=${processor.veryCloseMaxSpread} holdMs=${processor.liveMs} staleMs=${processor.staleMs} trendDelta=${processor.trendDelta}",
            )
            appendLine("locationServices: ${state.locationOn}")
            appendLine("locationPermission: ${state.hasLocationPermission}")
            appendLine("nearbyServiceUuids: ${state.nearbyUuids.joinToString()}")
            appendLine("--- log ---")
            appendLine(state.logText)
            appendLine("--- recent rssi (ms from start, dbm) ---")
            appendLine(offsets.ifBlank { "none" })
        }
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NearU diagnostics", text))
        copiedAt = SystemClock.elapsedRealtime()
        _ui.update { it.copy(copied = true) }
    }

    override fun onCleared() {
        active = false
        controller.stop()
        stopAudioHardware()
        super.onCleared()
    }

    private fun begin(code: String) {
        val uuid = SessionIdentity.uuidForCode(code)
        val nonce = java.security.SecureRandom().nextInt(255) + 1
        processor.reset()
        resetHeadlineLatch()
        logLines.clear()
        recentSamples.clear()
        nearbySet.clear()
        sessionPackets = 0
        otherPackets = 0
        sawFirstPacket = false
        warnedSelf = false
        playedFound = false
        hapticState = null
        hapticCandidate = null
        radioHeld = false
        backgroundPaused = false
        active = true
        liveUuid = uuid
        liveNonce = nonce.toByte()
        liveCode = SessionCodes.format(code)
        sessionStartedAt = SystemClock.elapsedRealtime()
        _ui.update {
            it.copy(
                screen = Screen.FINDING,
                sessionCode = liveCode,
                sessionUuid = uuid.toString(),
                nonce = nonce,
                headline = "Initialising",
                banner = null,
                needsResume = false,
                backgroundPaused = false,
                sessionPackets = 0,
                otherPackets = 0,
                nearbyUuids = emptyList(),
                reading = ProximityReading.searching(),
                copied = false,
                advertiseStatus = "Starting",
                scanStatus = "Starting",
                trendText = "Signal trend: not enough yet",
                audioNote = null,
                logText = "",
                elapsedSec = 0,
            )
        }
        log("session start code=$liveCode uuid=$uuid nonce=$nonce")
        log("thresholds veryClose>=${processor.veryCloseRssi} dBm strong>=${processor.veryCloseStrongRssi} stale=${processor.staleMs}ms hold=${processor.liveMs}ms. Not metres. Not a direction.")
        controller.start(uuid, liveNonce)
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRadioRefresh > 1000) {
            lastRadioRefresh = now
            refreshRadio()
        }
        if (_ui.value.copied && copiedAt > 0 && now - copiedAt > 2500) {
            _ui.update { it.copy(copied = false) }
        }
        if (!active) return
        if (backgroundPaused) return
        val state = _ui.value
        if (!state.bluetoothOn) {
            if (!radioHeld) {
                radioHeld = true
                controller.stop()
                stopAudioHardware()
                processor.reset()
                resetHeadlineLatch()
                log("bluetooth turned off")
                _ui.update {
                    it.copy(
                        headline = "Bluetooth unavailable",
                        banner = "Bluetooth turned off. Turn it on, then tap Resume.",
                        needsResume = true,
                        reading = ProximityReading.searching(),
                        trendText = "Signal trend: stopped",
                        advertiseStatus = "Stopped",
                        scanStatus = "Stopped",
                    )
                }
            }
            return
        }
        if (radioHeld) return
        val reading = processor.evaluate(now)
        val ready = state.advertiseStatus == "Running" && state.scanStatus == "Running"
        val advertiseFailed = state.advertiseStatus.startsWith("Failed")
        val scanFailed = state.scanStatus.startsWith("Failed")
        val permissionDenied = state.advertiseStatus == "Permission denied" ||
            state.scanStatus == "Permission denied"
        val headline = when {
            state.needsResume -> state.headline
            permissionDenied -> "Permission denied"
            advertiseFailed -> "Advertise failed"
            scanFailed -> "Scan failed"
            !ready -> "Initialising"
            else -> latched(now, reading.state).label()
        }
        val elapsed = ((now - sessionStartedAt) / 1000L).coerceAtLeast(0)
        val banner = if (advertiseFailed || scanFailed || permissionDenied || state.needsResume) {
            state.banner
        } else {
            bannerFor(state, reading, elapsed)
        }
        val trend = if (reading.state == ProximityState.SIGNAL_LOST) {
            "Signal trend: none. Readings are stale."
        } else if (reading.state == ProximityState.HOLDING) {
            "Signal trend: holding the last reading"
        } else {
            trendText(reading)
        }
        if (ready && now - lastRssiLogAt >= 1000) {
            lastRssiLogAt = now
            if (reading.smoothed != null) {
                log(
                    "rssi=${reading.raw} smooth=${reading.smoothed} age=${reading.lastHeardMillisAgo}ms ${reading.state.label()}",
                )
            } else if (reading.state == ProximityState.SIGNAL_LOST) {
                log("signal lost age=${reading.lastHeardMillisAgo}ms")
            }
        }
        driveFeedback(now, reading, state)
        _ui.update {
            it.copy(
                reading = reading,
                sessionPackets = sessionPackets,
                otherPackets = otherPackets,
                nearbyUuids = nearbySet.toList(),
                headline = headline,
                trendText = trend,
                banner = banner,
                elapsedSec = elapsed,
                logText = logLines.joinToString("\n"),
            )
        }
    }

    private fun bannerFor(state: UiState, reading: ProximityReading, elapsed: Long): String? {
        if (state.needsResume) return state.banner
        if (reading.state == ProximityState.SIGNAL_LOST) {
            return "Signal lost — move to an open area or reconnect."
        }
        if (reading.state == ProximityState.HOLDING) {
            return "The last reading is still on screen. A short gap is normal. This is not a direction."
        }
        if (!state.locationOn && elapsed >= 3) {
            return "Location services are off. NearU does not use GPS, but some phones hide Bluetooth results until Location is on."
        }
        if (state.scanStatus == "Running" && sessionPackets == 0 && otherPackets == 0 && elapsed >= 12) {
            return "Scan is running but this phone has seen no Bluetooth advertisements. Turn Location on, or move the phones closer and keep both apps open."
        }
        if (state.scanStatus == "Running" && sessionPackets == 0 && otherPackets > 0 && elapsed >= 8) {
            return "This phone sees other Bluetooth devices, not this session. Check that both codes and both UUIDs match, and that the other phone says Advertising: Running."
        }
        if (reading.state == ProximityState.VERY_CLOSE) {
            return "Strong, stable signal. This is not a measured distance."
        }
        return null
    }

    private fun trendText(reading: ProximityReading): String {
        val base = when (reading.trend) {
            Trend.CLOSER -> "Signal trend: stronger"
            Trend.FARTHER -> "Signal trend: weaker"
            Trend.STABLE -> "Signal trend: holding"
            Trend.UNKNOWN -> "Signal trend: not enough yet"
        }
        return if (reading.unstable) "$base, unstable" else base
    }

    private fun driveFeedback(now: Long, reading: ProximityReading, state: UiState) {
        val live = reading.smoothed != null &&
            reading.state != ProximityState.SIGNAL_LOST &&
            reading.state != ProximityState.HOLDING
        if (!live) {
            playedFound = false
            try {
                tone?.stopTone()
            } catch (_: RuntimeException) {
            }
        }
        if (state.hapticsOn) {
            val next = reading.state
            if (next != hapticCandidate) {
                hapticCandidate = next
                hapticCandidateSince = now
            }
            val immediate = next == ProximityState.SIGNAL_LOST ||
                next == ProximityState.VERY_CLOSE ||
                hapticState == null
            if (next != hapticState && (immediate || now - hapticCandidateSince >= 600)) {
                if (next == ProximityState.GETTING_CLOSER ||
                    next == ProximityState.GETTING_FARTHER ||
                    next == ProximityState.VERY_CLOSE ||
                    next == ProximityState.SIGNAL_LOST ||
                    next == ProximityState.FRIEND_DETECTED
                ) {
                    vibrate(pattern = next == ProximityState.VERY_CLOSE)
                }
                hapticState = next
            }
        }
        if (!state.audioOn) {
            _ui.update { if (it.audioNote == null) it else it.copy(audioNote = null) }
            return
        }
        val blocked = audioBlockReason()
        if (blocked != null) {
            try {
                tone?.stopTone()
            } catch (_: RuntimeException) {
            }
            if (state.audioNote != blocked) _ui.update { it.copy(audioNote = blocked) }
            return
        }
        if (state.audioNote != null) _ui.update { it.copy(audioNote = null) }
        val level = reading.smoothed
        if (!live || level == null) return
        if (reading.state == ProximityState.VERY_CLOSE) {
            if (!playedFound) {
                playedFound = true
                try {
                    ensureTone()?.startTone(ToneGenerator.TONE_CDMA_CONFIRM, 180)
                } catch (_: RuntimeException) {
                }
            }
            return
        }
        playedFound = false
        if (now >= nextClickAt) {
            try {
                ensureTone()?.startTone(ToneGenerator.TONE_PROP_BEEP, 35)
            } catch (_: RuntimeException) {
            }
            nextClickAt = now + clickInterval(level)
        }
    }

    private fun clickInterval(smoothed: Int): Long {
        val clamped = smoothed.coerceIn(-95, -50)
        val t = (clamped + 95) / 45.0
        return (1100 - (t * 1000).toLong()).coerceIn(100, 1100)
    }

    private fun audioBlockReason(): String? {
        val manager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (manager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            return "Phone ringer is muted. Sound is off."
        }
        if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            return "Media volume is 0. Turn it up to hear clicks."
        }
        return null
    }

    private fun vibrate(pattern: Boolean) {
        try {
            val vibrator = vibrator() ?: return
            if (!vibrator.hasVibrator()) return
            if (pattern) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 70, 40), -1))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
        }
    }

    private fun vibrator(): Vibrator? {
        val app = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= 31) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        }
    }

    private fun ensureTone(): ToneGenerator? {
        if (tone == null) {
            tone = try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            } catch (_: RuntimeException) {
                null
            }
        }
        return tone
    }

    private fun stopAudioHardware() {
        try {
            tone?.stopTone()
        } catch (_: RuntimeException) {
        }
        try {
            tone?.release()
        } catch (_: RuntimeException) {
        }
        tone = null
        nextClickAt = 0L
        playedFound = false
    }

    private var shownState: ProximityState? = null
    private var pendingState: ProximityState? = null
    private var pendingSince = 0L

    private fun resetHeadlineLatch() {
        shownState = null
        pendingState = null
        pendingSince = 0L
    }

    private fun latched(now: Long, state: ProximityState): ProximityState {
        val immediate = state == ProximityState.SIGNAL_LOST ||
            state == ProximityState.HOLDING ||
            state == ProximityState.SEARCHING ||
            state == ProximityState.VERY_CLOSE ||
            shownState == null
        if (immediate || state == shownState) {
            shownState = state
            pendingState = state
            pendingSince = now
            return state
        }
        if (state != pendingState) {
            pendingState = state
            pendingSince = now
            return shownState ?: state
        }
        if (now - pendingSince >= 900) shownState = state
        return shownState ?: state
    }

    private fun log(line: String) {
        val now = SystemClock.elapsedRealtime()
        val seconds = if (sessionStartedAt == 0L) 0.0 else (now - sessionStartedAt) / 1000.0
        logLines.addLast("%6.1fs  %s".format(seconds, line))
        while (logLines.size > 180) logLines.removeFirst()
        _ui.update { it.copy(logText = logLines.joinToString("\n")) }
    }
}
