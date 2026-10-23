package com.interlink.app.core

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Base64

data class LanDevice(
    val name: String,
    val role: String,
    val ip: String,
    val port: Int,
    val lastSeen: Long,
)

/**
 * LAN presence beacons, independent of the paired WebSocket link.
 *
 * Every InterLink device broadcasts ILANN|<b64url name>|<role>|<port> on UDP
 * 27342 every three seconds and listens for the same from its peers. The port is
 * deliberately separate from the link port so discovery cannot interfere with
 * pairing or an active connection.
 */
object LanDiscovery {
    const val BEACON_PORT = 27342
    private const val TAG = "LanDiscovery"
    private const val ANNOUNCE_MS = 3_000L
    private const val EXPIRE_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val seen = LinkedHashMap<String, LanDevice>()

    private val _devices = MutableStateFlow<List<LanDevice>>(emptyList())
    val devices: StateFlow<List<LanDevice>> = _devices

    /** Idempotent; safe to call from Application.onCreate. All I/O is background. */
    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            while (isActive) {
                try {
                    runSocket(appContext)
                } catch (e: Exception) {
                    Log.d(TAG, "beacon socket restarting: ${e.message}")
                    delay(5_000)
                }
            }
        }
    }

    private suspend fun runSocket(context: Context) {
        val prefs = Prefs(context)
        DatagramSocket(null).use { sock ->
            sock.reuseAddress = true
            sock.broadcast = true
            sock.soTimeout = 1_000
            sock.bind(InetSocketAddress(BEACON_PORT))
            Log.i(TAG, "listening for LAN devices on UDP $BEACON_PORT")
            coroutineScope {
                launch { announceLoop(sock, prefs) }
                while (isActive) {
                    receiveOne(sock)
                }
            }
        }
    }

    private suspend fun announceLoop(sock: DatagramSocket, prefs: Prefs) {
        while (true) {
            val cfg = prefs.snapshot()
            val name = cfg.deviceName.ifBlank { Build.MODEL ?: "Phone" }
            val encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(name.toByteArray(Charsets.UTF_8))
            val msg = "ILANN|$encoded|phone|${cfg.port}".toByteArray(Charsets.UTF_8)
            for (address in broadcastAddresses()) {
                try {
                    sock.send(DatagramPacket(msg, msg.size, address, BEACON_PORT))
                } catch (e: Exception) {
                    Log.d(TAG, "announce to $address failed: ${e.message}")
                }
            }
            pruneExpired()
            delay(ANNOUNCE_MS)
        }
    }

    private fun receiveOne(sock: DatagramSocket) {
        val buf = ByteArray(512)
        val packet = DatagramPacket(buf, buf.size)
        try {
            sock.receive(packet)
        } catch (_: SocketTimeoutException) {
            return
        }
        val ip = packet.address?.hostAddress ?: return
        if (ip in localIpv4Addresses()) return // our own broadcast
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val device = parseLanBeacon(text, ip, System.currentTimeMillis()) ?: return
        synchronized(seen) {
            val isNew = !seen.containsKey("${device.ip}:${device.port}")
            seen["${device.ip}:${device.port}"] = device
            publishLocked()
            if (isNew) Log.i(TAG, "discovered ${device.role} ${device.name} at ${device.ip}:${device.port}")
        }
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        synchronized(seen) {
            if (seen.values.removeAll { now - it.lastSeen >= EXPIRE_MS }) {
                publishLocked()
            }
        }
    }

    /** Must be called with [seen] locked. */
    private fun publishLocked() {
        _devices.value = seen.values.sortedWith(compareBy({ it.role }, { it.name }, { it.ip }))
    }
}

/** Parse one beacon. Exposed internally so the wire format can be unit-tested. */
internal fun parseLanBeacon(text: String, ip: String, now: Long): LanDevice? {
    val parts = text.split('|')
    if (parts.size != 4 || parts[0] != "ILANN") return null
    val port = parts[3].toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    val role = parts[2]
    if (role != "laptop" && role != "phone") return null
    val name = try {
        String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return LanDevice(name = name, role = role, ip = ip, port = port, lastSeen = now)
}

private fun localIpv4Addresses(): Set<String> = buildSet {
    runCatching {
        for (iface in NetworkInterface.getNetworkInterfaces()) {
            for (address in iface.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    add(address.hostAddress)
                }
            }
        }
    }
}

private fun broadcastAddresses(): Set<InetAddress> = buildSet {
    add(InetAddress.getByName("255.255.255.255"))
    runCatching {
        for (iface in NetworkInterface.getNetworkInterfaces()) {
            if (!iface.isUp || iface.isLoopback) continue
            for (address in iface.interfaceAddresses) {
                address.broadcast?.let { add(it) }
            }
        }
    }
}
