package com.smartcash.analyzer.telegram

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smartcash.analyzer.App
import com.smartcash.analyzer.data.settings.RelayMode
import com.smartcash.analyzer.notifications.AppNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** Periodic check (every 15 minutes, when online) so receipts sent to the bot are picked up even if the service is off. */
class TelegramSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as App).container
        return try {
            // a few quick rounds so a burst of receipts is handled in one run
            repeat(3) {
                val n = c.botEngine.syncOnce(longPollSeconds = 0)
                if (n <= 0) return Result.success()
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val PERIODIC = "telegram_sync_periodic"
        private const val NOW = "telegram_sync_now"
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<TelegramSyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<TelegramSyncWorker>().setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/**
 * Keeps the Telegram bot online with long polling, so replies are instant. Optional (Settings).
 */
class BotService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = AppNotifier.botNotification(this, "بانتظار الرسائل والإيصالات…")
        ServiceCompat.startForeground(
            this, AppNotifier.BOT_NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        return START_STICKY
    }

    private suspend fun runLoop() {
        val c = (application as App).container
        c.botEngine.setServiceRunning(true)
        var backoff = 2_000L
        while (scope.isActive) {
            val s = c.settings.current()
            if (!s.botServiceEnabled || !s.telegramConfigured || s.telegramChatId.isBlank()) break
            try {
                val relay = s.relayMode == RelayMode.RELAY && s.relayConfigured
                val n = c.botEngine.syncOnce(longPollSeconds = if (relay) 0 else 50)
                if (n < 0 || relay) delay(if (relay) 5_000 else 1_000)
                backoff = 2_000L
            } catch (e: Exception) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(120_000L)
            }
        }
        c.botEngine.setServiceRunning(false)
        stopSelf()
    }

    override fun onDestroy() {
        (application as? App)?.container?.botEngine?.setServiceRunning(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "stop"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BotService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BotService::class.java))
        }
    }
}

/** Restarts background work after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TelegramSyncWorker.schedule(context)
        val app = context.applicationContext as? App ?: return
        val enabled = runCatching { runBlocking { app.container.settings.current().botServiceEnabled } }.getOrDefault(false)
        if (enabled) runCatching { BotService.start(context) }
    }
}
