package com.gozar.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.gozar.app.MainActivity
import com.gozar.app.R

class ServiceNotification(private val service: GozarVpnService) {

    private val manager: NotificationManager
        get() = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun build(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getBroadcast(
            service,
            1,
            Intent(service, StopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, service.getString(R.string.notification_stop), stopIntent)
            .build()
    }

    fun update(title: String, text: String) {
        runCatching { manager.notify(NOTIFICATION_ID, build(title, text)) }
    }

    /** Notifications raised by the core itself (deprecation warnings and such). */
    fun showCoreNotification(title: String?, body: String?) {
        if (title.isNullOrBlank() && body.isNullOrBlank()) return
        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.orEmpty())
            .setContentText(body.orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.orEmpty()))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(CORE_NOTIFICATION_ID, notification) }
    }

    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_STOP) return
            context.startService(
                Intent(context, GozarVpnService::class.java)
                    .setAction(GozarVpnService.ACTION_STOP),
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "gozar_vpn"
        const val NOTIFICATION_ID = 1001
        const val CORE_NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.gozar.app.STOP_FROM_NOTIFICATION"

        val foregroundServiceType: Int
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
    }
}
