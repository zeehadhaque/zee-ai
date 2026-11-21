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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zeeai.companion.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val restartRunnable = Runnable {
        if (!isStopping && !isPausedForActiveVoice) {
            startListeningCycle()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        // Android 14/15 compliance: verify RECORD_AUDIO before starting microphone FGS
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

        try {
            val notification = buildNotification("Listening for \"Hey Zee\", \"Zee\", or \"ZHEE\"")
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        } catch (_: Exception) {
            // Android 14/15 throws ForegroundServiceStartNotAllowedException if launched from background
            stopSelf()
            return START_NOT_STICKY
        }

        WakeWordEventBus.setRunning(true)
        mainHandler.post { startListeningCycle() }
        return START_STICKY
    }

    private fun startListeningCycle() {
        if (isStopping || isPausedForActiveVoice) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopWakeWordService()
            return
        }

        destroyRecognizer()

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
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
                    checkForWakeWord(text, isFinal = false)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) {
                    checkForWakeWord(text, isFinal = true)
                }
                scheduleRestart(350L)
            }

            override fun onError(error: Int) {
                val delayMs = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 400L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1200L
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        stopWakeWordService()
                        return
                    }
                    else -> 1500L
                }
                scheduleRestart(delayMs)
            }
        })

        val listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !isNetworkAvailable())
        }

        speechRecognizer = recognizer
        runCatching { recognizer.startListening(listenIntent) }
            .onFailure { scheduleRestart(1500L) }
    }

    private fun checkForWakeWord(rawTranscript: String, isFinal: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTimeMs < WAKE_COOLDOWN_MS) return

        val normalized = rawTranscript.trim()
        val zheeRegex = Regex("\\b(hey\\s+zhee|zhee|জি)\\b", RegexOption.IGNORE_CASE)
        val zeeRegex = Regex("\\b(hey\\s+zee|zee)\\b", RegexOption.IGNORE_CASE)

        val zheeMatch = zheeRegex.find(normalized)
        val zeeMatch = zeeRegex.find(normalized)

        val (targetChar, match) = when {
            zheeMatch != null -> "zhee" to zheeMatch
            zeeMatch != null -> "zee" to zeeMatch
            else -> return
        }

        val trailing = normalized.substring(match.range.last + 1).trim()
        // Wait for final result if the user is still mid-sentence after the wake word
        if (!isFinal && trailing.isNotEmpty() && trailing.length < 4) return

        lastTriggerTimeMs = now
        WakeWordEventBus.emitDetection(
            WakeWordDetection(
                targetCharacterId = targetChar,
                trailingCommand = trailing
            )
        )

        // Update notification so user has immediate visual feedback even if app is minimized
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            NOTIFICATION_ID,
            buildNotification("${targetChar.uppercase()} activated${if (trailing.isNotBlank()) ": $trailing" else ""}")
        )
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.removeCallbacks(restartRunnable)
        if (!isStopping && !isPausedForActiveVoice) {
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
                "ZEE AI Wake Word Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active microphone listener for Hey Zee, Zee, and ZHEE."
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
            .setContentTitle("ZEE AI Wake Word Active")
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
        private const val WAKE_COOLDOWN_MS = 2500L

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
