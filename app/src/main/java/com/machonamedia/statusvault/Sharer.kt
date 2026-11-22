package com.machonamedia.statusvault

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Sharer {
    /** Shares the items; with [pkg] set it goes straight to that app (used for "Repost"). */
    suspend fun share(a: Activity, items: List<MediaItem>, pkg: String? = null) {
        val uris: ArrayList<Uri> = withContext(Dispatchers.IO) {
            val dir = File(a.cacheDir, "share")
            dir.mkdirs()
            val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            val out = ArrayList<Uri>()
            for (m in items) {
                try {
                    val file = File(dir, m.name)
                    val input = a.contentResolver.openInputStream(m.uri) ?: continue
                    input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                    out.add(FileProvider.getUriForFile(a, a.packageName + ".files", file))
                } catch (e: Exception) {
                    // Skip files that can no longer be read (status expired).
                }
            }
            out
        }
        if (uris.isEmpty()) {
            Toast.makeText(a, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }
        val intent: Intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = when {
            items.all { it.isVideo } -> "video/*"
            items.none { it.isVideo } -> "image/*"
            else -> "*/*"
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        App.skipLock = true
        if (pkg != null) {
            try {
                a.startActivity(Intent(intent).setPackage(pkg))
                return
            } catch (e: ActivityNotFoundException) {
                // App not installed: fall back to the normal share sheet.
            }
        }
        a.startActivity(Intent.createChooser(intent, "Share"))
    }
}
