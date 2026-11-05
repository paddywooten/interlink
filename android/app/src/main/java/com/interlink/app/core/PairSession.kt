package com.interlink.app.core

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.KeyAgreement

/**
 * Approve-to-pair, the phone half. Mirrors desktop/src-tauri/src/pair.rs
 * byte-for-byte:
 *
 *   transcript = SHA-256("interlink-v1-pair" ‖ phone_pub ‖ laptop_pub
 *                                          ‖ phone_nonce ‖ laptop_nonce)
 *   code       = HKDF-SHA256(Z, salt = transcript, info = "pair-sas",   4 bytes)
 *   seal_key   = HKDF-SHA256(Z, salt = transcript, info = "pair-grant", 32 bytes)
 *
 * The six-digit code is shown on both screens; the user approves on the laptop
 * only when they match. The pairing secret then arrives inside a single frame
 * sealed under `seal_key`, so it never crosses the Wi-Fi in the clear.
 */
object PairSession {
    private const val TAG = "PairApprove"
    private const val CURVE = "secp256r1"
    private const val TRANSCRIPT_TAG = "interlink-v1-pair"

    /** The laptop sends its challenge promptly; a stall means it is not there. */
    private const val WAIT_CHALLENGE_MS = 15_000L

    /** Covers the laptop's 120 s decision window plus the round trip. */
    private const val WAIT_DECISION_MS = 130_000L

    /** Which end of the exchange this side is. The transcript is phone-first on
     * both sides, so the role must be stated — getting it backwards yields two
     * different codes and pairing fails with a mismatch that looks like user error. */
    enum class Role { PHONE, LAPTOP }

    class PairException(message: String) : Exception(message)

    /** What both sides must agree on. `code` is six zero-padded digits. */
    class Finished(val code: String, val key: Crypto.FrameKey)

    /** The secret and endpoint carried by the sealed grant. */
    data class Grant(
        /** STANDARD base64 — the same encoding LinkClient decodes. */
        val secretStdB64: String,
        val port: Int,
        val ip: String?,
        val name: String,
    )

    /**
     * One side's half of the key exchange. Ephemeral by construction: the
     * private key never leaves this object and [finish] can be called once.
     */
    class Handshake internal constructor(
        private val privateKey: PrivateKey,
        private val params: ECParameterSpec,
        /** SEC1 uncompressed point: 0x04 ‖ X(32) ‖ Y(32). */
        val pub: ByteArray,
        val nonce: ByteArray,
    ) {
        fun finish(role: Role, peerPub: ByteArray, peerNonce: ByteArray): Finished {
            require(peerPub.size == 65 && peerPub[0] == 0x04.toByte()) {
                "laptop sent a malformed public key"
            }
            require(peerNonce.size == 32) { "laptop sent a malformed nonce" }

            val point = ECPoint(
                BigInteger(1, peerPub.copyOfRange(1, 33)),
                BigInteger(1, peerPub.copyOfRange(33, 65)),
            )
            val peer = KeyFactory.getInstance("EC")
                .generatePublic(ECPublicKeySpec(point, params))
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(privateKey)
            agreement.doPhase(peer, true)
            val z = agreement.generateSecret()

            return if (role == Role.PHONE) {
                derive(z, pub, peerPub, nonce, peerNonce)
            } else {
                derive(z, peerPub, pub, peerNonce, nonce)
            }
        }
    }

    fun newHandshake(): Handshake {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE))
        val pair = generator.generateKeyPair()
        val public = pair.public as ECPublicKey
        val raw = byteArrayOf(0x04) +
            fixed32(public.w.affineX) +
            fixed32(public.w.affineY)
        return Handshake(
            privateKey = pair.private,
            params = public.params,
            pub = raw,
            nonce = Crypto.randomBytes(32),
        )
    }

    /** The shared derivation, split out so the tests can drive the pinned vector. */
    fun derive(
        z: ByteArray,
        phonePub: ByteArray,
        laptopPub: ByteArray,
        phoneNonce: ByteArray,
        laptopNonce: ByteArray,
    ): Finished {
        val transcript = Crypto.sha256(
            TRANSCRIPT_TAG.toByteArray(Charsets.UTF_8) +
                phonePub + laptopPub + phoneNonce + laptopNonce,
        )
        val sas = Crypto.hkdf(z, transcript, "pair-sas".toByteArray(Charsets.UTF_8), 4)
        val digits = (java.nio.ByteBuffer.wrap(sas).int.toLong() and 0xFFFF_FFFFL) % 1_000_000L
        val code = String.format(Locale.ROOT, "%06d", digits)
        val key = Crypto.hkdf(z, transcript, "pair-grant".toByteArray(Charsets.UTF_8), 32)
        return Finished(code, Crypto.FrameKey(key))
    }

    /**
     * Ask the laptop at [host]:[port] to pair.
     *
     * [onCode] is called as soon as the six digits exist, while the request is
     * still waiting for the laptop's user — the screen has to show them then,
     * not after the decision. Throws [PairException] with a user-readable
     * message when the laptop refuses or the exchange cannot be verified.
     */
    suspend fun pair(
        host: String,
        port: Int,
        deviceName: String,
        onCode: suspend (String) -> Unit,
    ): Grant = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        val inbox = Channel<ByteArray>(Channel.BUFFERED)
        val opened = CompletableDeferred<WebSocket>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                inbox.trySend(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // The laptop only speaks binary frames.
                Log.d(TAG, "ignoring text frame (${text.length} chars)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t)
                inbox.close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                inbox.close()
            }
        }
        val socket = client.newWebSocket(
            Request.Builder().url("ws://$host:$port/ws").build(),
            listener,
        )
        try {
            withTimeout(WAIT_DECISION_MS) {
                opened.await()
                val self = newHandshake()
                send(
                    socket,
                    Envelope.push(
                        "pair.begin",
                        objOf(
                            "device" to objOf("name" to deviceName, "os" to "android"),
                            "pub" to b64u(self.pub),
                            "nonce" to b64u(self.nonce),
                        ),
                    ),
                )

                val challenge = readEnvelope(inbox, WAIT_CHALLENGE_MS)
                if (challenge.t == "pair.reject") throw PairException(refusal(challenge.d))
                if (challenge.t != "pair.challenge") {
                    throw PairException("unexpected reply from the laptop: ${challenge.t}")
                }
                val peerPub = runCatching { unb64u(challenge.d.strOrNull("pub") ?: "") }
                    .getOrNull() ?: throw PairException("laptop sent an undecodable key")
                val peerNonce = runCatching { unb64u(challenge.d.strOrNull("nonce") ?: "") }
                    .getOrNull() ?: throw PairException("laptop sent an undecodable nonce")
                val finished = runCatching { self.finish(Role.PHONE, peerPub, peerNonce) }
                    .getOrElse { throw PairException("the laptop's key did not validate") }

                onCode(finished.code)

                val answer = nextBinary(inbox, WAIT_DECISION_MS)
                if (answer.isEmpty() || answer[0] != Crypto.KIND_MSG) {
                    val reject = runCatching {
                        Envelope.json.decodeFromString(Envelope.serializer(), String(answer))
                    }.getOrNull()
                    throw PairException(refusal(reject?.d))
                }
                val plaintext = runCatching { finished.key.openMsg(answer) }
                    .getOrElse {
                        throw PairException(
                            "the laptop's reply did not decrypt — the codes did not match",
                        )
                    }
                val grant = runCatching {
                    Envelope.json.parseToJsonElement(String(plaintext))
                }.getOrElse { throw PairException("the laptop's reply was malformed") }
                val secret = grant.strOrNull("secret")
                    ?: throw PairException("the laptop's reply carried no secret")
                Grant(
                    secretStdB64 = secret,
                    port = grant.intOrNull("port") ?: port,
                    ip = grant.strOrNull("ip"),
                    name = grant.objOrNull("device")?.strOrNull("name") ?: "laptop",
                )
            }
        } finally {
            socket.cancel()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun send(socket: WebSocket, env: Envelope) {
        val bytes = Envelope.json.encodeToString(Envelope.serializer(), env).toByteArray()
        if (!socket.send(bytes.toByteString())) throw PairException("the laptop closed the connection")
    }

    private suspend fun readEnvelope(inbox: Channel<ByteArray>, waitMs: Long): Envelope {
        val bytes = nextBinary(inbox, waitMs)
        return runCatching {
            Envelope.json.decodeFromString(Envelope.serializer(), String(bytes))
        }.getOrElse { throw PairException("the laptop sent an unreadable reply") }
    }

    private suspend fun nextBinary(inbox: Channel<ByteArray>, waitMs: Long): ByteArray =
        withTimeout(waitMs) {
            inbox.receive()
        }

    /** A laptop refusal, in words the person tapping the screen can act on. */
    private fun refusal(d: JsonElement?): String {
        val detail = d.strOrNull("detail")
        if (!detail.isNullOrBlank()) return detail
        return when (d.strOrNull("reason")) {
            "pairing_closed" -> "Open the Pair dialog on the laptop first."
            "denied" -> "The laptop rejected the request, or the codes did not match."
            "bad_request" -> "The laptop could not read the request."
            "bad_key" -> "The laptop could not accept this phone's key."
            else -> "The laptop refused to pair."
        }
    }

    /** Big-endian, left-padded to 32 bytes (BigInteger may drop leading zeros). */
    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> raw.copyOfRange(raw.size - 32, raw.size)
        }
    }

    private fun b64u(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun unb64u(text: String): ByteArray =
        Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    // Nullable-safe helpers: the refusal's payload may be missing entirely.
    private fun JsonElement?.strOrNull(key: String): String? =
        (this as? JsonObject)?.get(key)?.let { element ->
            runCatching { element.jsonPrimitive.content }.getOrNull()
        }

    private fun JsonElement?.objOrNull(key: String): JsonElement? =
        (this as? JsonObject)?.get(key)

    private fun JsonElement?.intOrNull(key: String): Int? =
        (this as? JsonObject)?.get(key)?.let { element ->
            runCatching { element.jsonPrimitive.int }.getOrNull()
        }
}
