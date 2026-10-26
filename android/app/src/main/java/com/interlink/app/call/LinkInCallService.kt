package com.interlink.app.call

import android.telecom.Call
import android.telecom.InCallService
import android.os.SystemClock
import com.interlink.app.core.Bus

/**
 * System bridge for GSM calls. Telecom binds us as a NON_UI InCallService —
 * requires the MANAGE_ONGOING_CALLS appop (declared in the manifest; granted
 * via `adb shell appops set com.interlink.app MANAGE_ONGOING_CALLS allow`).
 * Without it Android 15+ classifies this service INVALID and never binds, so
 * no call.state pushes would ever fire. We track state changes and push them
 * to the laptop as call.state (PROTOCOL.md §4.6).
 *
 * Manifest declares the standard android.telecom.InCallService intent-filter
 * with BIND_INCALL_SERVICE protection — do not remove.
 */
class LinkInCallService : InCallService() {

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            handleState(call, state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        CallRegistry.service = this
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val id = CallRegistry.attach(call)
        call.registerCallback(callback)
        CallRegistry.log("call added id=$id state=${call.state}")
        // Not only RINGING: an outgoing call arrives already DIALING, and that
        // is the moment the laptop needs to know a call is under way — it is
        // what puts the end-call control on screen while the far end rings.
        handleState(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callback)
        val id = CallRegistry.detach(call) ?: CallRegistry.currentCallId
        val number = CallRegistry.number(call)
        val duration = if (CallRegistry.activeSince > 0) {
            (SystemClock.elapsedRealtime() - CallRegistry.activeSince) / 1000
        } else null
        if (id != null) {
            pushBoth(CallRegistry.statePayload("ended", id, number, null, duration))
        }
        // The relay may have been cut short (link drop) rather than stopped
        // cleanly — clear the route we moved so the next call starts where the
        // user expects.
        CallRegistry.restoreLoudspeaker()
        CallRegistry.log("call removed id=$id")
    }

    override fun onDestroy() {
        CallRegistry.restoreLoudspeaker()
        CallRegistry.service = null
        super.onDestroy()
    }

    private fun handleState(call: Call, state: Int) {
        val id = CallRegistry.currentCallId ?: return
        when (state) {
            Call.STATE_RINGING -> emit("incoming", call, id)
            // Outgoing: dialling is the state we spend the longest in, and the
            // one the laptop must show an end-call button for.
            Call.STATE_DIALING -> emit("dialing", call, id)
            Call.STATE_ACTIVE -> {
                CallRegistry.activeSince = SystemClock.elapsedRealtime()
                emit("active", call, id)
            }
            Call.STATE_DISCONNECTED -> {
                val duration = if (CallRegistry.activeSince > 0) {
                    (SystemClock.elapsedRealtime() - CallRegistry.activeSince) / 1000
                } else null
                pushBoth(CallRegistry.statePayload("ended", id, CallRegistry.number(call), null, duration))
                CallRegistry.detach(call)
            }
            else -> Unit
        }
    }

    private fun emit(state: String, call: Call, id: String) {
        val number = CallRegistry.number(call)
        val name = CallRegistry.nameFor(this, number)
        pushBoth(CallRegistry.statePayload(state, id, number, name))
    }

    /**
     * Push both the PROTOCOL-canonical type (server.rs forwards call.state to
     * the desktop UI) and the short call.incoming alias some UIs listen for.
     */
    private fun pushBoth(payload: kotlinx.serialization.json.JsonElement) {
        Bus.push("call.state", payload)
        Bus.push("call.incoming", payload)
    }
}
