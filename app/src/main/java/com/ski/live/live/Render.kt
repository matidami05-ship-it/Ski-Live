package com.ski.live.live

import android.graphics.Color
import com.ski.live.R
import com.ski.live.model.Kind
import com.ski.live.model.LiveActivity
import com.ski.live.sports.Leagues
import com.ski.live.sports.gameFraction
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil

const val ACCENT = 0xFF3D8BFF.toInt()
const val POINT = 0xFFFFFFFF.toInt()

/**
 * What an activity looks like right now. One source of truth used by the
 * notification (lock screen / Now Bar / status chip), the in-app preview and the pill.
 */
data class Render(
    val title: String,
    val text: String,
    val sub: String? = null,
    /** Status bar chip text (Android 16) and pill right side. */
    val chip: String? = null,
    /** Short label for the pill's left side. */
    val pillLabel: String,
    /** null = no progress bar. */
    val fraction: Float? = null,
    val segments: Int = 1,
    val segmentColors: List<Int> = listOf(ACCENT),
    val pointsAtBoundaries: Boolean = false,
    val trackerIcon: Int = R.drawable.ic_star,
    /** Counts down to this time with a live chronometer. 0 = none. */
    val countdownTo: Long = 0L,
    val bigText: String? = null,
    val ongoing: Boolean = true,
) {
    /** Changes only when something visible changes, so we don't spam updates. */
    fun signature(): Int = listOf(
        title, text, sub, chip, fraction?.let { (it * 200).toInt() }, segments, countdownTo, bigText, ongoing,
    ).hashCode()
}

fun iconFor(key: String): Int = when (key) {
    "car" -> R.drawable.ic_car
    "truck" -> R.drawable.ic_truck
    "food" -> R.drawable.ic_food
    "bag" -> R.drawable.ic_bag
    "plane" -> R.drawable.ic_plane
    "timer" -> R.drawable.ic_timer
    "ball" -> R.drawable.ic_ball
    "key" -> R.drawable.ic_key
    else -> R.drawable.ic_star
}

private fun clock(t: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))

private fun minutesLeft(endAt: Long, now: Long) = ceil((endAt - now) / 60000.0).toInt().coerceAtLeast(0)

private fun parseColor(hex: String): Int? = try {
    if (hex.isBlank()) null else Color.parseColor("#" + hex.removePrefix("#"))
} catch (_: Exception) {
    null
}

/** Team colors that are too dark to see on a dark card fall back to the accent. */
private fun visible(c: Int?): Int {
    if (c == null) return ACCENT
    val lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255
    return if (lum < 0.18) ACCENT else c
}

fun render(a: LiveActivity, now: Long = System.currentTimeMillis()): Render = when (a.kind) {
    Kind.GAME -> renderGame(a)
    Kind.TIMER -> {
        val done = a.ended || (a.endAt in 1..now)
        Render(
            title = a.title.ifBlank { "Timer" },
            text = if (done) "Time's up" else "Ends at ${clock(a.endAt)}",
            sub = a.detail.ifBlank { null },
            pillLabel = a.title.ifBlank { "Timer" },
            chip = if (done) "Done" else null,
            fraction = if (done) 1f else a.timeFraction(now),
            trackerIcon = R.drawable.ic_timer,
            countdownTo = if (done) 0L else a.endAt,
            ongoing = !a.ended,
        )
    }
    Kind.TRACKER -> {
        val steps = a.steps.ifEmpty { listOf("Started", "On the way", "Arriving") }
        val f = if (a.progress >= 0) a.progress / 100f else a.timeFraction(now)
        val idx = (f * steps.size).toInt().coerceIn(0, steps.size - 1)
        val left = if (a.endAt > now) minutesLeft(a.endAt, now) else -1
        val stage = if (f >= 1f) steps.last() else steps[idx]
        Render(
            title = a.title.ifBlank { "Tracker" },
            text = if (left > 0) "$stage · $left min" else stage,
            sub = a.detail.ifBlank { null },
            chip = if (left > 0) "$left min" else stage.take(12),
            pillLabel = a.title.ifBlank { "Tracker" },
            fraction = f,
            segments = steps.size,
            pointsAtBoundaries = steps.size > 1,
            trackerIcon = iconFor(a.icon.ifBlank { "car" }),
            ongoing = !a.ended,
        )
    }
    Kind.OTP -> Render(
        title = a.chip,
        text = a.title.ifBlank { "Verification code" },
        sub = a.detail.ifBlank { null },
        chip = a.chip,
        pillLabel = a.title.ifBlank { "Code" },
        trackerIcon = R.drawable.ic_key,
        ongoing = !a.ended,
    )
    Kind.CUSTOM -> {
        val f: Float? = when {
            a.progress >= 0 -> a.progress / 100f
            a.endAt > 0 -> a.timeFraction(now)
            else -> null
        }
        Render(
            title = a.title.ifBlank { "Live" },
            text = a.text,
            sub = a.detail.ifBlank { null },
            chip = a.chip.ifBlank { null },
            pillLabel = a.title.ifBlank { "Live" },
            fraction = f,
            segments = a.steps.size.coerceAtLeast(1),
            pointsAtBoundaries = a.steps.size > 1,
            trackerIcon = iconFor(a.icon),
            countdownTo = if (a.endAt > now && a.progress < 0) a.endAt else 0L,
            ongoing = !a.ended,
        )
    }
}

private fun renderGame(a: LiveActivity): Render {
    val g = a.game ?: return Render(title = a.title, text = a.text, pillLabel = a.title)
    val league = Leagues.byKey(g.league)
    val a1 = g.awayAbbr.ifBlank { g.awayName }
    val h1 = g.homeAbbr.ifBlank { g.homeName }
    val awayN = g.awayScore.toIntOrNull() ?: 0
    val homeN = g.homeScore.toIntOrNull() ?: 0
    val leaderColor = visible(
        when {
            awayN > homeN -> parseColor(g.awayColor)
            homeN > awayN -> parseColor(g.homeColor)
            else -> null
        },
    )
    val note = g.note.ifBlank { null }
    return when (g.state) {
        "pre" -> Render(
            title = "$a1 @ $h1",
            text = g.detail,
            sub = note ?: league.name,
            pillLabel = "$a1 @ $h1",
            chip = if (g.startTime > 0) clock(g.startTime) else null,
            fraction = 0f,
            segments = league.periods,
            pointsAtBoundaries = true,
            trackerIcon = R.drawable.ic_ball,
            ongoing = !a.ended,
        )
        else -> {
            val final = g.state == "post"
            Render(
                title = "$a1 ${g.awayScore}  –  ${g.homeScore} $h1",
                text = if (final) "Final" + (note?.let { " · $it" } ?: "") else g.detail,
                sub = if (final) league.name else (note ?: league.name),
                chip = "${g.awayScore}-${g.homeScore}",
                pillLabel = "$a1 ${g.awayScore}-${g.homeScore} $h1",
                fraction = gameFraction(g, league),
                segments = league.periods,
                segmentColors = listOf(leaderColor),
                pointsAtBoundaries = true,
                trackerIcon = R.drawable.ic_ball,
                bigText = g.lastPlay.ifBlank { null }?.let { "${g.detail}\n$it" },
                ongoing = !final && !a.ended,
            )
        }
    }
}
