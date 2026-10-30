package com.ski.live.sports

import com.ski.live.model.GameState

data class League(
    val key: String,
    val name: String,
    /** ESPN path: sport/league. */
    val path: String,
    val periods: Int,
    /** Minutes per period for clocks that count down; 0 for baseball. */
    val periodMinutes: Int,
    /** Soccer clocks count up ("67'"). */
    val clockCountsUp: Boolean = false,
)

object Leagues {
    val all = listOf(
        League("nba", "NBA", "basketball/nba", 4, 12),
        League("nfl", "NFL", "football/nfl", 4, 15),
        League("mlb", "MLB", "baseball/mlb", 9, 0),
        League("nhl", "NHL", "hockey/nhl", 3, 20),
        League("cfb", "College FB", "football/college-football", 4, 15),
        League("cbb", "College BB", "basketball/mens-college-basketball", 2, 20),
        League("wnba", "WNBA", "basketball/wnba", 4, 10),
        League("mls", "MLS", "soccer/usa.1", 2, 45, clockCountsUp = true),
        League("epl", "Premier League", "soccer/eng.1", 2, 45, clockCountsUp = true),
        League("ucl", "Champions League", "soccer/uefa.champions", 2, 45, clockCountsUp = true),
        League("liga", "LaLiga", "soccer/esp.1", 2, 45, clockCountsUp = true),
    )

    fun byKey(key: String): League = all.firstOrNull { it.key == key } ?: all.first()
}

/** How far through the game we are, 0..1, from period + clock. */
fun gameFraction(g: GameState, league: League): Float {
    when (g.state) {
        "pre" -> return 0f
        "post" -> return 1f
    }
    val periods = league.periods
    val p = g.period.coerceIn(1, periods)
    val within: Float = when {
        league.periodMinutes == 0 -> { // baseball: Top / Mid / Bot / End of inning
            val d = g.detail.lowercase()
            when {
                d.startsWith("top") -> 0.25f
                d.startsWith("mid") -> 0.5f
                d.startsWith("bot") -> 0.75f
                d.startsWith("end") -> 1f
                else -> 0.5f
            }
        }
        league.clockCountsUp -> {
            val minute = Regex("\\d+").find(g.clock)?.value?.toIntOrNull() ?: 0
            ((minute - (p - 1) * league.periodMinutes).toFloat() / league.periodMinutes)
        }
        else -> {
            val secs = clockSeconds(g.clock)
            if (secs == null) 0.5f else 1f - secs / (league.periodMinutes * 60f)
        }
    }.coerceIn(0f, 1f)
    if (g.period > periods) return 0.99f // overtime / extra innings
    return ((p - 1) + within) / periods
}

/** "1:41" -> 101s, "34.2" -> 34s, "" -> null. */
fun clockSeconds(clock: String): Float? {
    val c = clock.trim()
    if (c.isEmpty()) return null
    return if (':' in c) {
        val parts = c.split(':')
        val m = parts[0].toFloatOrNull() ?: return null
        val s = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
        m * 60 + s
    } else c.toFloatOrNull()
}
