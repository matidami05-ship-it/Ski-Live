package com.ski.live.live

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.ski.live.R
import com.ski.live.data.Store
import com.ski.live.island.Pill
import com.ski.live.model.Kind
import com.ski.live.model.LiveActivity
import com.ski.live.sports.Espn
import com.ski.live.sports.Leagues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps live activities fresh while any are running: polls scores, moves trackers
 * and timers along, ends finished ones. Its foreground notification IS the first
 * live activity, so it adds no extra notification of its own.
 */
class LiveService : Service() {

    companion object {
        const val ACTION_START = "com.ski.live.START"
        const val ACTION_END = "com.ski.live.END"
        const val ACTION_EXTEND = "com.ski.live.EXTEND"
        const val ACTION_COPY = "com.ski.live.COPY"
        const val EXTRA_ID = "id"
        const val EXTRA_MINUTES = "minutes"
        private const val PLACEHOLDER_ID = 1
        private const val TAG = "SkiLive"

        /** Start (or nudge) the service. Falls back to an exact alarm if Android blocks a background start. */
        fun kick(ctx: Context, retry: Boolean = true) {
            try {
                ctx.startForegroundService(Intent(ctx, LiveService::class.java).setAction(ACTION_START))
            } catch (e: Exception) {
                Log.w(TAG, "Background start blocked", e)
                if (retry) Scheduler.kickSoon(ctx)
            }
        }

        fun send(ctx: Context, action: String, id: String, minutes: Int = 0) {
            try {
                ctx.startForegroundService(
                    Intent(ctx, LiveService::class.java).setAction(action)
                        .putExtra(EXTRA_ID, id).putExtra(EXTRA_MINUTES, minutes),
                )
            } catch (e: Exception) {
                Log.w(TAG, "send failed", e)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var fgId = 0
    private var lastPoll = 0L
    private val posted = HashMap<String, Int>()
    private val icons = HashMap<String, Bitmap?>()
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        handle(intent)
        ensureForeground()
        if (loop == null) loop = scope.launch { runLoop() }
        wake.trySend(Unit)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Pill.refresh(this)
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_ID) ?: return
        val now = System.currentTimeMillis()
        when (intent.action) {
            ACTION_END -> {
                Store.get(id)?.game?.let { Store.dismissEvent(it.eventId) }
                Store.remove(id)
            }
            ACTION_EXTEND -> {
                val mins = intent.getIntExtra(EXTRA_MINUTES, 5)
                Store.update(id) {
                    it.copy(endAt = maxOf(it.endAt, now) + mins * 60_000L, ended = false, endedAt = 0)
                }
                Store.get(id)?.let { if (it.kind == Kind.TIMER) Scheduler.timer(this, it) }
            }
            ACTION_COPY -> {
                Store.get(id)?.let { copyCode(this, it.chip) }
                Store.remove(id)
            }
        }
    }

    private fun startFg(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, n)
        }
        fgId = id
    }

    /** Must run on every start: Android requires startForeground() within seconds. */
    private fun ensureForeground() {
        val first = Store.activities.value.firstOrNull { !it.ended }
        if (first != null) {
            startFg(first.notificationId, LiveNotifier.build(this, first, icons[first.id]))
            posted[first.id] = 0 // force a full repost with icons on the next tick
        } else {
            val n = Notification.Builder(this, LiveNotifier.CHANNEL_LIVE)
                .setSmallIcon(R.drawable.ic_stat_live)
                .setContentTitle("Ski Live")
                .build()
            startFg(PLACEHOLDER_ID, n)
        }
    }

    private suspend fun runLoop() {
        while (true) {
            val keepGoing = try {
                tick()
            } catch (e: Exception) {
                Log.e(TAG, "tick failed", e)
                true
            }
            if (!keepGoing) break
            withTimeoutOrNull(5_000L) { wake.receive() }
        }
        loop = null
    }

    /** One update pass. Returns false once nothing is live and the service has stopped. */
    private suspend fun tick(): Boolean {
        val now = System.currentTimeMillis()

        // 1. Scores
        val games = Store.activities.value.filter { it.kind == Kind.GAME && !it.ended && it.game != null }
        val anyLive = games.any { it.game?.state == "in" }
        val pollEvery = if (anyLive) 15_000L else 60_000L
        if (games.isNotEmpty() && now - lastPoll >= pollEvery) {
            lastPoll = now
            for (leagueKey in games.mapNotNull { it.game?.league }.distinct()) {
                val fresh = try {
                    Espn.scoreboard(Leagues.byKey(leagueKey)).associateBy { it.eventId }
                } catch (e: Exception) {
                    Log.w(TAG, "scores failed for $leagueKey", e)
                    continue
                }
                Store.replaceAll { list ->
                    list.map { a ->
                        val g = a.game
                        val newG = if (g != null && g.league == leagueKey) fresh[g.eventId] else null
                        if (a.kind != Kind.GAME || newG == null || a.ended) a
                        else {
                            val final = newG.state == "post"
                            a.copy(game = newG, ended = final, endedAt = if (final) now else 0L)
                        }
                    }
                }
            }
        }

        // 2. Timers, codes, trackers, cleanup
        val finishedTimers = ArrayList<LiveActivity>()
        Store.replaceAll { list ->
            list.mapNotNull { a ->
                when {
                    a.kind == Kind.OTP && a.endAt in 1..now -> null
                    a.kind == Kind.TIMER && !a.ended && a.endAt in 1..now -> {
                        finishedTimers += a
                        a.copy(ended = true, endedAt = now)
                    }
                    a.kind == Kind.TRACKER && !a.ended && a.endAt > 0 && now > a.endAt + 30 * 60_000L ->
                        a.copy(ended = true, endedAt = now)
                    a.ended && now - a.endedAt > keepEndedFor(a) -> null
                    else -> a
                }
            }
        }
        finishedTimers.forEach { LiveNotifier.timerDone(this, it) }

        // 3. Notifications
        val list = Store.activities.value
        val target = list.firstOrNull { !it.ended }
        if (fgId != 0 && fgId != target?.notificationId) {
            val stillShown = list.any { it.notificationId == fgId }
            stopForeground(if (stillShown) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
            if (!stillShown) nm.cancel(fgId)
            fgId = 0
        }
        if (target != null && fgId == 0) {
            startFg(target.notificationId, LiveNotifier.build(this, target, icons[target.id]))
            posted[target.id] = 0
        }
        for (a in list) {
            if (a.game != null && !icons.containsKey(a.id)) icons[a.id] = LiveNotifier.largeIcon(a)
            val sig = render(a, now).signature() * 31 + a.ended.hashCode()
            if (posted[a.id] != sig) {
                nm.notify(a.notificationId, LiveNotifier.build(this, a, icons[a.id]))
                posted[a.id] = sig
            }
        }
        val ids = list.map { it.id }.toSet()
        posted.keys.filter { it !in ids }.forEach { gone ->
            nm.cancel(LiveActivity(gone, Kind.CUSTOM, "").notificationId)
            posted.remove(gone)
            icons.remove(gone)
        }

        Pill.refresh(this)

        // 4. Nothing live any more: stop, leaving finished cards (e.g. "Final") in place.
        if (target == null) {
            if (fgId != 0) stopForeground(STOP_FOREGROUND_DETACH)
            if (fgId == PLACEHOLDER_ID) nm.cancel(PLACEHOLDER_ID)
            fgId = 0
            if (list.isNotEmpty()) Scheduler.cleanup(this)
            stopSelf()
            return false
        }
        return true
    }

    private fun keepEndedFor(a: LiveActivity): Long = when (a.kind) {
        Kind.GAME -> 30 * 60_000L
        Kind.TIMER -> 5 * 60_000L
        else -> 10 * 60_000L
    }
}

fun copyCode(ctx: Context, code: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Code", code))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied $code", Toast.LENGTH_SHORT).show()
}
