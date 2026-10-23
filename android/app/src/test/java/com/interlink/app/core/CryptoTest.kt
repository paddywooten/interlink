package com.interlink.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Regression guards for the crypto that must stay byte-identical to the Rust desktop
 * (desktop/src-tauri/src/crypto.rs). These use published test vectors so a divergence
 * between Kotlin and Rust fails loudly in CI instead of surfacing as a mysterious
 * "hello failed to decrypt" on the wire.
 */
class CryptoTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** HChaCha20 — draft-irtf-cfrg-xchacha, Appendix A (Test Vector 1). */
    @Test
    fun hchacha20_matchesRfcVector() {
        val key = hex("000102030405060708090a0b0c0d0e0f" + "101112131415161718191a1b1c1d1e1f")
        val nonce16 = hex("00000009" + "0000004a" + "00000000" + "31415927")
        val expected = hex(
            "82413b4227b27bfed30e42508a877d73" + "a0f9e4d58a74a853c12ec41326d3ecdc"
        )
        assertArrayEquals(expected, Crypto.hchacha20(key, nonce16))
    }

    /**
     * The pre-fix bug: the subkey was built from words 0..7 instead of 0..3 || 12..15.
     * Assert against the *wrong* old behavior being gone by pinning the correct output above;
     * here we additionally sanity-check sealMsg/openMsg round-trips.
     */
    @Test
    fun frameKey_sealOpenMsg_roundTrip() {
        val secret = ByteArray(32) { it.toByte() }
        val key = Crypto.deriveRootKey(secret)
        val pt = """{"type":"hello","v":1,"nonce":"abc"}""".toByteArray()
        val frame = key.sealMsg(pt)
        assertArrayEquals(pt, key.openMsg(frame))
    }

    /** HKDF-SHA256 — RFC 5869 Test Case 1. */
    @Test
    fun hkdf_matchesRfc5869Vector() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val out = Crypto.hkdf(ikm, salt, info, 42)
        val expected = hex(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"
        )
        assertArrayEquals(expected, out)
    }
}
