package com.machonamedia.statusvault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receives photos and videos shared from Telegram (or any app) and saves them to the gallery. */
class ShareActivity : AppCompatActivity() {

    private var uris: List<Uri> = emptyList()

    private val writePerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) saveAll() else done("Storage permission is needed to save")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uris = readUris(intent)
        if (uris.isEmpty()) {
            done("Nothing to save. Share a photo or video, not a link.")
            return
        }
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            writePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        saveAll()
    }

    private fun readUris(i: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { out.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { out.addAll(it) }
            }
        }
        if (out.isEmpty()) {
            val clip = i.clipData
            if (clip != null) {
                for (n in 0 until clip.itemCount) clip.getItemAt(n).uri?.let { out.add(it) }
            }
        }
        return out
    }

    private fun saveAll() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var saved = 0
                var skipped = 0
                for ((index, uri) in uris.withIndex()) {
                    val item = describe(uri, index)
                    if (item == null) {
                        skipped++
                    } else if (Saver.save(this@ShareActivity, item)) {
                        saved++
                    }
                }
                Pair(saved, skipped)
            }
            val saved = result.first
            val skipped = result.second
            val msg = when {
                saved == 0 && skipped > 0 -> "Only photos and videos can be saved"
                saved == 0 -> "Could not save. The sender may have blocked saving."
                saved == uris.size -> "Saved $saved to gallery"
                else -> "Saved $saved of ${uris.size}"
            }
            done(msg)
        }
    }

    /** Builds a MediaItem for a shared file, or null if it is not a photo or video. */
    private fun describe(uri: Uri, index: Int): MediaItem? {
        var name: String? = null
        var size = 0L
        try {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) {
            // Some apps don't answer the query; fall back to the type below.
        }
        val mime = (contentResolver.getType(uri) ?: intent.type ?: "").lowercase()
        val ext = name?.substringAfterLast('.', "")?.lowercase() ?: ""
        val video = when {
            mime.startsWith("video/") -> true
            mime.startsWith("image/") -> false
            ext in setOf("mp4", "3gp", "mkv", "webm", "mov") -> true
            ext in setOf("jpg", "jpeg", "png", "webp", "gif") -> false
            else -> return null
        }
        val stamp = System.currentTimeMillis()
        val finalName = if (name.isNullOrBlank() || !name!!.contains('.')) {
            val guessExt = when {
                mime.endsWith("png") -> "png"
                mime.endsWith("webp") -> "webp"
                mime.endsWith("gif") -> "gif"
                video -> "mp4"
                else -> "jpg"
            }
            "Shared_${stamp}_$index.$guessExt"
        } else {
            name!!
        }
        return MediaItem(uri, finalName, video, stamp, size)
    }

    private fun done(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
