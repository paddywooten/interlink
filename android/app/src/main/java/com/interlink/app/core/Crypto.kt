package com.interlink.app.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mirrors desktop/src-tauri/src/crypto.rs exactly:
 *  - HKDF-SHA256 key derivation
 *  - XChaCha20-Poly1305 (HChaCha20 subkey + Android's ChaCha20-Poly1305 AEAD)
 *
 * MSG frame  (0x01): kind(1) || nonce(24) || ct(+16)                    AAD = kind
 * DATA frame (0x02): kind(1) || nonce(24) || tid(16) || seq(4 BE) || ct  AAD = kind||tid||seq
 */
object Crypto {
    const val KIND_MSG: Byte = 0x01
    const val KIND_DATA: Byte = 0x02
    private const val ALGO = "ChaCha20-Poly1305"
    private val rng = SecureRandom()

    class DecryptException : Exception("frame decryption failed")

    class FrameKey(private val key: ByteArray) {
        init {
            require(key.size == 32) { "key must be 32 bytes" }
        }

        /** Seal a kind-0x01 JSON frame. */
        fun sealMsg(plaintext: ByteArray): ByteArray {
            val nonce = ByteArray(24).also { rng.nextBytes(it) }
            val ct = aeadEncrypt(key, nonce, plaintext, byteArrayOf(KIND_MSG))
            return byteArrayOf(KIND_MSG) + nonce + ct
        }

        /** Open a kind-0x01 frame, returns JSON bytes. */
        fun openMsg(frame: ByteArray): ByteArray {
            require(frame.size >= 1 + 24 + 16 && frame[0] == KIND_MSG) { "bad frame" }
            val nonce = frame.copyOfRange(1, 25)
            val ct = frame.copyOfRange(25, frame.size)
            return aeadDecrypt(key, nonce, ct, byteArrayOf(KIND_MSG))
        }

        /** Seal a kind-0x02 data frame. */
        fun sealData(tid: ByteArray, seq: Int, chunk: ByteArray): ByteArray {
            require(tid.size == 16)
            val nonce = ByteArray(24).also { rng.nextBytes(it) }
            val aad = dataAad(tid, seq)
            val ct = aeadEncrypt(key, nonce, chunk, aad)
            val seqB = ByteBuffer.allocate(4).putInt(seq).array()
            return byteArrayOf(KIND_DATA) + nonce + tid + seqB + ct
        }

        /** Open a kind-0x02 frame → (tid, seq, chunk). */
        fun openData(frame: ByteArray): Triple<ByteArray, Int, ByteArray> {
            require(frame.size >= 1 + 24 + 16 + 4 + 16 && frame[0] == KIND_DATA) { "bad frame" }
            val nonce = frame.copyOfRange(1, 25)
            val tid = frame.copyOfRange(25, 41)
            val seq = ByteBuffer.wrap(frame, 41, 4).int
            val ct = frame.copyOfRange(45, frame.size)
            val pt = aeadDecrypt(key, nonce, ct, dataAad(tid, seq))
            return Triple(tid, seq, pt)
        }

        fun kindOf(frame: ByteArray): Byte? =
            if (frame.isNotEmpty() && (frame[0] == KIND_MSG || frame[0] == KIND_DATA)) frame[0] else null
    }

    private fun dataAad(tid: ByteArray, seq: Int): ByteArray {
        val seqB = ByteBuffer.allocate(4).putInt(seq).array()
        return byteArrayOf(KIND_DATA) + tid + seqB
    }

    private fun aeadEncrypt(key: ByteArray, nonce24: ByteArray, pt: ByteArray, aad: ByteArray): ByteArray {
        val (subkey, iv) = xchachaParams(key, nonce24)
        val c = Cipher.getInstance(ALGO)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(subkey, ALGO), IvParameterSpec(iv))
        c.updateAAD(aad)
        return c.doFinal(pt)
    }

    private fun aeadDecrypt(key: ByteArray, nonce24: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray {
        val (subkey, iv) = xchachaParams(key, nonce24)
        val c = Cipher.getInstance(ALGO)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(subkey, ALGO), IvParameterSpec(iv))
        c.updateAAD(aad)
        return try {
            c.doFinal(ct)
        } catch (e: Exception) {
            throw DecryptException()
        }
    }

    /** XChaCha20: subkey = HChaCha20(key, nonce[0..16]); iv = 4 zero bytes || nonce[16..24]. */
    private fun xchachaParams(key: ByteArray, nonce24: ByteArray): Pair<ByteArray, ByteArray> {
        val subkey = hchacha20(key, nonce24.copyOfRange(0, 16))
        val iv = ByteArray(12)
        System.arraycopy(nonce24, 16, iv, 4, 8)
        return subkey to iv
    }

    // ——— HChaCha20 ———
    // internal (not private) so unit tests can check it against the RFC test vector.
    internal fun hchacha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val st = IntArray(16)
        // constants "expand 32-byte k"
        st[0] = 0x61707865
        st[1] = 0x3320646e
        st[2] = 0x79622d32
        st[3] = 0x6b206574
        for (i in 0..7) st[4 + i] = le32(key, i * 4)
        for (i in 0..3) st[8 + i + 4] = le32(nonce16, i * 4) // st[12..15] = nonce words
        val w = st.copyOf()
        repeat(10) {
            quarter(w, 0, 4, 8, 12); quarter(w, 1, 5, 9, 13)
            quarter(w, 2, 6, 10, 14); quarter(w, 3, 7, 11, 15)
            quarter(w, 0, 5, 10, 15); quarter(w, 1, 6, 11, 12)
            quarter(w, 2, 7, 8, 13); quarter(w, 3, 4, 9, 14)
        }
        // HChaCha20 output = the raw round output words 0..3 ‖ 12..15.
        // CRITICAL: unlike regular ChaCha20's output function, HChaCha20 does NOT
        // add the original state back (no feed-forward). This must match the
        // RFC-standard XChaCha20 used on the desktop (chacha20poly1305 crate).
        val out = ByteArray(32)
        for (i in 0..3) putLe32(out, i * 4, w[i])
        for (i in 0..3) putLe32(out, 16 + i * 4, w[12 + i])
        return out
    }

    private fun quarter(w: IntArray, a: Int, b: Int, c: Int, d: Int) {
        w[a] += w[b]; w[d] = Integer.rotateLeft(w[d] xor w[a], 16)
        w[c] += w[d]; w[b] = Integer.rotateLeft(w[b] xor w[c], 12)
        w[a] += w[b]; w[d] = Integer.rotateLeft(w[d] xor w[a], 8)
        w[c] += w[d]; w[b] = Integer.rotateLeft(w[b] xor w[c], 7)
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte(); b[off + 1] = ((v shr 8) and 0xff).toByte()
        b[off + 2] = ((v shr 16) and 0xff).toByte(); b[off + 3] = ((v shr 24) and 0xff).toByte()
    }

    // ——— HKDF-SHA256 ———
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        var t = ByteArray(0)
        var okm = ByteArray(0)
        var i = 1
        while (okm.size < len) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            okm += t
            i++
        }
        return okm.copyOf(len)
    }

    private fun hmac(k: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(k, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    fun deriveRootKey(secret: ByteArray): FrameKey =
        FrameKey(hkdf(secret, "interlink-v1-root".toByteArray(), "frame-key".toByteArray(), 32))

    fun deriveSessionKey(secret: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): FrameKey =
        FrameKey(hkdf(secret, clientNonce + serverNonce, "session-key".toByteArray(), 32))

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** 16-byte transfer id derived from a string (mirrors desktop tid_from_str). */
    fun tidFromString(s: String): ByteArray = sha256(s.toByteArray()).copyOf(16)
}
