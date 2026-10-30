package com.ski.live.notif

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ski.live.data.Store
import com.ski.live.island.Pill
import com.ski.live.live.LiveNotifier
import com.ski.live.live.LiveService
import com.ski.live.live.copyCode
import com.ski.live.model.Kind
import com.ski.live.model.LiveActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Another app's live notification (ride, navigation, call, timer...) shown in the pill. */
data class Mirrored(
    val key: String,
    val pkg: String,
    val app: String,
    val icon: Drawable?,
    val title: String,
    val text: String,
    val chip: String?,
    val fraction: Float?,
    /** Chronometer base time, or 0. */
    val chronoAt: Long,
    val countDown: Boolean,
    val color: Int,
    val intent: PendingIntent?,
    val postTime: Long,
)

/** A new notification flashed briefly in the pill. */
data class Flash(
    val key: String,
    val app: String,
    val icon: Drawable?,
    val title: String,
    val text: String,
    val intent: PendingIntent?,
    val until: Long,
)

object Mirror {
    private val _items = MutableStateFlow<Map<String, Mirrored>>(emptyMap())
    val items: StateFlow<Map<String, Mirrored>> = _items

    private val _flash = MutableStateFlow<Flash?>(null)
    val flash: StateFlow<Flash?> = _flash

    fun put(m: Mirrored) {
        _items.value = _items.value + (m.key to m)
    }

    fun remove(key: String) {
        if (key in _items.value) _items.value = _items.value - key
        if (_flash.value?.key == key) _flash.value = null
    }

    fun clear() {
        _items.value = emptyMap()
    }

    fun flash(f: Flash?) {
        _flash.value = f
    }

    fun isAccessGranted(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, NotificationWatcher::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

/**
 * Watches every app's notifications (on-device only) to feed the pill:
 * incoming alerts, other apps' live notifications, and one-time codes.
 */
class NotificationWatcher : NotificationListenerService() {

    private val seen = HashMap<String, Int>()
    private var lastCode = ""
    private var lastCodeAt = 0L

    override fun onListenerConnected() {
        Store.init(this)
        Mirror.clear()
        try {
            activeNotifications?.forEach { handle(it, null, initial = true) }
        } catch (_: Exception) {
        }
        Pill.refresh(this)
    }

    override fun onListenerDisconnected() {
        Mirror.clear()
        Pill.refresh(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap?) {
        handle(sbn, rankingMap, initial = false)
        Pill.refresh(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        seen.remove(sbn.key)
        Mirror.remove(sbn.key)
        Pill.refresh(this)
    }

    private fun handle(sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?, initial: Boolean) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val s = Store.settings.value
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = lastMessage(n)
            ?: (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val sig = (title + "\u0000" + text).hashCode()
        val changed = seen[sbn.key] != sig
        seen[sbn.key] = sig

        // One-time codes
        if (s.otp && changed && !initial) {
            Otp.find("$title\n$text")?.let { onCode(it, appName(sbn.packageName), title) }
        }

        if (isLive(n, title)) {
            if (s.mirrorLive) Mirror.put(mirror(sbn, n, title, text)) else Mirror.remove(sbn.key)
            return
        }
        Mirror.remove(sbn.key)

        // Flash new, non-silent notifications.
        if (!s.flashNotifications || initial || !changed || sbn.isOngoing) return
        if (n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && seen.containsKey(sbn.key) && !changed) return
        val r = NotificationListenerService.Ranking()
        if (ranking != null && ranking.getRanking(sbn.key, r) && r.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        Mirror.flash(
            Flash(
                key = sbn.key,
                app = appName(sbn.packageName),
                icon = appIcon(sbn.packageName),
                title = title.ifBlank { appName(sbn.packageName) },
                text = text,
                intent = n.contentIntent,
                until = System.currentTimeMillis() + 5_000,
            ),
        )
    }

    private fun onCode(code: String, app: String, title: String) {
        val now = System.currentTimeMillis()
        if (code == lastCode && now - lastCodeAt < 120_000) return
        lastCode = code
        lastCodeAt = now
        if (Store.settings.value.otpAutoCopy) copyCode(this, code)
        val a = LiveActivity(
            id = "otp-$code",
            kind = Kind.OTP,
            title = app,
            detail = title.takeIf { it != app }.orEmpty(),
            chip = code,
            icon = "key",
            endAt = now + 3 * 60_000L,
        )
        Store.upsert(a)
        getSystemService(NotificationManager::class.java).notify(a.notificationId, LiveNotifier.build(this, a))
        LiveService.kick(this)
    }

    /** Ongoing notifications that are really "live": progress, timers, rides, navigation, calls. */
    private fun isLive(n: Notification, title: String): Boolean {
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return false
        if (title.isBlank()) return false
        val ex = n.extras
        val template = ex.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        if ("MediaStyle" in template) return false
        val cat = n.category
        if (cat == Notification.CATEGORY_TRANSPORT || cat == Notification.CATEGORY_SERVICE ||
            cat == Notification.CATEGORY_SYSTEM
        ) return false
        val liveCats = setOf(
            Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_CALL,
            Notification.CATEGORY_STOPWATCH, Notification.CATEGORY_WORKOUT,
            Notification.CATEGORY_LOCATION_SHARING, Notification.CATEGORY_EVENT,
        )
        return "ProgressStyle" in template ||
            ex.containsKey(SHORT_CRITICAL_TEXT) ||
            ex.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 ||
            ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) ||
            cat in liveCats
    }

    private fun mirror(sbn: StatusBarNotification, n: Notification, title: String, text: String): Mirrored {
        val ex = n.extras
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX)
        val prog = ex.getInt(Notification.EXTRA_PROGRESS)
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        return Mirrored(
            key = sbn.key,
            pkg = sbn.packageName,
            app = appName(sbn.packageName),
            icon = appIcon(sbn.packageName),
            title = title,
            text = text,
            chip = ex.getCharSequence(SHORT_CRITICAL_TEXT)?.toString()
                ?: if (max > 0 && !chrono) "${(prog * 100 / max).coerceIn(0, 100)}%" else null,
            fraction = if (max > 0) (prog.toFloat() / max).coerceIn(0f, 1f) else null,
            chronoAt = if (chrono) n.`when` else 0L,
            countDown = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            color = n.color,
            intent = n.contentIntent,
            postTime = sbn.postTime,
        )
    }

    private fun lastMessage(n: Notification): String? {
        val msgs = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = msgs.lastOrNull() as? android.os.Bundle ?: return null
        return last.getCharSequence("text")?.toString()
    }

    private val names = HashMap<String, String>()
    private fun appName(pkg: String): String = names.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }
    }

    private fun appIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Notification.EXTRA_SHORT_CRITICAL_TEXT on Android 16. */
        private const val SHORT_CRITICAL_TEXT = "android.shortCriticalText"
    }
}
