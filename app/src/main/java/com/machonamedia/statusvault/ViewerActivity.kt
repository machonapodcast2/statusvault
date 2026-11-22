package com.machonamedia.statusvault

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ViewerActivity : AppCompatActivity() {

    private val items = ArrayList<MediaItem>()
    private val savedNames = HashSet<String>()
    private var savedMode = false
    private var activePos = 0

    private lateinit var pager: ViewPager2
    private lateinit var counter: TextView
    private lateinit var primary: Button

    private val writePerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) saveCurrent() else toast("Storage permission is needed to save")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        items.addAll(ViewerData.items)
        if (items.isEmpty()) {
            finish()
            return
        }
        savedMode = ViewerData.savedMode

        pager = findViewById(R.id.pager)
        counter = findViewById(R.id.counter)
        primary = findViewById(R.id.primary)

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<Button>(R.id.share).setOnClickListener {
            current()?.let { m -> lifecycleScope.launch { Sharer.share(this@ViewerActivity, listOf(m)) } }
        }
        findViewById<Button>(R.id.repost).setOnClickListener {
            current()?.let { m ->
                lifecycleScope.launch {
                    Sharer.share(this@ViewerActivity, listOf(m), ViewerData.source.pkg)
                }
            }
        }
        primary.setOnClickListener {
            if (savedMode) confirmDelete() else requestSave()
        }

        activePos = intent.getIntExtra("index", 0).coerceIn(0, items.size - 1)
        pager.adapter = PageAdapter()
        pager.setCurrentItem(activePos, false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                activePos = position
                updateUi()
                syncVideos()
            }
        })

        lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) {
                Saver.listSaved(this@ViewerActivity).map { it.name }
            }
            savedNames.addAll(names)
            updateUi()
        }
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        if (::pager.isInitialized) forEachVideo { it.pause() }
    }

    override fun onResume() {
        super.onResume()
        if (::pager.isInitialized) syncVideos()
    }

    private fun current(): MediaItem? = items.getOrNull(activePos)

    private fun updateUi() {
        counter.text = "${activePos + 1} / ${items.size}"
        val button = primary as? MaterialButton
        if (savedMode) {
            primary.setText(R.string.delete)
            button?.setIconResource(R.drawable.ic_delete)
        } else {
            val name = current()?.name
            val done = name != null && savedNames.contains(name)
            primary.setText(if (done) R.string.saved else R.string.save)
            button?.setIconResource(if (done) R.drawable.ic_check else R.drawable.ic_download)
            primary.isEnabled = !done
            primary.alpha = if (done) 0.6f else 1f
        }
    }

    private fun requestSave() {
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            writePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        saveCurrent()
    }

    private fun saveCurrent() {
        val m = current() ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { Saver.save(this@ViewerActivity, m) }
            if (ok) {
                savedNames.add(m.name)
                toast("Saved to gallery")
            } else {
                toast("Could not save this status")
            }
            updateUi()
        }
    }

    private fun confirmDelete() {
        val m = current() ?: return
        MaterialAlertDialogBuilder(this)
            .setMessage("Delete this saved item?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { Saver.delete(this@ViewerActivity, m) }
                    if (!ok) {
                        toast("Could not delete. Remove it from your gallery app.")
                        return@launch
                    }
                    val pos = items.indexOf(m)
                    if (pos >= 0) {
                        items.removeAt(pos)
                        pager.adapter?.notifyItemRemoved(pos)
                    }
                    if (items.isEmpty()) {
                        finish()
                    } else {
                        activePos = activePos.coerceIn(0, items.size - 1)
                        updateUi()
                        pager.post { syncVideos() }
                    }
                }
            }
            .show()
    }

    private fun forEachVideo(block: (VideoHolder) -> Unit) {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val h = rv.getChildViewHolder(rv.getChildAt(i))
            if (h is VideoHolder) block(h)
        }
    }

    private fun syncVideos() {
        forEachVideo { h ->
            if (h.bindingAdapterPosition == activePos) h.play() else h.pause()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- pages ----------

    class ImageHolder(v: View) : RecyclerView.ViewHolder(v) {
        val zoom: ZoomLayout = v.findViewById(R.id.zoom)
    }

    class VideoHolder(v: View) : RecyclerView.ViewHolder(v) {
        val video: VideoView = v.findViewById(R.id.video)
        val icon: ImageView = v.findViewById(R.id.playIcon)

        fun play() {
            video.start()
            icon.visibility = View.GONE
        }

        fun pause() {
            if (video.isPlaying) video.pause()
            icon.visibility = View.VISIBLE
        }
    }

    inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = if (items[position].isVideo) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) {
                VideoHolder(layoutInflater.inflate(R.layout.page_video, parent, false))
            } else {
                ImageHolder(layoutInflater.inflate(R.layout.page_image, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val m = items[position]
            if (holder is ImageHolder) {
                holder.zoom.reset()
                holder.zoom.image.load(m.uri)
            } else if (holder is VideoHolder) {
                holder.video.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    // Show the first frame on pages that are not playing yet.
                    if (!holder.video.isPlaying) holder.video.seekTo(1)
                }
                holder.video.setOnErrorListener { _, _, _ ->
                    toast("This video cannot be played")
                    true
                }
                holder.video.setVideoURI(m.uri)
                holder.itemView.setOnClickListener {
                    if (holder.video.isPlaying) holder.pause() else holder.play()
                }
                if (position == activePos) holder.play() else holder.pause()
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is VideoHolder) holder.video.stopPlayback()
        }
    }
}
