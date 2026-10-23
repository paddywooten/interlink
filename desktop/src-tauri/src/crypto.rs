//! InterLink frame encryption: XChaCha20-Poly1305 with HKDF-derived keys.
//!
//! Message frame  (kind 0x01): kind(1) || nonce(24) || ct(+16)        AAD = kind
//! Data frame     (kind 0x02): kind(1) || nonce(24) || tid(16) || seq(4 BE) || ct(+16)
//!                                                                   AAD = kind || tid || seq
//! The data-frame header is cleartext so the receiver can route/assemble before decrypting.

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use rand::RngCore;
use sha2::Sha256;

pub const KIND_MSG: u8 = 0x01;
pub const KIND_DATA: u8 = 0x02;
const MSG_OVERHEAD: usize = 1 + 24 + 16; // kind + nonce + tag
const DATA_HEADER: usize = 1 + 24 + 16 + 4; // kind + nonce + tid + seq

#[derive(thiserror::Error, Debug)]
pub enum CryptoError {
    #[error("frame decryption failed (wrong key or tampered data)")]
    Decrypt,
    #[error("bad frame layout")]
    Layout,
}

#[derive(Clone)]
pub struct FrameKey {
    cipher: XChaCha20Poly1305,
}

impl FrameKey {
    /// Seal a kind-0x01 (JSON) frame.
    pub fn seal_msg(&self, plaintext: &[u8]) -> Vec<u8> {
        let mut nonce = [0u8; 24];
        rand::thread_rng().fill_bytes(&mut nonce);
        let ct = self
            .cipher
            .encrypt(XNonce::from_slice(&nonce), Payload { msg: plaintext, aad: &[KIND_MSG] })
            .expect("encryption cannot fail");
        let mut out = Vec::with_capacity(MSG_OVERHEAD + ct.len());
        out.push(KIND_MSG);
        out.extend_from_slice(&nonce);
        out.extend_from_slice(&ct);
        out
    }

    /// Open a kind-0x01 frame, returns the JSON plaintext.
    pub fn open_msg(&self, frame: &[u8]) -> Result<Vec<u8>, CryptoError> {
        if frame.len() < MSG_OVERHEAD || frame[0] != KIND_MSG {
            return Err(CryptoError::Layout);
        }
        let nonce = XNonce::from_slice(&frame[1..25]);
        self.cipher
            .decrypt(nonce, Payload { msg: &frame[25..], aad: &[KIND_MSG] })
            .map_err(|_| CryptoError::Decrypt)
    }

    /// Seal a kind-0x02 (data) frame.
    pub fn seal_data(&self, tid: &[u8; 16], seq: u32, chunk: &[u8]) -> Vec<u8> {
        let mut nonce = [0u8; 24];
        rand::thread_rng().fill_bytes(&mut nonce);
        let aad = data_aad(tid, seq);
        let ct = self
            .cipher
            .encrypt(XNonce::from_slice(&nonce), Payload { msg: chunk, aad: &aad })
            .expect("encryption cannot fail");
        let mut out = Vec::with_capacity(DATA_HEADER + ct.len());
        out.push(KIND_DATA);
        out.extend_from_slice(&nonce);
        out.extend_from_slice(tid);
        out.extend_from_slice(&seq.to_be_bytes());
        out.extend_from_slice(&ct);
        out
    }

    /// Open a kind-0x02 frame, returns (tid, seq, chunk).
    pub fn open_data(&self, frame: &[u8]) -> Result<([u8; 16], u32, Vec<u8>), CryptoError> {
        if frame.len() < DATA_HEADER + 16 || frame[0] != KIND_DATA {
            return Err(CryptoError::Layout);
        }
        let mut tid = [0u8; 16];
        tid.copy_from_slice(&frame[25..41]);
        let mut seqb = [0u8; 4];
        seqb.copy_from_slice(&frame[41..45]);
        let seq = u32::from_be_bytes(seqb);
        let aad = data_aad(&tid, seq);
        let nonce = XNonce::from_slice(&frame[1..25]);
        let chunk = self
            .cipher
            .decrypt(nonce, Payload { msg: &frame[45..], aad: &aad })
            .map_err(|_| CryptoError::Decrypt)?;
        Ok((tid, seq, chunk))
    }

    /// Frame kind byte (no decryption) for dispatch.
    pub fn kind_of(frame: &[u8]) -> Option<u8> {
        match frame.first() {
            Some(&k) if k == KIND_MSG || k == KIND_DATA => Some(k),
            _ => None,
        }
    }
}

fn data_aad(tid: &[u8; 16], seq: u32) -> Vec<u8> {
    let mut aad = Vec::with_capacity(21);
    aad.push(KIND_DATA);
    aad.extend_from_slice(tid);
    aad.extend_from_slice(&seq.to_be_bytes());
    aad
}

pub fn derive_root_key(secret: &[u8]) -> [u8; 32] {
    hkdf32(secret, b"interlink-v1-root", b"frame-key")
}

pub fn derive_session_key(secret: &[u8], client_nonce: &[u8], server_nonce: &[u8]) -> [u8; 32] {
    let mut salt = Vec::with_capacity(client_nonce.len() + server_nonce.len());
    salt.extend_from_slice(client_nonce);
    salt.extend_from_slice(server_nonce);
    hkdf32(secret, &salt, b"session-key")
}

fn hkdf32(ikm: &[u8], salt: &[u8], info: &[u8]) -> [u8; 32] {
    let hk = Hkdf::<Sha256>::new(Some(salt), ikm);
    let mut out = [0u8; 32];
    hk.expand(info, &mut out).expect("hkdf expand 32");
    out
}

pub fn frame_key(key32: [u8; 32]) -> FrameKey {
    FrameKey {
        cipher: XChaCha20Poly1305::new_from_slice(&key32).expect("32-byte key"),
    }
}

pub fn random_bytes32() -> [u8; 32] {
    let mut b = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut b);
    b
}

pub fn random_nonce32() -> Vec<u8> {
    let mut b = vec![0u8; 32];
    rand::thread_rng().fill_bytes(&mut b);
    b
}

pub fn new_transfer_id() -> [u8; 16] {
    let mut b = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut b);
    b
}

/// 16-byte transfer id derived from an arbitrary string (e.g. callId).
pub fn tid_from_str(s: &str) -> [u8; 16] {
    use sha2::Digest;
    let h = Sha256::digest(s.as_bytes());
    let mut tid = [0u8; 16];
    tid.copy_from_slice(&h[..16]);
    tid
}
