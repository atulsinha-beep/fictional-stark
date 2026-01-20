package com.example.pulsar.vision

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.pulsar.AppState
import com.example.pulsar.R
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import androidx.core.graphics.createBitmap

class GestureService : LifecycleService() {

    private lateinit var handLandmarker: HandLandmarker
    private lateinit var cameraExecutor: ExecutorService

    // Timings & State
    private var lastActionTime = 0L
    private val ACTION_COOLDOWN = 1200L
    private var swipeStartX: Float? = null
    private var swipeStartY: Float? = null
    private var swipeStartTime: Long = 0
    private val SWIPE_TIME_LIMIT = 400L
    private val SWIPE_DISTANCE = 0.18f

    // Lock logic
    private var lockStartTime: Long = 0L
    private var lockCandidate = false
    private val LOCK_HOLD_TIME = 500L
    private val LOCK_PINCH_DIST = 0.045f
    private val LOCK_STABILITY_DIST = 0.02f
    private var lastWristX: Float? = null
    private var lastWristY: Float? = null

    override fun onCreate() {
        super.onCreate()
        // Initialize AI components here
        initMediaPipe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FIX: Must be called FIRST to prevent ForegroundServiceDidNotStartInTimeException
        startForegroundService()
        super.onStartCommand(intent, flags, startId)

        Log.d("PULSAR", "Gesture Service Starting...")

        // DELAY CAMERA START: Wait 500ms for UI to release camera lock
        Handler(Looper.getMainLooper()).postDelayed({
            startCamera()
        }, 500)

        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "pulsar_vision"
        val channel = NotificationChannel(channelId, "Pulsar Vision", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Pulsar Vision Active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        startForeground(102, notification) // Unique ID 102
    }

    private fun initMediaPipe() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ -> processGestures(result) }
                .setErrorListener { error -> Log.e("PULSAR", "MediaPipe error", error) }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(this, options)
            cameraExecutor = Executors.newSingleThreadExecutor()
        } catch (e: Exception) {
            Log.e("PULSAR", "MediaPipe Init Failed", e)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { image -> detectHand(image) }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Ensure UI has released camera
                cameraProvider.unbindAll()
                // Bind to Service Lifecycle
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalyzer)
                Log.d("PULSAR", "Service Camera Bound Successfully")
            } catch (exc: Exception) {
                Log.e("PULSAR", "Service Camera Init Failed (Locked by UI?)", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Inside your GestureService CameraX Analyzer
    private fun detectHand(imageProxy: ImageProxy) {
        // FIX: If the AI isn't ready, just drop the frame and wait.

        val mpImage = BitmapImageBuilder(imageProxy.toBitmap()).build()

        try {
            handLandmarker.detectAsync(mpImage, SystemClock.elapsedRealtime())
        } catch (e: Exception) {
            Log.e("PULSAR", "Detection failed", e)
        } finally {
            imageProxy.close()
        }
    }
    // --- LOGIC ENGINE ---
    private fun processGestures(result: HandLandmarkerResult) {
        if (result.landmarks().isEmpty()) {
            // Optional: Clear gesture if hand lost
            // AppState.currentGesture.value = ""
            return
        }

        val landmarks = result.landmarks()[0]

        // ⛔ BLOCK BACK OF HAND
        if (!isPalmFacingCamera(landmarks)) {
            AppState.currentGesture.value = "BACK OF HAND"
            return
        }

        val thumbTip = landmarks[4]
        val indexTip = landmarks[8]
        val midTip = landmarks[12]
        val ringTip = landmarks[16]
        val pinkyTip = landmarks[20]
        val wrist = landmarks[0]
        val indexMcp = landmarks[5]

        val isIndexUp = indexTip.y() < landmarks[6].y()
        val isMidUp = midTip.y() < landmarks[10].y()
        val isRingUp = ringTip.y() < landmarks[14].y()
        val isPinkyUp = pinkyTip.y() < landmarks[18].y()

        val currentTime = System.currentTimeMillis()
        var detectedAction = ""

        // 1. OPEN PALM ✋ (Home)
        if (isIndexUp && isMidUp && isRingUp && isPinkyUp) {
            detectedAction = "HOME"
        }
        // 2. CLOSED FIST ✊ (Back)
        else if (!isIndexUp && !isMidUp && !isRingUp && !isPinkyUp) {
            detectedAction = "BACK"
        }
        // 3. VICTORY ✌️ (Recents)
        else if (isIndexUp && isMidUp && !isRingUp && !isPinkyUp) {
            detectedAction = "RECENTS"
        }
        // 4. POINT UP ☝️ (Scroll Up)
        else if (isIndexUp && !isMidUp && !isRingUp && !isPinkyUp) {
            detectedAction = "SCROLL UP"
        }

        // 5. SWIPES
        if (isIndexUp && !isMidUp && !isRingUp && !isPinkyUp) {
            val swipe = detectSwipe(indexMcp.x(), indexMcp.y(), currentTime)
            when (swipe) {
                "SWIPE_LEFT" -> detectedAction = "SWIPE LEFT"
                "SWIPE_RIGHT" -> detectedAction = "SWIPE RIGHT"
                "SWIPE_UP" -> detectedAction = "SCROLL UP"
                "SWIPE_DOWN" -> detectedAction = "SCROLL DOWN"
            }
        }

        // 6. OK SIGN 👌 (Lock)
        val distThumbIndex = hypot((thumbTip.x() - indexTip.x()).toDouble(), (thumbTip.y() - indexTip.y()).toDouble())
        val fingersOk = isMidUp && isRingUp && isPinkyUp
        val isPinched = distThumbIndex < LOCK_PINCH_DIST
        val stable = isHandStable(wrist.x(), wrist.y())

        if (detectLockGesture(isPinched, fingersOk, stable, currentTime)) {
            detectedAction = "LOCK DEVICE"
            AppState.currentStatus.value = "LOCKING..."
        }

        // 7. VULCAN/SCREENSHOT
        if (isIndexUp && isMidUp && isRingUp && isPinkyUp) {
            val midRingDist = hypot((midTip.x() - ringTip.x()).toDouble(), (midTip.y() - ringTip.y()).toDouble())
            if (midRingDist > 0.15) {
                detectedAction = "SCREENSHOT"
            }
        }

        // UPDATE UI STATE
        AppState.currentGesture.value = detectedAction.ifBlank { "NONE" }

        // --- ACTION EXECUTION (Commented out until you are ready) ---
        /*
        if (detectedAction.isNotEmpty() && checkCooldown(currentTime, ACTION_COOLDOWN)) {
             performAction { service ->
                 when(detectedAction) {
                     "HOME" -> service.homeScreen()
                     "BACK" -> service.goBack()
                     // ... map others
                 }
             }
        }
        */
    }

    private fun detectSwipe(indexMcpX: Float, indexMcpY: Float, currentTime: Long): String? {
        if (swipeStartX == null) {
            swipeStartX = indexMcpX
            swipeStartY = indexMcpY
            swipeStartTime = currentTime
            return null
        }
        val dx = indexMcpX - swipeStartX!!
        val dy = indexMcpY - swipeStartY!!
        val dt = currentTime - swipeStartTime

        if (dt > SWIPE_TIME_LIMIT) {
            resetSwipe(); return null
        }

        val absDx = kotlin.math.abs(dx)
        val absDy = kotlin.math.abs(dy)

        if (absDx > SWIPE_DISTANCE && absDx > absDy) {
            val result = if (dx > 0) "SWIPE_LEFT" else "SWIPE_RIGHT"
            resetSwipe(); return result
        }
        if (absDy > SWIPE_DISTANCE && absDy > absDx) {
            val result = if (dy > 0) "SWIPE_DOWN" else "SWIPE_UP"
            resetSwipe(); return result
        }
        return null
    }

    private fun detectLockGesture(isPinched: Boolean, fingersOk: Boolean, stable: Boolean, currentTime: Long): Boolean {
        if (isPinched && fingersOk && stable) {
            if (!lockCandidate) {
                lockCandidate = true
                lockStartTime = currentTime
                return false
            }
            if (currentTime - lockStartTime >= LOCK_HOLD_TIME) {
                resetLock(); return true
            }
        } else {
            resetLock()
        }
        return false
    }

    private fun isHandStable(wristX: Float, wristY: Float): Boolean {
        if (lastWristX == null) {
            lastWristX = wristX; lastWristY = wristY
            return false
        }
        val dx = kotlin.math.abs(wristX - lastWristX!!)
        val dy = kotlin.math.abs(wristY - lastWristY!!)
        lastWristX = wristX; lastWristY = wristY
        return dx < LOCK_STABILITY_DIST && dy < LOCK_STABILITY_DIST
    }

    private fun isPalmFacingCamera(landmarks: List<NormalizedLandmark>): Boolean {
        val wrist = landmarks[0]
        val indexMcp = landmarks[5]
        val pinkyMcp = landmarks[17]
        val palmWidth = kotlin.math.abs(indexMcp.x() - pinkyMcp.x())
        val palmHeight = kotlin.math.abs(indexMcp.y() - wrist.y())
        return palmWidth > palmHeight
    }

    private fun imageProxyToMPImage(imageProxy: ImageProxy): MPImage {
        // Create Bitmap from the ImageProxy
        // Note: For RGBA_8888, planes[0] copy is usually safe on modern Android
        val bitmap = createBitmap(imageProxy.width, imageProxy.height)
        bitmap.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
        return BitmapImageBuilder(bitmap).build()
    }

    private fun resetLock() { lockCandidate = false; lockStartTime = 0L }
    private fun resetSwipe() { swipeStartX = null; swipeStartY = null; swipeStartTime = 0 }

    private fun checkCooldown(current: Long, needed: Long): Boolean {
        if (current - lastActionTime > needed) {
            lastActionTime = current
            return true
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handLandmarker.close()
    }
}