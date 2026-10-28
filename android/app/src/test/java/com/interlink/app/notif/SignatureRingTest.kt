package com.interlink.app.notif

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guards for attachment de-duplication.
 *
 * Messaging apps re-post the same notification on every incoming message and
 * each re-post carries the same picture again. Without this, every message
 * would ship a couple of hundred kilobytes of base64 for an image the laptop
 * already holds.
 */
class SignatureRingTest {

    @Test
    fun firstSightOfAKey_isNotAlreadySent() {
        val ring = SignatureRing(4)
        assertFalse(ring.alreadySent("0|com.whatsapp|1", 11))
    }

    @Test
    fun theSameAttachmentTwice_reportsItAlreadySent() {
        val ring = SignatureRing(4)
        assertFalse(ring.alreadySent("0|com.whatsapp|1", 11))
        assertTrue(ring.alreadySent("0|com.whatsapp|1", 11))
    }

    /** A new photo on the same notification must go out again. */
    @Test
    fun aChangedAttachment_isSentAgain() {
        val ring = SignatureRing(4)
        ring.alreadySent("0|com.whatsapp|1", 11)
        assertFalse(ring.alreadySent("0|com.whatsapp|1", 12))
        assertTrue(ring.alreadySent("0|com.whatsapp|1", 12))
    }

    /** Different notifications are independent, even with the same signature. */
    @Test
    fun keysAreIndependent() {
        val ring = SignatureRing(4)
        ring.alreadySent("a", 7)
        assertFalse(ring.alreadySent("b", 7))
    }

    /** Bounded: the oldest key ages out, and so does its signature. */
    @Test
    fun oldestKeysAgeOut() {
        val ring = SignatureRing(2)
        ring.alreadySent("a", 1)
        ring.alreadySent("b", 2)
        ring.alreadySent("c", 3) // evicts "a"
        assertFalse(ring.alreadySent("a", 1))
        assertTrue(ring.alreadySent("c", 3))
    }
}
