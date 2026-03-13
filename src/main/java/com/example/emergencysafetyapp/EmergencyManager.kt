package com.example.emergencysafetyapp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EmergencyManager(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun setEmergencyState(active: Boolean) {
        prefs.edit().putBoolean("emergency_active", active).apply()
    }

    fun isEmergencyActive(): Boolean {
        return prefs.getBoolean("emergency_active", false)
    }

    fun addContact(number: String) {
        val contacts = prefs.getStringSet("trusted_contacts", mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()

        if (contacts.size < 5) {
            contacts.add(number)
            prefs.edit().putStringSet("trusted_contacts", contacts).apply()
        }
    }

    fun getContacts(): Set<String> {
        return prefs.getStringSet("trusted_contacts", emptySet()) ?: emptySet()
    }

    fun saveHistory(entry: String) {
        val history = prefs.getStringSet("history", mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()

        history.add(entry)
        prefs.edit().putStringSet("history", history).apply()
    }

    fun getHistory(): List<String> {
        return prefs.getStringSet("history", emptySet())?.toList()?.reversed()
            ?: emptyList()
    }
}