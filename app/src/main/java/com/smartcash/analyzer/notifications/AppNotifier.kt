package com.smartcash.analyzer.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.smartcash.analyzer.MainActivity
import com.smartcash.analyzer.R

/** Local notifications shown by the app itself. */
object AppNotifier {
    const val CHANNEL_TX = "transactions"
    const val CHANNEL_BOT = "bot_service"
    const val BOT_NOTIFICATION_ID = 1001

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TX, "المعاملات الجديدة", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "إشعار عند إضافة معاملة تلقائياً"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BOT, "بوت تيليجرام", NotificationManager.IMPORTANCE_MIN).apply {
                description = "يبقى ظاهراً أثناء تشغيل البوت في الخلفية"
                setShowBadge(false)
            }
        )
    }

    fun openAppIntent(context: Context, txId: Long? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            txId?.let { putExtra(MainActivity.EXTRA_TX_ID, it) }
        }
        return PendingIntent.getActivity(
            context, (txId ?: 0L).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun transactionAdded(context: Context, txId: Long, title: String, text: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_TX)
            .setSmallIcon(R.drawable.ic_stat_cash)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context, txId))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify((2000 + txId % 100000).toInt(), n) }
    }

    fun botNotification(context: Context, text: String) =
        NotificationCompat.Builder(context, CHANNEL_BOT)
            .setSmallIcon(R.drawable.ic_stat_cash)
            .setContentTitle("بوت تيليجرام يعمل")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openAppIntent(context))
            .build()
}
