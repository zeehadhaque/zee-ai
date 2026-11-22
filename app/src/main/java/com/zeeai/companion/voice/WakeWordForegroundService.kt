package com.zeeai.companion.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zeeai.companion.MainActivity
import com.zeeai.companion.automation.NativePhoneCommander
import com.zeeai.companion.automation.PhoneCommandOutcome
import com.zeeai.companion.offline.OfflineConversationalBrain
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class WakeWordDetection(
    val targetCharacterId: String, // "zee" or "zhee"
    val trailingCommand: String,
    val timestamp: Long = System.currentTimeMillis()
)

object WakeWordEventBus {
    private val _detections = MutableSharedFlow<WakeWordDetection>(extraBufferCapacity = 4)
    val detections: SharedFlow<WakeWordDetection> = _detections.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    internal fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    internal fun emitDetection(detection: WakeWordDetection) {
        _detections.tryEmit(detection)
    }
}

class WakeWordForegroundService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isPausedForActiveVoice = false
    private var isStopping = false
    private var lastTriggerTimeMs = 0L

    // Autonomous background execution & speech reply even when app is killed/closed
    private var phoneCommander: NativePhoneCommander? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isTtsSpeaking = false

    private val restartRunnable = Runnable {
        if (!isStopping && !isPausedForActiveVoice && !isTtsSpeaking) {
            startListeningCycle()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        phoneCommander = NativePhoneCommander(applicationContext)

        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.forLanguageTag("bn-BD")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isTtsSpeaking = true
                        mainHandler.post { speechRecognizer?.cancel() }
                    }

                    override fun onDone(utteranceId: String?) {
                        isTtsSpeaking = false
                        scheduleRestart(250L)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        isTtsSpeaking = false
                        scheduleRestart(250L)
                    }
                })
            }
        }
    }

    private fun speakReply(text: String, isFemale: Boolean = false) {
        if (text.isBlank() || !ttsReady) return
        val clean = text.replace(Regex("\\[.*?\\]"), "").trim()
        if (clean.isBlank()) return

        val hasBengali = clean.any { it in '\u0980'..'\u09FF' }
        val targetLocale = if (hasBengali) Locale.forLanguageTag("bn-BD") else Locale.getDefault()

        var langStatus = tts?.setLanguage(targetLocale)
        if (langStatus == TextToSpeech.LANG_MISSING_DATA || langStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.getDefault())
        }

        tts?.setPitch(if (isFemale) 1.18f else 0.85f)
        tts?.setSpeechRate(if (isFemale) 0.96f else 1.02f)

        try {
            tts?.voices?.let { allVoices ->
                val activeLang = tts?.voice?.locale?.language ?: targetLocale.language
                val langVoices = allVoices.filter { it.locale.language.equals(activeLang, ignoreCase = true) }
                val targetPool = if (langVoices.isNotEmpty()) langVoices else allVoices.toList()
                val targetVoice = if (isFemale) {
                    targetPool.firstOrNull { it.name.contains("female", true) || it.name.contains("-f-", true) }
                } else {
                    targetPool.firstOrNull { it.name.contains("male", true) || it.name.contains("-m-", true) }
                } ?: targetPool.firstOrNull()

                if (targetVoice != null) tts?.voice = targetVoice
            }
        } catch (_: Exception) {}

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "fgs_${System.currentTimeMillis()}")
        }

        isTtsSpeaking = true
        mainHandler.post { speechRecognizer?.cancel() }
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, params, "fgs_${System.currentTimeMillis()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure Foreground notification is always attached
        val notification = buildNotification("Listening 24/7 for \"Hey Zee\", \"Zee\", or phone commands")
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        } catch (_: Exception) {}

        when (intent?.action) {
            ACTION_STOP -> {
                stopWakeWordService()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                isPausedForActiveVoice = true
                destroyRecognizer()
                return START_STICKY
            }
            ACTION_RESUME -> {
                isPausedForActiveVoice = false
                scheduleRestart(400L)
                return START_STICKY
            }
        }

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            stopSelf()
            return START_NOT_STICKY
        }

        isStopping = false
        isPausedForActiveVoice = false

        WakeWordEventBus.setRunning(true)
        mainHandler.post { startListeningCycle() }
        return START_STICKY
    }

    private fun startListeningCycle() {
        if (isStopping || isPausedForActiveVoice || isTtsSpeaking) return

        destroyRecognizer()

        val recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(applicationContext)
        }.getOrElse {
            runCatching { SpeechRecognizer.createSpeechRecognizer(this) }.getOrNull()
        }

        if (recognizer == null) {
            scheduleRestart(2500L)
            return
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) {
                    checkForWakeWordOrCommand(text, isFinal = false)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) {
                    checkForWakeWordOrCommand(text, isFinal = true)
                }
                scheduleRestart(350L)
            }

            override fun onError(error: Int) {
                val delayMs = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 350L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        stopWakeWordService()
                        return
                    }
                    else -> 1200L
                }
                scheduleRestart(delayMs)
            }
        })

        val listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer = recognizer
        runCatching { recognizer.startListening(listenIntent) }
            .onFailure { scheduleRestart(1200L) }
    }

    private fun checkForWakeWordOrCommand(rawTranscript: String, isFinal: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTimeMs < WAKE_COOLDOWN_MS) return

        val normalized = rawTranscript.trim()
        val zheeRegex = Regex("\\b(hey\\s+zhee|zhee|জি)\\b", RegexOption.IGNORE_CASE)
        val zeeRegex = Regex("\\b(hey\\s+zee|zee)\\b", RegexOption.IGNORE_CASE)

        val zheeMatch = zheeRegex.find(normalized)
        val zeeMatch = zeeRegex.find(normalized)

        if (zheeMatch != null || zeeMatch != null) {
            val (targetChar, match) = if (zheeMatch != null) "zhee" to zheeMatch else "zee" to zeeMatch!!
            val trailing = normalized.substring(match.range.last + 1).trim()
            if (!isFinal && trailing.isNotEmpty() && trailing.length < 3) return

            lastTriggerTimeMs = now

            // 1. Emit to EventBus if UI is open
            WakeWordEventBus.emitDetection(
                WakeWordDetection(targetCharacterId = targetChar, trailingCommand = trailing)
            )

            // 2. Autonomous Background Execution: works even if app is closed/killed!
            if (trailing.isNotBlank()) {
                val commander = phoneCommander
                val outcome = commander?.tryHandleCommand(trailing)
                if (outcome is PhoneCommandOutcome.Executed) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, buildNotification("${targetChar.uppercase()}: ${outcome.replyText}"))
                    speakReply(outcome.replyText, isFemale = targetChar == "zhee")
                    return
                } else {
                    val offlineReply = OfflineConversationalBrain.respond(trailing, targetChar)
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, buildNotification("${targetChar.uppercase()}: $offlineReply"))
                    speakReply(offlineReply, isFemale = targetChar == "zhee")
                    return
                }
            } else {
                val greeting = if (targetChar == "zhee") "হ্যাঁ বলো, আমি শুনছি।" else "হ্যাঁ, বলো কী করব?"
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification("${targetChar.uppercase()}: $greeting"))
                speakReply(greeting, isFemale = targetChar == "zhee")
            }
        } else if (isFinal) {
            // Direct phone command without saying "Hey Zee"
            val commander = phoneCommander
            val outcome = commander?.tryHandleCommand(normalized)
            if (outcome is PhoneCommandOutcome.Executed) {
                lastTriggerTimeMs = now
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification("Action: ${outcome.replyText}"))
                speakReply(outcome.replyText, isFemale = false)
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.removeCallbacks(restartRunnable)
        if (!isStopping && !isPausedForActiveVoice && !isTtsSpeaking) {
            mainHandler.postDelayed(restartRunnable, delayMs)
        }
    }

    private fun destroyRecognizer() {
        mainHandler.removeCallbacks(restartRunnable)
        speechRecognizer?.let { sr ->
            runCatching { sr.cancel() }
            runCatching { sr.destroy() }
        }
        speechRecognizer = null
    }

    private fun stopWakeWordService() {
        isStopping = true
        destroyRecognizer()
        WakeWordEventBus.setRunning(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isStopping = true
        destroyRecognizer()
        WakeWordEventBus.setRunning(false)
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ZEE AI Background Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "24/7 background listener for voice commands and wake words."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusLine: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WakeWordForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ZEE AI Always-On Active")
            .setContentText(statusLine)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "zee_ai_wake_word_channel"
        private const val NOTIFICATION_ID = 4701
        private const val WAKE_COOLDOWN_MS = 2000L

        const val ACTION_START = "com.zeeai.companion.WAKE_START"
        const val ACTION_STOP = "com.zeeai.companion.WAKE_STOP"
        const val ACTION_PAUSE = "com.zeeai.companion.WAKE_PAUSE"
        const val ACTION_RESUME = "com.zeeai.companion.WAKE_RESUME"

        fun start(context: Context) {
            val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun pause(context: Context) {
            if (!WakeWordEventBus.isRunning.value) return
            val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            if (!WakeWordEventBus.isRunning.value) return
            val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }
    }
}
