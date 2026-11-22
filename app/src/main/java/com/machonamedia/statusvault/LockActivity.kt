package com.machonamedia.statusvault

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class LockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        val pin = findViewById<EditText>(R.id.pin)
        val unlock = findViewById<Button>(R.id.unlock)
        val fingerprint = findViewById<Button>(R.id.fingerprint)

        val tryPin = {
            if (Prefs.checkPin(this, pin.text.toString())) {
                done()
            } else {
                pin.text.clear()
                Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
        }
        unlock.setOnClickListener { tryPin() }
        pin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                tryPin()
                true
            } else {
                false
            }
        }

        // Back leaves the app instead of slipping past the lock.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAffinity()
            }
        })

        if (Prefs.biometric(this) && biometricAvailable()) {
            fingerprint.visibility = View.VISIBLE
            fingerprint.setOnClickListener { askBiometric() }
            if (savedInstanceState == null) askBiometric()
        }
    }

    private fun biometricAvailable(): Boolean {
        return BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun askBiometric() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    done()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock MachStatusVault")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            // Fall back to the PIN field.
        }
    }

    private fun done() {
        App.unlocked = true
        finish()
        overridePendingTransition(0, 0)
    }
}
