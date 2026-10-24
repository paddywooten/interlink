package com.interlink.app.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.interlink.app.core.Bus
import com.interlink.app.core.objOf

/**
 * Receives PackageInstaller session status for remote installs and relays it
 * to the laptop as an `install.status` push (PROTOCOL.md §4.10).
 *
 * Registered `exported="false"` and only ever addressed explicitly by
 * [com.interlink.app.handlers.AppHandler], so no other app can spoof it.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkg = intent.getStringExtra("pkg") ?: ""
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Hand the system confirmation dialog to the user.
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Log.e(TAG, "cannot show install prompt: ${it.message}") }
            }
            return
        }

        Log.i(TAG, "install $pkg → ${name(status)} ($detail)")
        Bus.push(
            "install.status",
            objOf(
                "pkg" to pkg,
                "status" to name(status),
                "message" to detail,
            ),
        )
    }

    private fun name(status: Int): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "installed"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "cancelled"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked"
        PackageInstaller.STATUS_FAILURE_INVALID -> "invalid"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "storage"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflict"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible"
        else -> "failed"
    }

    private companion object {
        const val TAG = "InstallResult"
    }
}
