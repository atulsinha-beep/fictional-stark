package com.example.pulsar.voice

import ai.picovoice.cobra.Cobra
import ai.picovoice.leopard.Leopard
import ai.picovoice.porcupine.PorcupineManager
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.pulsar.AppState
import com.example.pulsar.MainActivity
import com.example.pulsar.R
import com.example.pulsar.accessibility.PulsarAccessibilityService
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VoiceService : Service() {

    private val CHANNEL_ID = "pulsar_voice_core"
    private val NOTIFICATION_ID = 101
    private val ACCESS_KEY = "egU6QbT9P534khBrZvKdKUIfE7MXZMmvaxC7KwLHV3yhW7VSOFnS/g=="

    private var porcupine: PorcupineManager? = null
    private var cobra: Cobra? = null
    private var leopard: Leopard? = null

    private val appCache = mutableMapOf<String, String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val voiceExecutor = Executors.newSingleThreadExecutor()

    private val frameBuffer = ShortArray(512)
    private val maxRecordingLength = 16000 * 8
    private val recordingBuffer = ShortArray(maxRecordingLength)

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var processPlayer: MediaPlayer? = null

    @Volatile private var isListening = false
    @Volatile private var isProcessing = false
    @Volatile private var isSystemReady = false
    @Volatile private var isServiceDestroyed = false
    @Volatile private var hasMicFocus = false
    @Volatile private var porcupineRunning = false

    private var retryCount = 0
    private val MAX_RETRIES = 1
    private var timeoutRunnable: Runnable? = null
    private val isInitializing = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundInternal()
        voiceExecutor.execute { attemptInitialization() }
        return START_STICKY
    }

    private fun attemptInitialization() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (!isInitializing.get() && !isSystemReady) {
                isInitializing.set(true)
                initVoiceEnginesSync()
            }
        } else { stopSelf() }
    }

    private fun initVoiceEnginesSync() {
        mainHandler.post { AppState.currentStatus.value = "BOOTING..." }
        try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(ACCESS_KEY)
                .setKeywordPath("hey_pulsar.ppn")
                .setSensitivity(0.7f)
                .build(applicationContext) { mainHandler.post { onWakeWordDetected() } }

            cobra = Cobra.Builder().setAccessKey(ACCESS_KEY).build()
            leopard = Leopard.Builder().setAccessKey(ACCESS_KEY).setModelPath("leopard_en_assistant.pv").build(applicationContext)

            isSystemReady = true
            mainHandler.post {
                ResponseEngine.play(this, ResponseEngine.ResponseType.SYSTEM_READY) {
                    restartWakeWord()
                }
            }
        } catch (e: Exception) {
            mainHandler.post { AppState.currentStatus.value = "ENGINE_ERROR" }
        } finally { isInitializing.set(false) }
    }

    private fun onWakeWordDetected() {
        if (isServiceDestroyed || isListening || isProcessing) return
        safeStopPorcupine()
        ResponseEngine.play(this, ResponseEngine.ResponseType.WAKE) {
            mainHandler.postDelayed({ startSilentRecognition() }, 50)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSilentRecognition() {
        if (isServiceDestroyed || !requestMicFocus()) { restartWakeWord(); return }

        isListening = true
        mainHandler.post { AppState.currentStatus.value = "LISTENING" }
        startTimeoutTimer()

        voiceExecutor.execute {
            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 3200)
            var samplesRead = 0
            var userSpoke = false
            var silenceFrames = 0

            try {
                recorder.startRecording()
                while (isListening && samplesRead < maxRecordingLength) {
                    val read = recorder.read(frameBuffer, 0, frameBuffer.size)
                    if (read > 0) {
                        System.arraycopy(frameBuffer, 0, recordingBuffer, samplesRead, read)
                        samplesRead += read
                        val prob = cobra?.process(frameBuffer) ?: 0f
                        if (!userSpoke && prob > 0.85f) { userSpoke = true; cancelTimeout() }
                        if (userSpoke) {
                            if (prob < 0.15f) silenceFrames++ else silenceFrames = 0
                            if (silenceFrames > 40) break
                        }
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
                isListening = false
                cancelTimeout()
            }

            // --- THE FIX IS HERE ---
            mainHandler.post {
                AppState.currentStatus.value = "EXECUTING"
                isProcessing = true
            }

            if (!userSpoke || samplesRead < 4000) {
                mainHandler.post {
                    isProcessing = false
                    restartWakeWord()
                }
            } else {
                try {
                    val transcript = leopard?.process(recordingBuffer.copyOfRange(0, samplesRead))?.transcriptString ?: ""
                    mainHandler.post {
                        isProcessing = false // Reset flag BEFORE handling command
                        handleCommand(transcript)
                    }
                } catch (e: Exception) {
                    mainHandler.post { isProcessing = false; restartWakeWord() }
                }
            }
        }
    }

    private fun handleCommand(text: String) {
        val cmd = text.lowercase().trim()
            .replace("can sell", "cancel").replace("dental","cancel")
            .replace("what set", "whatsapp").replace("whats up","whatsapp")

        Log.d("PULSAR_VOICE", "Cmd: $cmd")

        if (cmd.isBlank()) {
            ResponseEngine.play(this, ResponseEngine.ResponseType.STANDBY)
            restartWakeWord()
            return
        }

        when {
            cmd.contains("cancel") || cmd.contains("stop") -> {
                ResponseEngine.play(this, ResponseEngine.ResponseType.CANCEL)
                restartWakeWord()
            }
            cmd.startsWith("open ") || cmd.contains("start ") -> {
                val app = cmd.replace("open ", "").replace("start ", "").trim()
                ResponseEngine.play(this, ResponseEngine.ResponseType.OPEN_OK)
                openAppFast(app)
                restartWakeWord()
            }
            cmd.contains("exit") -> {
                ResponseEngine.play(this, ResponseEngine.ResponseType.CLOSE_OK)
                PulsarAccessibilityService.instance?.smartClose()
                restartWakeWord()
            }
            else -> {
                if (retryCount < MAX_RETRIES) {
                    retryCount++
                    ResponseEngine.play(this, ResponseEngine.ResponseType.RETRY) {
                        mainHandler.postDelayed({ startSilentRecognition() }, 500)
                    }
                } else {
                    retryCount = 0
                    ResponseEngine.play(this, ResponseEngine.ResponseType.STANDBY)
                    restartWakeWord()
                }
            }
        }
    }

    private fun restartWakeWord() {
        mainHandler.postDelayed({
            // Ensure UI is reset even if logic fails
            if (AppState.currentStatus.value == "EXECUTING") {
                AppState.currentStatus.value = "WAITING"
            }

            if (!isServiceDestroyed && isSystemReady && !isListening && !isProcessing) {
                if (requestMicFocus()) safeStartPorcupine()
            }
        }, 300)
    }

    private fun safeStartPorcupine() {
        if (porcupineRunning) return
        try {
            porcupine?.start()
            porcupineRunning = true
            AppState.currentStatus.value = "WAITING"
        } catch (e: Exception) { porcupineRunning = false }
    }

    private fun safeStopPorcupine() {
        try { porcupine?.stop() } catch (e: Exception) {}
        porcupineRunning = false
    }

    private fun requestMicFocus(): Boolean {
        if (hasMicFocus) return true
        val result = audioManager.requestAudioFocus(
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focus ->
                    if (focus == AudioManager.AUDIOFOCUS_GAIN) {
                        hasMicFocus = true
                        restartWakeWord()
                    } else {
                        hasMicFocus = false
                        safeStopPorcupine()
                    }
                }.build()
        )
        hasMicFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasMicFocus
    }

    // --- UTILS ---
    private fun openAppFast(appName: String): Boolean {
        val search = appName.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (appCache.isEmpty()) refreshAppCache()
        val pkg = appCache.filterKeys { it.contains(search) || search.contains(it) }.values.firstOrNull()
        pkg?.let { executeLaunch(it); return true }
        return false
    }

    private fun refreshAppCache() {
        val pm = packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).forEach {
            val label = it.loadLabel(pm).toString().lowercase().replace(Regex("[^a-z0-9]"), "")
            appCache[label] = it.activityInfo.packageName
        }
    }

    private fun executeLaunch(pkg: String) {
        val acc = PulsarAccessibilityService.instance
        if (acc != null) {
            acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            mainHandler.postDelayed({ acc.openApp(pkg) }, 300)
        } else {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    private fun startForegroundInternal() {
        val chan = NotificationChannel(CHANNEL_ID, "Pulsar Core", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_background).setContentTitle("Pulsar AI").build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun createNotificationChannel() {
        // Notification Channels are only required/available on API 26+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Pulsar Voice Service"
            val descriptionText = "Continuous wake-word and command processing"

            // IMPORTANCE_LOW: Shows in the tray but doesn't make a sound/pop up.
            // Use IMPORTANCE_DEFAULT if you want it to be more visible.
            val importance = NotificationManager.IMPORTANCE_LOW

            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                // Optional: Disable the notification light and vibration for background service
                enableLights(false)
                enableVibration(false)
                setShowBadge(false) // Don't show a dot on the app icon
            }

            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startTimeoutTimer() {
        cancelTimeout()
        timeoutRunnable = Runnable { if (isListening) { isListening = false; restartWakeWord() } }
        mainHandler.postDelayed(timeoutRunnable!!, 6000)
    }

    private fun cancelTimeout() { timeoutRunnable?.let { mainHandler.removeCallbacks(it) } }

    override fun onDestroy() {
        isServiceDestroyed = true
        safeStopPorcupine()
        voiceExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(p0: Intent?): IBinder? = null
}