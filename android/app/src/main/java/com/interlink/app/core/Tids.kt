package com.interlink.app.core

import android.util.Base64

/**
 * Transfer ids travel inside JSON payloads as base64url-no-pad (the desktop
 * encodes with URL_SAFE_NO_PAD), but LinkClient keys its data-collector map
 * with android Base64.NO_WRAP (standard alphabet, WITH padding) of the raw
 * 16 bytes. These helpers bridge the two encodings so a tid taken from a
 * request payload lines up with the tid recovered from a 0x02 frame header.
 */
fun tidRaw(tidB64: String): ByteArray =
    Base64.decode(tidB64, Base64.NO_WRAP or Base64.URL_SAFE)

/** Collector-map key from raw tid bytes — must match LinkClient.framePump. */
fun tidKeyFromRaw(raw: ByteArray): String =
    Base64.encodeToString(raw, Base64.NO_WRAP)

/** Collector-map key from a base64url tid as it appears in JSON payloads. */
fun tidKey(tidB64: String): String = tidKeyFromRaw(tidRaw(tidB64))

/** base64url-no-pad of raw bytes, for echoing tids back to the desktop. */
fun tidToB64u(raw: ByteArray): String =
    Base64.encodeToString(raw, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
