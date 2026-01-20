package com.example.pulsar.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.os.Handler
import android.os.Looper

@SuppressLint("AccessibilityPolicy")
class PulsarAccessibilityService : AccessibilityService() {

    companion object {
        var instance: PulsarAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.w("PULSAR", "Accessibility connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // --- GLOBAL ACTIONS ---
    fun homeScreen() = performGlobalAction(GLOBAL_ACTION_HOME)
    // Inside PulsarAccessibilityService.kt
    fun openApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true // Success!
            } else {
                false // App not found or not launchable
            }
        } catch (e: Exception) {
            false // Something went wrong
        }
    }    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun lockDevice() = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    fun showNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun takeScreenshot() = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

    // --- GESTURE DISPATCHING (Swipes & Scrolls) ---

    fun scrollUp() {
        // Simulates dragging finger DOWN (content moves UP)
        val path = Path().apply {
            moveTo(500f, 1000f)
            lineTo(500f, 500f)
        }
        dispatchSwipe(path)
    }

    fun scrollDown() {
        // Simulates dragging finger UP (content moves DOWN)
        val path = Path().apply {
            moveTo(500f, 500f)
            lineTo(500f, 1000f)
        }
        dispatchSwipe(path)
    }

    fun swipeRight() {
        // Go Back gesture usually, or switch tabs
        val path = Path().apply {
            moveTo(100f, 1000f)
            lineTo(900f, 1000f)
        }
        dispatchSwipe(path)
    }

    fun swipeLeft() {
        val path = Path().apply {
            moveTo(900f, 1000f)
            lineTo(100f, 1000f)
        }
        dispatchSwipe(path)
    }

    private fun dispatchSwipe(path: Path) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // Smart Close Logic
    fun smartClose() {
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        else
        {
            // Double check: if back didn't exit app, force home after delay
            Handler(Looper.getMainLooper()).postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
                // Optional: Check if still in same package? For now just simplistic fallback
            }, 500)
        }
    }



    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
}