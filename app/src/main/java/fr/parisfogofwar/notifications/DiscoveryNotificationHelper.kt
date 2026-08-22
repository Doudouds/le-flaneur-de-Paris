package fr.parisfogofwar.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.BitmapFactory
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.parisfogofwar.MainActivity
import fr.parisfogofwar.R
import fr.parisfogofwar.domain.model.Monument
import javax.inject.Inject

class DiscoveryNotificationHelper @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Découvertes de monuments",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifications lorsqu'un nouveau monument est révélé."
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    TRACKING_CHANNEL_ID,
                    "Suivi de promenade",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Notification affichée lorsque le suivi en arrière-plan est actif."
                },
            )
        }
    }

    fun notifyNewMonument(monument: Monument) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_MONUMENT_ID, monument.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            monument.id.hashCode() and Int.MAX_VALUE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.logo))
            .setContentTitle("Nouveau monument découvert !")
            .setContentText(monument.name)
            .setStyle(NotificationCompat.BigTextStyle().bigText(monument.name))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(monument.id.hashCode(), notification)
    }

    fun buildTrackingNotification() = NotificationCompat.Builder(context, TRACKING_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.logo))
        .setContentTitle("Suivi de promenade actif")
        .setContentText("La carte continue de se dévoiler autour de vous.")
        .setContentIntent(mainActivityPendingIntent())
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun mainActivityPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        TRACKING_PENDING_INTENT_REQUEST_CODE,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val EXTRA_MONUMENT_ID = "fr.parisfogofwar.extra.DISCOVERY_MONUMENT_ID"
        const val CHANNEL_ID = "monument-discoveries"
        const val TRACKING_CHANNEL_ID = "location-tracking"
        const val TRACKING_PENDING_INTENT_REQUEST_CODE = 2001
    }
}
