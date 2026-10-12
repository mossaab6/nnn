package com.smartcash.analyzer.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.smartcash.analyzer.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Receives every notification posted on the phone (after the user grants "Notification access") and forwards
 * the Sham Cash ones to [NotificationProcessor]. Nothing from other apps is stored.
 */
class ShamCashNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    override fun onListenerConnected() {
        // catch notifications that arrived while the listener was not connected
        runCatching { activeNotifications?.forEach { handle(it) } }
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() }
        val body = listOfNotNull(big ?: text, lines).joinToString("\n").ifBlank { null }
        val eventTime = n.`when`.takeIf { it > 0 } ?: sbn.postTime
        val app = application as? App ?: return
        val pkg = sbn.packageName
        val key = sbn.key
        scope.launch {
            runCatching { app.container.notificationProcessor.onNotification(pkg, key, eventTime, title, body) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, ShamCashNotificationListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
