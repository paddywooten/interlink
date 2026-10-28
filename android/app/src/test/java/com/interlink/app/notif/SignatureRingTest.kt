package com.interlink.app.notif

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guards for attachment de-duplication.
 *
 * The first of these tests is the important one. The ring originally answered
 * "have we already sent this?", and the call site inverted it — which shipped
 * every repeat and dropped every first picture, silently, with all five tests
 * here still passing. Pinned on the decision the call site actually makes.
 */
class SignatureRingTest {

    @Test
    fun a_picture_never_seen_before_is_sent() {
        val ring = SignatureRing(4)
        assertTrue(ring.shouldSend("0|com.whatsapp|1", 11))
    }

    @Test
    fun the_same_picture_twice_is_not_sent_again() {
        val ring = SignatureRing(4)
        assertTrue(ring.shouldSend("0|com.whatsapp|1", 11))
        assertFalse(ring.shouldSend("0|com.whatsapp|1", 11))
        assertFalse(ring.shouldSend("0|com.whatsapp|1", 11))
    }

    /** A new photo on the same notification must go out again. */
    @Test
    fun a_changed_picture_is_sent_again() {
        val ring = SignatureRing(4)
        ring.shouldSend("0|com.whatsapp|1", 11)
        assertTrue(ring.shouldSend("0|com.whatsapp|1", 12))
        assertFalse(ring.shouldSend("0|com.whatsapp|1", 12))
    }

    /** Different notifications are independent, even with the same signature. */
    @Test
    fun keysAreIndependent() {
        val ring = SignatureRing(4)
        ring.shouldSend("a", 7)
        assertTrue(ring.shouldSend("b", 7))
    }

    /** Bounded: the oldest key ages out, and so does its signature. */
    @Test
    fun oldestKeysAgeOut() {
        val ring = SignatureRing(2)
        ring.shouldSend("a", 1)
        ring.shouldSend("b", 2)
        ring.shouldSend("c", 3) // evicts "a"
        assertTrue(ring.shouldSend("a", 1))
        assertFalse(ring.shouldSend("c", 3))
    }
}
