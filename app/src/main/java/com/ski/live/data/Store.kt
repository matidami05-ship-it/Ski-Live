package com.ski.live.data

import android.content.Context
import android.content.SharedPreferences
import com.ski.live.model.LiveActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

enum class PillPosition { LEFT, CENTER, RIGHT }

data class Settings(
    /** Show the floating pill at the top of the screen. */
    val pill: Boolean = true,
    val pillPosition: PillPosition = PillPosition.LEFT,
    /** Flash other apps' new notifications in the pill. */
    val flashNotifications: Boolean = true,
    /** Show other apps' live/ongoing notifications (rides, navigation, timers) in the pill. */
    val mirrorLive: Boolean = true,
    /** Detect one-time codes in notifications. */
    val otp: Boolean = true,
    /** Put detected codes straight on the clipboard. */
    val otpAutoCopy: Boolean = false,
)

/** Everything Ski Live keeps: active activities, favorite teams and settings. Local only. */
object Store {
    private lateinit var prefs: SharedPreferences
    private val lock = Any()

    private val _activities = MutableStateFlow<List<LiveActivity>>(emptyList())
    val activities: StateFlow<List<LiveActivity>> = _activities

    /** "league|ABBR", e.g. "nba|MIN". */
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("ski_live", Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString("activities", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        _activities.value = List(arr.length()) { arr.optJSONObject(it) }
            .mapNotNull { it?.let(LiveActivity::fromJson) }
        _favorites.value = prefs.getStringSet("favorites", emptySet())!!.toSet()
        _settings.value = Settings(
            pill = prefs.getBoolean("pill", true),
            pillPosition = runCatching {
                PillPosition.valueOf(prefs.getString("pillPosition", "LEFT")!!)
            }.getOrDefault(PillPosition.LEFT),
            flashNotifications = prefs.getBoolean("flash", true),
            mirrorLive = prefs.getBoolean("mirror", true),
            otp = prefs.getBoolean("otp", true),
            otpAutoCopy = prefs.getBoolean("otpAutoCopy", false),
        )
    }

    fun get(id: String): LiveActivity? = _activities.value.firstOrNull { it.id == id }

    fun upsert(a: LiveActivity) = edit { list ->
        val i = list.indexOfFirst { it.id == a.id }
        if (i >= 0) list.toMutableList().also { it[i] = a } else list + a
    }

    fun update(id: String, f: (LiveActivity) -> LiveActivity) = edit { list ->
        list.map { if (it.id == id) f(it) else it }
    }

    fun remove(id: String) = edit { list -> list.filterNot { it.id == id } }

    fun replaceAll(f: (List<LiveActivity>) -> List<LiveActivity>) = edit(f)

    private fun edit(f: (List<LiveActivity>) -> List<LiveActivity>) {
        synchronized(lock) {
            val next = f(_activities.value)
            _activities.value = next
            prefs.edit().putString("activities", JSONArray(next.map { it.toJson() }).toString()).apply()
        }
    }

    fun toggleFavorite(league: String, abbr: String) {
        val key = "$league|$abbr"
        val next = _favorites.value.let { if (key in it) it - key else it + key }
        _favorites.value = next
        prefs.edit().putStringSet("favorites", next).apply()
    }

    fun isFavorite(league: String, abbr: String) = "$league|$abbr" in _favorites.value

    /** Games the user ended by hand, so auto-follow doesn't bring them back. */
    fun dismissEvent(eventId: String) {
        val set = prefs.getStringSet("dismissed", emptySet())!!.toMutableSet()
        set += eventId
        // keep the list small
        val trimmed = if (set.size > 200) set.toList().takeLast(100).toSet() else set
        prefs.edit().putStringSet("dismissed", trimmed).apply()
    }

    fun isDismissed(eventId: String) =
        eventId in prefs.getStringSet("dismissed", emptySet())!!

    fun updateSettings(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        _settings.value = s
        prefs.edit()
            .putBoolean("pill", s.pill)
            .putString("pillPosition", s.pillPosition.name)
            .putBoolean("flash", s.flashNotifications)
            .putBoolean("mirror", s.mirrorLive)
            .putBoolean("otp", s.otp)
            .putBoolean("otpAutoCopy", s.otpAutoCopy)
            .apply()
    }
}
