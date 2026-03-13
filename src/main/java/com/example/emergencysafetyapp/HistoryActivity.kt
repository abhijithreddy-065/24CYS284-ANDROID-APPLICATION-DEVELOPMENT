package com.example.emergencysafetyapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlin.collections.emptySet

class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)

        // 🔥 Use EncryptedSharedPreferences (same as MainActivity)

        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val prefs = EncryptedSharedPreferences.create(
            this,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val history =
            prefs.getStringSet("history", emptySet())?.toList()?.reversed()
                ?: emptyList()

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = EmergencyAdapter(history)
    }
}