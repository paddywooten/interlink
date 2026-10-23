package com.interlink.app.core

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Session surface handed to request handlers. */
interface LinkSession {
    suspend fun respond(envelope: Envelope)
    suspend fun push(type: String, payload: kotlinx.serialization.json.JsonElement)
    suspend fun sendData(tid: ByteArray, seq: Int, chunk: ByteArray): Boolean
    val connected: Boolean
}

enum class LinkState { DISCONNECTED, CONNECTING, HANDSHAKING, CONNECTED }

/**
 * WebSocket client: handshake (root key → session key), keepalive, reconnect
 * with backoff + UDP LAN discovery. Single OkHttp listener feeds one channel
 * that is used both for the handshake response and the frame pump.
 */
class LinkClient(
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val onRequest: suspend (Envelope, LinkSession) -> Unit,
    private val onState: (LinkState, String?) -> Unit,
) {
    private val tag = "LinkClient"
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile
    var state: LinkState = LinkState.DISCONNECTED
        private set

    private var ws: WebSocket? = null
    @Volatile private var sessionKey: Crypto.FrameKey? = null
    private var job: Job? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<Envelope>>()
    val dataCollectors = ConcurrentHashMap<String, Channel<Pair<Int, ByteArray>>>()
    @Volatile private var lastInbound = 0L

    // ——— public API ———

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        ws?.close(1000, "bye")
        ws = null
        sessionKey = null
        setState(LinkState.DISCONNECTED, null)
    }

    val isConnected: Boolean get() = state == LinkState.CONNECTED

    suspend fun request(
        type: String,
        payload: kotlinx.serialization.json.JsonElement,
        timeoutMs: Long = 20_000,
    ): Envelope {
        if (!isConnected) throw Exception("offline: laptop is not connected")
        val (id, env) = Envelope.request(type, payload)
        val d = CompletableDeferred<Envelope>()
        pendingRequests[id] = d
        try {
            if (!sendEnvelope(env)) throw Exception("offline: connection lost")
            val resp = withTimeout(timeoutMs) { d.await() }
            if (resp.ok == false) throw Exception(resp.e ?: "request failed")
            return resp
        } finally {
            pendingRequests.remove(id)
        }
    }

    suspend fun push(type: String, payload: kotlinx.serialization.json.JsonElement) {
        sendEnvelope(Envelope.push(type, payload))
    }

    suspend fun sendData(tid: ByteArray, seq: Int, chunk: ByteArray): Boolean {
        val key = sessionKey ?: return false
        return sendFrame(key.sealData(tid, seq, chunk))
    }

    // ——— internals ———

    private fun setState(s: LinkState, msg: String?) {
        state = s
        onState(s, msg)
    }

    private fun sendEnvelope(env: Envelope): Boolean {
        val key = sessionKey ?: return false
        val bytes = Envelope.json.encodeToString(Envelope.serializer(), env)
        return sendFrame(key.sealMsg(bytes.toByteArray(Charsets.UTF_8)))
    }

    private fun sendFrame(frame: ByteArray): Boolean {
        val w = ws ?: return false
        return w.send(ByteString.of(*frame))
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (scope.isActive) {
            val cfg = prefs.snapshot()
            if (cfg.secretB64 == null) {
                setState(LinkState.DISCONNECTED, "unpaired")
                delay(2000)
                continue
            }
            var host = cfg.host
            if (host == null) {
                setState(LinkState.DISCONNECTED, "no endpoint")
                discover(cfg.port)?.let { (h, p) ->
                    prefs.updateEndpoint(h, p)
                    host = h
                }
                if (host == null) {
                    delay(3000)
                    continue
                }
            }
            try {
                setState(LinkState.CONNECTING, "$host:${cfg.port}")
                connectSession(host!!, cfg.port, cfg)
                attempt = 0
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "connect failed: ${e.message}")
                setState(LinkState.DISCONNECTED, e.message)
                attempt++
                if (attempt >= 3) {
                    // Endpoint likely stale (DHCP renewal) → UDP whois.
                    discover(cfg.port)?.let { (h, p) ->
                        Log.i(tag, "discovered laptop at $h:$p")
                        prefs.updateEndpoint(h, p)
                        attempt = 0
                    }
                }
                delay(backoff(attempt))
            }
        }
    }

    private fun backoff(attempt: Int): Long = when (attempt.coerceIn(0, 4)) {
        0 -> 1000
        1 -> 2000
        2 -> 4000
        3 -> 8000
        else -> 15000
    }

    /** One connection: handshake → frame pump → drop. */
    private suspend fun connectSession(host: String, port: Int, cfg: LinkConfig) {
        val secret = Base64.decode(cfg.secretB64, Base64.DEFAULT)
        require(secret.size == 32) { "corrupt pairing secret" }
        val rootKey = Crypto.deriveRootKey(secret)

        val inbound = Channel<ByteArray>(Channel.BUFFERED)
        val opened = CompletableDeferred<WebSocket>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                inbound.trySend(bytes.toByteArray())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.w(tag, "ignoring text frame")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t)
                inbound.close(t)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                inbound.close()
            }
        }

        val req = Request.Builder().url("ws://$host:$port/ws").build()
        val socket = http.newWebSocket(req, listener)
        ws = socket

        try {
            val socket = withTimeout(12_000) { opened.await() }
            setState(LinkState.HANDSHAKING, null)

            // ——— hello (root-key sealed) ———
            val clientNonce = Crypto.randomBytes(32)
            val hello = Envelope.push(
                "hello",
                objOf(
                    "nonce" to Base64.encodeToString(clientNonce, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('='),
                    "device" to objOf(
                        "name" to cfg.deviceName,
                        "model" to android.os.Build.MODEL,
                        "os" to "android ${android.os.Build.VERSION.RELEASE}",
                        "app" to "InterLink Phone",
                    ),
                ),
            )
            val helloJson = Envelope.json.encodeToString(Envelope.serializer(), hello)
            socket.send(ByteString.of(*rootKey.sealMsg(helloJson.toByteArray(Charsets.UTF_8))))

            // ——— hello_ack ———
            val first = withTimeout(15_000) { inbound.receive() }
            val ackJson = rootKey.openMsg(first)
            val ack = Envelope.json.decodeFromString(Envelope.serializer(), String(ackJson, Charsets.UTF_8))
            if (ack.t != "hello_ack") throw Exception("expected hello_ack, got ${ack.t}")
            val serverNonce = Base64.decode(
                ack.d.str("nonce") ?: throw Exception("missing server nonce"),
                Base64.NO_WRAP or Base64.URL_SAFE,
            )
            require(serverNonce.size == 32) { "bad server nonce" }
            sessionKey = Crypto.deriveSessionKey(secret, clientNonce, serverNonce)

            setState(LinkState.CONNECTED, "$host:$port")
            framePump(socket, inbound)
        } finally {
            sessionKey = null
            ws = null
            socket.cancel()
            pendingRequests.forEach { (_, d) ->
                d.complete(Envelope.errorResponse("?", "internal", "offline: connection lost"))
            }
            pendingRequests.clear()
            setState(LinkState.DISCONNECTED, null)
        }
    }

    /** Process frames until drop; send keepalive pings. */
    private suspend fun framePump(socket: WebSocket, inbound: Channel<ByteArray>) {
        lastInbound = System.currentTimeMillis()
        val pinger = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15_000)
                if (System.currentTimeMillis() - lastInbound > 45_000) {
                    Log.w(tag, "idle timeout — closing")
                    socket.close(4000, "idle")
                    break
                }
                sendEnvelope(Envelope.push("ping", objOf()))
            }
        }
        try {
            for (frame in inbound) {
                lastInbound = System.currentTimeMillis()
                val key = sessionKey ?: break
                when (key.kindOf(frame)) {
                    Crypto.KIND_MSG -> {
                        val pt = key.openMsg(frame)
                        val env = Envelope.json.decodeFromString(
                            Envelope.serializer(),
                            String(pt, Charsets.UTF_8),
                        )
                        if (env.r != null) {
                            pendingRequests.remove(env.r)?.complete(env)
                        } else if (env.t == "ping") {
                            sendEnvelope(
                                Envelope.response(env.id, "ping", objOf()).copy(t = "pong"),
                            )
                        } else {
                            // Request from the laptop (or push) → handle async.
                            scope.launch {
                                try {
                                    onRequest(env, this@LinkClient.sessionObj)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e(tag, "handler ${env.t}: ${e.message}")
                                    sendEnvelope(
                                        Envelope.errorResponse(env.id, env.t, "internal: ${e.message}"),
                                    )
                                }
                            }
                        }
                    }
                    Crypto.KIND_DATA -> {
                        val (tid, seq, chunk) = key.openData(frame)
                        val tidKey = Base64.encodeToString(tid, Base64.NO_WRAP)
                        val col = dataCollectors[tidKey]
                        if (col == null) {
                            Log.w(tag, "data frame for unknown tid")
                        } else if (col.trySend(seq to chunk).isFailure) {
                            Log.w(tag, "collector busy — chunk dropped")
                        }
                    }
                    else -> break
                }
            }
        } finally {
            pinger.cancel()
        }
    }

    private val sessionObj = object : LinkSession {
        override suspend fun respond(envelope: Envelope) {
            sendEnvelope(envelope)
        }
        override suspend fun push(type: String, payload: kotlinx.serialization.json.JsonElement) {
            this@LinkClient.push(type, payload)
        }
        override suspend fun sendData(tid: ByteArray, seq: Int, chunk: ByteArray): Boolean =
            this@LinkClient.sendData(tid, seq, chunk)
        override val connected: Boolean get() = this@LinkClient.isConnected
    }

    /** Used by handlers that received data frames (media.get → none; uploads do). */
    fun collectorFor(tidB64: String, capacity: Int = 512): Channel<Pair<Int, ByteArray>> {
        val ch = Channel<Pair<Int, ByteArray>>(capacity)
        dataCollectors[tidB64] = ch
        return ch
    }

    fun dropCollector(tidB64: String) {
        dataCollectors.remove(tidB64)?.close()
    }

    /** UDP discovery: broadcast ILWHOIS?, await ILHERE|name|port. */
    private fun discover(port: Int): Pair<String, Int>? = try {
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 2500
            val msg = "ILWHOIS?".toByteArray()
            sock.send(
                DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), port),
            )
            val buf = ByteArray(512)
            val p = DatagramPacket(buf, buf.size)
            sock.receive(p)
            val s = String(buf, 0, p.length)
            if (s.startsWith("ILHERE|")) {
                val replyPort = s.substringAfterLast("|").toIntOrNull() ?: port
                p.address.hostAddress to replyPort
            } else null
        }
    } catch (e: Exception) {
        Log.d(tag, "discovery: ${e.message}")
        null
    }
}
