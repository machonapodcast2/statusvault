package com.machonamedia.statusvault

import android.net.Uri

data class MediaItem(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val modified: Long,
    val size: Long
)

enum class Source(val pkg: String, val dir: String, val label: String) {
    WHATSAPP("com.whatsapp", "WhatsApp", "WhatsApp"),
    BUSINESS("com.whatsapp.w4b", "WhatsApp Business", "WA Business")
}

/** Hands the current list to the full-screen viewer without parcelling it. */
object ViewerData {
    var items: List<MediaItem> = emptyList()
    var savedMode: Boolean = false
    var source: Source = Source.WHATSAPP
}
