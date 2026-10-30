package com.ski.live.sports

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import com.ski.live.model.GameState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

/** Free public ESPN scoreboard feed; no account or key needed. */
object Espn {

    suspend fun scoreboard(league: League): List<GameState> = withContext(Dispatchers.IO) {
        val body = get("https://site.api.espn.com/apis/site/v2/sports/${league.path}/scoreboard")
        parse(league.key, body)
    }

    fun parse(leagueKey: String, body: String): List<GameState> {
        val root = JSONObject(body)
        val events = root.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<GameState>()
        for (i in 0 until events.length()) {
            try {
                val ev = events.getJSONObject(i)
                val comp = ev.getJSONArray("competitions").getJSONObject(0)
                val comps = comp.getJSONArray("competitors")
                var home: JSONObject? = null
                var away: JSONObject? = null
                for (j in 0 until comps.length()) {
                    val c = comps.getJSONObject(j)
                    if (c.optString("homeAway") == "home") home = c else away = c
                }
                if (home == null || away == null) continue
                val status = ev.optJSONObject("status") ?: comp.optJSONObject("status")
                val type = status?.optJSONObject("type")
                val ht = home.optJSONObject("team") ?: JSONObject()
                val at = away.optJSONObject("team") ?: JSONObject()
                val note = comp.optJSONObject("series")?.optString("summary").orEmpty()
                    .ifBlank { comp.optJSONArray("notes")?.optJSONObject(0)?.optString("headline").orEmpty() }
                out += GameState(
                    league = leagueKey,
                    eventId = ev.optString("id"),
                    awayAbbr = at.optString("abbreviation"),
                    homeAbbr = ht.optString("abbreviation"),
                    awayName = at.optString("shortDisplayName", at.optString("displayName")),
                    homeName = ht.optString("shortDisplayName", ht.optString("displayName")),
                    awayScore = away.optString("score"),
                    homeScore = home.optString("score"),
                    awayColor = at.optString("color"),
                    homeColor = ht.optString("color"),
                    awayLogo = at.optString("logo"),
                    homeLogo = ht.optString("logo"),
                    state = type?.optString("state") ?: "pre",
                    detail = type?.optString("shortDetail").orEmpty(),
                    period = status?.optInt("period") ?: 0,
                    clock = status?.optString("displayClock").orEmpty(),
                    note = note,
                    lastPlay = comp.optJSONObject("situation")?.optJSONObject("lastPlay")
                        ?.optString("text").orEmpty(),
                    startTime = parseTime(ev.optString("date")),
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun parseTime(s: String): Long = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "SkiLive/1.0")
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/** Team logos for notification icons, cached in memory and on disk. */
object Logos {
    private val mem = HashMap<String, Bitmap>()
    var cacheDir: File? = null

    suspend fun get(url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        synchronized(mem) { mem[url] }?.let { return@withContext it }
        val file = cacheDir?.let { File(it, "logo_" + url.hashCode().toUInt() + ".png") }
        var bmp: Bitmap? = file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        if (bmp == null) {
            bmp = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
            val scaled = bmp?.let { Bitmap.createScaledBitmap(it, 128, 128, true) }
            bmp = scaled
            if (scaled != null && file != null) {
                try {
                    file.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } catch (_: Exception) {
                }
            }
        }
        val result = bmp
        if (result != null) synchronized(mem) { mem[url] = result }
        result
    }

    /** Away logo on the left, home logo on the right, in one square icon. */
    suspend fun matchup(g: GameState): Bitmap? {
        val a = get(g.awayLogo)
        val h = get(g.homeLogo)
        if (a == null && h == null) return null
        val out = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        a?.let { c.drawBitmap(it, null, Rect(0, 48, 96, 144), null) }
        h?.let { c.drawBitmap(it, null, Rect(96, 48, 192, 144), null) }
        return out
    }
}
