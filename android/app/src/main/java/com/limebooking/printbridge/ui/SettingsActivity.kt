package com.limebooking.printbridge.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.limebooking.printbridge.MainActivity
import com.limebooking.printbridge.PrintBridgeApp
import com.limebooking.printbridge.R
import com.limebooking.printbridge.printer.BleScanner
import com.limebooking.printbridge.printer.PrinterRegistry
import com.limebooking.printbridge.qz.QzService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {

    private val app: PrintBridgeApp get() = application as PrintBridgeApp
    private val bleScanner by lazy { BleScanner(this) }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            ensureServiceStarted()
            recreate()
        } else {
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
        }
    }

    private val approvalPoller = Handler(Looper.getMainLooper())
    private val approvalPollTask = object : Runnable {
        override fun run() {
            val req = app.pendingCertApproval()
            if (req != null) showApprovalDialog(req)
            approvalPoller.postDelayed(this, 1500)
        }
    }

    // Mutable Compose state holders, hoisted so we can mutate from BLE callbacks.
    private val classicList = mutableStateOf<List<PrinterRegistry.Printer>>(emptyList())
    private val bleList = mutableStateOf<List<PrinterRegistry.Printer>>(emptyList())
    private val bleScanning = mutableStateOf(false)
    private val selectedAddress = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!hasAllPermissions()) {
            permLauncher.launch(requiredPermissions())
        } else {
            ensureServiceStarted()
        }

        classicList.value = app.printerRegistry.listClassicPaired()
        selectedAddress.value = app.printerRegistry.defaultPrinter()?.address

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        registry = app.printerRegistry,
                        serverPort = if (app.isServerRunning()) app.serverPort() else -1,
                        classicPrinters = classicList.value,
                        blePrinters = bleList.value,
                        isBleScanning = bleScanning.value,
                        selectedAddress = selectedAddress.value,
                        onSelect = { p ->
                            selectedAddress.value = p.address
                            app.printerRegistry.setDefault(p)
                        },
                        onRefreshClassic = {
                            classicList.value = app.printerRegistry.listClassicPaired()
                        },
                        onScanBle = { startBleScan() },
                        onStopScan = { stopBleScan() },
                        onOpenBluetoothSettings = {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                        onTestPrint = { runTestPrint() },
                        onOpenLime = {
                            startActivity(Intent(this, MainActivity::class.java))
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        approvalPoller.post(approvalPollTask)
        // Refresh paired-Classic list in case user just paired a new device in
        // system settings.
        classicList.value = app.printerRegistry.listClassicPaired()
    }

    override fun onPause() {
        super.onPause()
        approvalPoller.removeCallbacks(approvalPollTask)
        // Stop the BLE scan if active so we don't keep the radio busy after backgrounding.
        stopBleScan()
    }

    private fun ensureServiceStarted() {
        if (!app.isServerRunning()) QzService.start(this)
    }

    private fun startBleScan() {
        val adapter = app.printerRegistry.adapter() ?: run {
            Toast.makeText(this, "Bluetooth ni na voljo", Toast.LENGTH_SHORT).show(); return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth ni vklopljen", Toast.LENGTH_SHORT).show(); return
        }
        if (!bleScanner.hasScanPermission()) {
            permLauncher.launch(requiredPermissions())
            return
        }
        bleScanning.value = true
        bleList.value = emptyList()
        bleScanner.start(adapter, object : BleScanner.Listener {
            override fun onDiscovered(devices: List<BleScanner.Discovered>) {
                bleList.value = devices.map {
                    PrinterRegistry.Printer(
                        name = if (it.isLikelyPrinter) it.name else "${it.name} · RSSI ${it.rssi}",
                        address = it.address,
                        transport = PrinterRegistry.TransportType.BLE
                    )
                }
            }
            override fun onScanFinished() { bleScanning.value = false }
            override fun onError(message: String) {
                bleScanning.value = false
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun stopBleScan() {
        if (bleScanner.isScanning()) {
            app.printerRegistry.adapter()?.let { bleScanner.stop(it) }
        }
        bleScanning.value = false
    }

    private fun runTestPrint() {
        val saved = app.printerRegistry.defaultPrinter()
        if (saved == null) {
            Toast.makeText(this, R.string.no_printer_selected, Toast.LENGTH_SHORT).show(); return
        }
        val adapter = app.printerRegistry.adapter()
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth ni vklopljen", Toast.LENGTH_SHORT).show(); return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { app.printer.testPrint() }
                Toast.makeText(this@SettingsActivity, "Test poslan", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Napaka: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showApprovalDialog(req: QzService.CertApprovalRequest) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.allow_lime_to_print))
            .setMessage(req.commonName ?: "Neznana stranka")
            .setPositiveButton(R.string.allow_always) { _, _ ->
                app.allowedCerts.allow(req.pem)
                req.response.offer(true)
            }
            .setNeutralButton(R.string.allow_once) { _, _ -> req.response.offer(true) }
            .setNegativeButton(R.string.block) { _, _ ->
                app.allowedCerts.block(req.pem)
                req.response.offer(false)
            }
            .setCancelable(false)
            .show()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        // Pre-12: BLE scan also requires location permission per Android docs.
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    registry: PrinterRegistry,
    serverPort: Int,
    classicPrinters: List<PrinterRegistry.Printer>,
    blePrinters: List<PrinterRegistry.Printer>,
    isBleScanning: Boolean,
    selectedAddress: String?,
    onSelect: (PrinterRegistry.Printer) -> Unit,
    onRefreshClassic: () -> Unit,
    onScanBle: () -> Unit,
    onStopScan: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onTestPrint: () -> Unit,
    onOpenLime: () -> Unit
) {
    var paperCols by remember { mutableStateOf(registry.paperColumns) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Top
        ) {
            ServerStatusCard(serverPort)

            Spacer(Modifier.height(16.dp))

            // ── Bluetooth Classic (paired) ────────────────────────────────
            Text(
                "Bluetooth Classic (sparjeni)",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(6.dp))
            if (classicPrinters.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Ni sparjenih Classic tiskalnikov.", fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = onOpenBluetoothSettings) {
                            Text(stringResource(R.string.open_bt_settings))
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn {
                        items(classicPrinters, key = { it.address }) { p ->
                            PrinterRow(p, selectedAddress, onSelect)
                            HorizontalDivider()
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onRefreshClassic) { Text("Osveži sparjene") }

            Spacer(Modifier.height(16.dp))

            // ── BLE (scan) ────────────────────────────────────────────────
            Text("BLE (skeniraj)", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            if (blePrinters.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            if (isBleScanning) "Iskanje BLE naprav…"
                            else "Še ni rezultatov. Vklopite tiskalnik, pritisnite feed in zaženite scan.",
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn {
                        items(blePrinters, key = { it.address }) { p ->
                            PrinterRow(p, selectedAddress, onSelect)
                            HorizontalDivider()
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isBleScanning) {
                    OutlinedButton(onClick = onStopScan) { Text("Ustavi") }
                    Spacer(Modifier.height(0.dp))
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .height(20.dp)
                    )
                } else {
                    OutlinedButton(onClick = onScanBle) { Text("Iskanje BLE tiskalnikov") }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(stringResource(R.string.paper_width), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = paperCols == 33,
                    onClick = { paperCols = 33; registry.paperColumns = 33 }
                )
                Text(stringResource(R.string.paper_58mm))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = paperCols == 48,
                    onClick = { paperCols = 48; registry.paperColumns = 48 }
                )
                Text(stringResource(R.string.paper_80mm))
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onTestPrint,
                enabled = selectedAddress != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.test_print)) }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onOpenLime,
                enabled = selectedAddress != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.open_lime_booking)) }
        }
    }
}

@Composable
private fun PrinterRow(
    p: PrinterRegistry.Printer,
    selectedAddress: String?,
    onSelect: (PrinterRegistry.Printer) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        RadioButton(
            selected = selectedAddress == p.address,
            onClick = { onSelect(p) }
        )
        Column {
            Text(p.name, fontWeight = FontWeight.Medium)
            Text(
                "${p.transport} · ${p.address}",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun ServerStatusCard(port: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (port > 0) Color(0xFFD7F3B8) else Color(0xFFFFD7D7)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (port > 0) "QZ Tray emulator: wss://localhost:$port" else "Strežnik ne teče",
                fontWeight = FontWeight.SemiBold
            )
            if (port > 0) {
                Text("Lime Booking PWA bo zaznala bridge avtomatsko.", fontSize = 13.sp)
            }
        }
    }
}
