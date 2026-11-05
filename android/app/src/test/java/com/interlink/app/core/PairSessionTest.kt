package com.interlink.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The phone half of approve-to-pair must derive exactly what
 * desktop/src-tauri/src/pair.rs derives. If these drift, the two screens show
 * different six-digit codes and pairing fails with what looks like user error,
 * so the vector is pinned on both sides.
 */
class PairSessionTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Same inputs and expected outputs as `derivation_is_pinned_across_platforms`. */
    @Test
    fun derivation_matchesTheDesktopPin() {
        val z = ByteArray(32) { it.toByte() }
        val phonePub = byteArrayOf(0x04) + ByteArray(32) { 0x11 } + ByteArray(32) { 0x22 }
        val laptopPub = byteArrayOf(0x04) + ByteArray(32) { 0x33 } + ByteArray(32) { 0x44 }
        val phoneNonce = ByteArray(32) { 0x55 }
        val laptopNonce = ByteArray(32) { 0x66 }

        val finished = PairSession.derive(z, phonePub, laptopPub, phoneNonce, laptopNonce)
        assertEquals("542601", finished.code)

        // The sealing key must be the same one: open a frame sealed with the
        // key the Rust test pins.
        val expected = Crypto.FrameKey(
            hex("cadabe265fed840bbe27d4309ebf5b2c49537341f67bbbb3e702f0a5dc2b6753"),
        )
        val frame = expected.sealMsg("ok".toByteArray())
        assertEquals("ok", String(finished.key.openMsg(frame)))
    }

    /** A real exchange must land on the same code and interchangeable keys. */
    @Test
    fun bothSidesAgreeOnTheCodeAndKey() {
        val phone = PairSession.newHandshake()
        val laptop = PairSession.newHandshake()

        val a = phone.finish(PairSession.Role.PHONE, laptop.pub, laptop.nonce)
        val b = laptop.finish(PairSession.Role.LAPTOP, phone.pub, phone.nonce)

        assertEquals(a.code, b.code)
        assertEquals(6, a.code.length)

        val grant = a.key.sealMsg("""{"secret":"abc"}""".toByteArray())
        assertEquals("""{"secret":"abc"}""", String(b.key.openMsg(grant)))
    }

    /** A man in the middle terminates two exchanges, so the codes must differ. */
    @Test
    fun twoDifferentExchangesYieldDifferentCodes() {
        val phone = PairSession.newHandshake()
        val laptop = PairSession.newHandshake()
        val mitm = PairSession.newHandshake()

        val phoneCode = phone.finish(PairSession.Role.PHONE, mitm.pub, mitm.nonce).code
        val laptopCode = laptop.finish(PairSession.Role.LAPTOP, mitm.pub, mitm.nonce).code
        assertNotEquals(phoneCode, laptopCode)
    }
}
