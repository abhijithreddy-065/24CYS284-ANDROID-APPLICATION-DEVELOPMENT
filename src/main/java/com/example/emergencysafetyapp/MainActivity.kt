package com.example.emergencysafetyapp

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.*
import android.location.Location
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.location.*
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private lateinit var statusText: TextView

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    private var shakeEnabled = false
    private var emergencyActive = false

    // ================= SMART SHAKE =================
    private var accelDetected = false
    private var gyroDetected = false
    private var shakeCount = 0
    private var firstShakeTime = 0L
    private var lastSOSActivationTime = 0L

    private val ACCEL_THRESHOLD = 2.7f
    private val GYRO_THRESHOLD = 3.0f
    private val SHAKE_WINDOW = 2000L
    private val COOLDOWN_TIME = 30000L

    // ================= FALL DETECTION =================
    private var fallImpactDetected = false
    private var fallStartTime = 0L
    private val FALL_THRESHOLD = 3.5f
    private val IMMOBILITY_TIME = 8000L

    // ================= RETRY SYSTEM =================
    private var retryHandler: Handler? = null
    private var pendingMessage: String? = null

    // ================= OFFLINE SIREN =================
    private var mediaPlayer: MediaPlayer? = null

    // ================= MEDICAL INFO =================
    private val medicalInfoBlock = """
MEDICAL INFO:
Blood Group: O+
Condition: Cardiac Patient
Allergies: None
Medication: Aspirin
"""

    private val policeNumber = "8651379246"
    private val fireNumber = "101"
    private val ambulanceNumber = "108"
    private val defaultPin = "1234"

    private val pickContactLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val index =
                            it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        if (index != -1) {
                            val number = it.getString(index)
                            saveTrustedContact(number)
                        }
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initSecurePrefs()
        emergencyActive = prefs.getBoolean("emergency_active", false)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        statusText = findViewById(R.id.statusText)

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        updateStatusUI()
        setupUI()
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopSiren()
    }

    private fun setupUI() {

        findViewById<MaterialButton>(R.id.sosBtn).setOnClickListener {
            handleSosPress()
        }

        findViewById<MaterialButton>(R.id.cancelEmergencyBtn).setOnClickListener {
            handleCancelEmergency()
        }

        findViewById<MaterialButton>(R.id.viewHistoryBtn).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.selectContactsBtn).setOnClickListener {
            pickContact()
        }

        findViewById<MaterialButton>(R.id.policeBtn).setOnClickListener {
            openDialer(policeNumber)
        }

        findViewById<MaterialButton>(R.id.fireBtn).setOnClickListener {
            openDialer(fireNumber)
        }

        findViewById<MaterialButton>(R.id.ambulanceBtn).setOnClickListener {
            openDialer(ambulanceNumber)
        }

        findViewById<MaterialSwitch>(R.id.shakeSwitch)
            .setOnCheckedChangeListener { _, isChecked ->
                shakeEnabled = isChecked
            }
    }

    private fun updateStatusUI() {
        if (emergencyActive) {
            statusText.text = "EMERGENCY ACTIVE"
            statusText.setTextColor(getColor(android.R.color.holo_red_dark))
        } else {
            statusText.text = "STATUS: SAFE"
            statusText.setTextColor(getColor(android.R.color.holo_green_dark))
        }
    }

    override fun onSensorChanged(event: SensorEvent) {

        if (!shakeEnabled || emergencyActive) return

        val now = System.currentTimeMillis()
        if (now - lastSOSActivationTime < COOLDOWN_TIME) return

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {

            val gForce =
                sqrt(
                    event.values[0] * event.values[0] +
                            event.values[1] * event.values[1] +
                            event.values[2] * event.values[2]
                ) / SensorManager.GRAVITY_EARTH

            // ----- SMART SHAKE -----
            if (gForce > ACCEL_THRESHOLD) {
                accelDetected = true
            }

            // ----- FALL DETECTION -----
            if (gForce > FALL_THRESHOLD) {
                fallImpactDetected = true
                fallStartTime = now
            }

            if (fallImpactDetected) {
                if (gForce < 0.5f) {
                    if (now - fallStartTime > IMMOBILITY_TIME) {
                        fallImpactDetected = false
                        showFallDialog()
                    }
                }
            }
        }

        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {

            val rotation =
                sqrt(
                    event.values[0] * event.values[0] +
                            event.values[1] * event.values[1] +
                            event.values[2] * event.values[2]
                )

            if (rotation > GYRO_THRESHOLD) {
                gyroDetected = true
            }
        }

        if (accelDetected && gyroDetected) {

            if (shakeCount == 0) {
                firstShakeTime = now
            }

            shakeCount++
            accelDetected = false
            gyroDetected = false

            if (now - firstShakeTime > SHAKE_WINDOW) {
                shakeCount = 0
                return
            }

            if (shakeCount >= 3) {
                shakeCount = 0
                lastSOSActivationTime = now
                handleSosPress()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun showFallDialog() {
        AlertDialog.Builder(this)
            .setTitle("Possible Fall Detected")
            .setMessage("Are you okay?")
            .setCancelable(false)
            .setPositiveButton("I'm Safe") { d, _ -> d.dismiss() }
            .setNegativeButton("Trigger SOS") { _, _ -> handleSosPress() }
            .show()
    }

    private fun handleSosPress() {

        if (emergencyActive) {
            Toast.makeText(this, "Emergency already active", Toast.LENGTH_SHORT).show()
            return
        }

        val contacts = prefs.getStringSet("trusted_contacts", emptySet())
        if (contacts.isNullOrEmpty()) {
            Toast.makeText(this, "Select trusted contacts first", Toast.LENGTH_LONG).show()
            pickContact()
            return
        }

        showCountdown()
    }

    private fun showCountdown() {

        val dialog = AlertDialog.Builder(this)
            .setTitle("Emergency Broadcast")
            .setMessage("Sending alert in 5 seconds...")
            .setCancelable(false)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        Handler(mainLooper).postDelayed({
            if (dialog.isShowing) {
                dialog.dismiss()
                startEmergency()
            }
        }, 5000)
    }

    private fun startEmergency() {

        emergencyActive = true
        prefs.edit().putBoolean("emergency_active", true).apply()
        updateStatusUI()

        getLocation { location ->

            val id = UUID.randomUUID().toString().take(6)
            val timestamp = getTime()
            val battery = getBattery()
            val accuracy = location?.accuracy ?: 0f
            val lat = location?.latitude ?: 0.0
            val lon = location?.longitude ?: 0.0

            val message = """
🚨 EMERGENCY ALERT 🚨
ID: $id
Time: $timestamp
Battery: $battery%
Accuracy: ${accuracy.toInt()}m
Location:
https://maps.google.com/?q=$lat,$lon

$medicalInfoBlock
            """.trimIndent()

            sendSms(message)
            saveHistory("ID:$id | $timestamp | Battery:$battery%")

            Toast.makeText(
                this,
                "Contacts notified\nEmergency ID: $id",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun sendSms(message: String) {

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "SMS Permission missing", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val contacts = prefs.getStringSet("trusted_contacts", emptySet()) ?: return
            val smsManager = SmsManager.getDefault()
            contacts.forEach {
                smsManager.sendTextMessage(it, null, message, null, null)
            }
        } catch (e: Exception) {
            pendingMessage = message
            startRetry()
            startSiren()
            Toast.makeText(this, "Network issue. Retrying...", Toast.LENGTH_LONG).show()
        }
    }

    private fun startRetry() {
        retryHandler = Handler(mainLooper)
        retryHandler?.postDelayed(object : Runnable {
            override fun run() {
                pendingMessage?.let {
                    sendSms(it)
                }
                retryHandler?.postDelayed(this, 30000)
            }
        }, 30000)
    }

    private fun startSiren() {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI)
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        }
    }

    private fun stopSiren() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun handleCancelEmergency() {

        if (!emergencyActive) {
            Toast.makeText(this, "No active emergency", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this)
        input.hint = "Enter 4-digit PIN"

        AlertDialog.Builder(this)
            .setTitle("Cancel Emergency")
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                if (input.text.toString() == defaultPin) {
                    emergencyActive = false
                    stopSiren()
                    prefs.edit().putBoolean("emergency_active", false).apply()
                    updateStatusUI()
                    Toast.makeText(this, "Emergency Cancelled", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveHistory(entry: String) {

        val existing = prefs.getStringSet("history", emptySet()) ?: emptySet()
        val newSet = HashSet(existing)

        newSet.add(entry)

        prefs.edit().remove("history").apply()
        prefs.edit().putStringSet("history", newSet).apply()
    }


    private fun getLocation(callback: (Location?) -> Unit) {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            callback(null)
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2000
        ).setMaxUpdates(1).build()

        val callbackLocation = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                fusedLocationClient.removeLocationUpdates(this)
                callback(result.lastLocation)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            callbackLocation,
            mainLooper
        )
    }

    private fun getTime(): String =
        SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
            .format(Date())

    private fun getBattery(): Int {
        val manager = getSystemService(BATTERY_SERVICE) as BatteryManager
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun openDialer(number: String) {
        val intent = Intent(Intent.ACTION_DIAL)
        intent.data = Uri.parse("tel:$number")
        startActivity(intent)
    }

    private fun pickContact() {
        val intent =
            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        pickContactLauncher.launch(intent)
    }

    private fun saveTrustedContact(number: String) {
        val contacts =
            prefs.getStringSet("trusted_contacts", mutableSetOf())?.toMutableSet()
                ?: mutableSetOf()

        if (contacts.size < 5) {
            contacts.add(number)
            prefs.edit().putStringSet("trusted_contacts", contacts).apply()
            Toast.makeText(this, "Contact Added", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Max 5 contacts allowed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initSecurePrefs() {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            this,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_CONTACTS
            ),
            101
        )
    }
}