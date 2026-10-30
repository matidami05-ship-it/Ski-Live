package com.ski.live.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import com.ski.live.R
import com.ski.live.model.Kind
import com.ski.live.model.LiveActivity
import com.ski.live.sports.Logos
import com.ski.live.ui.MainActivity

/** Builds the system notification for an activity — the actual "live activity". */
object LiveNotifier {
    const val CHANNEL_LIVE = "live_updates"
    const val CHANNEL_ALERT = "live_alerts"
    const val CHANNEL_CODES = "codes"
    const val ALERT_ID = 77

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, "Live activities", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Scores, timers and trackers on your lock screen and status bar"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Finished", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When a timer finishes"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CODES, "One-time codes", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Codes found in your messages, with a copy button"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun canPromote(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 36) {
            ctx.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        } else false

    /** Large icon for an activity (team logos for games). */
    suspend fun largeIcon(a: LiveActivity): Bitmap? = a.game?.let { Logos.matchup(it) }

    fun build(ctx: Context, a: LiveActivity, large: Bitmap? = null): Notification {
        val now = System.currentTimeMillis()
        val r = render(a, now)
        val channel = if (a.kind == Kind.OTP) CHANNEL_CODES else CHANNEL_LIVE
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle(r.title)
            .setContentText(r.text)
            .setOngoing(r.ongoing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(ACCENT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(
                when (a.kind) {
                    Kind.GAME -> Notification.CATEGORY_EVENT
                    Kind.TIMER -> Notification.CATEGORY_STOPWATCH
                    Kind.TRACKER -> Notification.CATEGORY_PROGRESS
                    Kind.OTP -> Notification.CATEGORY_MESSAGE
                    Kind.CUSTOM -> Notification.CATEGORY_STATUS
                },
            )
            .setContentIntent(openApp(ctx))
            .setGroup("live_" + a.id) // keep each activity its own card
        r.sub?.let { b.setSubText(it) }
        if (large != null) b.setLargeIcon(large)

        if (r.countdownTo > now) {
            b.setWhen(r.countdownTo)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }

        // Buttons
        if (!a.ended) {
            when (a.kind) {
                Kind.TIMER -> b.addAction(action(ctx, a, LiveService.ACTION_EXTEND, "+1 min", R.drawable.ic_add, 1))
                Kind.TRACKER -> b.addAction(action(ctx, a, LiveService.ACTION_EXTEND, "+5 min", R.drawable.ic_add, 5))
                Kind.OTP -> b.addAction(action(ctx, a, LiveService.ACTION_COPY, "Copy ${a.chip}", R.drawable.ic_copy, 0))
                else -> {}
            }
            b.addAction(action(ctx, a, LiveService.ACTION_END, if (a.kind == Kind.OTP) "Dismiss" else "End", R.drawable.ic_stop, 0))
        }
        // Swiping away an ended card just forgets it; a broadcast, so no service start needed.
        b.setDeleteIntent(
            PendingIntent.getBroadcast(
                ctx, ("swipe" + a.id).hashCode(),
                Intent(ctx, AlarmReceiver::class.java)
                    .setAction(AlarmReceiver.ACTION_SWIPED)
                    .putExtra(LiveService.EXTRA_ID, a.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

        if (Build.VERSION.SDK_INT >= 36) {
            // Android 16 Live Updates: lock screen card, status bar chip, Samsung Now Bar.
            // Notification.EXTRA_REQUEST_PROMOTED_ONGOING
            if (r.ongoing) b.addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            r.chip?.let { b.setShortCriticalText(it) }
            val f = r.fraction
            if (f != null) {
                val n = r.segments.coerceIn(1, 20)
                val len = 1000 / n
                val total = len * n
                val style = Notification.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgressSegments(
                        List(n) { i ->
                            Notification.ProgressStyle.Segment(len)
                                .setColor(r.segmentColors[i % r.segmentColors.size])
                        },
                    )
                    .setProgress((f * total).toInt().coerceIn(0, total))
                    .setProgressTrackerIcon(Icon.createWithResource(ctx, r.trackerIcon))
                if (r.pointsAtBoundaries && n > 1) {
                    style.setProgressPoints(
                        (1 until n).map { Notification.ProgressStyle.Point(it * len).setColor(POINT) },
                    )
                }
                b.setStyle(style)
            } else if (r.bigText != null) {
                b.setStyle(Notification.BigTextStyle().bigText(r.bigText))
            }
        } else {
            r.fraction?.let { b.setProgress(1000, (it * 1000).toInt(), false) }
            r.bigText?.let { b.setStyle(Notification.BigTextStyle().bigText(it)) }
        }
        return b.build()
    }

    fun timerDone(ctx: Context, a: LiveActivity) {
        val n = Notification.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle(a.title.ifBlank { "Timer" })
            .setContentText("Time's up")
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun service(ctx: Context, a: LiveActivity, act: String, minutes: Int): PendingIntent {
        val i = Intent(ctx, LiveService::class.java)
            .setAction(act)
            .putExtra(LiveService.EXTRA_ID, a.id)
            .putExtra(LiveService.EXTRA_MINUTES, minutes)
        return PendingIntent.getForegroundService(
            ctx, (act + a.id).hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun action(ctx: Context, a: LiveActivity, act: String, label: String, icon: Int, minutes: Int) =
        Notification.Action.Builder(Icon.createWithResource(ctx, icon), label, service(ctx, a, act, minutes)).build()
}
