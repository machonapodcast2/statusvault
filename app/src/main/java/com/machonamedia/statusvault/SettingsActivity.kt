package com.machonamedia.statusvault

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var swLock: MaterialSwitch
    private lateinit var swBio: MaterialSwitch
    private lateinit var rowTheme: TextView
    private var pendingSource: Source = Source.WHATSAPP
    private var muteLock = false

    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // The main screen will ask again if access did not stick.
                }
                Prefs.setTreeUri(this, pendingSource, uri)
                toast("${pendingSource.label} folder linked")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val swAuto = findViewById<MaterialSwitch>(R.id.swAuto)
        swLock = findViewById(R.id.swLock)
        swBio = findViewById(R.id.swBio)
        rowTheme = findViewById(R.id.rowTheme)

        swAuto.isChecked = Prefs.autoSave(this)
        swAuto.setOnCheckedChangeListener { _, on ->
            Prefs.setAutoSave(this, on)
            if (on) {
                AutoSaveWorker.schedule(this)
                lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) { AutoSaver.run(applicationContext) }
                    toast(if (n > 0) "Auto-save on. Saved $n now." else "Auto-save on")
                }
            } else {
                AutoSaveWorker.cancel(this)
            }
        }

        swLock.isChecked = Prefs.lockEnabled(this)
        swLock.setOnCheckedChangeListener { _, on ->
            if (muteLock) return@setOnCheckedChangeListener
            if (on) {
                askNewPin()
            } else {
                Prefs.setLockEnabled(this, false)
                Prefs.clearPin(this)
                refreshBio()
            }
        }

        swBio.isChecked = Prefs.biometric(this)
        swBio.setOnCheckedChangeListener { _, on -> Prefs.setBiometric(this, on) }
        refreshBio()

        showTheme()
        rowTheme.setOnClickListener {
            val names = arrayOf("Follow system", "Light", "Dark")
            MaterialAlertDialogBuilder(this)
                .setTitle("Theme")
                .setSingleChoiceItems(names, Prefs.theme(this)) { dialog, which ->
                    Prefs.setTheme(this, which)
                    dialog.dismiss()
                    showTheme()
                    Prefs.applyTheme(this)
                }
                .show()
        }

        findViewById<TextView>(R.id.rowLinkWa).setOnClickListener { link(Source.WHATSAPP) }
        findViewById<TextView>(R.id.rowLinkBiz).setOnClickListener { link(Source.BUSINESS) }
    }

    private fun showTheme() {
        val names = arrayOf("Follow system", "Light", "Dark")
        rowTheme.text = "Theme: " + names[Prefs.theme(this).coerceIn(0, 2)]
    }

    private fun refreshBio() {
        val available = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
        swBio.isEnabled = available && Prefs.lockEnabled(this)
    }

    private fun setLockSwitch(on: Boolean) {
        muteLock = true
        swLock.isChecked = on
        muteLock = false
    }

    private fun link(s: Source) {
        pendingSource = s
        App.skipLock = true
        try {
            pickTree.launch(StatusRepo.initialUri(s))
        } catch (e: Exception) {
            toast("Could not open the folder picker on this phone")
        }
    }

    private fun pinField(hint: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        e.filters = arrayOf(InputFilter.LengthFilter(8))
        return e
    }

    private fun askNewPin() {
        val first = pinField("New PIN (4 to 8 digits)")
        val second = pinField("Repeat PIN")
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(first)
        box.addView(second)

        var saved = false
        MaterialAlertDialogBuilder(this)
            .setTitle("Set a PIN")
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val a = first.text.toString()
                val b = second.text.toString()
                if (a.length < 4) {
                    toast("PIN must be at least 4 digits")
                } else if (a != b) {
                    toast("PINs do not match")
                } else {
                    Prefs.setPin(this, a)
                    Prefs.setLockEnabled(this, true)
                    App.unlocked = true
                    saved = true
                    toast("App lock is on")
                }
            }
            .setOnDismissListener {
                if (!saved) setLockSwitch(false)
                refreshBio()
            }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
