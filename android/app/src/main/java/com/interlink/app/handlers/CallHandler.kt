package com.interlink.app.handlers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.interlink.app.call.CallRegistry
import com.interlink.app.core.Bus
import com.interlink.app.core.Crypto
import com.interlink.app.core.LinkClient
import com.interlink.app.core.objOf
import com.interlink.app.core.str
import com.interlink.app.core.tidKeyFromRaw
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.thread

/**
 * Phone-call bridge (PROTOCOL.md §4.6): place/answer/end GSM calls and
 * optionally relay mic ↔ laptop as PCM S16LE 16 kHz mono 20 ms frames over
 * kind-0x02 DATA frames (tid = SHA-256(callId)[0..16], mirroring the
 * desktop's tid_from_str).
 *
 * Handlers throw Exception with a stable code prefix ("perm:", "busy:",
 * "not_found:", "internal:") — LinkService maps them onto ok=false responses.
 */
class CallHandler(private val ctx: Context, private val client: LinkClient) {
    private val tel = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @Volatile
    private var callId: String? = null

    @Volatile
    private var streaming = false

    @Volatile
    private var audioSink: AudioTrack? = null

    private var audioThread: Thread? = null

    // ——— control ———

    fun dial(d: JsonElement): JsonObject {
        val number = (d.str("number") ?: "").trim()
        if (number.isEmpty()) throw Exception("internal: missing number")
        if (tel.callState != TelephonyManager.CALL_STATE_IDLE) {
            throw Exception("busy: a call is already in progress")
        }
        val cid = CallRegistry.newCallId()
        callId = cid
        // Pre-seed so LinkInCallService.onCallAdded adopts this id.
        CallRegistry.incomingCallId = cid
        CallRegistry.direction = "out"
        val uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null)
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                ctx.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                // No CALL_PHONE — hand off to the dialer with the number filled in.
                ctx.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (t: Throwable) {
            callId = null
            CallRegistry.incomingCallId = null
            throw Exception("internal: cannot place call: ${t.message}")
        }
        return objOf(
            "callId" to cid,
            "number" to number,
            "name" to (lookupName(number) ?: ""),
        )
    }

    fun answer(@Suppress("UNUSED_PARAMETER") d: JsonElement): JsonObject {
        val call = CallRegistry.active ?: throw Exception("not_found: no call to answer")
        runCatching { call.answer(0) }.getOrElse {
            throw Exception("internal: Telecom refused to answer")
        }
        return objOf("callId" to (CallRegistry.currentCallId ?: callId ?: ""), "state" to "active")
    }

    fun decline(@Suppress("UNUSED_PARAMETER") d: JsonElement): JsonObject {
        CallRegistry.active?.let { runCatching { it.disconnect() } }
        return objOf("ok" to true)
    }

    fun end(@Suppress("UNUSED_PARAMETER") d: JsonElement): JsonObject {
        CallRegistry.active?.let { runCatching { it.disconnect() } }
        stopAudio()
        callId = null
        return objOf("ok" to true)
    }

    fun setCallId(id: String?) {
        callId = id
    }

    fun info(): JsonObject {
        val state = when (tel.callState) {
            TelephonyManager.CALL_STATE_OFFHOOK -> "active"
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            else -> "idle"
        }
        return objOf(
            "callId" to (callId ?: CallRegistry.currentCallId ?: ""),
            "state" to state,
            "hasSim" to runCatching { tel.simState == TelephonyManager.SIM_STATE_READY }
                .getOrDefault(false),
        )
    }

    private fun lookupName(number: String): String? = runCatching {
        ctx.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    // ——— audio relay ———

    /**
     * Negotiate per PROTOCOL.md §4.6: answer call.audio_start with
     * call.audio_state ok:true or a failure reason. Cellular call audio
     * capture is OEM-restricted; on failure the call stays a phone-only call.
     */
    suspend fun audioStart(d: JsonElement): JsonObject {
        val cid = d.str("callId") ?: callId ?: CallRegistry.currentCallId
        if (cid == null || cid != callId ?: CallRegistry.currentCallId) {
            throw Exception("not_found: no such call")
        }
        if (!streaming) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Bus.push("call.audio_state", objOf("callId" to cid, "ok" to false, "reason" to "mic_permission"))
                return objOf("ok" to false, "reason" to "mic_permission")
            }
            startAudioLoop(cid)
        }
        return objOf("ok" to true)
    }

    fun audioStop(@Suppress("UNUSED_PARAMETER") d: JsonElement): JsonObject {
        stopAudio()
        return objOf("ok" to true)
    }

    private fun startAudioLoop(cid: String) {
        if (streaming) return
        streaming = true
        audioThread = thread(name = "il-call-audio", isDaemon = true) {
            val sampleRate = 16000
            val frameBytes = 320 * 2 // 20 ms @ 16 kHz mono S16LE (rate/ch/frameMs per protocol)
            val tid = Crypto.tidFromString(cid) // desktop tid_from_str(callId)
            var rec: AudioRecord? = null
            var track: AudioTrack? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                val bufSize = maxOf(minBuf, frameBytes * 8)
                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    audioBlocked(cid, "capture_blocked")
                    return@thread
                }
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioSink = track
                track.play()
                rec.startRecording()
                Bus.push("call.audio_state", objOf("callId" to cid, "ok" to true))

                // Laptop → phone: its mic arrives on the same tid; feed the earpiece.
                val inboundKey = tidKeyFromRaw(tid)
                val inbound = client.collectorFor(inboundKey, capacity = 64)
                try {
                    val buf = ByteArray(frameBytes)
                    var seq = 0
                    while (streaming) {
                        drainInbound(inbound, track)
                        var off = 0
                        while (off < frameBytes && streaming) {
                            val r = rec.read(buf, off, frameBytes - off)
                            if (r <= 0) break
                            off += r
                        }
                        if (off < frameBytes) break
                        // sendData is suspend — bridge the thread boundary.
                        if (!runBlocking { client.sendData(tid, seq, buf.copyOf()) }) break
                        seq++
                    }
                } finally {
                    client.dropCollector(inboundKey)
                }
            } catch (t: Throwable) {
                audioBlocked(cid, t.message ?: "capture_blocked")
            } finally {
                runCatching { rec?.stop() }
                rec?.release()
                runCatching { track?.pause(); track?.flush(); track?.release() }
                audioSink = null
                streaming = false
            }
        }
    }

    private fun drainInbound(
        inbound: kotlinx.coroutines.channels.Channel<Pair<Int, ByteArray>>,
        track: AudioTrack,
    ) {
        while (true) {
            val polled = inbound.tryReceive().getOrNull() ?: break
            runCatching { track.write(polled.second, 0, polled.second.size) }
        }
    }

    private fun audioBlocked(cid: String, reason: String) {
        streaming = false
        Bus.push("call.audio_state", objOf("callId" to cid, "ok" to false, "reason" to reason))
    }

    private fun stopAudio() {
        streaming = false
        audioThread?.interrupt()
        audioThread = null
        runCatching { audioSink?.stop() }
        audioSink = null
    }
}
