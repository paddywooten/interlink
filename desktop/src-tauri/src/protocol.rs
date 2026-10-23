//! Wire protocol envelope (PROTOCOL.md §3).

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

pub const PROTO_VERSION: u8 = 1;

fn default_version() -> u8 {
    PROTO_VERSION
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Envelope {
    /// Protocol version. The phone's serializer omits fields left at their
    /// default (`encodeDefaults = false`), so `v` may be absent on the wire.
    #[serde(default = "default_version")]
    pub v: u8,
    pub t: String,
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub r: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ok: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub e: Option<String>,
    #[serde(default)]
    pub d: Value,
}

impl Envelope {
    /// A fresh request from this side.
    pub fn request(msg_type: &str, payload: Value) -> (String, Self) {
        let id = new_id();
        let env = Envelope {
            v: PROTO_VERSION,
            t: msg_type.to_string(),
            id: id.clone(),
            r: None,
            ok: None,
            e: None,
            d: payload,
        };
        (id, env)
    }

    /// A response to `req_id`.
    pub fn response(req_id: &str, msg_type: &str, payload: Value) -> Self {
        Envelope {
            v: PROTO_VERSION,
            t: msg_type.to_string(),
            id: new_id(),
            r: Some(req_id.to_string()),
            ok: Some(true),
            e: None,
            d: payload,
        }
    }

    pub fn error_response(req_id: &str, msg_type: &str, code: &str) -> Self {
        Envelope {
            v: PROTO_VERSION,
            t: msg_type.to_string(),
            id: new_id(),
            r: Some(req_id.to_string()),
            ok: Some(false),
            e: Some(code.to_string()),
            // Always an object: PROTOCOL.md §3 says `d` is a JSON payload.
            d: json!({}),
        }
    }

    /// A push (no correlation): notification posts, status, etc.
    pub fn push(msg_type: &str, payload: Value) -> Self {
        Envelope {
            v: PROTO_VERSION,
            t: msg_type.to_string(),
            id: new_id(),
            r: None,
            ok: None,
            e: None,
            d: payload,
        }
    }

    pub fn is_response(&self) -> bool {
        self.r.is_some()
    }
}

/// 8 random bytes, base64url — short unique message ids.
pub fn new_id() -> String {
    use base64::Engine;
    let mut b = [0u8; 8];
    use rand::RngCore;
    rand::thread_rng().fill_bytes(&mut b);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(b)
}

/// QR payload: il1:<b64url(secret)>:<port>:<b64url(device name)>[:<ipv4>]
/// The IP (4 parts when absent → phone falls back to UDP discovery) lets the
/// phone connect directly without relying on broadcast discovery.
pub fn make_qr_payload(secret: &[u8; 32], port: u16, device_name: &str, ip: Option<&str>) -> String {
    use base64::Engine;
    let e = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    let mut s = format!(
        "il1:{}:{}:{}",
        e.encode(secret),
        port,
        e.encode(device_name.as_bytes())
    );
    if let Some(ip) = ip {
        // IPv4 only — a colon-separated payload cannot carry IPv6.
        if !ip.contains(':') && !ip.is_empty() {
            s.push(':');
            s.push_str(ip);
        }
    }
    s
}

/// Returns (secret, port, device name, optional ipv4).
pub fn parse_qr_payload(s: &str) -> Result<([u8; 32], u16, String, Option<String>), String> {
    use base64::Engine;
    let e = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    let parts: Vec<&str> = s.split(':').collect();
    if (parts.len() != 4 && parts.len() != 5) || parts[0] != "il1" {
        return Err("invalid: not an InterLink pairing code".into());
    }
    let raw = e.decode(parts[1]).map_err(|_| "invalid: bad secret encoding")?;
    if raw.len() != 32 {
        return Err("invalid: bad secret length".into());
    }
    let mut secret = [0u8; 32];
    secret.copy_from_slice(&raw);
    let port: u16 = parts[2].parse().map_err(|_| "invalid: bad port")?;
    let name = e
        .decode(parts[3])
        .map(|b| String::from_utf8(b).unwrap_or_else(|_| "Phone".into()))
        .unwrap_or_else(|_| "Phone".into());
    let ip = parts
        .get(4)
        .map(|s| (*s).to_string())
        .filter(|s| !s.is_empty());
    Ok((secret, port, name, ip))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The phone serializes with `encodeDefaults = false`: `v` (1), `r`, `ok`,
    /// `e` and an empty `d` are omitted from the wire. Those envelopes must
    /// still parse or every phone→laptop message would be dropped.
    #[test]
    fn envelope_parses_with_default_fields_omitted() {
        let env: Envelope = serde_json::from_str(r#"{"t":"ping","id":"abc"}"#).expect("parse");
        assert_eq!(env.v, PROTO_VERSION);
        assert_eq!(env.t, "ping");
        assert!(!env.is_response());
        assert!(env.d.is_null());
    }

    /// Responses still parse when only the correlating fields are present.
    #[test]
    fn response_envelope_parses() {
        let env: Envelope =
            serde_json::from_str(r#"{"t":"file.done","id":"x","r":"req1","ok":true,"d":{}}"#)
                .expect("parse");
        assert!(env.is_response());
        assert_eq!(env.r.as_deref(), Some("req1"));
    }

    /// Error responses must carry a JSON object in `d` (PROTOCOL.md §3).
    #[test]
    fn error_responses_carry_an_object_payload() {
        let env = Envelope::error_response("req1", "sms.send", "perm: denied");
        let s = serde_json::to_string(&env).expect("serialize");
        assert!(s.contains(r#""ok":false"#), "{s}");
        assert!(s.contains(r#""d":{}"#), "{s}");
    }

    /// The QR payload is what the phone parses — make/parse must agree.
    #[test]
    fn qr_payload_round_trips() {
        let secret = crate::crypto::random_bytes32();
        // With IP (5 fields) …
        let qr = make_qr_payload(&secret, 27341, "My Laptop", Some("192.168.1.5"));
        let (s2, port, name, ip) = parse_qr_payload(&qr).expect("round-trip");
        assert_eq!(s2, secret);
        assert_eq!(port, 27341);
        assert_eq!(name, "My Laptop");
        assert_eq!(ip.as_deref(), Some("192.168.1.5"));
        // … and without IP (legacy 4 fields, discovery fallback).
        let qr4 = make_qr_payload(&secret, 27341, "My Laptop", None);
        let (s3, _, _, ip4) = parse_qr_payload(&qr4).expect("round-trip 4-part");
        assert_eq!(s3, secret);
        assert_eq!(ip4, None);
    }
}
