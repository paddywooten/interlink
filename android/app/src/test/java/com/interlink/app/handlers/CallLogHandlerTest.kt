package com.interlink.app.handlers

import android.provider.CallLog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `calllog.list` sends `kind`, not Android's `CallLog.Calls.TYPE` int.
 *
 * The mapping is a **closed vocabulary on the wire**: the laptop renders
 * eight known values and has no branch for anything else, so a provider type
 * that quietly falls through the `when` would arrive as raw data and paint
 * an unlabelled row. Every constant the platform defines gets pinned here —
 * including the one for a type nobody has thought of yet, which must be
 * `unknown` rather than an integer leaking out of the phone.
 */
class CallLogHandlerTest {

    @Test
    fun providerTypes_mapToTheWireVocabulary() {
        assertEquals("in", CallLogHandler.kindOf(CallLog.Calls.INCOMING_TYPE))
        assertEquals("out", CallLogHandler.kindOf(CallLog.Calls.OUTGOING_TYPE))
        assertEquals("missed", CallLogHandler.kindOf(CallLog.Calls.MISSED_TYPE))
        assertEquals("voicemail", CallLogHandler.kindOf(CallLog.Calls.VOICEMAIL_TYPE))
        assertEquals("rejected", CallLogHandler.kindOf(CallLog.Calls.REJECTED_TYPE))
        assertEquals("blocked", CallLogHandler.kindOf(CallLog.Calls.BLOCKED_TYPE))
        assertEquals("answered", CallLogHandler.kindOf(CallLog.Calls.ANSWERED_EXTERNALLY_TYPE))
    }

    /** Anything the platform doesn't document must still be a known word. */
    @Test
    fun unmappedTypes_neverLeakAsIntegers() {
        assertEquals("unknown", CallLogHandler.kindOf(0)) // no TYPE_UNKNOWN constant exists
        assertEquals("unknown", CallLogHandler.kindOf(99))
        assertEquals("unknown", CallLogHandler.kindOf(-1))
    }
}
