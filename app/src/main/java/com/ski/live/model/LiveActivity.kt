package com.ski.live.model

import org.json.JSONArray
import org.json.JSONObject

enum class Kind { GAME, TIMER, TRACKER, CUSTOM, OTP }

/** Latest known state of a followed game, refreshed from the scoreboard. */
data class GameState(
    val league: String,
    val eventId: String,
    val awayAbbr: String = "",
    val homeAbbr: String = "",
    val awayName: String = "",
    val homeName: String = "",
    val awayScore: String = "",
    val homeScore: String = "",
    val awayColor: String = "",
    val homeColor: String = "",
    val awayLogo: String = "",
    val homeLogo: String = "",
    /** "pre", "in" or "post". */
    val state: String = "pre",
    /** Human status, e.g. "Q4 - 1:41", "Top 5th", "Final", "10/30 - 7:30 PM". */
    val detail: String = "",
    val period: Int = 0,
    val clock: String = "",
    /** Series / round note, e.g. "West 1st Round - Game 5". */
    val note: String = "",
    val lastPlay: String = "",
    val startTime: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("league", league).put("eventId", eventId)
        .put("awayAbbr", awayAbbr).put("homeAbbr", homeAbbr)
        .put("awayName", awayName).put("homeName", homeName)
        .put("awayScore", awayScore).put("homeScore", homeScore)
        .put("awayColor", awayColor).put("homeColor", homeColor)
        .put("awayLogo", awayLogo).put("homeLogo", homeLogo)
        .put("state", state).put("detail", detail).put("period", period)
        .put("clock", clock).put("note", note).put("lastPlay", lastPlay)
        .put("startTime", startTime)

    companion object {
        fun fromJson(o: JSONObject) = GameState(
            league = o.optString("league"),
            eventId = o.optString("eventId"),
            awayAbbr = o.optString("awayAbbr"),
            homeAbbr = o.optString("homeAbbr"),
            awayName = o.optString("awayName"),
            homeName = o.optString("homeName"),
            awayScore = o.optString("awayScore"),
            homeScore = o.optString("homeScore"),
            awayColor = o.optString("awayColor"),
            homeColor = o.optString("homeColor"),
            awayLogo = o.optString("awayLogo"),
            homeLogo = o.optString("homeLogo"),
            state = o.optString("state", "pre"),
            detail = o.optString("detail"),
            period = o.optInt("period"),
            clock = o.optString("clock"),
            note = o.optString("note"),
            lastPlay = o.optString("lastPlay"),
            startTime = o.optLong("startTime"),
        )
    }
}

/** One live activity: a game, timer, tracker, custom card or one-time code. */
data class LiveActivity(
    val id: String,
    val kind: Kind,
    val title: String,
    val text: String = "",
    /** Secondary line, e.g. "7NLR004 · Silver Honda Civic". */
    val detail: String = "",
    val startAt: Long = System.currentTimeMillis(),
    /** Timer end / tracker ETA / code expiry. 0 = none. */
    val endAt: Long = 0L,
    /** Tracker stages; each becomes a segment of the progress bar. */
    val steps: List<String> = emptyList(),
    /** Tracker icon key: car, truck, food, bag, plane, timer, ball, key, star. */
    val icon: String = "",
    /** Explicit progress 0..100, or -1 to derive it from time. */
    val progress: Int = -1,
    /** Short text for the status bar chip / pill. */
    val chip: String = "",
    val game: GameState? = null,
    val ended: Boolean = false,
    val endedAt: Long = 0L,
    /** Package that created it through the API, if any. */
    val source: String = "",
) {
    val notificationId: Int get() = 1000 + (id.hashCode() and 0xFFFFF)

    fun timeFraction(now: Long): Float =
        if (endAt <= startAt) 0f
        else ((now - startAt).toFloat() / (endAt - startAt)).coerceIn(0f, 1f)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.name).put("title", title)
        .put("text", text).put("detail", detail)
        .put("startAt", startAt).put("endAt", endAt)
        .put("steps", JSONArray(steps)).put("icon", icon)
        .put("progress", progress).put("chip", chip)
        .put("game", game?.toJson())
        .put("ended", ended).put("endedAt", endedAt).put("source", source)

    companion object {
        fun fromJson(o: JSONObject): LiveActivity? = try {
            val stepsArr = o.optJSONArray("steps")
            LiveActivity(
                id = o.getString("id"),
                kind = Kind.valueOf(o.getString("kind")),
                title = o.optString("title"),
                text = o.optString("text"),
                detail = o.optString("detail"),
                startAt = o.optLong("startAt"),
                endAt = o.optLong("endAt"),
                steps = if (stepsArr == null) emptyList()
                else List(stepsArr.length()) { stepsArr.optString(it) },
                icon = o.optString("icon"),
                progress = o.optInt("progress", -1),
                chip = o.optString("chip"),
                game = o.optJSONObject("game")?.let { GameState.fromJson(it) },
                ended = o.optBoolean("ended"),
                endedAt = o.optLong("endedAt"),
                source = o.optString("source"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
