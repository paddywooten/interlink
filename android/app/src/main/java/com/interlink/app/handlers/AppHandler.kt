package com.interlink.app.handlers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.interlink.app.install.InstallResultReceiver
import com.interlink.app.core.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileInputStream

/**
 * Remote app install (PROTOCOL.md §4.10).
 *
 * The laptop streams the .apk over the normal file.offer path first, then
 * asks us to install the received file. We open a PackageInstaller session,
 * hand it to the system, and the user confirms the standard "Install this
 * app?" dialog **on the phone** — nothing ever installs silently.
 * [InstallResultReceiver] reports the outcome back to the laptop.
 */
object AppHandler {
    private const val TAG = "AppHandler"

    fun install(context: Context, d: JsonElement): JsonObject {
        val path = d.str("path") ?: throw Exception("internal: missing path")

        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            throw Exception(
                "perm: turn on \"Install unknown apps\" for InterLink on the phone, then retry",
            )
        }

        val apk = resolveApk(context, path)
            ?: throw Exception("not_found: apk not found on the phone ($path)")
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw Exception("internal: not a valid apk")
        val pkg = archive.packageName
            ?: throw Exception("internal: apk has no package name")
        val version = archive.versionName ?: ""

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(pkg)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                FileInputStream(apk).use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // FLAG_MUTABLE: the installer fills in the status extras.
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setPackage(context.packageName)
                    .putExtra("pkg", pkg)
                val pi = PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pi.intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw Exception("internal: cannot start install (${t.message})")
        }
        Log.i(TAG, "install prompt shown for $pkg $version (session $sessionId)")
        return com.interlink.app.core.objOf(
            "pkg" to pkg,
            "version" to version,
            "sessionId" to sessionId,
            "status" to "prompting",
        )
    }

    /**
     * Locate the file the laptop just sent. MediaStore.Downloads holds our own
     * contribution; a direct path works too when we own the file.
     */
    private fun resolveApk(context: Context, path: String): File? {
        val direct = File(path)
        if (direct.isAbsolute && direct.exists() && direct.canRead()) return direct

        val name = path.substringAfterLast('/')
        @Suppress("DEPRECATION")
        val inDownloads = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "InterLink/$name",
        )
        if (inDownloads.exists() && inDownloads.canRead()) return inDownloads

        // Scoped-storage fallback: resolve through MediaStore, stage in cache.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}/InterLink/"),
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0),
                        )
                        val staged = File(context.cacheDir, "apks/$name")
                        staged.parentFile?.mkdirs()
                        context.contentResolver.openInputStream(uri)?.use { ins ->
                            staged.outputStream().use { ins.copyTo(it) }
                        } ?: return null
                        if (staged.length() > 0) return staged
                    }
                }
            }
        }
        return null
    }
}
