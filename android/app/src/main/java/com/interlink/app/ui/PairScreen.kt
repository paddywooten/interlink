package com.interlink.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.interlink.app.core.LanDevice
import com.interlink.app.core.LanDiscovery
import com.interlink.app.core.PairSession
import com.interlink.app.core.Prefs
import com.interlink.app.service.LinkService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val TAG = "PairScan"

/**
 * QR pairing: CameraX preview + ImageAnalysis → zxing QR decode, plus a
 * manual paste-the-code path for when the camera can't focus on the screen.
 *
 * Payload format (protocol.rs make_qr_payload):
 *   il1:<b64url(secret 32B)>:<port>:<b64url(laptop name utf8)>[:<ipv4>]
 * The optional 5th field is the laptop's IP — when present the phone skips
 * UDP discovery and connects straight to it.
 */
@Composable
fun PairScreen(prefs: Prefs, onPaired: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var status by remember { mutableStateOf("Point the camera at the QR code shown on the laptop.") }
    var tab by remember { mutableIntStateOf(0) }
    val discovered by LanDiscovery.devices.collectAsState()

    // Approve-to-pair state — see the "Approve" tab below.
    var pairTarget by remember { mutableStateOf<String?>(null) }
    var pairCode by remember { mutableStateOf<String?>(null) }
    var pairError by remember { mutableStateOf<String?>(null) }
    var pairJob by remember { mutableStateOf<Job?>(null) }

    val camLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { hasCamera = it }

    /** Shared by both tabs: parse → save → start the link service. */
    fun pairWith(rawText: String, hostOverride: String): Boolean {
        val p = parsePairingPayload(rawText)
        if (p == null) {
            status = "Not an InterLink pairing code."
            return false
        }
        val host = hostOverride.trim().ifEmpty { null } ?: p.ip
        status = "Paired with ${p.name} — starting link…"
        scope.launch {
            prefs.savePairing(p.secretStdB64, host, p.port)
            LinkService.start(context)
            onPaired()
        }
        return true
    }

    /**
     * Approve-to-pair: ask the laptop, show the code the laptop will also show,
     * and wait for the sealed secret. Nothing is saved until the grant decrypts,
     * which only happens if both screens carried the same six digits.
     */
    fun pairNearby(host: String, port: Int, label: String) {
        if (pairJob?.isActive == true) return
        pairError = null
        pairCode = null
        pairTarget = label
        pairJob = scope.launch {
            try {
                val cfg = prefs.snapshot()
                val grant = PairSession.pair(host, port, cfg.deviceName) { code ->
                    withContext(Dispatchers.Main) { pairCode = code }
                }
                // Prefer the address that just worked. The grant's LAN IP is a
                // better stored endpoint only when we came in over loopback
                // (adb reverse), where that IP may still work without the cable.
                val saveHost = if (host == "127.0.0.1" || host == "localhost") {
                    grant.ip ?: host
                } else {
                    host
                }
                prefs.savePairing(grant.secretStdB64, saveHost, grant.port)
                status = "Paired with ${grant.name} — starting link…"
                LinkService.start(context)
                onPaired()
            } catch (e: CancellationException) {
                status = "Pairing cancelled."
            } catch (e: Exception) {
                Log.w(TAG, "approve-to-pair failed", e)
                pairError = e.message ?: "Pairing failed."
            } finally {
                pairTarget = null
                pairCode = null
            }
        }
    }

    Column(
        modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pair", style = MaterialTheme.typography.titleMedium)

        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = {
                    tab = 0
                    status = "Point the camera at the QR code shown on the laptop."
                },
                text = { Text("Scan QR") },
            )
            Tab(
                selected = tab == 1,
                onClick = {
                    tab = 1
                    status = "Paste the pairing code from the laptop, or enter its IP."
                },
                text = { Text("Enter code") },
            )
            Tab(
                selected = tab == 2,
                onClick = {
                    tab = 2
                    status = "Ask a laptop to pair — open its Pair dialog first."
                },
                text = { Text("Approve") },
            )
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)

        when (tab) {
            0 -> {
                if (!hasCamera) {
                    Button(onClick = { camLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant camera permission")
                    }
                } else {
                    Card(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        Box(Modifier.fillMaxSize()) {
                            var done by remember { mutableStateOf(false) }
                            QrScannerView { text ->
                                // Latch the scanner only when pairing actually succeeded.
                                pairWith(text, "").also { ok -> if (ok) done = true }
                            }
                            if (done) {
                                Text(
                                    "✓ Paired",
                                    Modifier.align(Alignment.Center),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                            }
                        }
                    }
                    Text(
                        "The code is in the laptop app's Pair dialog. " +
                            "Scanning is automatic — no button to press.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            1 -> ManualPairTab(::pairWith) // fallback: paste code / enter Wi-Fi IP
            2 -> PairNearbyTab(
                devices = discovered.filter { it.role == "laptop" },
                waitingOn = pairTarget,
                code = pairCode,
                error = pairError,
                onPick = { d -> pairNearby(d.ip, d.port, d.name) },
                onManual = { host, port -> pairNearby(host, port, host) },
                onCancel = { pairJob?.cancel() },
            )
        }
    }
}

/** Paste-the-pairing-code path, with an optional manual laptop IP field. */
@Composable
private fun ManualPairTab(pairWith: (rawText: String, hostOverride: String) -> Boolean) {
    var code by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Pairing code from laptop (il1:…)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Laptop IP — optional (e.g. 192.168.1.23)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = { pairWith(code.trim(), host) },
            enabled = code.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect")
        }
        Text(
            "Leave the IP blank to use the code's address, or let discovery find " +
                "the laptop on your Wi-Fi network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Ask a discovered laptop to pair. The laptop must have its Pair dialog open,
 * and it shows the same six digits before anyone presses Approve there.
 */
@Composable
private fun PairNearbyTab(
    devices: List<LanDevice>,
    waitingOn: String?,
    code: String?,
    error: String?,
    onPick: (LanDevice) -> Unit,
    onManual: (String, Int) -> Unit,
    onCancel: () -> Unit,
) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("27341") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (waitingOn != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pairing with $waitingOn", style = MaterialTheme.typography.titleSmall)
                    if (code != null) {
                        Text(
                            "Check that the laptop shows the same six digits:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${code.take(3)} ${code.drop(3)}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Then press Approve on the laptop. Nothing is saved until you do.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("Asking the laptop…", style = MaterialTheme.typography.bodySmall)
                    }
                    if (error != null) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                }
            }
        } else {
            Text(
                "Ask a laptop to pair without scanning the QR code. Both devices must be " +
                    "on the same network.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (devices.isEmpty()) {
                Text(
                    "No laptop seen yet. Open the laptop app and its Pair dialog, and keep " +
                        "this screen open for a few seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (device in devices) {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${device.ip}:${device.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { onPick(device) }) { Text("Ask to pair") }
                    }
                }
            }

            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("Laptop IP (if not listed)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = { onManual(ip.trim(), port.toIntOrNull() ?: 27341) },
                enabled = ip.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Ask this laptop to pair")
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Parsed pairing payload. */
data class Pairing(
    /** Secret re-encoded as STANDARD base64 — LinkClient decodes with Base64.DEFAULT. */
    val secretStdB64: String,
    val port: Int,
    val name: String,
    /** IPv4 from the QR (5-field payload), or null → UDP discovery. */
    val ip: String?,
)

/**
 * Parse an il1 payload (4 fields legacy, 5 with IP) or null.
 * The URL-safe QR secret is re-encoded to the standard alphabet before saving.
 */
fun parsePairingPayload(text: String): Pairing? {
    val parts = text.trim().split(':')
    if ((parts.size != 4 && parts.size != 5) || parts[0] != "il1") return null
    val secret = runCatching {
        Base64.decode(parts[1], Base64.NO_WRAP or Base64.URL_SAFE)
    }.getOrNull() ?: return null
    if (secret.size != 32) return null
    val port = parts[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val name = runCatching {
        String(Base64.decode(parts[3], Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
    }.getOrDefault("laptop")
    val ip = parts.getOrNull(4)?.takeIf {
        it.isNotEmpty() && it.count { c -> c == '.' } == 3 && it.none { c -> c == ':' }
    }
    return Pairing(Base64.encodeToString(secret, Base64.NO_WRAP), port, name, ip)
}

/**
 * Camera preview + analyzer. [onText] returns true to latch the scanner
 * (stops analysis) — returning false keeps scanning (e.g. bad payload).
 */
@Composable
private fun QrScannerView(onText: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { MultiFormatReader() }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { proxy ->
                    try {
                        val text = decodeQr(reader, proxy)
                        if (text != null && text.startsWith("il1:")) {
                            Log.d(TAG, "decoded pairing code (${text.length} chars)")
                            if (onText(text)) {
                                // Latched: unbind analysis so we stop burning CPU.
                                Log.d(TAG, "pairing accepted, stopping scanner")
                                previewView.post { runCatching { provider.unbindAll() } }
                            }
                        }
                    } catch (t: Throwable) {
                        // Never let a bad frame kill the analyzer loop.
                        Log.w(TAG, "analyzer error", t)
                    } finally {
                        proxy.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/** Y-plane extraction honoring rowStride/pixelStride, then zxing decode. */
private fun decodeQr(reader: MultiFormatReader, proxy: ImageProxy): String? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val w = proxy.width
    val h = proxy.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    if (w < 20 || h < 20) return null
    val yPlane = ByteArray(w * h)
    if (pixelStride == 1 && rowStride == w) {
        buffer.rewind()
        buffer.get(yPlane)
    } else {
        for (row in 0 until h) {
            if (pixelStride == 1) {
                buffer.position(row * rowStride)
                buffer.get(yPlane, row * w, w)
            } else {
                for (col in 0 until w) {
                    yPlane[row * w + col] = buffer.get(row * rowStride + col * pixelStride)
                }
            }
        }
    }
    val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
    )
    tryDecode(reader, yPlane, w, h, hints)?.let { return it }
    // Fallback for inverted (light-on-dark) codes: zxing assumes dark-on-light,
    // so flip the luminance and try again.
    val inverted = ByteArray(w * h) { i -> (255 - (yPlane[i].toInt() and 0xFF)).toByte() }
    return tryDecode(reader, inverted, w, h, hints)
}

private fun tryDecode(
    reader: MultiFormatReader,
    y: ByteArray,
    w: Int,
    h: Int,
    hints: Map<DecodeHintType, Any>,
): String? = try {
    val source = PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)
    reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
} catch (_: com.google.zxing.NotFoundException) {
    null
} catch (t: Throwable) {
    Log.w(TAG, "qr decode failure", t)
    null
} finally {
    reader.reset()
}
