//! Approve-to-pair: pairing without scanning the QR code.
//!
//! The phone finds the laptop on the LAN and asks to pair. Both sides then
//! generate an **ephemeral P-256 key pair**, swap public points, and derive two
//! things from the shared secret `Z`:
//!
//! ```text
//! transcript = SHA-256("interlink-v1-pair" ‖ phone_pub ‖ laptop_pub
//!                                         ‖ phone_nonce ‖ laptop_nonce)
//! code       = HKDF-SHA256(Z, salt = transcript, info = "pair-sas",   4 bytes)
//! seal_key   = HKDF-SHA256(Z, salt = transcript, info = "pair-grant", 32 bytes)
//! ```
//!
//! `code` is six digits shown on **both** screens; the user approves on the
//! laptop only when they match. `seal_key` seals the one frame that carries the
//! pairing secret, so the secret itself never crosses the wire in the clear.
//!
//! Why the code matters: the WebSocket is unauthenticated (plain `ws://`), so
//! an attacker on the LAN can sit in the middle, terminate one ECDH exchange
//! with the phone and another with the laptop. They end up with two *different*
//! `Z` values, hence two different codes — the comparison is what turns an
//! anonymous key exchange into an authenticated one. Six digits give a
//! 1-in-a-million chance of a coincidental match, and a wrong match is visible
//! on both screens rather than silently accepted.
//!
//! Nothing here is used for the session itself: once paired, the phone keeps
//! the secret and the normal root-key handshake in `server.rs` takes over.

use crate::crypto::{self, FrameKey};
use hkdf::Hkdf;
use p256::ecdh::EphemeralSecret;
use p256::{EncodedPoint, PublicKey};
use rand::rngs::OsRng;
use sha2::{Digest, Sha256};

/// Binds the derivation to this protocol version and this exact use.
const TRANSCRIPT_TAG: &[u8] = b"interlink-v1-pair";

/// One side's half of the key exchange.
///
/// Ephemeral by construction: [EphemeralSecret] cannot be cloned or serialised,
/// and [Session::finish] consumes it, so a key pair can never be reused for a
/// second attempt (which would let an attacker replay a captured code).
pub struct Session {
    secret: EphemeralSecret,
    /// SEC1 uncompressed point (65 bytes, leading 0x04).
    pubkey: Vec<u8>,
    nonce: [u8; 32],
}

/// The result both sides must agree on.
pub struct Finished {
    /// Six decimal digits, zero-padded. Rendered as "123 456" by the UI.
    pub code: String,
    /// Key that seals the frame carrying the pairing secret.
    pub key: FrameKey,
}

/// Which end of the exchange this session is. The transcript puts the phone
/// first on both sides, so the role has to be stated explicitly: getting it
/// backwards yields two different codes and pairing fails with a mismatch that
/// looks like a user error.
///
/// The desktop only ever completes the laptop half (the phone runs Kotlin), but
/// the variant stays because the derivation is role-symmetric and the tests
/// drive both sides.
#[allow(dead_code)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role {
    Phone,
    Laptop,
}

impl Session {
    pub fn new() -> Session {
        let secret = EphemeralSecret::random(&mut OsRng);
        let pubkey = EncodedPoint::from(secret.public_key()).as_bytes().to_vec();
        Session { secret, pubkey, nonce: crypto::random_bytes32() }
    }

    pub fn pubkey(&self) -> &[u8] {
        &self.pubkey
    }

    pub fn nonce(&self) -> &[u8; 32] {
        &self.nonce
    }

    /// Complete the exchange as `role` with the peer's public point and nonce.
    ///
    /// Consumes the session: a failed or aborted attempt must not be retried
    /// with the same ephemeral key.
    pub fn finish(
        self,
        role: Role,
        peer_pub: &[u8],
        peer_nonce: &[u8],
    ) -> Result<Finished, String> {
        let peer = PublicKey::from_sec1_bytes(peer_pub)
            .map_err(|_| "pairing: invalid public key".to_string())?;
        let shared = self.secret.diffie_hellman(&peer);
        let (phone_pub, phone_nonce, laptop_pub, laptop_nonce) = match role {
            Role::Laptop => (peer_pub, peer_nonce, self.pubkey.as_slice(), self.nonce.as_slice()),
            Role::Phone => (self.pubkey.as_slice(), self.nonce.as_slice(), peer_pub, peer_nonce),
        };
        Ok(derive(
            shared.raw_secret_bytes().as_slice(),
            phone_pub,
            laptop_pub,
            phone_nonce,
            laptop_nonce,
        ))
    }
}

/// The derivation itself, pure and split out so both roles (and the tests) can
/// call it with the transcript in the same order.
pub fn derive(
    z: &[u8],
    phone_pub: &[u8],
    laptop_pub: &[u8],
    phone_nonce: &[u8],
    laptop_nonce: &[u8],
) -> Finished {
    let mut h = Sha256::new();
    h.update(TRANSCRIPT_TAG);
    // Fixed-width fields, so plain concatenation is unambiguous.
    for part in [phone_pub, laptop_pub, phone_nonce, laptop_nonce] {
        h.update(part);
    }
    let transcript = h.finalize();

    let hk = Hkdf::<Sha256>::new(Some(&transcript), z);
    let mut sas = [0u8; 4];
    hk.expand(b"pair-sas", &mut sas).expect("hkdf expand 4");
    let digits = u32::from_be_bytes(sas) % 1_000_000;

    let mut key = [0u8; 32];
    hk.expand(b"pair-grant", &mut key).expect("hkdf expand 32");

    Finished { code: format!("{digits:06}"), key: crypto::frame_key(key) }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Both sides of a real exchange must land on the same six digits and the
    /// same sealing key. This is the property the whole feature rests on.
    #[test]
    fn both_sides_derive_the_same_code_and_key() {
        let phone = Session::new();
        let laptop = Session::new();
        let (phone_pub, phone_nonce) = (phone.pubkey().to_vec(), *phone.nonce());
        let (laptop_pub, laptop_nonce) = (laptop.pubkey().to_vec(), *laptop.nonce());

        let a = phone.finish(Role::Phone, &laptop_pub, &laptop_nonce).expect("phone side");
        let b = laptop.finish(Role::Laptop, &phone_pub, &phone_nonce).expect("laptop side");

        assert_eq!(a.code, b.code);
        assert_eq!(a.code.len(), 6);
        assert!(a.code.chars().all(|c| c.is_ascii_digit()));

        // Prove the keys are interchangeable, not merely equal-looking: the
        // laptop seals the grant, the phone must be able to open it.
        let grant = a.key.seal_msg(br#"{"secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}"#);
        let opened = b.key.open_msg(&grant).expect("grant opens with the phone's key");
        assert!(String::from_utf8_lossy(&opened).contains("secret"));
    }

    /// Cross-language pin: the phone (Kotlin) test asserts exactly these values
    /// for exactly these inputs. One side drifting would break pairing with a
    /// code mismatch that looks like a user error, so pin the derivation.
    #[test]
    fn derivation_is_pinned_across_platforms() {
        let z: Vec<u8> = (0u8..32).collect();
        let phone_pub = [&[0x04u8][..], &[0x11; 32], &[0x22; 32]].concat();
        let laptop_pub = [&[0x04u8][..], &[0x33; 32], &[0x44; 32]].concat();
        let phone_nonce = [0x55u8; 32];
        let laptop_nonce = [0x66u8; 32];

        let f = derive(&z, &phone_pub, &laptop_pub, &phone_nonce, &laptop_nonce);
        assert_eq!(f.code, "542601");
        // Seal under the derived key and compare bytes with the expected key
        // by re-deriving it independently of `derive`.
        let mut h = Sha256::new();
        h.update(TRANSCRIPT_TAG);
        h.update(&phone_pub);
        h.update(&laptop_pub);
        h.update(phone_nonce);
        h.update(laptop_nonce);
        let mut key = [0u8; 32];
        Hkdf::<Sha256>::new(Some(&h.finalize()), &z)
            .expand(b"pair-grant", &mut key)
            .expect("hkdf");
        assert_eq!(
            key.to_vec(),
            hex("cadabe265fed840bbe27d4309ebf5b2c49537341f67bbbb3e702f0a5dc2b6753")
        );
        // And the key really is the one in `Finished`.
        let sealed = f.key.seal_msg(b"x");
        assert!(crypto::frame_key(key).open_msg(&sealed).is_ok());
    }

    /// A man in the middle terminates two exchanges and therefore holds two
    /// different secrets. The codes must differ, or the comparison would be
    /// theatre.
    #[test]
    fn a_man_in_the_middle_sees_two_different_codes() {
        let phone = Session::new();
        let laptop = Session::new();
        let m1 = Session::new(); // MITM's leg towards the phone
        let m2 = Session::new(); // MITM's leg towards the laptop

        let phone_pub = phone.pubkey().to_vec();
        let phone_nonce = *phone.nonce();
        let laptop_pub = laptop.pubkey().to_vec();
        let laptop_nonce = *laptop.nonce();

        // Leg 1: the phone talks to the MITM believing it is the laptop.
        let phone_code = phone.finish(Role::Phone, m1.pubkey(), m1.nonce()).expect("phone").code;
        let m1_code = m1.finish(Role::Laptop, &phone_pub, &phone_nonce).expect("mitm A").code;
        // Leg 2: the MITM talks to the laptop believing it is the phone.
        let laptop_code = laptop.finish(Role::Laptop, m2.pubkey(), m2.nonce()).expect("laptop").code;
        let m2_code = m2.finish(Role::Phone, &laptop_pub, &laptop_nonce).expect("mitm B").code;

        // Each leg agrees with itself…
        assert_eq!(phone_code, m1_code);
        assert_eq!(laptop_code, m2_code);
        // …but the MITM cannot make the two screens show the same digits.
        assert_ne!(phone_code, laptop_code);
    }

    #[test]
    fn a_short_code_is_zero_padded() {
        // Any derivation whose digits are small must still render six chars, or
        // the phone and laptop would show different strings for the same value.
        let f = derive(&[0u8; 32], &[1], &[2], &[3], &[4]);
        assert_eq!(f.code.len(), 6, "code {} lost its padding", f.code);
    }

    #[test]
    fn a_malformed_public_key_is_rejected_not_panicked_on() {
        assert!(Session::new().finish(Role::Laptop, &[0x04; 65], &[0u8; 32]).is_err());
        assert!(Session::new().finish(Role::Laptop, &[], &[0u8; 32]).is_err());
    }

    fn hex(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).expect("hex"))
            .collect()
    }
}
