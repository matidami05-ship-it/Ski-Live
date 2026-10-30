package com.ski.live.live

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ski.live.data.Store
import com.ski.live.model.Kind
import com.ski.live.model.LiveActivity
import com.ski.live.sports.Espn
import com.ski.live.sports.Leagues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/** Exact alarms: timer finish, retrying a blocked start, auto-follow checks, cleanup. */
object Scheduler {
    private fun pi(ctx: Context, action: String, code: Int, id: String? = null): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, AlarmReceiver::class.java).setAction(action).apply { id?.let { putExtra(LiveService.EXTRA_ID, it) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun at(ctx: Context, time: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
    }

    fun timer(ctx: Context, a: LiveActivity) {
        if (a.endAt > 0) at(ctx, a.endAt, pi(ctx, AlarmReceiver.ACTION_TIMER, ("t" + a.id).hashCode(), a.id))
    }

    fun kickSoon(ctx: Context) = at(ctx, System.currentTimeMillis() + 1_000, pi(ctx, AlarmReceiver.ACTION_KICK, 2))

    fun cleanup(ctx: Context) = at(ctx, System.currentTimeMillis() + 11 * 60_000L, pi(ctx, AlarmReceiver.ACTION_CLEANUP, 3))

    /** Check favorite teams every 10 minutes while there are any. */
    fun autoFollow(ctx: Context) {
        val p = pi(ctx, AlarmReceiver.ACTION_AUTO, 4)
        if (Store.favorites.value.isEmpty()) {
            ctx.getSystemService(AlarmManager::class.java).cancel(p)
        } else {
            at(ctx, System.currentTimeMillis() + 10 * 60_000L, p)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TIMER = "com.ski.live.alarm.TIMER"
        const val ACTION_KICK = "com.ski.live.alarm.KICK"
        const val ACTION_AUTO = "com.ski.live.alarm.AUTO"
        const val ACTION_CLEANUP = "com.ski.live.alarm.CLEANUP"
        const val ACTION_SWIPED = "com.ski.live.alarm.SWIPED"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        val now = System.currentTimeMillis()
        when (intent.action) {
            ACTION_KICK -> LiveService.kick(ctx, retry = false)
            ACTION_TIMER -> {
                val id = intent.getStringExtra(LiveService.EXTRA_ID) ?: return
                val a = Store.get(id) ?: return
                if (!a.ended && a.endAt <= now + 1_000) {
                    val done = a.copy(ended = true, endedAt = now)
                    Store.upsert(done)
                    LiveNotifier.timerDone(ctx, done)
                    ctx.getSystemService(NotificationManager::class.java)
                        .notify(done.notificationId, LiveNotifier.build(ctx, done))
                    LiveService.kick(ctx)
                }
            }
            ACTION_SWIPED -> {
                val id = intent.getStringExtra(LiveService.EXTRA_ID) ?: return
                Store.get(id)?.game?.let { Store.dismissEvent(it.eventId) }
                Store.remove(id)
                if (Store.activities.value.any { !it.ended }) LiveService.kick(ctx)
            }
            ACTION_CLEANUP -> {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                Store.replaceAll { list ->
                    list.filter { a ->
                        val stale = a.ended && now - a.endedAt > 9 * 60_000L
                        if (stale) nm.cancel(a.notificationId)
                        !stale
                    }
                }
            }
            ACTION_AUTO -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (AutoFollow.check(ctx) > 0) LiveService.kick(ctx)
                    } catch (e: Exception) {
                        Log.w("SkiLive", "auto-follow failed", e)
                    } finally {
                        Scheduler.autoFollow(ctx)
                        pending.finish()
                    }
                }
            }
        }
    }
}

/** Starts a live activity on its own when a favorite team's game tips off. */
object AutoFollow {
    suspend fun check(ctx: Context): Int {
        val favs = Store.favorites.value
        if (favs.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var started = 0
        val following = Store.activities.value.mapNotNull { it.game?.eventId }.toSet()
        for (leagueKey in favs.map { it.substringBefore('|') }.distinct()) {
            val games = try {
                Espn.scoreboard(Leagues.byKey(leagueKey))
            } catch (_: Exception) {
                continue
            }
            for (g in games) {
                val fav = Store.isFavorite(leagueKey, g.homeAbbr) || Store.isFavorite(leagueKey, g.awayAbbr)
                val soon = g.state == "in" || (g.state == "pre" && g.startTime > 0 && g.startTime - now < 15 * 60_000L)
                if (fav && soon && g.eventId !in following && !Store.isDismissed(g.eventId)) {
                    Store.upsert(
                        LiveActivity(
                            id = "game-" + g.eventId, kind = Kind.GAME,
                            title = "${g.awayAbbr} @ ${g.homeAbbr}", game = g,
                        ),
                    )
                    started++
                }
            }
        }
        return started
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        val now = System.currentTimeMillis()
        Store.activities.value.filter { it.kind == Kind.TIMER && !it.ended && it.endAt > now }
            .forEach { Scheduler.timer(ctx, it) }
        Scheduler.autoFollow(ctx)
        if (Store.activities.value.any { !it.ended }) LiveService.kick(ctx)
    }
}

/**
 * Public API for other apps. Example:
 *   adb shell am broadcast -a com.ski.live.action.UPDATE -n com.ski.live/.live.ApiReceiver \
 *     --es id pizza --es kind tracker --es title "Pizza" --ei eta_minutes 25 --es steps "Ordered,Baking,On the way,Delivered"
 */
class ApiReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        val id = intent.getStringExtra("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val fullId = "api-$id"
        when (intent.action) {
            "com.ski.live.action.END" -> {
                Store.remove(fullId)
                ctx.getSystemService(NotificationManager::class.java)
                    .cancel(LiveActivity(fullId, Kind.CUSTOM, "").notificationId)
                LiveService.kick(ctx)
            }
            "com.ski.live.action.UPDATE" -> {
                val now = System.currentTimeMillis()
                val old = Store.get(fullId)
                val kind = when (intent.getStringExtra("kind")?.lowercase()) {
                    "timer" -> Kind.TIMER
                    "tracker" -> Kind.TRACKER
                    else -> old?.kind ?: Kind.CUSTOM
                }
                val eta = intent.getIntExtra("eta_minutes", -1)
                val endAt = when {
                    intent.hasExtra("end_at") -> intent.getLongExtra("end_at", 0L)
                    eta >= 0 -> now + eta * 60_000L
                    else -> old?.endAt ?: 0L
                }
                val a = LiveActivity(
                    id = fullId,
                    kind = kind,
                    title = intent.getStringExtra("title") ?: old?.title ?: "Live",
                    text = intent.getStringExtra("text") ?: old?.text ?: "",
                    detail = intent.getStringExtra("detail") ?: old?.detail ?: "",
                    startAt = old?.startAt ?: now,
                    endAt = endAt,
                    steps = intent.getStringExtra("steps")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                        ?: old?.steps ?: emptyList(),
                    icon = intent.getStringExtra("icon") ?: old?.icon ?: "",
                    progress = intent.getIntExtra("progress", old?.progress ?: -1),
                    chip = intent.getStringExtra("chip") ?: old?.chip ?: "",
                    source = intent.getStringExtra("source") ?: old?.source ?: "",
                )
                Store.upsert(a)
                // Post right away; the service takes over keeping it fresh.
                ctx.getSystemService(NotificationManager::class.java)
                    .notify(a.notificationId, LiveNotifier.build(ctx, a))
                if (a.kind == Kind.TIMER) Scheduler.timer(ctx, a)
                LiveService.kick(ctx)
            }
        }
    }
}
