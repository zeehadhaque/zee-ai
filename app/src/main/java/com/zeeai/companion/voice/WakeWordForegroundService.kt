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
import java.lang.ref.WeakReference
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

    @Volatile
    var isPausedForActiveVoice: Boolean = false
        private set

    internal var activeServiceRef: WeakReference<WakeWordForegroundService>? = null

    internal fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    internal fun setPaused(paused: Boolean) {
        isPausedForActiveVoice = paused
    }

    internal fun emitDetection(detection: WakeWordDetection) {
        _detections.tryEmit(detection)
    }
}

class WakeWordForegroundService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isStopping = false
    private var lastTriggerTimeMs = 0L

    private var phoneCommander: NativePhoneCommander? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isTtsSpeaking = false

    private val restartRunnable = Runnable {
        if (!isStopping && !WakeWordEventBus.isPausedForActiveVoice && !isTtsSpeaking) {
            startListeningCycle()
        }
    }

    override fun onCreate() {
        super.onCreate()
        WakeWordEventBus.activeServiceRef = WeakReference(this)
        createNotificationChannel()
        phoneCommander = NativePhoneCommander(applicationContext)

        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
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

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        isTtsSpeaking = false
                        scheduleRestart(250L)
                    }
                })
            }
        }
    }

    internal fun pauseImmediately() {
        WakeWordEventBus.setPaused(true)
        mainHandler.post { destroyRecognizer() }
    }

    internal fun resumeImmediately() {
        WakeWordEventBus.setPaused(false)
        scheduleRestart(400L)
    }

    internal fun stopImmediately() {
        mainHandler.post { stopWakeWordService() }
    }

    private fun speakReply(text: String, isFemale: Boolean = false) {
        val currentTts = tts
        if (text.isBlank() || !ttsReady || currentTts == null) return
        val clean = text.replace(Regex("\\[.*?\\]"), "").trim()
        if (clean.isBlank()) return

        val hasBengali = clean.any { it in '\u0980'..'\u09FF' }
        val online = isNetworkAvailable()
        val allVoices = runCatching { currentTts.voices }.getOrNull().orEmpty()
        val installedBnVoice = allVoices.any { v ->
            v.locale.language.equals("bn", ignoreCase = true) &&
                !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) &&
                (online || !v.isNetworkConnectionRequired)
        }

        val textToSpeak: String
        val targetLangPrefix: String
        if (hasBengali && installedBnVoice) {
            val bnLocale = listOf(Locale("bn", "BD"), Locale("bn", "IN"), Locale("bn")).firstOrNull { loc ->
                runCatching { currentTts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
            }
            if (bnLocale != null) {
                currentTts.language = bnLocale
                textToSpeak = clean
                targetLangPrefix = "bn"
            } else {
                textToSpeak = BengaliToBanglish.toBanglish(clean)
                currentTts.language = Locale.US
                targetLangPrefix = "en"
            }
        } else {
            textToSpeak = if (hasBengali) BengaliToBanglish.toBanglish(clean) else clean
            val safeEn = listOf(Locale.US, Locale.UK, Locale("en", "IN"), Locale.getDefault()).firstOrNull { loc ->
                runCatching { currentTts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
            } ?: Locale.getDefault()
            currentTts.language = safeEn
            targetLangPrefix = safeEn.language
        }

        currentTts.setPitch(if (isFemale) 1.12f else 0.92f)
        currentTts.setSpeechRate(if (isFemale) 0.96f else 1.0f)

        runCatching {
            val pool = allVoices.filter { v ->
                v.locale.language.equals(targetLangPrefix, ignoreCase = true) &&
                    !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) &&
                    (online || !v.isNetworkConnectionRequired)
            }
            val targetVoice = if (isFemale) {
                pool.firstOrNull { it.name.contains("female", true) || it.name.contains("-f-", true) }
            } else {
                pool.firstOrNull { it.name.contains("male", true) || it.name.contains("-m-", true) }
            } ?: pool.firstOrNull()
            if (targetVoice != null) currentTts.voice = targetVoice
        }

        val uid = "fgs_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
        }

        isTtsSpeaking = true
        mainHandler.post { speechRecognizer?.cancel() }
        val res = runCatching { currentTts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, uid) }.getOrDefault(TextToSpeech.ERROR)
        if (res != TextToSpeech.SUCCESS) {
            isTtsSpeaking = false
            scheduleRestart(300L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notification = buildNotification("Listening 24/7 for \"Hey Zee\", \"Zee\", or phone commands")
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        val foregroundOk = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
            true
        }.getOrDefault(false)

        if (!foregroundOk || !hasMic) {
            stopWakeWordService()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                stopWakeWordService()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                WakeWordEventBus.setPaused(true)
                destroyRecognizer()
                return START_STICKY
            }
            ACTION_RESUME -> {
                WakeWordEventBus.setPaused(false)
                scheduleRestart(400L)
                return START_STICKY
            }
        }

        isStopping = false
        WakeWordEventBus.setPaused(false)
        WakeWordEventBus.setRunning(true)
        mainHandler.post { startListeningCycle() }
        return START_STICKY
    }

    private fun startListeningCycle() {
        if (isStopping || WakeWordEventBus.isPausedForActiveVoice || isTtsSpeaking) return

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
        val zheeRegex = Regex("""(?:^|[\s,।.!?])(?:hey\s+zhee|zhee|জি|ঝি)(?=$|[\s,।.!?])""", RegexOption.IGNORE_CASE)
        val zeeRegex = Regex("""(?:^|[\s,।.!?])(?:hey\s+zee|zee)(?=$|[\s,।.!?])""", RegexOption.IGNORE_CASE)

        val zheeMatch = zheeRegex.find(normalized)
        val zeeMatch = zeeRegex.find(normalized)

        if (zheeMatch != null || zeeMatch != null) {
            val (targetChar, match) = if (zheeMatch != null) "zhee" to zheeMatch else "zee" to zeeMatch!!
            val trailing = normalized.substring(match.range.last + 1).trim()
            if (!isFinal && trailing.isNotEmpty() && trailing.length < 3) return

            lastTriggerTimeMs = now

            WakeWordEventBus.emitDetection(
                WakeWordDetection(targetCharacterId = targetChar, trailingCommand = trailing)
            )

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
        if (!isStopping && !WakeWordEventBus.isPausedForActiveVoice && !isTtsSpeaking) {
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
        WakeWordEventBus.setPaused(false)
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        isStopping = true
        WakeWordEventBus.activeServiceRef = null
        destroyRecognizer()
        WakeWordEventBus.setRunning(false)
        WakeWordEventBus.setPaused(false)
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
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) return
            val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                action = ACTION_START
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            WakeWordEventBus.activeServiceRef?.get()?.let {
                it.stopImmediately()
                return
            }
            if (WakeWordEventBus.isRunning.value) {
                runCatching { context.stopService(Intent(context, WakeWordForegroundService::class.java)) }
            }
        }

        fun pause(context: Context) {
            if (!WakeWordEventBus.isRunning.value) return
            WakeWordEventBus.setPaused(true)
            WakeWordEventBus.activeServiceRef?.get()?.pauseImmediately()
        }

        fun resume(context: Context) {
            if (!WakeWordEventBus.isRunning.value) return
            WakeWordEventBus.setPaused(false)
            WakeWordEventBus.activeServiceRef?.get()?.resumeImmediately()
        }
    }
}
