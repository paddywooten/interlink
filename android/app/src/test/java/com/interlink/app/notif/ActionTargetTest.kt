package com.interlink.app.notif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression guards for laptop → phone action routing.
 *
 * These pin the bug that made replying to WhatsApp impossible: a typed reply
 * arrived with a blank token, `"".toIntOrNull()` gave null, and the handler
 * returned without invoking anything — while the laptop toasted "Reply sent".
 * Nothing on the wire ever changed to reveal it, which is exactly what tests
 * are for.
 */
class ActionTargetTest {

    /** WhatsApp's shape: `Reply` (carries RemoteInput), then `Mark as read`. */
    private val whatsapp = listOf(true, false)

    /** The reported bug, in one assertion. */
    @Test
    fun blankToken_withReply_landsOnRemoteInputAction() {
        assertEquals(0, resolveActionTarget("", "on my way", whatsapp))
    }

    @Test
    fun plainTap_usesTheIndexTheLaptopSent() {
        assertEquals(1, resolveActionTarget("1", null, whatsapp))
    }

    @Test
    fun replyText_canReachAReplyActionThatIsNotFirst() {
        assertEquals(2, resolveActionTarget("", "hi", listOf(false, false, true)))
    }

    /** A stale index must fail, not silently fire whichever action sits there. */
    @Test
    fun outOfRangeIndex_doesNotFallThroughToAnUnrelatedAction() {
        assertNull(resolveActionTarget("7", null, whatsapp))
    }

    @Test
    fun nonNumericToken_isIgnoredRatherThanGuessed() {
        assertNull(resolveActionTarget("reply", null, whatsapp))
    }

    /** Nowhere to put the text is a failure, not a best-effort send. */
    @Test
    fun replyWithNoRemoteInput_reportsFailure() {
        assertNull(resolveActionTarget("", "hi", listOf(false, false)))
    }

    @Test
    fun plainTapWithBlankToken_firesNothing() {
        assertNull(resolveActionTarget("", null, whatsapp))
    }
}
