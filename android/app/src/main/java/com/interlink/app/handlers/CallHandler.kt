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
import com.interlink.app.core.bool
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

    /** Direction flags for the active relay (see audioStart). */
    @Volatile
    private var uplink = true

    @Volatile
    private var downlink = true

    /**
     * Per-run stop switch. `streaming` alone can't gate the loop: a restart
     * flips it back on before the previous thread has drained, which would
     * keep the old thread alive alongside the new one.
     */
    @Volatile
    private var runFlag: java.util.concurrent.atomic.AtomicBoolean? = null

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
                placeCall(uri)
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

    /**
     * Put the call onto the network — not merely onto the laptop's screen.
     *
     * `ACTION_CALL` hands off to the *dialer's* activity, and Android 10+
     * blocks an activity launch from an app with no visible window (this one
     * runs as a foreground service, usually with the screen off). The block is
     * the nasty kind: the intent is dropped, **no exception is thrown**, so
     * from here everything looks like success while the phone never dials.
     * Telecom's own API is a binder call rather than an activity launch, so
     * there is no background-activity rule in the way, and a refused call
     * comes back as an exception — which the laptop can show, instead of
     * sitting on a call that does not exist.
     *
     * `ACTION_CALL` stays as a fallback for the case where Telecom itself
     * refuses `placeCall` but our own activity happens to be on screen: that
     * was the only path that ever worked, so it is worth keeping.
     */
    private fun placeCall(uri: Uri) {
        val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        try {
            telecom.placeCall(uri, Bundle())
            CallRegistry.log("placeCall accepted for ${uri.schemeSpecificPart}")
            return
        } catch (t: Throwable) {
            CallRegistry.log(
                "placeCall refused (${t.javaClass.simpleName}: ${t.message}) — trying ACTION_CALL"
            )
        }
        ctx.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
     *
     * Two independent directions are negotiated:
     *   `uplink`   (default true) — laptop mic → phone (speak from the laptop)
     *   `downlink` (default true) — phone → laptop speakers (hear on the laptop)
     * Turning both off keeps every sound on the phone itself.
     */
    suspend fun audioStart(d: JsonElement): JsonObject {
        val cid = d.str("callId") ?: callId ?: CallRegistry.currentCallId
        if (cid == null || cid != callId ?: CallRegistry.currentCallId) {
            throw Exception("not_found: no such call")
        }
        val wantUp = d.bool("uplink") ?: true
        val wantDown = d.bool("downlink") ?: true
        if (!wantUp && !wantDown) {
            stopAudio()
            Bus.push("call.audio_state", objOf("callId" to cid, "ok" to true, "mode" to "phone"))
            return objOf("ok" to true, "mode" to "phone")
        }
        if (streaming && (uplink != wantUp || downlink != wantDown)) {
            stopAudio() // direction change → restart with the new flags
        }
        if (!streaming) {
            if (wantDown && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Bus.push("call.audio_state", objOf("callId" to cid, "ok" to false, "reason" to "mic_permission"))
                return objOf("ok" to false, "reason" to "mic_permission")
            }
            startAudioLoop(cid, wantUp, wantDown)
        }
        return objOf("ok" to true, "mode" to mode(wantUp, wantDown))
    }

    private fun mode(up: Boolean, down: Boolean): String = when {
        up && down -> "relay"
        down -> "hear_laptop"
        else -> "speak_laptop"
    }

    fun audioStop(@Suppress("UNUSED_PARAMETER") d: JsonElement): JsonObject {
        stopAudio()
        return objOf("ok" to true)
    }

    /** Frames read per candidate source while probing (~600 ms). */
    private val probeFrames = 30

    /** Consecutive frames of exact zeros that means "muted", not "quiet". */
    private val silenceGateFrames = 100 // 2 s at 50 frames/s

    /**
     * Sources that might carry the far end of a cellular call, best first.
     * Only `VOICE_CALL` and its siblings tap the call itself, and Android
     * guards them behind `CAPTURE_AUDIO_OUTPUT` — a signature permission we do
     * not have — so most builds refuse them and we fall through to the VoIP
     * source and finally the raw microphone. Which one we land on decides what
     * the laptop hears, so it is logged every time.
     */
    private val captureSources = listOf(
        MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
        MediaRecorder.AudioSource.VOICE_DOWNLINK to "VOICE_DOWNLINK",
        MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
        MediaRecorder.AudioSource.DEFAULT to "DEFAULT(mic)",
    )

    /** Why [openCapture] found nothing — shown to the user, not swallowed. */
    @Volatile
    private var captureFailure: String = "capture_blocked"

    /**
     * The first source that hands back *audio*, not merely bytes.
     *
     * Bytes are not enough. A source can open, start and read perfectly well
     * while the platform holds it muted for the whole call, handing back exact
     * zeros — which is precisely what this handset does to
     * VOICE_COMMUNICATION. Each candidate therefore gets ~600 ms of reads and
     * is judged on its loudest sample; silence means "try the next one", and
     * silence everywhere means the phone simply does not hand its call audio
     * over, which the laptop is told rather than left to guess.
     */
    private fun openCapture(bufSize: Int, sampleRate: Int, frameBytes: Int): Pair<AudioRecord, String>? {
        var opened = false
        for ((src, name) in captureSources) {
            val rec = try {
                val r = AudioRecord(
                    src, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
                )
                if (r.state != AudioRecord.STATE_INITIALIZED) {
                    r.release()
                    CallRegistry.log("call audio: source $name will not initialise")
                    null
                } else {
                    r
                }
            } catch (t: Throwable) {
                CallRegistry.log("call audio: source $name refused to open (${t.javaClass.simpleName})")
                null
            } ?: continue

            opened = true
            val probe = ByteArray(frameBytes)
            var loudest = 0
            var reads = 0
            while (reads < probeFrames) {
                val n = try {
                    if (reads == 0) rec.startRecording()
                    rec.read(probe, 0, probe.size)
                } catch (t: Throwable) {
                    CallRegistry.log("call audio: source $name failed to read (${t.message})")
                    -1
                }
                if (n <= 0) break
                loudest = maxOf(loudest, peakOf(probe, n))
                reads++
                if (loudest > 0) break
            }
            CallRegistry.log("call audio: probe $name — loudest sample $loudest over $reads frames")
            if (loudest > 0) return rec to name
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        captureFailure = if (opened) "capture_silent" else "capture_blocked"
        return null
    }

    /**
     * Largest absolute 16-bit sample in `buf[0 until len]`. The only honest
     * measure of "is there audio here": bytes can be plentiful and still be
     * the exact zeros a muted HAL hands back.
     */
    private fun peakOf(buf: ByteArray, len: Int): Int {
        var peak = 0
        var k = 0
        while (k + 1 < len) {
            val v = (buf[k + 1].toInt() shl 8) or (buf[k].toInt() and 0xff)
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            k += 2
        }
        return peak
    }

    private fun startAudioLoop(cid: String, up: Boolean, down: Boolean) {
        if (streaming) return
        streaming = true
        uplink = up
        downlink = down
        val flag = java.util.concurrent.atomic.AtomicBoolean(true)
        runFlag = flag
        audioThread = thread(name = "il-call-audio", isDaemon = true) {
            val sampleRate = 16000
            val frameBytes = 320 * 2 // 20 ms @ 16 kHz mono S16LE (rate/ch/frameMs per protocol)
            val tid = Crypto.tidFromString(cid) // desktop tid_from_str(callId)
            val inboundKey = tidKeyFromRaw(tid)
            var inbound: kotlinx.coroutines.channels.Channel<Pair<Int, ByteArray>>? = null
            var rec: AudioRecord? = null
            var track: AudioTrack? = null
            try {
                val bufSize = maxOf(
                    AudioRecord.getMinBufferSize(
                        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    ),
                    frameBytes * 8,
                )
                var capSrc = "none"
                var tapSource = false
                if (down) {
                    val cap = openCapture(bufSize, sampleRate, frameBytes)
                    if (cap == null) {
                        audioBlocked(cid, captureFailure)
                        return@thread
                    }
                    rec = cap.first
                    capSrc = cap.second
                    // A tap carries the call itself. A microphone can only
                    // hear what the handset plays, so with a mic source the
                    // handset has to keep playing or there is nothing to relay.
                    tapSource = capSrc == "VOICE_CALL" || capSrc == "VOICE_DOWNLINK"
                }
                if (up) {
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
                    // Laptop → phone: its mic arrives on the same tid.
                    inbound = client.collectorFor(inboundKey, capacity = 64)
                }
                if (rec?.recordingState != AudioRecord.RECORDSTATE_RECORDING) rec?.startRecording()
                // `ok:true` is earned by audio, not by a successful open():
                // this handset reads perfectly from a muted source, so nothing
                // is announced until a non-zero sample actually arrives.
                var announced = !down
                var silence = 0L
                var silenceSaid = !down

                val buf = ByteArray(frameBytes)
                var seq = 0
                var frames = 0L
                var peak = 0
                var logAt = System.currentTimeMillis()
                while (flag.get()) {
                    val t = track
                    if (up && t != null) drainInbound(inbound, t)
                    if (down) {
                        var off = 0
                        val r0 = rec
                        while (off < frameBytes && flag.get() && r0 != null) {
                            val r = r0.read(buf, off, frameBytes - off)
                            if (r < 0) throw Exception("capture read failed (code $r)")
                            if (r == 0) break
                            off += r
                        }
                        if (off < frameBytes) {
                            // stopAudio() clears the flag before interrupting,
                            // so that is the normal exit. Anything else is a
                            // capture that died — and it has to be *said*: the
                            // old `break` swallowed it, leaving the laptop
                            // promising "hearing through your laptop" while
                            // every frame quietly stopped.
                            if (flag.get()) audioBlocked(cid, "capture_stopped")
                            break
                        }
                        val loudest = peakOf(buf, off)
                        if (loudest == 0) silence++ else silence = 0
                        if (!announced) {
                            if (loudest > 0) {
                                announced = true
                                CallRegistry.log("call audio: downlink live via $capSrc")
                                Bus.push(
                                    "call.audio_state",
                                    objOf("callId" to cid, "ok" to true, "mode" to mode(up, down)),
                                )
                            } else if (silence >= silenceGateFrames && !silenceSaid) {
                                // Two seconds of exact zeros is not a quiet
                                // call — it is a platform holding the tap
                                // shut. Say so once, then keep watching: if
                                // audio turns up later it announces itself.
                                silenceSaid = true
                                CallRegistry.log(
                                    "call audio: $capSrc stays silent — this phone keeps its own call audio",
                                )
                                Bus.push(
                                    "call.audio_state",
                                    objOf("callId" to cid, "ok" to false, "reason" to "capture_silent"),
                                )
                            }
                        }
                        if (announced) {
                            frames++
                            if (loudest > peak) peak = loudest
                            val now = System.currentTimeMillis()
                            if (now - logAt >= 2000) {
                                CallRegistry.log(
                                    "call audio: downlink via $capSrc — $frames frames, peak $peak",
                                )
                                // A mic source needs the handset playing; only
                                // a true tap earns taking it out of the way.
                                if (peak > 0 && tapSource) CallRegistry.leaveLoudspeaker()
                                peak = 0
                                logAt = now
                            }
                            // sendData is suspend — bridge the thread boundary.
                            if (!runBlocking { client.sendData(tid, seq, buf.copyOf()) }) break
                            seq++
                        }
                    } else {
                        // No capture (hear through the phone) — keep draining only.
                        Thread.sleep(20)
                    }
                }
            } catch (t: Throwable) {
                // An intentional stop interrupts the sleep/read above — don't
                // report that as a relay failure.
                if (flag.get()) audioBlocked(cid, t.message ?: "capture_blocked")
            } finally {
                if (up) client.dropCollector(inboundKey)
                runCatching { rec?.stop() }
                rec?.release()
                runCatching { track?.pause(); track?.flush(); track?.release() }
                if (runFlag === flag) {
                    audioSink = null
                    runFlag = null
                    streaming = false
                }
            }
        }
    }

    private fun drainInbound(
        inbound: kotlinx.coroutines.channels.Channel<Pair<Int, ByteArray>>?,
        track: AudioTrack,
    ) {
        if (inbound == null) return
        while (true) {
            val polled = inbound.tryReceive().getOrNull() ?: break
            runCatching { track.write(polled.second, 0, polled.second.size) }
        }
    }

    private fun audioBlocked(cid: String, reason: String) {
        streaming = false
        // Nothing is being relayed any more: hand the call back to the phone
        // as it was before we took the loudspeaker away.
        CallRegistry.restoreLoudspeaker()
        CallRegistry.log("call audio: relay down ($reason)")
        Bus.push("call.audio_state", objOf("callId" to cid, "ok" to false, "reason" to reason))
    }

    /** Stop and (best-effort) join the relay thread so a restart can't overlap. */
    private fun stopAudio() {
        streaming = false
        runFlag?.set(false)
        runFlag = null
        val t = audioThread
        audioThread = null
        t?.interrupt()
        runCatching { t?.join(1500) }
        runCatching { audioSink?.stop() }
        audioSink = null
        CallRegistry.restoreLoudspeaker()
    }
}
