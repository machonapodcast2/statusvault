package com.machonamedia.statusvault

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract

/** Reads the WhatsApp ".Statuses" folder through the folder access the user granted. */
object StatusRepo {
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private val VIDEO_EXT = setOf("mp4", "3gp", "mkv", "webm")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif")

    private class Row(
        val id: String,
        val name: String,
        val mime: String,
        val modified: Long,
        val size: Long
    ) {
        val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    /** Where the folder picker should open. */
    fun initialUri(s: Source): Uri {
        val path = if (Build.VERSION.SDK_INT >= 29) {
            "primary:Android/media/${s.pkg}/${s.dir}/Media/.Statuses"
        } else {
            "primary:${s.dir}/Media/.Statuses"
        }
        return DocumentsContract.buildDocumentUri(AUTHORITY, path)
    }

    fun hasAccess(c: Context, s: Source): Boolean {
        val tree = Prefs.treeUri(c, s) ?: return false
        return c.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
    }

    /** Returns null when access has not been granted (or was lost). */
    fun list(c: Context, s: Source): List<MediaItem>? {
        val tree = Prefs.treeUri(c, s) ?: return null
        if (!hasAccess(c, s)) return null
        return try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            var rows = children(c, tree, rootId)
            if (!rootId.endsWith(".Statuses")) {
                // The user picked a parent folder; look one level down.
                val sub = rows.firstOrNull { it.isDir && it.name == ".Statuses" }
                if (sub != null) rows = children(c, tree, sub.id)
            }
            val out = ArrayList<MediaItem>()
            for (r in rows) {
                if (r.isDir) continue
                val video = kind(r.name, r.mime) ?: continue
                out.add(
                    MediaItem(
                        DocumentsContract.buildDocumentUriUsingTree(tree, r.id),
                        r.name, video, r.modified, r.size
                    )
                )
            }
            out.sortedByDescending { it.modified }
        } catch (e: Exception) {
            null
        }
    }

    /** true = video, false = image, null = not media. */
    private fun kind(name: String, mime: String): Boolean? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in VIDEO_EXT || mime.startsWith("video/") -> true
            ext in IMAGE_EXT || mime.startsWith("image/") -> false
            else -> null
        }
    }

    private fun children(c: Context, tree: Uri, parentId: String): List<Row> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val out = ArrayList<Row>()
        c.contentResolver.query(uri, proj, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                out.add(
                    Row(
                        cur.getString(0) ?: continue,
                        cur.getString(1) ?: "",
                        cur.getString(2) ?: "",
                        if (cur.isNull(3)) 0L else cur.getLong(3),
                        if (cur.isNull(4)) 0L else cur.getLong(4)
                    )
                )
            }
        }
        return out
    }
}
