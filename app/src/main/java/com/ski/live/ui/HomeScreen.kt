@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ski.live.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil.compose.AsyncImage
import com.ski.live.data.PillPosition
import com.ski.live.data.Store
import com.ski.live.island.Pill
import com.ski.live.live.LiveNotifier
import com.ski.live.live.Render
import com.ski.live.live.render
import com.ski.live.model.Kind
import com.ski.live.model.LiveActivity
import com.ski.live.notif.Mirror
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(open: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val acts by Store.activities.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Ski Live", fontWeight = FontWeight.Bold) })
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { SetupCard() }
            item { StartRow(open) }
            if (acts.isEmpty()) {
                item {
                    Text(
                        "Nothing live yet. Follow a game, start a timer or track a ride — " +
                            "it shows on your lock screen, the status bar chip and the pill.",
                        color = Muted,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(acts.sortedBy { it.ended }, key = { it.id }) { a ->
                LiveCard(a, now)
            }
            item { PillSettings() }
            item { Favorites() }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun StartRow(open: (Screen) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Start a live activity", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StartButton("Game", Icons.Filled.SportsBasketball, Modifier.weight(1f)) { open(Screen.Games) }
            StartButton("Timer", Icons.Filled.Timer, Modifier.weight(1f)) { open(Screen.Create(Kind.TIMER)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StartButton("Ride / order", Icons.Filled.TwoWheeler, Modifier.weight(1f)) { open(Screen.Create(Kind.TRACKER)) }
            StartButton("Custom", Icons.Filled.Tune, Modifier.weight(1f)) { open(Screen.Create(Kind.CUSTOM)) }
        }
    }
}

@Composable
private fun StartButton(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(56.dp), shape = RoundedCornerShape(16.dp)) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

// ---------- the live card preview (looks like the lock screen) ----------

@Composable
fun LiveCard(a: LiveActivity, now: Long) {
    val ctx = LocalContext.current
    val r = render(a, now)
    val tint = Color(r.segmentColors.first())
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(r.trackerIcon), null, tint = tint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(kindLabel(a), color = Muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                val chip = if (r.countdownTo > now) hms(r.countdownTo - now) else r.chip
                if (chip != null) {
                    Text(chip, color = if (a.kind == Kind.OTP) Green else tint, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            val g = a.game
            if (g != null && g.state != "pre") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(g.awayLogo, null, Modifier.size(36.dp))
                    Text(
                        "${g.awayScore}  –  ${g.homeScore}",
                        fontSize = 30.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    AsyncImage(g.homeLogo, null, Modifier.size(36.dp))
                }
                Row {
                    Text(g.awayAbbr, color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(g.homeAbbr, color = Muted, fontSize = 12.sp)
                }
            } else {
                Text(r.title, fontSize = if (a.kind == Kind.OTP) 30.sp else 20.sp, fontWeight = FontWeight.Bold)
            }
            if (r.text.isNotBlank()) Text(r.text, color = Color.White.copy(alpha = 0.85f))
            r.sub?.let { Text(it, color = Muted, fontSize = 13.sp) }
            if (g != null && g.lastPlay.isNotBlank() && g.state == "in") {
                Text(g.lastPlay, color = Muted, fontSize = 12.sp, maxLines = 2)
            }
            r.fraction?.let {
                Spacer(Modifier.height(12.dp))
                SegmentBar(r, it)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!a.ended) {
                    when (a.kind) {
                        Kind.TIMER -> OutlinedButton(onClick = { Actions.extend(ctx, a.id, 1) }) { Text("+1 min") }
                        Kind.TRACKER -> OutlinedButton(onClick = { Actions.extend(ctx, a.id, 5) }) { Text("+5 min") }
                        Kind.OTP -> OutlinedButton(onClick = {
                            com.ski.live.live.copyCode(ctx, a.chip)
                            Actions.end(ctx, a.id)
                        }) { Text("Copy") }
                        else -> {}
                    }
                }
                TextButton(onClick = { Actions.end(ctx, a.id) }) { Text(if (a.ended) "Clear" else "End") }
            }
        }
    }
}

private fun kindLabel(a: LiveActivity) = when (a.kind) {
    Kind.GAME -> a.game?.let { com.ski.live.sports.Leagues.byKey(it.league).name } ?: "Game"
    Kind.TIMER -> "Timer"
    Kind.TRACKER -> "Tracker"
    Kind.OTP -> "Code"
    Kind.CUSTOM -> if (a.source.isNotBlank()) a.source else "Live"
} + if (a.ended) " · ended" else ""

/** Segmented progress bar with stage dots and a moving marker, like the Uber card. */
@Composable
fun SegmentBar(r: Render, fraction: Float) {
    val color = Color(r.segmentColors.first())
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val n = r.segments.coerceIn(1, 20)
        val gap = if (n > 1) 6.dp.toPx() else 0f
        val h = 5.dp.toPx()
        val y = (size.height - h) / 2
        val segW = (size.width - gap * (n - 1)) / n
        val filled = fraction * size.width
        for (i in 0 until n) {
            val x = i * (segW + gap)
            drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(x, y), Size(segW, h), CornerRadius(h / 2))
            val fw = (filled - x).coerceIn(0f, segW)
            if (fw > 0) drawRoundRect(color, Offset(x, y), Size(fw, h), CornerRadius(h / 2))
        }
        if (r.pointsAtBoundaries) {
            for (i in 1 until n) {
                val cx = i * (segW + gap) - gap / 2
                drawCircle(if (filled >= cx) Color.White else Color.White.copy(alpha = 0.4f), 3.dp.toPx(), Offset(cx, size.height / 2))
            }
        }
        drawCircle(Color.White, 7.dp.toPx(), Offset(filled.coerceIn(7.dp.toPx(), size.width - 7.dp.toPx()), size.height / 2))
        drawCircle(color, 4.dp.toPx(), Offset(filled.coerceIn(7.dp.toPx(), size.width - 7.dp.toPx()), size.height / 2))
    }
}

fun hms(ms: Long): String {
    val t = (ms.coerceAtLeast(0) + 999) / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ---------- setup ----------

private data class Perms(val notify: Boolean, val promote: Boolean, val overlay: Boolean, val access: Boolean)

private fun readPerms(ctx: Context) = Perms(
    notify = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    promote = Build.VERSION.SDK_INT < 36 || LiveNotifier.canPromote(ctx),
    overlay = Settings.canDrawOverlays(ctx),
    access = Mirror.isAccessGranted(ctx),
)

@Composable
private fun SetupCard() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val perms = remember(tick) { readPerms(ctx) }
    LifecycleResumeEffect(Unit) {
        tick++
        Pill.refresh(ctx)
        onPauseOrDispose { }
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    if (perms.notify && perms.promote && perms.overlay && perms.access) return

    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2333))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SetupRow("Notifications", "Needed for every live activity", perms.notify) {
                if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= 36) {
                SetupRow("Live notifications", "Lock screen card, status bar chip and Now Bar", perms.promote) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                    )
                }
            } else {
                Text(
                    "Your phone is on Android ${Build.VERSION.RELEASE}. The status bar chip and Now Bar need Android 16; " +
                        "you still get live lock screen notifications and the pill.",
                    color = Muted, fontSize = 12.sp,
                )
            }
            SetupRow("Floating pill", "Draw the pill over other apps", perms.overlay) {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)),
                )
            }
            SetupRow("Notification access", "Other apps' alerts, their live notifications and codes in the pill", perms.access) {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            if (!perms.access) {
                Text(
                    "Greyed out? Android blocks this for apps installed from an APK. Open App info, tap ⋮ " +
                        "→ Allow restricted settings, then come back.",
                    color = Muted, fontSize = 12.sp,
                )
                TextButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)),
                    )
                }) { Text("Open App info") }
            }
        }
    }
}

@Composable
private fun SetupRow(title: String, sub: String, done: Boolean, fix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Muted, fontSize = 12.sp)
        }
        if (done) Icon(Icons.Filled.CheckCircle, "Done", tint = Green)
        else Button(onClick = fix) { Text("Allow") }
    }
}

// ---------- pill settings ----------

@Composable
private fun PillSettings() {
    val ctx = LocalContext.current
    val s by Store.settings.collectAsState()
    fun set(f: (com.ski.live.data.Settings) -> com.ski.live.data.Settings) {
        Store.updateSettings(f)
        Pill.refresh(ctx)
    }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 34.dp, height = 14.dp).clip(CircleShape).background(Color.Black))
                Spacer(Modifier.width(10.dp))
                Text("Pill", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Toggle("Show the pill", "Floating at the top of the screen", s.pill) { v -> set { it.copy(pill = v) } }
            Text("Position", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillPosition.entries.forEach { p ->
                    FilterChip(
                        selected = s.pillPosition == p,
                        onClick = { set { it.copy(pillPosition = p) } },
                        label = { Text(p.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                    )
                }
            }
            Toggle("Incoming notifications", "Flash new alerts from any app", s.flashNotifications) { v ->
                set { it.copy(flashNotifications = v) }
            }
            Toggle("Other apps' live notifications", "Rides, navigation, calls, timers, downloads", s.mirrorLive) { v ->
                set { it.copy(mirrorLive = v) }
            }
            Toggle("One-time codes", "Show login codes from texts and emails, tap to copy", s.otp) { v ->
                set { it.copy(otp = v) }
            }
            if (s.otp) {
                Toggle("Copy codes automatically", "Put each new code on the clipboard", s.otpAutoCopy) { v ->
                    set { it.copy(otpAutoCopy = v) }
                }
            }
            Text(
                "Everything stays on your phone. Hold the pill to dismiss what it's showing.",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, color = Muted, fontSize = 12.sp)
        }
        Switch(checked = on, onCheckedChange = change)
    }
}

// ---------- favorites ----------

@Composable
private fun Favorites() {
    val ctx = LocalContext.current
    val favs by Store.favorites.collectAsState()
    if (favs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Auto-follow", style = MaterialTheme.typography.titleMedium)
        Text("These teams' games start a live activity on their own.", color = Muted, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            favs.sorted().forEach { f ->
                val (league, abbr) = f.split('|').let { it[0] to it.getOrElse(1) { "" } }
                FilterChip(
                    selected = true,
                    onClick = {
                        Store.toggleFavorite(league, abbr)
                        com.ski.live.live.Scheduler.autoFollow(ctx)
                    },
                    label = { Text("$abbr · ${com.ski.live.sports.Leagues.byKey(league).name}  ✕") },
                )
            }
        }
    }
}
