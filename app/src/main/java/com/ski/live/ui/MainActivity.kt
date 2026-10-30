package com.ski.live.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.ski.live.data.Store
import com.ski.live.island.Pill
import com.ski.live.live.LiveService
import com.ski.live.live.Scheduler
import com.ski.live.model.Kind
import com.ski.live.model.LiveActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        // Pick up anything left running (e.g. after an update).
        if (Store.activities.value.any { !it.ended }) LiveService.kick(this)
        setContent { SkiTheme { AppRoot() } }
    }
}

val Accent = Color(0xFF3D8BFF)
val Green = Color(0xFF3DFF8B)
val CardBg = Color(0xFF17171D)
val Muted = Color(0xFF9A9AA6)

@Composable
fun SkiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondary = Green,
            background = Color(0xFF0B0B0F),
            surface = Color(0xFF0B0B0F),
            surfaceVariant = CardBg,
            surfaceContainer = CardBg,
            surfaceContainerHigh = Color(0xFF1F1F27),
        ),
        content = content,
    )
}

sealed class Screen {
    object Home : Screen()
    object Games : Screen()
    data class Create(val kind: Kind) : Screen()
}

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    when (val s = screen) {
        Screen.Home -> HomeScreen(open = { screen = it })
        Screen.Games -> GamesScreen(onBack = { screen = Screen.Home })
        is Screen.Create -> CreateScreen(s.kind, onDone = { screen = Screen.Home })
    }
}

/** UI-side actions. */
object Actions {
    fun start(ctx: Context, a: LiveActivity) {
        Store.upsert(a)
        if (a.kind == Kind.TIMER) Scheduler.timer(ctx, a)
        LiveService.kick(ctx)
        Pill.refresh(ctx)
    }

    fun end(ctx: Context, id: String) {
        LiveService.send(ctx, LiveService.ACTION_END, id)
    }

    fun extend(ctx: Context, id: String, minutes: Int) {
        LiveService.send(ctx, LiveService.ACTION_EXTEND, id, minutes)
    }
}
