@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ski.live.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ski.live.model.Kind
import com.ski.live.model.LiveActivity
import java.util.UUID

private data class Preset(val name: String, val icon: String, val steps: List<String>, val eta: Int)

private val presets = listOf(
    Preset("Ride", "car", listOf("Driver assigned", "On the way", "Almost there", "Arriving"), 8),
    Preset("Food delivery", "food", listOf("Order placed", "Preparing", "On the way", "Delivered"), 35),
    Preset("Pickup order", "bag", listOf("Ordered", "Preparing", "Ready"), 15),
    Preset("Package", "truck", listOf("Shipped", "Out for delivery", "Nearby", "Delivered"), 240),
    Preset("Flight", "plane", listOf("Boarding", "Departed", "In flight", "Landing"), 180),
)

@Composable
fun CreateScreen(kind: Kind, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var detail by rememberSaveable { mutableStateOf("") }
    var chip by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf(if (kind == Kind.TIMER) "10" else "") }
    var preset by rememberSaveable { mutableStateOf(0) }
    var useProgress by rememberSaveable { mutableStateOf(false) }
    var progress by rememberSaveable { mutableFloatStateOf(50f) }

    fun start() {
        val now = System.currentTimeMillis()
        val mins = minutes.toDoubleOrNull()
        val end = if (mins != null && mins > 0) now + (mins * 60_000).toLong() else 0L
        val a = when (kind) {
            Kind.TIMER -> LiveActivity(
                id = "timer-" + UUID.randomUUID(), kind = Kind.TIMER,
                title = title.ifBlank { "Timer" }, startAt = now, endAt = if (end > 0) end else now + 600_000, icon = "timer",
            )
            Kind.TRACKER -> {
                val p = presets[preset]
                LiveActivity(
                    id = "track-" + UUID.randomUUID(), kind = Kind.TRACKER,
                    title = title.ifBlank { p.name }, detail = detail, startAt = now,
                    endAt = if (end > 0) end else now + p.eta * 60_000L,
                    steps = p.steps, icon = p.icon,
                )
            }
            else -> LiveActivity(
                id = "custom-" + UUID.randomUUID(), kind = Kind.CUSTOM,
                title = title.ifBlank { "Live" }, text = text, detail = detail, chip = chip,
                startAt = now, endAt = end, progress = if (useProgress) progress.toInt() else -1,
            )
        }
        Actions.start(ctx, a)
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (kind) {
                            Kind.TIMER -> "New timer"
                            Kind.TRACKER -> "Track a ride or order"
                            else -> "Custom live activity"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (kind) {
                Kind.TIMER -> {
                    OutlinedTextField(title, { title = it }, label = { Text("Label (e.g. Pasta)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    MinutesField(minutes) { minutes = it }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 3, 5, 10, 15, 20, 30, 45, 60, 90).forEach { m ->
                            FilterChip(selected = minutes == "$m", onClick = { minutes = "$m" }, label = { Text("$m min") })
                        }
                    }
                }
                Kind.TRACKER -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEachIndexed { i, p ->
                            FilterChip(selected = preset == i, onClick = { preset = i }, label = { Text(p.name) })
                        }
                    }
                    Text(presets[preset].steps.joinToString("  →  "), color = Muted)
                    OutlinedTextField(title, { title = it }, label = { Text("Title (e.g. Uber to work)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(detail, { detail = it }, label = { Text("Details (e.g. 7NLR004 · Silver Honda Civic)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    MinutesField(minutes, "Arrives in (minutes), default ${presets[preset].eta}") { minutes = it }
                    Text(
                        "Moves along on its own until the ETA. Use +5 min from the card or notification if it's running late.",
                        color = Muted,
                    )
                }
                else -> {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(text, { text = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(detail, { detail = it }, label = { Text("Small text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(chip, { chip = it.take(12) }, label = { Text("Chip (status bar, max 12)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    MinutesField(minutes, "Count down (minutes, optional)") { minutes = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Progress bar", modifier = Modifier.weight(1f))
                        Switch(useProgress, { useProgress = it })
                    }
                    if (useProgress) Slider(progress, { progress = it }, valueRange = 0f..100f)
                }
            }
            Button(onClick = { start() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start") }
        }
    }
}

@Composable
private fun MinutesField(value: String, label: String = "Minutes", change: (String) -> Unit) {
    OutlinedTextField(
        value, { v -> change(v.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
