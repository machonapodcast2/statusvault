package com.machonamedia.statusvault

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import java.security.MessageDigest
import java.util.UUID

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("sv", Context.MODE_PRIVATE)

    fun treeUri(c: Context, s: Source): Uri? {
        val v = sp(c).getString("tree_" + s.name, null) ?: return null
        return Uri.parse(v)
    }

    fun setTreeUri(c: Context, s: Source, u: Uri) {
        sp(c).edit().putString("tree_" + s.name, u.toString()).apply()
    }

    fun source(c: Context): Source =
        if (sp(c).getString("source", Source.WHATSAPP.name) == Source.BUSINESS.name) Source.BUSINESS
        else Source.WHATSAPP

    fun setSource(c: Context, s: Source) {
        sp(c).edit().putString("source", s.name).apply()
    }

    fun autoSave(c: Context): Boolean = sp(c).getBoolean("autosave", false)
    fun setAutoSave(c: Context, v: Boolean) = sp(c).edit().putBoolean("autosave", v).apply()

    fun lockEnabled(c: Context): Boolean = sp(c).getBoolean("lock", false) && hasPin(c)
    fun setLockEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("lock", v).apply()

    fun biometric(c: Context): Boolean = sp(c).getBoolean("bio", false)
    fun setBiometric(c: Context, v: Boolean) = sp(c).edit().putBoolean("bio", v).apply()

    /** 0 = follow system, 1 = light, 2 = dark */
    fun theme(c: Context): Int = sp(c).getInt("theme", 0)
    fun setTheme(c: Context, v: Int) = sp(c).edit().putInt("theme", v).apply()

    fun applyTheme(c: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme(c)) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun hasPin(c: Context): Boolean = sp(c).getString("pin_hash", null) != null

    fun setPin(c: Context, pin: String) {
        val salt = UUID.randomUUID().toString()
        sp(c).edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun clearPin(c: Context) {
        sp(c).edit().remove("pin_salt").remove("pin_hash").apply()
    }

    fun checkPin(c: Context, pin: String): Boolean {
        val salt = sp(c).getString("pin_salt", null) ?: return false
        return sp(c).getString("pin_hash", null) == hash(salt, pin)
    }

    private fun hash(salt: String, pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Status file names auto-save has already dealt with, so deleted ones are not re-saved. */
    fun handled(c: Context): Set<String> =
        HashSet(sp(c).getStringSet("handled", emptySet()) ?: emptySet())

    fun setHandled(c: Context, v: Set<String>) {
        sp(c).edit().putStringSet("handled", HashSet(v)).apply()
    }
}
