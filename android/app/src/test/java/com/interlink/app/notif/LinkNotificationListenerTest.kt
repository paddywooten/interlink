package com.interlink.app.notif

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guards for the mirror filter.
 *
 * These pin the bug behind "no notification ever shows on the laptop" — the
 * one that survived every check on both ends of the link. An early build wrote
 * a five-package allowlist into SharedPreferences and nothing has written it
 * since, so on real devices it lingered as a stale list that filtered out
 * WhatsApp, Gmail, the gallery and everything else, with the listener bound
 * and healthy the whole time.
 *
 * It hid so well because `com.android.shell` was on that list: an adb test
 * notification mirrored perfectly, so the pipeline looked proven end to end
 * while real notifications were dropped at the first line of the callback.
 */
class LinkNotificationListenerTest {

    /**
     * The exact five packages a stale allowlist used to whitelist. Every one
     * of them must mirror — including the one that made the bug invisible.
     */
    private val legacyAllowlist =
        listOf(
            "com.android.shell",
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.android.dialer",
            "com.google.android.dialer",
        )

    @Test
    fun everyLegacyAllowlistPackage_stillMirrors() {
        for (pkg in legacyAllowlist) {
            assertTrue("$pkg must mirror", LinkNotificationListener.mirrored(pkg))
        }
    }

    /** Ordinary apps were the casualties. */
    @Test
    fun ordinaryApps_mirror() {
        for (pkg in listOf("com.whatsapp", "com.google.android.gm", "com.miui.player")) {
            assertTrue("$pkg must mirror", LinkNotificationListener.mirrored(pkg))
        }
    }

    /** Our own "Connected" notification must never echo back to the laptop. */
    @Test
    fun ourOwnPackage_neverMirrors() {
        assertFalse(LinkNotificationListener.mirrored("com.interlink.app"))
    }

    /** A missing package name is not a licence to mirror the world. */
    @Test
    fun nullPackage_neverMirrors() {
        assertFalse(LinkNotificationListener.mirrored(null))
    }
}
