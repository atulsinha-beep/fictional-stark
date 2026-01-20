package com.example.pulsar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.* // Imports remember, mutableStateOf, etc.
import androidx.compose.runtime.getValue // CRITICAL FIX: Enables 'by' delegation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.pulsar.voice.VoiceService
import com.example.pulsar.vision.GestureService

class MainActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    // 1 Permission launcher (MULTIPLE permissions)
    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val allGranted = permissions.values.all { it }

            if (allGranted) {
                startServices()
            } else {
                Log.e("PULSAR", "Required permissions not granted")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionsIfNeeded()

        setContent {
            PulsarUI()
        }
    }

    // 2 Permission gate (ONLY place permissions are handled)
    private fun requestPermissionsIfNeeded() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) ==
                    PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startServices()
        } else {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    // 3 Start services ONLY after permissions are confirmed
    private fun startServices() {
        // A 100ms delay ensures the OS has registered the permission grant
        // before the Service checks for it in onStartCommand
        mainHandler.postDelayed({
            startVoiceService()
            // startGestureService()
        }, 200)
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startGestureService() {
        val intent = Intent(this, GestureService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    // 4 UI lifecycle flags (correct usage)
    override fun onResume() {
        super.onResume()
        AppState.isUiActive.value = true
    }

    override fun onPause() {
        super.onPause()
        AppState.isUiActive.value = false
    }
}


@Composable
fun PulsarUI() {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // FIX: Use collectAsState for Flow/StateFlow. Do NOT use remember { State }
    val status by AppState.currentStatus.collectAsState()
    val gesture by AppState.currentGesture.collectAsState()

    // Orb Animation
    val infiniteTransition = rememberInfiniteTransition(label = "orb_anim")
    val orbColor by infiniteTransition.animateColor(
        initialValue = Color.Cyan,
        targetValue = Color(0xFF0066FF),
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_color"
    )

    // Manage Service lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // Stop vision to free camera for UI
                    context.stopService(Intent(context, GestureService::class.java))
                    // Keep Voice running
                    val intent = Intent(context, VoiceService::class.java)
                    ContextCompat.startForegroundService(context, intent)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // Start vision since UI is hidden
                    val intent = Intent(context, GestureService::class.java)
                    ContextCompat.startForegroundService(context, intent)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ─────────────────────────────────────────────────────────────

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── TOP SECTION: ORB (20%) ──────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.2f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            OrbComponent(color = orbColor)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = status.uppercase(),
                color = orbColor.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
        }

        // ── BOTTOM SECTION: CAMERA PREVIEW (80%) ───────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.8f)
                .padding(bottom = 16.dp, start = 12.dp, end = 12.dp)
                .clip(RoundedCornerShape(40.dp))
                .border(4.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(40.dp))
                .background(Color(0xFF0A0A0A))
        ) {
            // [A] CAMERA PREVIEW (Visible only when UI is Active)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        this.scaleType = PreviewView.ScaleType.FILL_CENTER
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { previewView ->
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        // Preview Use Case
                        val preview = Preview.Builder().build()
                        preview.surfaceProvider = previewView.surfaceProvider

                        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                        try {
                            // Unbind everything to claim camera for UI
                            cameraProvider.unbindAll()

                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview
                            )
                        } catch (e: Exception) {
                            Log.e("PULSAR", "UI Camera Binding Failed", e)
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
            )

            // [B] UI OVERLAY
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "LIVE SKELETAL FEED",
                    color = Color.Red.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color.Black.copy(0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "DETECTED INTENT",
                        color = Color.White.copy(0.7f),
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = gesture.ifBlank { "SCANNING..." }.uppercase(),
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.drawBehind {
                            drawCircle(
                                color = orbColor.copy(alpha = 0.2f),
                                radius = size.maxDimension / 1.5f
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun OrbComponent(color: Color) {
    Box(
        modifier = Modifier
            .size(100.dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.8f), Color.Transparent),
                        center = center,
                        radius = size.width / 1.5f
                    )
                )
                drawCircle(
                    color = color,
                    radius = size.width / 2.5f,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
    )
}