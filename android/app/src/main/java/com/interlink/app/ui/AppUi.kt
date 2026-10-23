package com.interlink.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.interlink.app.core.LinkState
import com.interlink.app.core.LanDiscovery
import com.interlink.app.core.Prefs
import com.interlink.app.service.LinkService
import kotlinx.coroutines.launch

/** Root: three tabs — Home (status + permissions), Pair (QR), Settings. */
@Composable
fun AppUi(prefs: Prefs) {
    var tab by remember { mutableIntStateOf(0) }

    // Auto-start the link service on launch if already paired, so the phone
    // reconnects without the user having to tap "Start link service" each time.
    val context = LocalContext.current
    val autoConfig by prefs.config.collectAsState(initial = null)
    LaunchedEffect(autoConfig?.secretB64 != null) {
        if (autoConfig?.secretB64 != null) LinkService.start(context)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("⌂") },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text("▦") },
                    label = { Text("Pair") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Text("⚙") },
                    label = { Text("Settings") },
                )
            }
        },
    ) { pad ->
        when (tab) {
            0 -> HomeScreen(prefs, Modifier.padding(pad))
            1 -> PairScreen(prefs, onPaired = { tab = 0 }, modifier = Modifier.padding(pad))
            2 -> SettingsScreen(prefs, Modifier.padding(pad))
        }
    }
}

// ————————————————————————————— Home —————————————————————————————

@Composable
fun HomeScreen(prefs: Prefs, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by LinkService.state.collectAsState()
    val detail by LinkService.stateDetail.collectAsState()
    val discovered by LanDiscovery.devices.collectAsState()
    // Local non-delegated copy: delegated properties can't be smart-cast.
    val statusDetail: String? = detail

    // Permission grant state; recomputed whenever `tick` changes.
    var tick by remember { mutableIntStateOf(0) }
    var listenerOn by remember(tick) { mutableStateOf(notifListenerEnabled(context)) }

    val multiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { tick++ }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        )) {
            Column(Modifier.padding(16.dp)) {
                Text("Laptop link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.padding(4.dp))
                Text(
                    text = when (state) {
                        LinkState.CONNECTED -> "Connected (${statusDetail ?: "laptop"})"
                        LinkState.CONNECTING, LinkState.HANDSHAKING -> "Connecting…"
                        LinkState.DISCONNECTED -> when {
                            statusDetail == "unpaired" -> "Not paired — scan the QR on your laptop"
                            statusDetail?.contains("re-scan") == true ||
                                statusDetail?.contains("keys differ") == true ->
                                "Pairing problem: $statusDetail"
                            !statusDetail.isNullOrBlank() -> "Disconnected ($statusDetail) — retrying"
                            else -> "Disconnected — retrying"
                        }
                    },
                    color = when (state) {
                        LinkState.CONNECTED -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { LinkService.start(context) }) { Text("Start link service") }
            OutlinedButton(onClick = { LinkService.stop(context) }) { Text("Stop") }
        }

        Card(colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        )) {
            Column(Modifier.padding(16.dp)) {
                Text("Devices on this network", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.padding(4.dp))
                if (discovered.isEmpty()) {
                    Text(
                        "Searching… Open InterLink on your other devices and keep everything on the same Wi-Fi.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    discovered.forEach { device ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(device.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${device.ip}:${device.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (device.role == "laptop") "Laptop" else "Phone",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.padding(5.dp))
                    }
                }
            }
        }

        Text("Permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        PermissionRow(
            label = "Notification access (mirroring)",
            ok = listenerOn,
            actionLabel = "Grant",
            onAction = {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )

        RuntimeRow(context, tick, multiLauncher, "Notifications (Android 13+)",
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
        RuntimeRow(context, tick, multiLauncher, "SMS",
            arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS))
        RuntimeRow(context, tick, multiLauncher, "Contacts",
            arrayOf(Manifest.permission.READ_CONTACTS))
        RuntimeRow(context, tick, multiLauncher, "Phone / calls",
            arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS))
        RuntimeRow(context, tick, multiLauncher, "Microphone (call audio)",
            arrayOf(Manifest.permission.RECORD_AUDIO))
        RuntimeRow(context, tick, multiLauncher, "Media (photos & videos)",
            if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            })
    }
}

@Composable
private fun RuntimeRow(
    context: Context,
    tick: Int,
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    label: String,
    perms: Array<String>,
) {
    if (perms.isEmpty()) return
    val ok = remember(tick, perms) {
        perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    PermissionRow(
        label = label,
        ok = ok,
        actionLabel = if (ok) "Granted" else "Request",
        enabled = !ok,
        onAction = { launcher.launch(perms) },
    )
}

@Composable
private fun PermissionRow(
    label: String,
    ok: Boolean,
    actionLabel: String,
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (ok) "✔" else "✖", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
    }
}

private fun notifListenerEnabled(context: Context): Boolean =
    Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?.contains(context.packageName) == true

// ————————————————————————————— Settings —————————————————————————————

@Composable
fun SettingsScreen(prefs: Prefs, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var confirmedReset by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val cfg = prefs.snapshot()
        host = cfg.host ?: ""
        port = cfg.port.toString()
        name = cfg.deviceName
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        androidx.compose.material3.OutlinedTextField(
            value = host, onValueChange = { host = it; saved = false },
            label = { Text("Laptop host (blank = auto-discover)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        androidx.compose.material3.OutlinedTextField(
            value = port, onValueChange = { port = it; saved = false },
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        androidx.compose.material3.OutlinedTextField(
            value = name, onValueChange = { name = it; saved = false },
            label = { Text("Device name") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Button(onClick = {
            scope.launch {
                // Blank host → clear endpoint so LinkClient falls back to UDP discovery.
                prefs.updateEndpoint(host.ifBlank { null }, port.toIntOrNull() ?: 27341)
                prefs.saveName(name.ifBlank { "Phone" })
                LinkService.start(context)
                saved = true
            }
        }) { Text(if (saved) "Saved ✔" else "Save & (re)start") }

        Spacer(Modifier.padding(8.dp))
        Text("Pairing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Resetting unpairs both ends — the laptop must show a new QR code.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            if (confirmedReset) {
                scope.launch {
                    prefs.clearPairing()
                    LinkService.stop(context)
                    confirmedReset = false
                }
            } else {
                confirmedReset = true
            }
        }) {
            Text(if (confirmedReset) "Tap again to confirm" else "Reset pairing")
        }
    }
}
