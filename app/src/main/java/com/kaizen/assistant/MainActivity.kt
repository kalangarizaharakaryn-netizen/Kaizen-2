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
import android.os.Build
import android.os.Bundle
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.kaizen.assistant.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ringWebView: WebView
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer

    private var userLat: Double? = null
    private var userLon: Double? = null

    private val conversation =
        mutableListOf<Pair<String, String>>()

    private val mainHandler =
        android.os.Handler(
            android.os.Looper.getMainLooper()
        )

    private var pulseRunnable: Runnable? = null

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
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        binding =
            ActivityMainBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        setupRing()
        setupPermissions()
        setupTextToSpeech()
        setupSpeechRecognizer()
        setupButtons()

        val greeting =
            "Kaizen online, ma'am. Systems nominal."

        appendLog(
            "Kaizen",
            greeting
        )

        speak(greeting)
    }

    // =========================================================
    // RING
    // =========================================================

    private fun setupRing() {

        ringWebView =
            binding.ringWebView

        ringWebView.settings.javaScriptEnabled =
            true

        ringWebView.loadUrl(
            "file:///android_asset/kaizen_ring.html"
        )
    }

    private fun setStatus(
        status: String
    ) {

        val mode =
            when (status.uppercase()) {

                "LISTENING" ->
                    "listening"

                "PROCESSING",
                "THINKING",
                "ANALYZING" ->
                    "thinking"

                "RESPONDING" ->
                    "speaking"

                else ->
                    "standby"
            }

        runOnUiThread {

            try {

                ringWebView.evaluateJavascript(
                    "kaizenSetMode('$mode')",
                    null
                )

            } catch (_: Exception) {
            }
        }
    }

    private fun setRingLevel(
        value: Float
    ) {

        runOnUiThread {

            try {

                ringWebView.evaluateJavascript(
                    "kaizenSetLevel($value)",
                    null
                )

            } catch (_: Exception) {
            }
        }
    }

    // =========================================================
    // PERMISSIONS
    // =========================================================

    private fun setupPermissions() {

        val permissions =
            mutableListOf(

                Manifest.permission.RECORD_AUDIO,

                Manifest.permission.CAMERA,

                Manifest.permission.ACCESS_FINE_LOCATION
            )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

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

        tts =
            TextToSpeech(
                this
            ) { status ->

                if (
                    status ==
                    TextToSpeech.SUCCESS
                ) {

                    tts.language =
                        Locale.UK

                    tts.setPitch(
                        0.85f
                    )

                    tts.setSpeechRate(
                        1.0f
                    )

                    tts.setOnUtteranceProgressListener(

                        object :
                            UtteranceProgressListener() {

                            override fun onStart(
                                utteranceId: String?
                            ) {

                                runOnUiThread {

                                    setStatus(
                                        "RESPONDING"
                                    )

                                    startSpeakingPulse()
                                }
                            }

                            override fun onDone(
                                utteranceId: String?
                            ) {

                                runOnUiThread {

                                    setStatus(
                                        "STANDBY"
                                    )

                                    stopSpeakingPulse()
                                }
                            }

                            @Deprecated(
                                "Deprecated in Java"
                            )
                            override fun onError(
                                utteranceId: String?
                            ) {

                                runOnUiThread {

                                    setStatus(
                                        "STANDBY"
                                         stopSpeakingPulse()
                                }
                            }
                        }
                    )
                }
            }
    }

    private fun speak(
        text: String
    ) {

        if (
            !::tts.isInitialized
        ) {
            return
        }

        try {

            tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "kaizen_utterance"
            )

        } catch (_: Exception) {
        }
    }

    // =========================================================
    // SPEAKING ANIMATION
    // =========================================================

    private fun startSpeakingPulse() {

        stopSpeakingPulse()

        pulseRunnable =
            object : Runnable {

                var phase =
                    0.0

                override fun run() {

                    phase +=
                        0.3

                    val level =
                        (
                            (
                                (
                                    Math.sin(
                                        phase
                                    ) + 1
                                ) / 2
                            ) * 0.7 + 0.15
                        ).toFloat()

                    setRingLevel(
                        level
                    )

                    mainHandler.postDelayed(
                        this,
                        60
                    )
                }
            }

        mainHandler.post(
            pulseRunnable!!
        )
    }

    private fun stopSpeakingPulse() {

        pulseRunnable?.let {

            mainHandler.removeCallbacks(
                it
            )
        }

        pulseRunnable =
            null

        mainHandler.post {

            setRingLevel(
                0f
            )
        }
    }

    // =========================================================
    // SPEECH RECOGNITION
    // =========================================================

    private fun setupSpeechRecognizer() {

        speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(
                this
            )

        speechRecognizer.setRecognitionListener(

            object :
                RecognitionListener {

                override fun onReadyForSpeech(
                    params: Bundle?
                ) {
                }

                override fun onBeginningOfSpeech() {
                }

                override fun onRmsChanged(
                    rmsdB: Float
                ) {

                    val level =
                        (
                            (rmsdB + 2f) / 12f
                        ).coerceIn(
                            0f,
                            1f
                        )

                    setRingLevel(
                        level
                    )
                }

                override fun onBufferReceived(
                    buffer: ByteArray?
                ) {
                }

                override fun onEndOfSpeech() {

                    setStatus(
                        "PROCESSING"
                    )
                }

                override fun onError(
                    error: Int
                ) {

                    setStatus(
                        "STANDBY"
                    )

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

                    if (
                        !text.isNullOrBlank()
                    ) {

                        handleCommand(
                            text
                        )
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

                respond(
                    "I need microphone permission first, ma'am."
                )

                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO
                    )
                )

                return@setOnClickListener
            }

            setStatus(
                "LISTENING"
            )

            try {

                speechRecognizer.startListening(
                    recognizerIntent
                )

            } catch (_: Exception) {

                setStatus(
                    "STANDBY"
                )
            }
        }

        binding.sendButton.setOnClickListener {

            val text =
                binding.inputField
                    .text
                    .toString()
                    .trim()

            binding.inputField.setText("")

            if (
                text.isNotBlank()
            ) {

                handleCommand(
                    text
                )
            }
        }

        binding.torchButton.setOnClickListener {

            respond(
                Hardware.toggleTorch(
                    this
                )
            )
        }

        binding.locationButton.setOnClickListener {

            fetchLocation()
        }

        binding.btButton.setOnClickListener {

            respond(
                Hardware.requestEnableBluetooth(
                    this,
                    enableBtLauncher
                )
            )
        }

        binding.wifiButton.setOnClickListener {

            respond(
                Hardware.openWifiSettings(
                    this
                )
            )
        }

        binding.calendarButton.setOnClickListener {

            showAddEventDialog()
        }

        binding.cameraButton.setOnClickListener {

            openCamera()
        }

        binding.settingsButton.setOnClickListener {

            showApiKeyDialog()
        }
    }

    // =========================================================
    // MAIN COMMAND ROUTER
    // =========================================================

    private fun handleCommand(
        text: String
    ) {

        val command =
            text.trim()

        if (
            command.isBlank()
        ) {
            return
        }

        appendLog(
            "You",
            command
        )

        setStatus(
            "PROCESSING"
        )

        // =====================================================
        // DATE AND TIME
        // =====================================================

        if (
            Regex(
                ".*(date and time|time and date).*",
                RegexOption.IGNORE_CASE
            ).matches(command)
        ) {

            tellDateAndTime()
            return
        }

        if (
            Regex(
                ".*(what time|tell me the time|current time|time right now|time is it).*",
                RegexOption.IGNORE_CASE
            ).matches(command)
        ) {

            tellTime()
            return
        }

        if (
            Regex(
                ".*(what('?s| is) the date|today('?s)? date|what is today's date|tell me the date|current date).*",
                RegexOption.IGNORE_CASE
            ).matches(command)
        ) {

            tellDate()
            return
        }

        if (
            Regex(
                ".*(what day is it|what day is today|which day is it|tell me the day).*",
                RegexOption.IGNORE_CASE
            ).matches(command)
        ) {

            tellDay()
            return
        }

        // =====================================================
        // MATH
        // =====================================================

        try {

            if (
                MathEval.looksLikeMath(
                    command
                )
            ) {

                val result =
                    MathEval.evaluate(
                        command
                    )

                if (
                    result != null
                ) {

                    val formatted =
                        if (
                            result ==
                            result.toLong()
                                .toDouble()
                        ) {

                            result
                                .toLong()
                                .toString()

                        } else {

                            result.toString()
                        }

                    respond(
                        "$command = $formatted"
                    )

                    return
                }
            }

        } catch (_: Exception) {
        }

        // =====================================================
        // CAMERA
        // =====================================================

        if (
            Regex(
                "^(open|start|use|activate|take) (the )?(camera|camera app|a picture|photo)",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(command)
        ) {

            openCamera()
            return
        }

        // =====================================================
        // OPEN APPS
        // =====================================================

        val openMatch =
            Regex(
                "^open (.+)",
                RegexOption.IGNORE_CASE
            ).find(command)

        if (
            openMatch != null
        ) {

            val appName =
                openMatch
                    .groupValues[1]
                    .trim()

            try {

                if (
                    AppLauncher.tryLaunch(
                        this,
                        appName
                    )
                ) {

                    respond(
                        "Opening $appName, ma'am."
                    )

                    return
                }

            } catch (_: Exception) {
            }

            respond(
                "I couldn't find an app called $appName, ma'am."
            )

            return
        }

        // =====================================================
        // BLUETOOTH
        // =====================================================

        if (
            Regex(
                "^(turn off|disable) (the )?bluetooth",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(command)
        ) {

            respond(
                Hardware.openBluetoothSettingsForOff(
                    this
                )
            )

            return
        }

        if (
            Regex(
                "^(turn on|enable|check) (the )?bluetooth",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(command)
        ) {

            respond(
                Hardware.requestEnableBluetooth(
                    this,
                    enableBtLauncher
                )
            )

            return
        }

        // =====================================================
        // WI-FI
        // =====================================================

        if (
            Regex(
                "^(turn on|turn off|enable|disable|check|toggle) (the )?wi-?fi",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(command)
        ) {

            respond(
                Hardware.openWifiSettings(
                    this
                )
            )

            return
        }

        // =====================================================
        // TORCH
        // =====================================================

        if (
            Regex(
                "^(turn on|turn off|toggle) (the )?(torch|flashlight)",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(command)
        ) {

            respond(
                Hardware.toggleTorch(
                    this
                )
            )

            return
        }

        // =====================================================
        // LOCATION
        // =====================================================

        if (
            Regex(
                "^(where am i|check my location|get my location|find my location|what is my location)",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(command)
        ) {

            fetchLocation()
            return
        }

        // =====================================================
        // CALENDAR
        // =====================================================

        if (
            Regex(
                "^(add|create|schedule|set) (a |an )?(calendar )?event",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(command)
        ) {

            showAddEventDialog()
            return
        }

        // =====================================================
        // SETTINGS
        // =====================================================

        if (
            Regex(
                "^(open|show) (the )?settings",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(command)
        ) {

            try {

                startActivity(
                    Intent(
                        Settings.ACTION_SETTINGS
                    )
                )

                respond(
                    "Opening settings, ma'am."
                )

            } catch (_: Exception) {

                respond(
                    "I couldn't open settings, ma'am."
                )
            }

            return
        }
        // =====================================================
        // INTERNET SETTINGS
        // =====================================================

        if (
            Regex(
                "^(open|show) (the )?(internet|network|connection) settings",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(command)
        ) {

            openConnectivitySettings()

            respond(
                "Opening network settings, ma'am."
            )

            return
        }

        // =====================================================
        // LOCAL CONVERSATION
        // =====================================================

        try {

            LocalReplies
                .tryReply(command)
                ?.let { reply ->

                    respond(reply)
                    return
                }

        } catch (_: Exception) {
        }

        // =====================================================
        // CLOUD BRAIN
        // =====================================================

        if (
            !NetworkUtils.isOnline(
                this
            )
        ) {

            respond(
                "I need an internet connection for that, ma'am. I've opened your network settings."
            )

            openConnectivitySettings()
            return
        }

        val apiKey =
            SecurePrefs.getApiKey(
                this
            )

        if (
            apiKey.isNullOrBlank()
        ) {

            respond(
                "I don't have an API key yet, ma'am. Tap the gear icon to add one."
            )

            return
        }

        val locationNote =
            if (
                userLat != null &&
                userLon != null
            ) {

                "\n\n[Current location: $userLat, $userLon]"

            } else {

                ""
            }

        conversation.add(
            "user" to
                (
                    command +
                        locationNote
                    )
        )

        setStatus(
            "THINKING"
        )

        CoroutineScope(
            Dispatchers.Main
        ).launch {

            try {

                val reply =
                    ClaudeClient.ask(
                        conversation,
                        apiKey
                    )

                conversation.add(
                    "assistant" to
                        reply
                )

                respond(reply)

            } catch (_: Exception) {

                respond(
                    "I'm having trouble reaching my cloud brain, ma'am."
                )
            }
        }
    }

    // =========================================================
    // TIME
    // =========================================================

    private fun tellTime() {

        val format =
            SimpleDateFormat(
                "h:mm a",
                Locale.UK
            )

        val time =
            format.format(
                Calendar.getInstance().time
            )

        respond(
            "The current time is $time, ma'am."
        )
    }

    // =========================================================
    // DATE
    // =========================================================

    private fun tellDate() {

        val format =
            SimpleDateFormat(
                "d MMMM yyyy",
                Locale.UK
            )

        val date =
            format.format(
                Calendar.getInstance().time
            )

        respond(
            "Today is $date, ma'am."
        )
    }

    // =========================================================
    // DAY
    // =========================================================

    private fun tellDay() {

        val format =
            SimpleDateFormat(
                "EEEE",
                Locale.UK
            )

        val day =
            format.format(
                Calendar.getInstance().time
            )

        respond(
            "Today is $day, ma'am."
        )
    }

    // =========================================================
    // DATE AND TIME
    // =========================================================

    private fun tellDateAndTime() {

        val format =
            SimpleDateFormat(
                "EEEE, d MMMM yyyy 'at' h:mm a",
                Locale.UK
            )

        val dateTime =
            format.format(
                Calendar.getInstance().time
            )

        respond(
            "It is $dateTime, ma'am."
        )
    }

    // =========================================================
    // RESPONSE
    // =========================================================

    private fun respond(
        text: String
    ) {

        appendLog(
            "Kaizen",
            text
        )

        speak(text)

        setStatus(
            "STANDBY"
        )
    }

    // =========================================================
    // LOG
    // =========================================================

    private fun appendLog(
        who: String,
        text: String
    ) {

        runOnUiThread {

            try {

                binding.logText.append(
                    "\n$who: $text\n"
                )

            } catch (_: Exception) {
            }
        }
    }

    // =========================================================
    // CAMERA
    // =========================================================

    private fun openCamera() {

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            respond(
                "I need camera permission first, ma'am."
            )

            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA
                )
            )

            return
        }

        try {

            setStatus(
                "PROCESSING"
            )

            takePictureLauncher.launch(
                null
            )

        } catch (_: Exception) {

            respond(
                "I couldn't open the camera, ma'am."
            )
        }
    }

    // =========================================================
    // IMAGE ANALYSIS
    // =========================================================

    private fun analyzeImage(
        bitmap: Bitmap
    ) {

        if (
            !NetworkUtils.isOnline(
                this
            )
        ) {

            respond(
                "Vision needs an internet connection, ma'am."
            )

            openConnectivitySettings()
            return
        }

        val apiKey =
            SecurePrefs.getApiKey(
                this
            )

        if (
            apiKey.isNullOrBlank()
        ) {

            respond(
                "I need an API key before I can analyze the image, ma'am."
            )

            return
        }

        try {

            val stream =
                ByteArrayOutputStream()

            bitmap.compress(
                Bitmap.CompressFormat.JPEG,
                85,
                stream
            )

            val base64 =
                Base64.encodeToString(
                    stream.toByteArray(),
                    Base64.NO_WRAP
                )

            appendLog(
                "You",
                "[Camera image sent to Kaizen]"
            )

            setStatus(
                "ANALYZING"
            )

            CoroutineScope(
                Dispatchers.Main
            ).launch {

                try {

                    val reply =
                        ClaudeClient.askVision(
                            base64,
                            apiKey
                        )

                    respond(reply)

                } catch (_: Exception) {

                    respond(
                        "I couldn't analyze that image, ma'am."
                    )
                }
            }

        } catch (_: Exception) {

            respond(
                "I couldn't prepare the camera image, ma'am."
            )
        }
    }

    // =========================================================
    // LOCATION
    // =========================================================

    private fun fetchLocation() {

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            respond(
                "Location permission isn't granted yet, ma'am."
            )

            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )

            return
        }

        val locationManager =
            getSystemService(
                Context.LOCATION_SERVICE
            ) as LocationManager

        setStatus(
            "PROCESSING"
        )

        try {

            locationManager.requestSingleUpdate(
                LocationManager.GPS_PROVIDER,

                { location: Location ->

                    userLat =
                        location.latitude

                    userLon =
                        location.longitude

                    respond(
                        "Location locked, ma'am. Latitude ${location.latitude}, longitude ${location.longitude}."
                    )
                },

                null
            )

        } catch (_: Exception) {

            respond(
                "I couldn't get a location fix, ma'am."
            )
        }
    }

    // =========================================================
    // NETWORK SETTINGS
    // =========================================================

    private fun openConnectivitySettings() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                startActivity(
                    Intent(
                        Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                    )
                )

            } else {

                startActivity(
                    Intent(
                        Settings.ACTION_WIRELESS_SETTINGS
                    )
                )
            }

        } catch (_: Exception) {

            try {

                startActivity(
                    Intent(
                        Settings.ACTION_WIRELESS_SETTINGS
                    )
                )

            } catch (_: Exception) {
            }
        }
    }

    // =========================================================
    // CALENDAR
    // =========================================================

    private fun showAddEventDialog() {

        val titleInput =
            EditText(this)

        titleInput.hint =
            "Event title"

        AlertDialog.Builder(
            this
        )
            .setTitle(
                "Event title"
            )
            .setView(
                titleInput
            )
            .setPositiveButton(
                "Next"
            ) { _, _ ->

                val title =
                    titleInput.text
                        .toString()
                        .trim()

                if (
                    title.isBlank()
                ) {

                    respond(
                        "I need an event title, ma'am."
                    )

                    return@setPositiveButton
                }

                val calendar =
                    Calendar.getInstance()

                DatePickerDialog(
                    this,

                    { _, year, month, day ->

                        calendar.set(
                            year,
                            month,
                            day
                        )

                        TimePickerDialog(
                            this,

                            { _, hour, minute ->

                                calendar.set(
                                    Calendar.HOUR_OF_DAY,
                                    hour
                                )

                                calendar.set(
                                    Calendar.MINUTE,
                                    minute
                                )

                                try {

                                    val intent =
                                        Intent(
                                            Intent.ACTION_INSERT
                                        )

                                    intent.data =
                                        CalendarContract
                                            .Events
                                            .CONTENT_URI

                                    intent.putExtra(
                                        CalendarContract
                                            .Events
                                            .TITLE,
                                        title
                                    )

                                    intent.putExtra(
                                        CalendarContract
                                            .EXTRA_EVENT_BEGIN_TIME,
                                        calendar.timeInMillis
                                    )

                                    startActivity(
                                        intent
                                    )

                                    respond(
                                        "Opening the calendar for $title, ma'am."
                                    )

                                } catch (_: Exception) {

                                    respond(
                                        "I couldn't open the calendar, ma'am."
                                    )
                                }

                            },

                            calendar.get(
                                Calendar.HOUR_OF_DAY
                            ),

                            calendar.get(
                                Calendar.MINUTE
                            ),

                            true

                        ).show()
                    },

                    calendar.get(
                        Calendar.YEAR
                    ),

                    calendar.get(
                        Calendar.MONTH
                    ),

                    calendar.get(
                        Calendar.DAY_OF_MONTH
                    )

                ).show()
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    // =========================================================
    // API KEY
    // =========================================================

    private fun showApiKeyDialog() {

        val input =
            EditText(this)

        input.hint =
            "sk-ant-..."

        AlertDialog.Builder(
            this
        )
            .setTitle(
                "Anthropic API key"
            )
            .setView(
                input
            )
            .setPositiveButton(
                "Save"
            ) { _, _ ->

                val key =
                    input.text
                        .toString()
                        .trim()

                if (
                    key.isBlank()
                ) {

                    Toast.makeText(
                        this,
                        "No API key entered.",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setPositiveButton
                }

                SecurePrefs.setApiKey(
                    this,
                    key
                )

                Toast.makeText(
                    this,
                    "API key saved.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    // =========================================================
    // CLEANUP
    // =========================================================

    override fun onDestroy() {

        stopSpeakingPulse()

        if (
            ::tts.isInitialized
        ) {

            try {

                tts.stop()
                tts.shutdown()

            } catch (_: Exception) {
            }
        }

        if (
            ::speechRecognizer.isInitialized
        ) {

            try {

                speechRecognizer.cancel()
                speechRecognizer.destroy()

            } catch (_: Exception) {
            }
        }

        super.onDestroy()
    }
                        }
