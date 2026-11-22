package com.machonamedia.statusvault

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Copies statuses into the phone gallery (Pictures/StatusVault and Movies/StatusVault). */
object Saver {
    const val FOLDER = "StatusVault"

    private fun mime(name: String, video: Boolean): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> if (video) "video/mp4" else "image/jpeg"
        }
    }

    private fun baseDir(video: Boolean): String =
        if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

    @Suppress("DEPRECATION")
    private fun legacyDir(video: Boolean): File =
        File(Environment.getExternalStoragePublicDirectory(baseDir(video)), FOLDER)

    fun save(c: Context, m: MediaItem): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) saveToStore(c, m) else saveLegacy(c, m)
        } catch (e: Exception) {
            false
        }
    }

    private fun saveToStore(c: Context, m: MediaItem): Boolean {
        val resolver = c.contentResolver
        val collection = if (m.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, m.name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime(m.name, m.isVideo))
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, baseDir(m.isVideo) + "/" + FOLDER)
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val target = resolver.insert(collection, values) ?: return false
        try {
            val input = resolver.openInputStream(m.uri) ?: throw IllegalStateException("no input")
            input.use { i ->
                val output = resolver.openOutputStream(target) ?: throw IllegalStateException("no output")
                output.use { o -> i.copyTo(o) }
            }
            val done = ContentValues()
            done.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(target, done, null, null)
            return true
        } catch (e: Exception) {
            try {
                resolver.delete(target, null, null)
            } catch (ignored: Exception) {
            }
            return false
        }
    }

    private fun saveLegacy(c: Context, m: MediaItem): Boolean {
        val dir = legacyDir(m.isVideo)
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = File(dir, m.name)
        val input = c.contentResolver.openInputStream(m.uri) ?: return false
        input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
        MediaScannerConnection.scanFile(c, arrayOf(file.absolutePath), null, null)
        return true
    }

    fun listSaved(c: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                queryStore(c, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), false, out)
                queryStore(c, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), true, out)
            } else {
                for (video in listOf(false, true)) {
                    val files = legacyDir(video).listFiles() ?: continue
                    for (f in files) {
                        if (f.isFile) out.add(MediaItem(Uri.fromFile(f), f.name, video, f.lastModified(), f.length()))
                    }
                }
            }
        } catch (e: Exception) {
            // Fall through with whatever was collected.
        }
        return out.sortedByDescending { it.modified }
    }

    private fun queryStore(c: Context, collection: Uri, video: Boolean, out: MutableList<MediaItem>) {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE
        )
        val where = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
        c.contentResolver.query(collection, proj, where, arrayOf("%/$FOLDER/%"), null)?.use { cur ->
            while (cur.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cur.getLong(0))
                out.add(
                    MediaItem(
                        uri,
                        cur.getString(1) ?: "",
                        video,
                        cur.getLong(2) * 1000L,
                        cur.getLong(3)
                    )
                )
            }
        }
    }

    fun delete(c: Context, m: MediaItem): Boolean {
        return try {
            if (m.uri.scheme == "file") {
                File(m.uri.path ?: return false).delete()
            } else {
                c.contentResolver.delete(m.uri, null, null) > 0
            }
        } catch (e: Exception) {
            false
        }
    }
}
