@file:OptIn(ExperimentalMaterial3Api::class)

package com.ski.live.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ski.live.data.Store
import com.ski.live.live.Scheduler
import com.ski.live.model.GameState
import com.ski.live.model.Kind
import com.ski.live.model.LiveActivity
import com.ski.live.sports.Espn
import com.ski.live.sports.Leagues

@Composable
fun GamesScreen(onBack: () -> Unit) {
    var league by rememberSaveable { mutableStateOf("nba") }
    var games by remember { mutableStateOf<List<GameState>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(league) {
        games = null
        error = null
        try {
            games = Espn.scoreboard(Leagues.byKey(league))
                .sortedBy { when (it.state) { "in" -> 0; "pre" -> 1; else -> 2 } }
        } catch (e: Exception) {
            error = "Couldn't load games. Check your connection."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Follow a game") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Leagues.all) { l ->
                    FilterChip(selected = l.key == league, onClick = { league = l.key }, label = { Text(l.name) })
                }
            }
            when {
                error != null -> Text(error!!, color = Muted, modifier = Modifier.padding(16.dp))
                games == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                games!!.isEmpty() -> Text("No games today.", color = Muted, modifier = Modifier.padding(16.dp))
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(games!!, key = { it.eventId }) { g -> GameRow(g) }
                }
            }
        }
    }
}

@Composable
private fun GameRow(g: GameState) {
    val ctx = LocalContext.current
    val acts by Store.activities.collectAsState()
    val favs by Store.favorites.collectAsState()
    val following = acts.any { it.game?.eventId == g.eventId }
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(14.dp)) {
            TeamLine(g.awayLogo, g.awayName, g.awayScore, g.state != "pre")
            TeamLine(g.homeLogo, g.homeName, g.homeScore, g.state != "pre")
            Text(
                listOf(g.detail, g.note).filter { it.isNotBlank() }.joinToString(" · "),
                color = if (g.state == "in") Green else Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                if (g.state == "post") {
                    Text("Final", color = Muted)
                } else if (following) {
                    OutlinedButton(onClick = {}, enabled = false) { Text("Following") }
                } else {
                    Button(onClick = {
                        Actions.start(
                            ctx,
                            LiveActivity(
                                id = "game-" + g.eventId, kind = Kind.GAME,
                                title = "${g.awayAbbr} @ ${g.homeAbbr}", game = g,
                            ),
                        )
                    }) { Text("Follow live") }
                }
                Spacer(Modifier.weight(1f))
                listOf(g.awayAbbr, g.homeAbbr).forEach { abbr ->
                    val fav = "${g.league}|$abbr" in favs
                    FilterChip(
                        selected = fav,
                        onClick = {
                            Store.toggleFavorite(g.league, abbr)
                            Scheduler.autoFollow(ctx)
                        },
                        label = { Text(abbr) },
                        leadingIcon = {
                            Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, null, Modifier.size(16.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(selectedLeadingIconColor = Color(0xFFFFC83D)),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TeamLine(logo: String, name: String, score: String, showScore: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        AsyncImage(logo, null, Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        if (showScore) Text(score, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
