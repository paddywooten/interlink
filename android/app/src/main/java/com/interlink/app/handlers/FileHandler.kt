package com.interlink.app.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.interlink.app.FilePickerActivity
import com.interlink.app.core.Envelope
import com.interlink.app.core.LinkClient
import com.interlink.app.core.LinkSession
import com.interlink.app.core.long
import com.interlink.app.core.objOf
import com.interlink.app.core.str
import com.interlink.app.core.tidRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Generic phone files for the desktop's Files page (PROTOCOL.md §4.4).
 *
 * Android scoped storage does not let a background service enumerate arbitrary
 * files. The user therefore grants one folder with the system's folder picker;
 * the persisted URI permission survives reboots and needs no root / all-files
 * permission. Until a folder is chosen, the Downloads collection is exposed as
 * a best-effort fallback.
 */
object FileHandler {
    private const val TAG = "FileHandler"
    private const val CHUNK = 64 * 1024
    private const val PREFS = "interlink_files"
    private const val KEY_TREE = "tree_uri"
    private const val KEY_LABEL = "tree_label"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Client waiting for the SAF picker to finish. Replaced by a newer request. */
    @Volatile
    private var pendingClient: LinkClient? = null

    // ——— folder grant ———

    /** Answer immediately; the result arrives later as a `file.granted` push. */
    fun requestGrant(context: Context, client: LinkClient): JsonObject {
        pendingClient = client
        val intent = Intent(context, FilePickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            pendingClient = null
            throw Exception("internal: could not open folder picker: ${e.message}")
        }
        return objOf("status" to "picker_opened")
    }

    fun onTreeResult(context: Context, uri: Uri?) {
        val client = pendingClient
        pendingClient = null
        if (uri == null) {
            if (client != null) {
                scope.launch { client.push("file.granted", objOf("granted" to false, "reason" to "cancelled")) }
            }
            return
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            Log.w(TAG, "could not persist folder permission: ${e.message}")
        }
        val prefs = prefs(context)
        prefs.edit()
            .putString(KEY_TREE, uri.toString())
            .putString(KEY_LABEL, labelFor(context, uri))
            .apply()
        if (client != null) {
            scope.launch {
                client.push(
                    "file.granted",
                    objOf("granted" to true, "root" to prefs.getString(KEY_LABEL, "Phone storage")),
                )
            }
        }
    }

    fun rootInfo(context: Context): JsonObject = objOf(
        "granted" to (treeUri(context) != null),
        "root" to (prefs(context).getString(KEY_LABEL, null) ?: "Downloads"),
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun treeUri(context: Context): Uri? =
        prefs(context).getString(KEY_TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun labelFor(context: Context, uri: Uri): String = try {
        val rootId = DocumentsContract.getTreeDocumentId(uri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, rootId)
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst() && c.getString(0).isNotBlank()) c.getString(0) else null
        } ?: rootId.substringAfterLast(':')
    } catch (e: Exception) {
        uri.lastPathSegment?.substringAfterLast(':') ?: "Phone storage"
    }

    // ——— file.list ———

    fun list(context: Context, d: JsonElement): JsonObject {
        val path = d.str("path")?.trim()?.trim('/') ?: ""
        if (path.split('/').any { it == ".." }) throw Exception("bad_request: invalid path")
        val offset = (d.intSafe("offset") ?: 0).coerceAtLeast(0)
        val limit = (d.intSafe("limit") ?: 100).coerceIn(1, 200)
        val q = d.str("q")?.trim()?.takeIf { it.isNotEmpty() }
        val tree = treeUri(context)
        return if (tree != null) listTree(context, tree, path, offset, limit, q)
        else listDownloads(context, offset, limit, q)
    }

    private data class Node(
        val id: String,
        val name: String,
        val mime: String,
        val size: Long,
        val date: Long,
        val uri: Uri,
    ) {
        val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun listTree(
        context: Context,
        tree: Uri,
        path: String,
        offset: Int,
        limit: Int,
        q: String?,
    ): JsonObject {
        val parentId = resolvePath(context, tree, path)
        val nodes = children(context, tree, parentId)
            .filter { q == null || it.name.contains(q, ignoreCase = true) }
            .sortedWith(compareBy<Node> { !it.isDir }.thenBy { it.name.lowercase() })

        val crumbs = buildJsonArray {
            var soFar = ""
            for (part in path.split('/').filter { it.isNotEmpty() }) {
                soFar = if (soFar.isEmpty()) part else "$soFar/$part"
                add(buildJsonObject {
                    put("name", part)
                    put("path", soFar)
                })
            }
        }
        return buildJsonObject {
            put("granted", true)
            put("root", prefs(context).getString(KEY_LABEL, "Phone storage"))
            put("path", path)
            put("crumbs", crumbs)
            put("total", nodes.size)
            put("items", buildJsonArray {
                nodes.drop(offset).take(limit).forEach { n ->
                    add(buildJsonObject {
                        put("id", encodeDoc(n.id))
                        put("name", n.name)
                        put("mime", n.mime)
                        put("size", n.size)
                        put("date", n.date)
                        put("dir", n.isDir)
                        put("path", if (n.isDir) joinPath(path, n.name) else path)
                    })
                }
            })
        }
    }

    /** Walk the (already sanitised) relative path to a document id. */
    private fun resolvePath(context: Context, tree: Uri, path: String): String {
        var id = try {
            // A tree URI carries a *tree* document id (primary:Folder); the
            // plain getDocumentId() only accepts document/ URIs and always
            // throws for the root we stored from ACTION_OPEN_DOCUMENT_TREE.
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: Exception) {
            throw Exception("need_grant: stored folder is no longer valid")
        }
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            val child = children(context, tree, id).firstOrNull { it.isDir && it.name == part }
                ?: throw Exception("not_found: folder $part no longer exists")
            id = child.id
        }
        return id
    }

    private fun children(context: Context, tree: Uri, parentId: String): List<Node> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val items = mutableListOf<Node>()
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(projection[0])
                val nameIdx = c.getColumnIndex(projection[1])
                val mimeIdx = c.getColumnIndex(projection[2])
                val sizeIdx = c.getColumnIndex(projection[3])
                val dateIdx = c.getColumnIndex(projection[4])
                while (c.moveToNext()) {
                    if (idIdx < 0 || nameIdx < 0) continue
                    val docId = c.getString(idIdx) ?: continue
                    items += Node(
                        id = docId,
                        name = c.getString(nameIdx) ?: docId,
                        mime = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else "",
                        size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L,
                        // DocumentsContract reports LAST_MODIFIED in ms already
                        // (unlike MediaStore.DATE_ADDED, which is in seconds).
                        date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L,
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                    )
                }
            }
        } catch (e: SecurityException) {
            throw Exception("need_grant: folder permission was revoked — choose it again")
        }
        return items
    }

    /** Downloads without a folder grant. Best effort: Android may hide other
     *  apps' downloads, in which case the UI asks the user to grant a folder. */
    private fun listDownloads(context: Context, offset: Int, limit: Int, q: String?): JsonObject {
        if (Build.VERSION.SDK_INT >= 29) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_ADDED,
            )
            val items = mutableListOf<JsonObject>()
            try {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection, null, null, "${MediaStore.Downloads.DATE_ADDED} DESC",
                )?.use { c ->
                    val idIdx = c.getColumnIndex(projection[0])
                    val nameIdx = c.getColumnIndex(projection[1])
                    val mimeIdx = c.getColumnIndex(projection[2])
                    val sizeIdx = c.getColumnIndex(projection[3])
                    val dateIdx = c.getColumnIndex(projection[4])
                    while (c.moveToNext()) {
                        val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "file" else "file"
                        if (q != null && !name.contains(q, ignoreCase = true)) continue
                        items += buildJsonObject {
                            put("id", "d:" + c.getLong(idIdx))
                            put("name", name)
                            put("mime", if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else "")
                            put("size", if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L)
                            put("date", (if (dateIdx >= 0) c.getLong(dateIdx) else 0L) * 1000L)
                            put("dir", false)
                            put("path", "")
                        }
                    }
                }
            } catch (e: SecurityException) {
                throw Exception("need_grant: choose a folder on the phone to browse its files")
            }
            return buildJsonObject {
                put("granted", false)
                put("root", "Downloads")
                put("path", "")
                put("crumbs", buildJsonArray {})
                put("total", items.size)
                put("items", buildJsonArray { items.drop(offset).take(limit).forEach { add(it) } })
            }
        }
        @Suppress("DEPRECATION")
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "InterLink")
        val files = (root.listFiles() ?: emptyArray())
            .filter { it.isFile && (q == null || it.name.contains(q, true)) }
            .sortedByDescending { it.lastModified() }
        return buildJsonObject {
            put("granted", false)
            put("root", "Downloads/InterLink")
            put("path", "")
            put("crumbs", buildJsonArray {})
            put("total", files.size)
            put("items", buildJsonArray {
                files.drop(offset).take(limit).forEach { f ->
                    add(buildJsonObject {
                        put("id", "f:" + b64url(f.absolutePath.toByteArray()))
                        put("name", f.name)
                        put("mime", "")
                        put("size", f.length())
                        put("date", f.lastModified())
                        put("dir", false)
                        put("path", "")
                    })
                }
            })
        }
    }

    // ——— file.get ———

    private class Resolved(
        val name: String,
        val size: Long,
        val mime: String,
        val input: InputStream,
    )

    /** Respond with metadata, then stream N × 0x02 frames like media.get.
     *
     * `offset` lets the laptop resume a transfer after the link drops. It is
     * always a whole number of chunks; the response still advertises the
     * *total* size/chunk count so the receiver can tell when the file is
     * complete, and `seq` starts at the chunk the offset lands on. */
    suspend fun get(context: Context, session: LinkSession, env: Envelope) = withContext(Dispatchers.IO) {
        val idStr = env.d.str("id") ?: throw Exception("not_found: missing id")
        val tidB64 = env.d.str("tid") ?: throw Exception("internal: missing tid")
        val offset = (env.d.long("offset") ?: 0L).coerceAtLeast(0L)
        if (offset % CHUNK != 0L) throw Exception("bad_request: offset must be a multiple of 64 KiB")
        val file = resolve(context, idStr)
        if (offset > file.size) throw Exception("bad_request: offset past end of file")
        session.respond(
            Envelope.response(
                env.id, env.t,
                objOf(
                    "tid" to tidB64,
                    "size" to file.size,
                    "chunks" to maxOf(1L, (file.size + CHUNK - 1) / CHUNK),
                    "name" to file.name,
                    "mime" to file.mime,
                ),
            ),
        )
        val raw = tidRaw(tidB64)
        val buf = ByteArray(CHUNK)
        var seq = (offset / CHUNK).toInt()
        file.input.use { input ->
            var left = offset
            var zeroReads = 0
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw Exception("internal: file ended before offset $offset")
                if (n == 0) {
                    // A SAF stream may legitimately hand back 0 bytes; don't
                    // spin forever if the provider is stuck, either.
                    if (++zeroReads > 100) throw Exception("internal: could not seek to $offset")
                    continue
                }
                zeroReads = 0
                left -= n
            }
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                if (!session.sendData(raw, seq, buf.copyOf(n))) {
                    Log.w(TAG, "file.get: connection lost at seq $seq")
                    break
                }
                seq++
            }
        }
    }

    private fun resolve(context: Context, id: String): Resolved {
        when {
            id.startsWith("doc:") -> {
                val docId = String(Base64.decode(id.removePrefix("doc:"), Base64.URL_SAFE or Base64.NO_WRAP))
                val tree = treeUri(context) ?: throw Exception("need_grant: no folder granted")
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                )
                var name = "file"
                var mime = "application/octet-stream"
                var size = 0L
                context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        // Never rely on the provider keeping our projection
                        // order: some DocumentProviders add columns of their
                        // own, which used to make `size` come from the wrong
                        // slot and silently truncate the transfer.
                        val nameIdx = c.getColumnIndex(projection[0])
                        val mimeIdx = c.getColumnIndex(projection[1])
                        val sizeIdx = c.getColumnIndex(projection[2])
                        if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                        if (mimeIdx >= 0 && !c.getString(mimeIdx).isNullOrBlank()) {
                            mime = c.getString(mimeIdx)
                        }
                        if (sizeIdx >= 0) size = c.getLong(sizeIdx)
                    }
                }
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("not_found: cannot open file")
                return Resolved(name, size, mime, input)
            }
            id.startsWith("d:") -> {
                val numeric = id.removePrefix("d:").toLongOrNull()
                    ?: throw Exception("not_found: bad download id")
                if (Build.VERSION.SDK_INT >= 29) {
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, numeric,
                    )
                    val projection = arrayOf(
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.MIME_TYPE,
                        MediaStore.Downloads.SIZE,
                    )
                    var name = "file"
                    var mime = "application/octet-stream"
                    var size = 0L
                    context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val nameIdx = c.getColumnIndex(projection[0])
                            val mimeIdx = c.getColumnIndex(projection[1])
                            val sizeIdx = c.getColumnIndex(projection[2])
                            if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                            if (mimeIdx >= 0 && !c.getString(mimeIdx).isNullOrBlank()) {
                                mime = c.getString(mimeIdx)
                            }
                            if (sizeIdx >= 0) size = c.getLong(sizeIdx)
                        }
                    }
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw Exception("not_found: cannot open download")
                    return Resolved(name, size, mime, input)
                }
            }
            id.startsWith("f:") -> {
                val path = String(Base64.decode(id.removePrefix("f:"), Base64.URL_SAFE or Base64.NO_WRAP))
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                val file = File(path)
                if (!file.canonicalPath.startsWith(downloads)) throw Exception("not_found: outside Downloads")
                return Resolved(file.name, file.length(), "", FileInputStream(file))
            }
        }
        throw Exception("not_found: unknown file id")
    }

    // ——— helpers ———

    /** JsonElement.int() would overflow a number larger than Int; these ids aren't,
     *  but coercing through Long keeps the helper honest for size-like fields. */
    private fun JsonElement.intSafe(name: String): Int? {
        val s = (this as? JsonObject)?.get(name)?.toString()?.trim('"') ?: return null
        return s.toLongOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
    }

    private fun joinPath(base: String, name: String) = if (base.isEmpty()) name else "$base/$name"

    private fun encodeDoc(s: String) = "doc:" + b64url(s.toByteArray())

    private fun b64url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP).trimEnd('=')
}
