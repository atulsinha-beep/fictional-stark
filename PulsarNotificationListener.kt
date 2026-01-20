package com.example.pulsar.voice

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification // It IS used here in the loop!

class PulsarNotificationListener : NotificationListenerService() {

    companion object {
        var instance: PulsarNotificationListener? = null
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    fun getNotificationCounts(): Map<String, Int> {
        val activeNotifs: Array<StatusBarNotification>? = activeNotifications
        if (activeNotifs == null) return emptyMap()

        val counts = mutableMapOf<String, Int>()

        for (sbn in activeNotifs) {
            try {
                val packageName = sbn.packageName
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val appLabel = packageManager.getApplicationLabel(appInfo).toString()

                counts[appLabel] = counts.getOrDefault(appLabel, 0) + 1
            } catch (_: Exception) {
                // If we can't get the app name, we group it under "System"
                counts["System"] = counts.getOrDefault("System", 0) + 1
            }
        }
        return counts
    }
}