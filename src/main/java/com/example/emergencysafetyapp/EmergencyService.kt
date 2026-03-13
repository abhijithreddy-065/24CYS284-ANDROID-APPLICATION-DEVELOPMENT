package com.example.emergencysafetyapp

import android.app.Service
import android.content.Intent
import android.os.IBinder

class EmergencyService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}