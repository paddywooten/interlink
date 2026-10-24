package com.interlink.app.notif

/**
 * Decide which of a notification's actions a laptop request actually meant.
 *
 * Deliberately free of Android types, so it can be tested on its own. This is
 * the exact spot where replies to WhatsApp (and anything else using
 * RemoteInput) went missing: the laptop's reply box sent a blank token,
 * `"".toIntOrNull()` is null, and the handler returned — no error, no reply,
 * while the UI cheerfully toasted "Reply sent". A wrong answer here used to do
 * *nothing* rather than fail, which is the one kind of bug a live run will
 * never surface.
 *
 * @param token the action index the laptop sent. Blank when it is sending
 *   typed reply text and has no index of its own to name.
 * @param reply the reply text, or null when this is a plain action tap.
 * @param acceptsRemoteInput, per action, whether it carries a RemoteInput —
 *   i.e. whether it is one a typed reply can be delivered to.
 * @return the action to fire, or null if nothing here can carry the request.
 */
internal fun resolveActionTarget(
    token: String,
    reply: String?,
    acceptsRemoteInput: List<Boolean>,
): Int? {
    val byIndex = token.toIntOrNull()?.takeIf { it in acceptsRemoteInput.indices }
    if (byIndex != null) return byIndex
    // No usable index: if the laptop sent reply text, land on whichever action
    // can actually hold it rather than on an arbitrary one.
    if (reply != null) return acceptsRemoteInput.indexOf(true).takeIf { it >= 0 }
    return null
}
