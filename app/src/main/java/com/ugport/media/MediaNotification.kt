package com.ugport.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.ugport.MainActivity
import com.ugport.R

object MediaNotification {

    const val CHANNEL_ID = "ugport_playback"
    const val NOTIFICATION_ID = 4102

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_playback),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_playback_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, session: MediaSession, snapshot: PlaybackSnapshot): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val playIntent = PendingIntent.getService(
            context, 1,
            Intent(context, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_PLAY),
            flags,
        )
        val pauseIntent = PendingIntent.getService(
            context, 2,
            Intent(context, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_PAUSE),
            flags,
        )
        val stopIntent = PendingIntent.getService(
            context, 3,
            Intent(context, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_STOP),
            flags,
        )
        val previousIntent = PendingIntent.getService(
            context, 5,
            Intent(context, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_PREVIOUS),
            flags,
        )
        val nextIntent = PendingIntent.getService(
            context, 6,
            Intent(context, MediaPlaybackService::class.java).setAction(MediaPlaybackService.ACTION_NEXT),
            flags,
        )
        val contentIntent = PendingIntent.getActivity(
            context, 4,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )

        val actions = mutableListOf<Notification.Action>()
        val compact = mutableListOf<Int>()
        if (snapshot.canPrev) {
            compact.add(actions.size)
            actions.add(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_previous,
                    context.getString(R.string.notification_previous),
                    previousIntent,
                ).build()
            )
        }

        compact.add(actions.size)
        actions.add(
            Notification.Action.Builder(
                if (snapshot.playing) {
                    android.R.drawable.ic_media_pause
                } else {
                    android.R.drawable.ic_media_play
                },
                context.getString(
                    if (snapshot.playing) R.string.notification_pause else R.string.notification_play
                ),
                if (snapshot.playing) pauseIntent else playIntent,
            ).build()
        )

        if (snapshot.canNext) {
            compact.add(actions.size)
            actions.add(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next,
                    context.getString(R.string.notification_next),
                    nextIntent,
                ).build()
            )
        }
        actions.add(
            Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.notification_stop),
                stopIntent,
            ).build()
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_playback)
            .setContentTitle(
                snapshot.title.ifBlank { context.getString(R.string.notification_unknown_track) }
            )
            .setContentIntent(contentIntent)
            .setDeleteIntent(stopIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(snapshot.playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(*compact.toIntArray())
            )
        if (snapshot.artist.isNotBlank()) builder.setContentText(snapshot.artist)
        actions.forEach { builder.addAction(it) }
        return builder.build()
    }

    fun update(context: Context, notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancel(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
}
