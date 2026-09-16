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
import android.util.Log
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var ringWebView: WebView
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var kaizenMemory: KaizenMemory

    private val mainHandler = Handler(Looper.getMainLooper())

    private var pulseRunnable: Runnable? = null

    private var userLat: Double? = null
    private var userLon: Double? = null

    /*
     * Conversation history.
     * This stays alive while the current app session is open.
     */
    private val conversation =
        mutableListOf<Pair<String, String>>()

    private val recognizerIntent by lazy {
        Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            putExtra(
                RecognizerIntent.EXTRA_PREFER_OFFLINE,
                true
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.UK
            )
        }
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            // Permissions handled when individual features are used.
        }

    private val takePictureLauncher =
        registerForActivityResult(
            ActivityResultContracts.TakePicturePreview()
        ) { bitmap ->

            if (bitmap != null) {
                analyzeImage(bitmap)
            }
        }

    private val enableBtLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            appendLog(
                "Kaizen",
                "Bluetooth settings returned, ma'am."
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        binding =
            ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        /*
         * Persistent Kaizen memory.
         */
        kaizenMemory = KaizenMemory(this)

        /*
         * Kaizen visual ring.
         */
        ringWebView = binding.ringWebView

        ringWebView.settings.javaScriptEnabled = true

        ringWebView.loadUrl(
            "file:///android_asset/kaizen_ring.html"
        )

        requestPermissions()

        setupTextToSpeech()

        setupSpeechRecognizer()

        setupButtons()

        /*
         * Initial greeting.
         */
        val greeting =
            "Kaizen online, ma'am. Systems nominal. How may I assist you?"

        appendLog("Kaizen", greeting)

        speak(greeting)
    }

    // =========================================================
    // PERMISSIONS
    // =========================================================

    private fun requestPermissions() {

        val permissions =
            mutableListOf<String>()

        permissions.add(
            Manifest.permission.RECORD_AUDIO
        )

        permissions.add(
            Manifest.permission.CAMERA
        )

        permissions.add(
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            permissions.add(
                Manifest.permission.BLUETOOTH_CONNECT
            )

            permissions.add(
                Manifest.permission.BLUETOOTH_SCAN
            )
        }

        permissionLauncher.launch(
            permissions.toTypedArray()
        )
    }

    // =========================================================
    // TEXT TO SPEECH
    // =========================================================

    private fun setupTextToSpeech() {

        tts = TextToSpeech(this) { status ->

            if (status == TextToSpeech.SUCCESS) {

                try {

                    tts.language = Locale.UK

                    tts.setPitch(0.85f)

                    tts.setSpeechRate(1.0f)

                } catch (e: Exception) {

                    Log.e(
                        "KAIZEN",
                        "TTS setup error",
                        e
                    )
                }

                tts.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {

                        override fun onStart(
                            utteranceId: String?
                        ) {

                            runOnUiThread {

                                setStatus("RESPONDING")

                                startSpeakingPulse()
                            }
                        }

                        override fun onDone(
                            utteranceId: String?
                        ) {

                            runOnUiThread {

                                stopSpeakingPulse()

                                setStatus("STANDBY")
                            }
                        }

                        override fun onError(
                            utteranceId: String?
                        ) {

                            runOnUiThread {

                                stopSpeakingPulse()

                                setStatus("STANDBY")
                            }
                        }
                    }
                )
            }
        }
    }

    private fun speak(text: String) {

        if (!::tts.isInitialized) {
            return
        }

        try {

            tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "kaizen_${System.currentTimeMillis()}"
            )

        } catch (e: Exception) {

            Log.e(
                "KAIZEN",
                "Speech error",
                e
            )
        }
    }

    // =========================================================
    // SPEECH RECOGNITION
    // =========================================================

    private fun setupSpeechRecognizer() {

        speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(
                    params: Bundle?
                ) {
                    setStatus("LISTENING")
                }

                override fun onBeginningOfSpeech() {
                    setStatus("LISTENING")
                }

                override fun onRmsChanged(
                    rmsdB: Float
                ) {

                    val normalized =
                        ((rmsdB + 2f) / 12f)
                            .coerceIn(0f, 1f)

                    setRingLevel(normalized)
                }

                override fun onBufferReceived(
                    buffer: ByteArray?
                ) {
                }

                override fun onEndOfSpeech() {
                    setStatus("PROCESSING")
                }

                override fun onError(
                    error: Int
                ) {

                    setStatus("STANDBY")

                    appendLog(
                        "Kaizen",
                        "I didn't catch that clearly, ma'am."
                    )
                }

                override fun onResults(
                    results: Bundle?
                ) {

                    val text =
                        results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.firstOrNull()

                    if (!text.isNullOrBlank()) {

                        handleCommand(text)
                    } else {

                        setStatus("STANDBY")
                    }
                }

                override fun onPartialResults(
                    partialResults: Bundle?
                ) {
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?
                ) {
                }
            }
        )
    }

    // =========================================================
    // BUTTONS
    // =========================================================

    private fun setupButtons() {

        binding.micButton.setOnClickListener {

            if (
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO
                    )
                )

                return@setOnClickListener
            }

            try {

                setStatus("LISTENING")

                speechRecognizer.startListening(
                    recognizerIntent
                )

            } catch (e: Exception) {

                Log.e(
                    "KAIZEN",
                    "Speech recognizer error",
                    e
                )

                setStatus("STANDBY")
            }
        }

        binding.sendButton.setOnClickListener {

            val text =
                binding.inputField.text
                    .toString()
                    .trim()

            binding.inputField.setText("")

            if (text.isNotBlank()) {

                handleCommand(text)
            }
        }

        binding.torchButton.setOnClickListener {

            safeRespond {

                Hardware.toggleTorch(this)
            }
        }

        binding.locationButton.setOnClickListener {

            fetchLocation()
        }

        binding.btButton.setOnClickListener {

            safeRespond {

                Hardware.requestEnableBluetooth(
                    this,
                    enableBtLauncher
                )
            }
        }

        binding.wifiButton.setOnClickListener {

            safeRespond {

                Hardware.openWifiSettings(this)
            }
        }

        binding.calendarButton.setOnClickListener {

            showAddEventDialog()
        }

        binding.cameraButton.setOnClickListener {

            try {

                takePictureLauncher.launch(null)

            } catch (e: Exception) {

                respond(
                    "I couldn't open the camera, ma'am."
                )
            }
        }

        binding.settingsButton.setOnClickListener {

            showApiKeyDialog()
        }
    }

    // =========================================================
    // MAIN COMMAND HANDLER
    // =========================================================

    private fun handleCommand(text: String) {

        val cleanText =
            text.trim()

        if (cleanText.isBlank()) {
            return
        }

        appendLog(
            "You",
            cleanText
        )

        setStatus("PROCESSING")

        val input =
            cleanText
                .lowercase(Locale.UK)
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        // -----------------------------------------------------
        // MEMORY
        // -----------------------------------------------------

        val remember =
            Regex(
                "^remember that (.+?) is (.+)$",
                RegexOption.IGNORE_CASE
            ).find(cleanText)

        if (remember != null) {

            val key =
                remember.groupValues[1].trim()

            val value =
                remember.groupValues[2].trim()

            kaizenMemory.remember(
                key,
                value
            )

            respond(
                "Understood, ma'am. I'll remember that $key is $value."
            )

            return
        }

        val rememberEquals =
            Regex(
                "^remember (.+?) = (.+)$",
                RegexOption.IGNORE_CASE
            ).find(cleanText)

        if (rememberEquals != null) {

            val key =
                rememberEquals.groupValues[1].trim()

            val value =
                rememberEquals.groupValues[2].trim()

            kaizenMemory.remember(
                key,
                value
            )

            respond(
                "Understood, ma'am. I've stored that."
            )

            return
        }

        val recall =
            Regex(
                "^what do you remember about (.+)$",
                RegexOption.IGNORE_CASE
            ).find(cleanText)

        if (recall != null) {

            val key =
                recall.groupValues[1].trim()

            val value =
                kaizenMemory.recall(key)

            if (value != null) {

                respond(
                    "You told me that $key is $value, ma'am."
                )

            } else {

                respond(
                    "I don't have anything stored about $key yet, ma'am."
                )
            }

            return
        }

        if (
            input == "what do you remember" ||
            input == "show my memories" ||
            input == "what have you learned"
        ) {

            val memories =
                kaizenMemory.all()

            if (memories.isEmpty()) {

                respond(
                    "I haven't learned any personal information yet, ma'am."
                )

            } else {

                val summary =
                    memories.entries.joinToString(
                        separator = ". "
                    ) {
                        "${it.key} is ${it.value}"
                    }

                respond(
                    "Here's what I currently remember, ma'am: $summary."
                )
            }

            return
        }

        if (
            input == "forget everything" ||
            input == "clear your memory" ||
            input == "clear memory"
        ) {

            kaizenMemory.clear()

            respond(
                "My stored memory has been cleared, ma'am."
            )

            return
        }

        // -----------------------------------------------------
        // BASIC CONVERSATION
        // -----------------------------------------------------

        if (
            input == "hi" ||
            input == "hello" ||
            input == "hey" ||
            input == "hello kaizen" ||
            input == "hi kaizen" ||
            input == "hey kaizen"
        ) {

            respond(
                "Hello, ma'am. How may I assist you?"
            )

            return
        }

        if (
            input.contains("good morning")
        ) {

            respond(
                "Good morning, ma'am. How may I assist you today?"
            )

            return
        }

        if (
            input.contains("good afternoon")
        ) {

            respond(
                "Good afternoon, ma'am. How may I assist you?"
            )

            return
        }

        if (
            input.contains("good evening")
        ) {

            respond(
                "Good evening, ma'am. How may I assist you?"
            )

            return
        }

        if (
            input == "who are you" ||
            input == "what are you" ||
            input.contains("tell me about yourself")
        ) {

            respond(
                "I'm Kaizen, your personal assistant. I'm designed to help you with conversations, information and device tasks."
            )

            return
        }

        if (
            input == "what can you do" ||
            input == "what do you do" ||
            input.contains("what are your capabilities")
        ) {

            respond(
                "I can converse with you, remember information you teach me, perform calculations, open supported apps, work with Bluetooth, Wi-Fi, the camera, calendar and location, and use my online brain for more advanced questions."
            )

            return
        }

        if (
            input.contains("how are you")
        ) {

            respond(
                "I'm functioning normally, ma'am. All systems are standing by."
            )

            return
        }

        if (
            input.contains("thank you") ||
            input == "thanks"
        ) {

            respond(
                "You're welcome, ma'am."
            )

            return
        }

        if (
            input == "bye" ||
            input == "goodbye"
        ) {

            respond(
                "Very well, ma'am. I'll be here when you need me."
            )

            return
        }

        // -----------------------------------------------------
        // MATH
        // -----------------------------------------------------

        try {

            if (MathEval.looksLikeMath(cleanText)) {

                val result =
                    MathEval.evaluate(cleanText)

                if (result != null) {

                    val formatted =
                        if (
                            result ==
                            result.toLong().toDouble()
                        ) {
                            result
                                .toLong()
                                .toString()
                        } else {
                            result.toString()
                        }

                    respond(
                        "$cleanText = $formatted"
                    )

                    return
                }
            }

        } catch (e: Exception) {

            respond(
                "I couldn't calculate that safely, ma'am."
            )

            return
        }

        // -----------------------------------------------------
        // OPEN APPS
        // -----------------------------------------------------

                // 2. Local app launching — PackageManager only, no network.
        Regex("^open (.+)", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val appName = m.groupValues[1].trim()

            if (AppLauncher.tryLaunch(this, appName)) {
                val out = "Opening $appName, ma'am."
                appendLog("Kaizen", out)
                speak(out)
                setStatus("STANDBY")
                return
            } else {
                val out = "I couldn't find an app called $appName, ma'am."
                appendLog("Kaizen", out)
                speak(out)
                setStatus("STANDBY")
                return
            }
        }

        // 3. Local dialer — opens the phone app with the number ready.
        Regex("^call (.+)", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val number = m.groupValues[1].trim()

            try {
                startActivity(
                    Intent(
                        Intent.ACTION_DIAL,
                        Uri.parse("tel:$number")
                    )
                )

                val out = "Dialer ready for $number, ma'am."
                appendLog("Kaizen", out)
                speak(out)
                setStatus("STANDBY")
            } catch (e: Exception) {
                val out = "I couldn't open the dialer, ma'am."
                appendLog("Kaizen", out)
                speak(out)
                setStatus("STANDBY")
            }

            return
        }

        // 4. Bluetooth commands.
        Regex(
            "^(turn off|disable) (the )?bluetooth",
            RegexOption.IGNORE_CASE
        ).find(text)?.let {

            val out =
                Hardware.openBluetoothSettingsForOff(this)

            appendLog("Kaizen", out)
            speak(out)
            setStatus("STANDBY")
            return
        }

        Regex(
            "^(turn on|enable|check) (the )?bluetooth",
            RegexOption.IGNORE_CASE
        ).find(text)?.let {

            val out =
                Hardware.requestEnableBluetooth(
                    this,
                    enableBtLauncher
                )

            appendLog("Kaizen", out)
            speak(out)
            setStatus("STANDBY")
            return
        }

        // 5. Wi-Fi commands.
        Regex(
            "^(turn on|turn off|enable|disable|check|toggle) (the )?wi-?fi",
            RegexOption.IGNORE_CASE
        ).find(text)?.let {

            val out =
                Hardware.openWifiSettings(this)

            appendLog("Kaizen", out)
            speak(out)
            setStatus("STANDBY")
            return
        }

        // 6. Torch / flashlight commands.
        Regex(
            "^(turn on|turn off|toggle) (the )?(torch|flashlight)",
            RegexOption.IGNORE_CASE
        ).find(text)?.let {

            val out =
                Hardware.toggleTorch(this)

            appendLog("Kaizen", out)
            speak(out)
            setStatus("STANDBY")
            return
        }

        // 7. Calendar commands.
        Regex(
            "^(add|create|schedule|set) (a |an )?(calendar )?event",
            RegexOption.IGNORE_CASE
        ).find(text)?.let {

            showAddEventDialog()
            setStatus("STANDBY")
            return
        }

        // 8. Location commands.
        Regex(
            "^(where am i|check my location|get my location|find my location)",
            RegexOption.IGNORE_CASE
        ).find(text)?.let {

            fetchLocation()
            setStatus("STANDBY")
            return
        }

        // 9. Local conversation replies.
        LocalReplies.tryReply(text)?.let { reply ->

            appendLog("Kaizen", reply)
            speak(reply)
            setStatus("STANDBY")
            return
        }

        // 10. If Kaizen doesn't know the command locally,
        // send it to the cloud brain.
        if (!NetworkUtils.isOnline(this)) {

            val out =
                "I need a connection for that, ma'am — " +
                "I've brought up your network settings."

            appendLog("Kaizen", out)
            speak(out)
            setStatus("STANDBY")

            openConnectivitySettings()
            return
        }

        // 11. Check for Claude API key.
        val apiKey =
            SecurePrefs.getApiKey(this)

        if (apiKey.isNullOrBlank()) {

            val out =
                "I don't have an API key yet, ma'am — " +
                "tap the gear icon to add one."

            appendLog("Kaizen", out)
            speak(out)
            setStatus("STANDBY")
            return
        }

        // 12. Send the conversation to Claude.
        val locationNote =
            if (userLat != null) {
                "\n\n[context: current coordinates $userLat, $userLon]"
            } else {
                ""
            }

        conversation.add(
            "user" to (text + locationNote)
        )

        setStatus("THINKING")

        CoroutineScope(Dispatchers.Main).launch {

            try {

                val reply =
                    ClaudeClient.ask(
                        conversation,
                        apiKey
                    )

                conversation.add(
                    "assistant" to reply
                )

                appendLog(
                    "Kaizen",
                    reply
                )

                speak(reply)

                setStatus("STANDBY")

            } catch (e: Exception) {

                val out =
                    "I'm sorry, ma'am. " +
                    "I couldn't process that request right now."

                appendLog(
                    "Kaizen",
                    out
                )

                speak(out)

                setStatus("STANDBY")
            }
        }
