package com.kaizen.assistant

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.kaizen.assistant.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer

    private var userLat: Double? = null
    private var userLon: Double? = null

    // (role, content) pairs, oldest first — sent to Claude for conversation memory.
    private val conversation = mutableListOf<Pair<String, String>>()

    private val recognizerIntent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) analyzeImage(bitmap)
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.UK
                tts.setPitch(0.85f)
                tts.setSpeechRate(1.0f)
            }
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setStatus("PROCESSING") }
            override fun onError(error: Int) {
                setStatus("STANDBY")
                appendLog("Kaizen", "I didn't catch that clearly, ma'am.")
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) handleCommand(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        binding.micButton.setOnClickListener {
            setStatus("LISTENING")
            speechRecognizer.startListening(recognizerIntent)
        }

        binding.sendButton.setOnClickListener {
            val text = binding.inputField.text.toString()
            binding.inputField.setText("")
            if (text.isNotBlank()) handleCommand(text)
        }

        binding.torchButton.setOnClickListener { Hardware.toggleTorch(this) }
        binding.locationButton.setOnClickListener { fetchLocation() }
        binding.btButton.setOnClickListener { Hardware.requestEnableBluetooth(this, enableBtLauncher) }
        binding.calendarButton.setOnClickListener { showAddEventDialog() }
        binding.cameraButton.setOnClickListener { takePictureLauncher.launch(null) }
        binding.settingsButton.setOnClickListener { showApiKeyDialog() }

        val greeting = "Kaizen online, ma'am. Systems nominal."
        appendLog("Kaizen", greeting)
        speak(greeting)
    }

    private fun setStatus(s: String) { binding.statusText.text = s }
    private fun speak(text: String) { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) }
    private fun appendLog(who: String, text: String) { binding.logText.append("\n$who: $text\n") }

    /**
     * Offline-first routing: math, app control, and greetings are all handled
     * on-device below and never touch the network. Only when none of those
     * match does this reach for the cloud brain — and even then, it checks
     * connectivity first and says so plainly rather than hanging.
     */
    private fun handleCommand(text: String) {
        appendLog("You", text)
        setStatus("PROCESSING")

        // 1. Local arithmetic — no network involved at all.
        if (MathEval.looksLikeMath(text)) {
            val result = MathEval.evaluate(text)
            if (result != null) {
                val out = "$text = ${if (result == result.toLong().toDouble()) result.toLong().toString() else result.toString()}"
                appendLog("Kaizen", out); speak(out); setStatus("STANDBY")
                return
            }
        }

        // 2. Local app launching — PackageManager only, no network.
        Regex("^open (.+)", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val appName = m.groupValues[1]
            if (AppLauncher.tryLaunch(this, appName)) {
                val out = "Opening $appName, ma'am."
                appendLog("Kaizen", out); speak(out); setStatus("STANDBY")
                return
            }
        }

        // 3. Local dialer — opens the phone app pre-filled, no permission needed.
        Regex("^call (.+)", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${m.groupValues[1]}")))
            val out = "Dialer ready for ${m.groupValues[1]}, ma'am."
            appendLog("Kaizen", out); speak(out); setStatus("STANDBY")
            return
        }

        // 4. Local canned replies for greetings/small talk — no network.
        LocalReplies.tryReply(text)?.let { reply ->
            appendLog("Kaizen", reply); speak(reply); setStatus("STANDBY")
            return
        }

        // 5. Everything else genuinely needs the cloud brain — check first, don't just hang.
        if (!NetworkUtils.isOnline(this)) {
            val out = "I need a connection for that, ma'am — I've brought up your network settings."
            appendLog("Kaizen", out)
            speak(out)
            setStatus("STANDBY")
            openConnectivitySettings()
            return
        }

        val apiKey = SecurePrefs.getApiKey(this)
        if (apiKey.isNullOrBlank()) {
            val out = "I don't have an API key yet, ma'am — tap the gear icon to add one."
            appendLog("Kaizen", out); speak(out); setStatus("STANDBY")
            return
        }

        val locationNote = if (userLat != null) "\n\n[context: current coordinates $userLat, $userLon]" else ""
        conversation.add("user" to (text + locationNote))
        setStatus("THINKING")

        CoroutineScope(Dispatchers.Main).launch {
            val reply = ClaudeClient.ask(conversation, apiKey)
            conversation.add("assistant" to reply)
            appendLog("Kaizen", reply)
            speak(reply)
            setStatus("STANDBY")
        }
    }

    /** Opens the quick Wi-Fi/mobile-data panel on Android 10+, falling back to full wireless settings on older versions. */
    private fun openConnectivitySettings() {
        try {
            startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    private fun analyzeImage(bitmap: Bitmap) {
        if (!NetworkUtils.isOnline(this)) {
            val out = "Vision needs a connection too, ma'am — I've brought up your network settings."
            appendLog("Kaizen", out); speak(out); openConnectivitySettings()
            return
        }
        val apiKey = SecurePrefs.getApiKey(this)
        if (apiKey.isNullOrBlank()) {
            appendLog("Kaizen", "I need an API key first, ma'am — tap the gear icon.")
            return
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        appendLog("You", "[shows Kaizen the camera view]")
        setStatus("ANALYZING")
        CoroutineScope(Dispatchers.Main).launch {
            val reply = object ClaudeClient {
    suspend fun askVision(imageBase64: String, apiKey: String): String {
        // Add your network request code here
    }
            }
            
            appendLog("Kaizen", reply)
            speak(reply)
            setStatus("STANDBY")
        }
    }

    private fun fetchLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            appendLog("Kaizen", "Location permission isn't granted yet, ma'am.")
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, { loc: Location ->
                userLat = loc.latitude; userLon = loc.longitude
                appendLog("Kaizen", "Location locked, ma'am: ${loc.latitude}, ${loc.longitude}")
            }, null)
        } catch (e: Exception) {
            appendLog("Kaizen", "Couldn't get a location fix: ${e.message}")
        }
    }

    private fun showAddEventDialog() {
        val titleInput = EditText(this)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Event title")
            .setView(titleInput)
            .setPositiveButton("Next") { _, _ ->
                val title = titleInput.text.toString()
                val cal = Calendar.getInstance()
                DatePickerDialog(this, { _, y, m, d ->
                    cal.set(y, m, d)
                    TimePickerDialog(this, { _, h, min ->
                        cal.set(Calendar.HOUR_OF_DAY, h)
                        cal.set(Calendar.MINUTE, min)
                        startActivity(Intent(Intent.ACTION_INSERT).apply {
                            data = CalendarContract.Events.CONTENT_URI
                            putExtra(CalendarContract.Events.TITLE, title)
                            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
                        })
                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showApiKeyDialog() {
        val input = EditText(this)
        input.hint = "sk-ant-..."
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Anthropic API key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                SecurePrefs.setApiKey(this, input.text.toString().trim())
                Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        tts.shutdown()
        speechRecognizer.destroy()
        super.onDestroy()
    }
}
