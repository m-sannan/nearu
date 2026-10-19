package app.nearu.finder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nearu.finder.FinderViewModel
import app.nearu.finder.Screen
import app.nearu.finder.UiState
import app.nearu.finder.logic.SessionCodes

@Composable
fun NearUApp(
    vm: FinderViewModel,
    onFind: () -> Unit,
    onJoin: () -> Unit,
    onSubmitJoin: () -> Unit,
    onScan: () -> Unit,
    onResume: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    BackHandler(enabled = ui.screen != Screen.HOME) {
        when (ui.screen) {
            Screen.FINDING -> vm.endSession("back")
            Screen.JOIN, Screen.CHECKLIST, Screen.ENDED -> vm.home()
            Screen.HOME -> Unit
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (ui.screen) {
            Screen.HOME -> HomeScreen(ui, onFind, onJoin, { vm.showChecklist() })
            Screen.JOIN -> JoinScreen(ui, vm::setCodeInput, onSubmitJoin, onScan, vm::home)
            Screen.FINDING -> FindingScreen(
                ui = ui,
                onAudio = vm::setAudio,
                onHaptics = vm::setHaptics,
                onCopy = vm::copyDiagnostics,
                onFound = { vm.endSession("found them") },
                onEnd = { vm.endSession("stopped") },
                onResume = onResume,
                onOpenLocationSettings = onOpenLocationSettings,
                onRequestLocationPermission = onRequestLocationPermission,
                onOpenAppSettings = onOpenAppSettings,
            )
            Screen.ENDED -> EndedScreen(ui, onFind, vm::copyDiagnostics, vm::home)
            Screen.CHECKLIST -> ChecklistScreen(vm::home)
        }
    }
}

@Composable
private fun HomeScreen(
    ui: UiState,
    onFind: () -> Unit,
    onJoin: () -> Unit,
    onChecklist: () -> Unit,
) {
    Text("NearU", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Ink)
    Text(
        "Find a friend in a crowd with Bluetooth signal strength. Not a map, and not a direction.",
        fontSize = 18.sp,
        color = Ink,
        lineHeight = 24.sp,
    )
    StatusBlock(ui)
    Text(
        "Both people install this same app and join one temporary session, by code or by QR. Other people's phones are not used. Discovery runs only while NearU is on screen. Nothing is saved.",
        color = Muted,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    )
    PrimaryButton("Find a friend", onFind)
    OutlinedButton(
        onClick = onJoin,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text("Join a session", fontSize = 18.sp, color = Ink)
    }
    OutlinedButton(
        onClick = onChecklist,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text("Test checklist", fontSize = 18.sp, color = Ink)
    }
    Text(
        "Proof of concept ${ui.versionName}. Readings are real Bluetooth only. If there is no packet, there is no number.",
        color = Muted,
        fontSize = 13.sp,
    )
}

@Composable
private fun StatusBlock(ui: UiState) {
    val radio = when {
        !ui.hasRadio -> "No Bluetooth radio"
        ui.bluetoothOn -> "Bluetooth on"
        else -> "Bluetooth off"
    }
    val advertise = when {
        !ui.bluetoothOn -> "Advertising: turn Bluetooth on to check"
        ui.canAdvertise -> "Advertising: available"
        else -> "Advertising: this phone may not be able to advertise"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(radio, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 16.sp)
        Text(advertise, color = Muted, fontSize = 15.sp)
        Text(
            if (ui.locationOn) "Location services: on" else "Location services: off",
            color = Muted,
            fontSize = 15.sp,
        )
        Text("${ui.model} · Android ${ui.androidVersion}", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun JoinScreen(
    ui: UiState,
    onCode: (String) -> Unit,
    onSubmit: () -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit,
) {
    Text("Join a session", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
    Text(
        "Scan the QR on your friend's phone, or type the code. Nothing starts until you start.",
        color = Muted,
        fontSize = 16.sp,
    )
    ui.banner?.let {
        Text(it, color = Bad, fontSize = 15.sp)
    }
    PrimaryButton("Scan QR", onScan)
    OutlinedTextField(
        value = ui.codeInput,
        onValueChange = onCode,
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(
            fontSize = 28.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = Ink,
        ),
        singleLine = true,
        label = { Text("Session code") },
    )
    Text("8 characters. No 0, 1, I, or O.", color = Muted, fontSize = 14.sp)
    PrimaryButton("Start finding", onSubmit, enabled = SessionCodes.isComplete(ui.codeInput))
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text("Back", fontSize = 18.sp, color = Ink)
    }
}

@Composable
private fun FindingScreen(
    ui: UiState,
    onAudio: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onFound: () -> Unit,
    onEnd: () -> Unit,
    onResume: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Text(ui.sessionCode, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
    Text("Same code on both phones. The other phone can scan this.", color = Muted, fontSize = 14.sp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        SessionQr(ui.sessionCode)
    }
    Text(
        ui.headline,
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        color = headlineColor(ui.headline),
        lineHeight = 44.sp,
    )
    Text(ui.trendText, fontSize = 18.sp, color = Ink)
    val smooth = ui.reading.smoothed?.let { "$it dBm" } ?: "No live reading"
    Text(smooth, fontSize = 36.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Ink)
    StrengthMeter(ui.reading.smoothed)
    Text(
        "Stronger is a number closer to zero. The bar is strength, not a direction or a route.",
        color = Muted,
        fontSize = 14.sp,
    )
    ui.banner?.let {
        Text(
            it,
            color = Ink,
            fontSize = 16.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(12.dp),
        )
    }
    if (ui.needsResume) {
        PrimaryButton("Resume", onResume)
    }
    if (!ui.locationOn) {
        OutlinedButton(
            onClick = onOpenLocationSettings,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Open location settings", color = Ink) }
    }
    if (ui.sdk >= 31 && !ui.hasLocationPermission && ui.sessionPackets == 0 && ui.elapsedSec >= 12) {
        OutlinedButton(
            onClick = onRequestLocationPermission,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Allow location to unstick scan", color = Ink) }
    }
    if (ui.headline == "Permission denied") {
        OutlinedButton(
            onClick = onOpenAppSettings,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Open app settings", color = Ink) }
    }
    ToggleRow("Sound", ui.audioOn, onAudio)
    ui.audioNote?.let { Text(it, color = Bad, fontSize = 14.sp) }
    ToggleRow("Haptics", ui.hapticsOn, onHaptics)
    PrimaryButton("Found them", onFound)
    OutlinedButton(
        onClick = onEnd,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(14.dp),
    ) { Text("End session", fontSize = 18.sp, color = Ink) }
    DiagnosticBlock(ui, onCopy)
}

@Composable
private fun DiagnosticBlock(ui: UiState, onCopy: () -> Unit) {
    val age = ui.reading.lastHeardMillisAgo?.let { "${it} ms ago" } ?: "—"
    val raw = ui.reading.raw?.let { "$it dBm" } ?: "—"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Diagnostics", fontWeight = FontWeight.Bold, color = Ink)
        Mono("Advertise: ${ui.advertiseStatus}")
        Mono("Scan: ${ui.scanStatus}")
        Mono("Raw: $raw")
        Mono("Last packet: $age")
        Mono("Session packets: ${ui.sessionPackets}")
        Mono("Other packets (not shown): ${ui.otherPackets}")
        Mono("UUID: ${ui.sessionUuid}")
        Mono("Nonce: ${ui.nonce}")
        Mono("Very close: -58 dBm if steady, or -42 dBm if strong")
        Mono("Hold last reading for 8 seconds. Not a direction.")
        if (ui.nearbyUuids.isNotEmpty()) {
            Text("Other service UUIDs seen", color = Muted, fontSize = 12.sp)
            ui.nearbyUuids.forEach { Mono(it) }
        }
        if (ui.copied) Text("Copied. Paste it back into the chat.", color = Good, fontSize = 14.sp)
        OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Copy diagnostics", color = Ink)
        }
        if (ui.logText.isNotBlank()) {
            Text(
                ui.logText.takeLast(900),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Muted,
            )
        }
    }
}

@Composable
private fun EndedScreen(
    ui: UiState,
    onAgain: () -> Unit,
    onCopy: () -> Unit,
    onHome: () -> Unit,
) {
    Text("Session ended", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Ink)
    Text(
        "Discovery has stopped. Nothing was saved. There is no location history.",
        fontSize = 18.sp,
        color = Ink,
    )
    if (ui.copied) Text("Copied.", color = Good)
    PrimaryButton("Find someone else", onAgain)
    OutlinedButton(
        onClick = onCopy,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(14.dp),
    ) { Text("Copy diagnostics", color = Ink, fontSize = 18.sp) }
    OutlinedButton(
        onClick = onHome,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(14.dp),
    ) { Text("Home", color = Ink, fontSize = 18.sp) }
}

@Composable
private fun ChecklistScreen(onBack: () -> Unit) {
    Text("Test checklist", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
    checklistItems.forEach { item ->
        Text(item, color = Ink, fontSize = 16.sp, lineHeight = 22.sp)
    }
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(14.dp),
    ) { Text("Back", color = Ink, fontSize = 18.sp) }
}

private val checklistItems = listOf(
    "1. Install this same build on both phones. Keep the screen on and NearU in front.",
    "2. Turn Bluetooth on. For the first trial, also turn Location services on. NearU does not store GPS.",
    "3. Phone A: Find a friend. Phone B: scan the QR, or type the code. The UUIDs must match. The nonces must differ.",
    "4. Wrong code, phones 30 cm apart for 20 seconds: session packets stay 0. No friend detected.",
    "5. Right code, 30 cm apart: both phones show Friend detected within about 20 seconds, and the number keeps updating.",
    "6. Walk apart slowly. The strength bar should fall. Walk back. It should rise. Getting farther and Getting closer can lag about a second. Not metres, and not a direction.",
    "7. Turn one phone's Bluetooth off. The other holds the last number, then shows Signal lost within about 8 seconds. The number then disappears.",
    "8. End the session. Advertising and scanning say Stopped. A new code must not see the old one.",
    "9. Press Home. Discovery must pause. It must not keep working in the background.",
    "10. Optional: turn sound and haptics on. Clicks get faster when the signal is stronger. They stop on signal loss. Mute stops the sound.",
    "11. Copy diagnostics from both phones and send them back, with the phone models.",
)

@Composable
private fun StrengthMeter(rssi: Int?) {
    val fraction = if (rssi == null) 0f else ((rssi + 95) / 60f).coerceIn(0f, 1f)
    val filled = (fraction * 8).toInt().coerceIn(if (rssi == null) 0 else 1, 8)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Strength, not direction", color = Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(8) { index ->
                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp)
                        .background(
                            if (index < filled) Signal else Line,
                            RoundedCornerShape(4.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 18.sp, color = Ink)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
}

private fun headlineColor(headline: String): Color = when (headline) {
    "Very close", "Getting closer" -> Good
    "Getting farther", "Holding last reading" -> Amber
    "Signal lost", "Bluetooth unavailable", "Permission denied", "Paused",
    "Scan failed", "Advertise failed", "Bluetooth advertising unavailable",
    -> Bad
    else -> Ink
}
