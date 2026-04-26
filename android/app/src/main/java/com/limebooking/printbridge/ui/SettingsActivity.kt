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
import com.limebooking.printbridge.printer.BluetoothTransport
import com.limebooking.printbridge.printer.PrintJob
import com.limebooking.printbridge.printer.PrinterRegistry
import com.limebooking.printbridge.qz.QzService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {

    private val app: PrintBridgeApp get() = application as PrintBridgeApp

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!hasAllPermissions()) {
            permLauncher.launch(requiredPermissions())
        } else {
            ensureServiceStarted()
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        registry = app.printerRegistry,
                        serverPort = if (app.isServerRunning()) app.serverPort() else -1,
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
    }

    override fun onPause() {
        super.onPause()
        approvalPoller.removeCallbacks(approvalPollTask)
    }

    private fun ensureServiceStarted() {
        if (!app.isServerRunning()) QzService.start(this)
    }

    private fun runTestPrint() {
        val printer = app.printerRegistry.defaultPrinter()
        if (printer == null) {
            Toast.makeText(this, R.string.no_printer_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val adapter = app.printerRegistry.adapter()
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth ni vklopljen", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    BluetoothTransport(adapter, printer.address).use { tx ->
                        tx.connect()
                        tx.write(PrintJob.testPage(app.printerRegistry.paperColumns))
                        tx.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x42, 0x00))
                    }
                }
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
            .setNeutralButton(R.string.allow_once) { _, _ ->
                req.response.offer(true)
            }
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
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    registry: PrinterRegistry,
    serverPort: Int,
    onOpenBluetoothSettings: () -> Unit,
    onTestPrint: () -> Unit,
    onOpenLime: () -> Unit
) {
    var printers by remember { mutableStateOf(registry.list()) }
    var selectedAddress by remember { mutableStateOf(registry.defaultPrinter()?.address) }
    var paperCols by remember { mutableStateOf(registry.paperColumns) }

    LaunchedEffect(Unit) {
        printers = registry.list()
    }

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

            Text(
                stringResource(R.string.select_printer),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(8.dp))

            if (printers.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.pair_in_settings))
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenBluetoothSettings) {
                            Text(stringResource(R.string.open_bt_settings))
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn {
                        items(printers, key = { it.address }) { p ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                RadioButton(
                                    selected = selectedAddress == p.address,
                                    onClick = {
                                        selectedAddress = p.address
                                        registry.setDefault(p)
                                    }
                                )
                                Column {
                                    Text(p.name, fontWeight = FontWeight.Medium)
                                    Text(p.address, fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { printers = registry.list() }) {
                    Text("Osveži")
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                stringResource(R.string.paper_width),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(8.dp))
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

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onTestPrint,
                enabled = selectedAddress != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.test_print)) }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onOpenLime,
                enabled = selectedAddress != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.open_lime_booking)) }
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
