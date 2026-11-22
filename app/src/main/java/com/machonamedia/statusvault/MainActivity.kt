package com.machonamedia.statusvault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import coil.request.videoFrameMillis
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var tabs: TabLayout
    private lateinit var grid: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var emptyButton: Button

    private val adapter = GridAdapter()
    private var items: List<MediaItem> = emptyList()
    private var savedNames: Set<String> = emptySet()
    private val selected = LinkedHashSet<Uri>()
    private var actionMode: ActionMode? = null
    private var pendingSave: List<MediaItem>? = null
    private var loadJob: Job? = null

    private val savedTab: Boolean get() = tabs.selectedTabPosition == 2

    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Handled by the access check on the next load.
                }
                Prefs.setTreeUri(this, Prefs.source(this), uri)
                load()
            }
        }

    private val writePerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            val list = pendingSave
            pendingSave = null
            if (ok && list != null) doSave(list) else toast("Storage permission is needed to save")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        tabs = findViewById(R.id.tabs)
        grid = findViewById(R.id.grid)
        swipe = findViewById(R.id.swipe)
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.emptyText)
        emptyButton = findViewById(R.id.emptyButton)

        tabs.addTab(tabs.newTab().setText(R.string.tab_images))
        tabs.addTab(tabs.newTab().setText(R.string.tab_videos))
        tabs.addTab(tabs.newTab().setText(R.string.tab_saved))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                actionMode?.finish()
                items = emptyList()
                adapter.notifyDataSetChanged()
                load()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                grid.smoothScrollToPosition(0)
            }
        })

        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = adapter
        swipe.setOnRefreshListener { load() }

        if (Prefs.autoSave(this)) {
            lifecycleScope.launch {
                val n = withContext(Dispatchers.IO) { AutoSaver.run(applicationContext) }
                if (n > 0) {
                    toast("Auto-saved $n new status" + if (n == 1) "" else "es")
                    load()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_source)?.title = Prefs.source(this).label
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_source -> {
                val next =
                    if (Prefs.source(this) == Source.WHATSAPP) Source.BUSINESS else Source.WHATSAPP
                Prefs.setSource(this, next)
                invalidateOptionsMenu()
                actionMode?.finish()
                toast("Showing ${next.label} statuses")
                load()
                return true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------- loading ----------

    private fun load() {
        loadJob?.cancel()
        val tab = tabs.selectedTabPosition
        val src = Prefs.source(this)
        loadJob = lifecycleScope.launch {
            swipe.isRefreshing = true
            val result = withContext(Dispatchers.IO) {
                val savedList = Saver.listSaved(this@MainActivity)
                val list: List<MediaItem>? = if (tab == 2) {
                    savedList
                } else {
                    StatusRepo.list(this@MainActivity, src)?.filter { it.isVideo == (tab == 1) }
                }
                Pair(list, savedList.map { it.name }.toSet())
            }
            swipe.isRefreshing = false
            val list = result.first
            savedNames = result.second
            items = list ?: emptyList()
            when {
                list == null -> showEmpty(
                    "Allow MachStatusVault to read your ${src.label} statuses.\n\n" +
                        "On the next screen the \"${src.dir}\" folder opens. " +
                        "Tap \"Use this folder\" and then \"Allow\".",
                    "Grant access"
                ) { askAccess() }
                list.isEmpty() && tab == 2 -> showEmpty(
                    "Nothing saved yet.\nStatuses you save will appear here and in your gallery.",
                    null
                ) {}
                list.isEmpty() -> {
                    val what = if (tab == 1) "videos" else "images"
                    if (StatusRepo.lastNarrow || StatusRepo.lastFileCount == 0) {
                        showEmpty(
                            "No $what found.\n\n" +
                                "Link the whole ${src.label} folder so every status folder is searched: " +
                                "tap below, make sure the folder named \"${src.dir}\" is open, " +
                                "then tap \"Use this folder\" and \"Allow\".\n\n" +
                                "Searched:\n" + StatusRepo.lastReport,
                            "Link ${src.label} folder"
                        ) { askAccess() }
                    } else {
                        showEmpty(
                            "No $what to show yet.\n\n" +
                                "Statuses with a green ring in ${src.label} have not been opened. " +
                                "Open them there first, then pull down here to refresh.\n\n" +
                                "Searched:\n" + StatusRepo.lastReport,
                            "Open ${src.label}"
                        ) { openWhatsApp(src) }
                    }
                }
                else -> hideEmpty()
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun hideEmpty() {
        empty.visibility = View.GONE
    }

    private fun showEmpty(text: String, button: String?, action: () -> Unit) {
        empty.visibility = View.VISIBLE
        emptyText.text = text
        if (button == null) {
            emptyButton.visibility = View.GONE
        } else {
            emptyButton.visibility = View.VISIBLE
            emptyButton.text = button
            emptyButton.setOnClickListener { action() }
        }
    }

    private fun askAccess() {
        App.skipLock = true
        try {
            pickTree.launch(StatusRepo.initialUri(Prefs.source(this)))
        } catch (e: Exception) {
            toast("Could not open the folder picker on this phone")
        }
    }

    private fun openWhatsApp(src: Source) {
        val intent = packageManager.getLaunchIntentForPackage(src.pkg)
        if (intent == null) {
            toast("${src.label} is not installed")
        } else {
            startActivity(intent)
        }
    }

    // ---------- actions ----------

    private fun requestSave(list: List<MediaItem>) {
        val todo = list.filter { it.name !in savedNames }
        if (todo.isEmpty()) {
            toast("Already saved")
            return
        }
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = todo
            writePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        doSave(todo)
    }

    private fun doSave(list: List<MediaItem>) {
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { list.count { Saver.save(this@MainActivity, it) } }
            toast(if (n == list.size) "Saved $n to gallery" else "Saved $n of ${list.size}")
            load()
        }
    }

    private fun confirmDelete(list: List<MediaItem>) {
        MaterialAlertDialogBuilder(this)
            .setMessage("Delete ${list.size} saved item" + (if (list.size == 1) "" else "s") + "?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) {
                        list.count { Saver.delete(this@MainActivity, it) }
                    }
                    if (n < list.size) {
                        toast("Could not delete ${list.size - n}. Remove them from your gallery app.")
                    }
                    load()
                }
            }
            .show()
    }

    private fun selectedItems(): List<MediaItem> = items.filter { it.uri in selected }

    private fun toggle(m: MediaItem) {
        if (!selected.remove(m.uri)) selected.add(m.uri)
        if (selected.isEmpty()) {
            actionMode?.finish()
        } else {
            actionMode?.title = "${selected.size} selected"
            adapter.notifyDataSetChanged()
        }
    }

    private val modeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.selection, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(R.id.action_save)?.isVisible = !savedTab
            menu.findItem(R.id.action_delete)?.isVisible = savedTab
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val chosen = selectedItems()
            when (item.itemId) {
                R.id.action_save -> {
                    mode.finish()
                    requestSave(chosen)
                }
                R.id.action_share -> {
                    mode.finish()
                    lifecycleScope.launch { Sharer.share(this@MainActivity, chosen) }
                }
                R.id.action_delete -> {
                    mode.finish()
                    confirmDelete(chosen)
                }
                R.id.action_select_all -> {
                    selected.clear()
                    items.forEach { selected.add(it.uri) }
                    mode.title = "${selected.size} selected"
                    adapter.notifyDataSetChanged()
                }
                else -> return false
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            selected.clear()
            adapter.notifyDataSetChanged()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- grid ----------

    inner class GridAdapter : RecyclerView.Adapter<GridAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val play: ImageView = v.findViewById(R.id.play)
            val overlay: View = v.findViewById(R.id.overlay)
            val check: ImageView = v.findViewById(R.id.check)
            val save: ImageButton = v.findViewById(R.id.save)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(layoutInflater.inflate(R.layout.item_grid, parent, false))
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val m = items[position]
            h.thumb.load(m.uri) {
                if (m.isVideo) videoFrameMillis(500)
            }
            h.play.visibility = if (m.isVideo) View.VISIBLE else View.GONE

            val isSelected = m.uri in selected
            h.overlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            h.check.visibility = if (isSelected) View.VISIBLE else View.GONE

            val alreadySaved = m.name in savedNames
            if (savedTab || actionMode != null) {
                h.save.visibility = View.GONE
            } else {
                h.save.visibility = View.VISIBLE
                h.save.setImageResource(if (alreadySaved) R.drawable.ic_check else R.drawable.ic_download)
                h.save.contentDescription = getString(if (alreadySaved) R.string.saved else R.string.save)
                h.save.setOnClickListener { requestSave(listOf(m)) }
            }

            h.itemView.setOnClickListener {
                if (actionMode != null) {
                    toggle(m)
                } else {
                    val index = items.indexOf(m)
                    ViewerData.items = items
                    ViewerData.savedMode = savedTab
                    ViewerData.source = Prefs.source(this@MainActivity)
                    startActivity(
                        Intent(this@MainActivity, ViewerActivity::class.java)
                            .putExtra("index", if (index < 0) 0 else index)
                    )
                }
            }
            h.itemView.setOnLongClickListener {
                if (actionMode == null) {
                    actionMode = startSupportActionMode(modeCallback)
                }
                toggle(m)
                true
            }
        }
    }
}
