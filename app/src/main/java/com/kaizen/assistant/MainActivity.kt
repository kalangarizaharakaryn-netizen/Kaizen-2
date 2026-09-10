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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.webkit.WebView
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

    private lateinit var ringWebView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pulseRunnable: Runnable? = null

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer

    private var userLat: Double? = null
    private var userLon: Double? = null

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

        ringWebView = binding.ringWebView
        ringWebView.settings.javaScriptEnabled = true
        ringWebView.loadUrl("file:///android_asset/kaizen_ring.html")

        val permsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            permsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        permissionLauncher.launch(permsToRequest.toTypedArray())

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.UK
                tts.setPitch(0.85f)
                tts.setSpeechRate(1.0f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        runOnUiThread { setStatus("RESPONDING") }
                        startSpeakingPulse()
                    }
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { setStatus("STANDBY") }
                        stopSpeakingPulse()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { setStatus("STANDBY") }
                        stopSpeakingPulse()
                    }
                })
            }
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                setRingLevel(normalized)
            }
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
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                appendLog("Kaizen", "I need microphone permission, ma'am — requesting it now.")
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                return@setOnClickListener
            }
            setStatus("LISTENING")
            speechRecognizer.startListening(recognizerIntent)
        }

        binding.sendButton.setOnClickListener {
            val text = binding.inputField.text.toString()
            binding.inputField.setText("")
            if (text.isNotBlank()) handleCommand(text)
        }

        binding.torchButton.setOnClickListener { appendLog("Kaizen", Hardware.toggleTorch(this)) }
        binding.locationButton.setOnClickListener { fetchLocation() }
        binding.btButton.setOnClickListener { appendLog("Kaizen", Hardware.requestEnableBluetooth(this, enableBtLauncher)) }
        binding.calendarButton.setOnClickListener { showAddEventDialog() }
        binding.cameraButton.setOnClickListener { takePictureLauncher.launch(null) }
        binding.settingsButton.setOnClickListener { showApiKeyDialog() }

        val greeting = "Kaizen online, ma'am. Systems nominal."
        appendLog("Kaizen", greeting)
        speak(greeting)
    }

    private fun setStatus(s: String) {
        val mode = when (s.uppercase()) {
            "LISTENING" -> "listening"
            "PROCESSING", "THINKING", "ANALYZING" -> "thinking"
            "RESPONDING" -> "speaking"
            else -> "standby"
        }
        ringWebView.evaluateJavascript("kaizenSetMode('$mode')", null)
    }

    private fun setRingLevel(v: Float) {
        ringWebView.evaluateJavascript("kaizenSetLevel($v)", null)
    }

    private fun startSpeakingPulse() {
        stopSpeakingPulse()
        pulseRunnable = object : Runnable {
            var phase = 0.0
            override fun run() {
                phase += 0.3
                val level = (((Math.sin(phase) + 1) / 2) * 0.7 + 0.15).toFloat()
                setRingLevel(level)
                mainHandler.postDelayed(this, 60)
            }
        }
        mainHandler.post(pulseRunnable!!)
    }

    private fun stopSpeakingPulse() {
        pulseRunnable?.let { mainHandler.removeCallbacks(it) }
        pulseRunnable = null
        // TTS callbacks fire on a background thread — WebView calls MUST happen on the
        // main thread, so this has to be posted, not called directly, or it crashes.
        mainHandler.post { setRingLevel(0f) }
    }

    private fun speak(text: String) { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kaizen_utt") }
    private fun appendLog(who: String, text: String) { binding.logText.append("\n$who: $text\n") }

    private fun handleCommand(text: String) {
        appendLog("You", text)
        setStatus("PROCESSING")

        if (MathEval.looksLikeMath(text)) {
            val result = MathEval.evaluate(text)
            if (result != null) {
                val out = "$text = ${if (result == result.toLong().toDouble()) result.toLong().toString() else result.toString()}"
                appendLog("Kaizen", out); speak(out); setStatus("STANDBY")
                return
            }
        }

        Regex("^open (.+)", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val appName = m.groupValues[1]
            if (AppLauncher.tryLaunch(this, appName)) {
                val out = "Opening $appName, ma'am."
                appendLog("Kaizen", out); speak(out); setStatus("STANDBY")
                return
            }
        }

        Regex("^call (.+)", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${m.groupValues[1]}")))
            val out = "Dialer ready for ${m.groupValues[1]}, ma'am."
            appendLog("Kaizen", out); speak(out); setStatus("STANDBY")
            return
        }

        LocalReplies.tryReply(text)?.let { reply ->
            appendLog("Kaizen", reply); speak(reply); setStatus("STANDBY")
            return
        }

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
            val reply = ClaudeClient.askVision(base64, apiKey)
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
        stopSpeakingPulse()
        tts.shutdown()
        speechRecognizer.destroy()
        super.onDestroy()
    }
}
