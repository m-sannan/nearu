package app.nearu.finder

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import app.nearu.finder.ble.Permissions
import app.nearu.finder.logic.SessionCodes
import app.nearu.finder.ui.NearUApp
import app.nearu.finder.ui.NearUTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val vm: FinderViewModel by viewModels()
    private var pending: (() -> Unit)? = null
    private var askedBt = false
    private var askedPerm = false

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { proceed() }

    private val enableBt = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { proceed() }

    private val locationPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.refreshRadio() }

    private val cameraPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchScan() else vm.rejectScan("Camera permission is needed to scan. You can still type the code.")
    }

    private val scanQr = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw.isNullOrBlank()) return@registerForActivityResult
        val code = SessionCodes.fromScan(raw)
        if (code == null) {
            vm.rejectScan("That QR is not a NearU session code.")
        } else {
            vm.setCodeInput(code)
            ensure { vm.joinCurrent() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            NearUTheme {
                NearUApp(
                    vm = vm,
                    onFind = { ensure { vm.host() } },
                    onJoin = { ensure { vm.showJoin() } },
                    onSubmitJoin = { ensure { vm.joinCurrent() } },
                    onScan = { startScan() },
                    onResume = { ensure { vm.resumeRadio() } },
                    onOpenLocationSettings = {
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                    onRequestLocationPermission = {
                        locationPerm.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                    onOpenAppSettings = {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.refreshRadio()
        vm.resumeFromBackground()
    }

    override fun onStop() {
        if (!isChangingConfigurations) vm.pauseForBackground()
        super.onStop()
    }

    private fun startScan() {
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchScan() else cameraPerm.launch(android.Manifest.permission.CAMERA)
    }

    private fun launchScan() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("Point at the code on your friend's NearU screen")
        options.setBeepEnabled(false)
        options.setOrientationLocked(true)
        scanQr.launch(options)
    }

    private fun ensure(action: () -> Unit) {
        pending = action
        askedBt = false
        askedPerm = false
        proceed()
    }

    private fun proceed() {
        val action = pending ?: return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            pending = null
            vm.onBluetoothMissing()
            return
        }
        val missing = Permissions.missing(this)
        if (missing.isNotEmpty()) {
            if (askedPerm) {
                pending = null
                askedPerm = false
                vm.onPermissionDenied()
                return
            }
            askedPerm = true
            permissions.launch(missing.toTypedArray())
            return
        }
        val enabled = try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            pending = null
            vm.onPermissionDenied()
            return
        }
        if (!enabled) {
            if (askedBt) {
                pending = null
                askedBt = false
                vm.onBluetoothOff()
                return
            }
            askedBt = true
            try {
                enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: SecurityException) {
                pending = null
                vm.onPermissionDenied()
            }
            return
        }
        pending = null
        action()
    }
}
