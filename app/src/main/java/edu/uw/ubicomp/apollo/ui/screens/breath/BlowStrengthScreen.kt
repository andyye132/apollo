package edu.uw.ubicomp.apollo.ui.screens.breath

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.uw.ubicomp.apollo.sensing.breath.BlowDetector
import edu.uw.ubicomp.apollo.sensing.breath.BlowSession
import edu.uw.ubicomp.apollo.sensing.breath.BlowStrengthStore
import edu.uw.ubicomp.apollo.ui.components.ApolloCard
import edu.uw.ubicomp.apollo.ui.components.StatusChip
import edu.uw.ubicomp.apollo.ui.theme.ApolloTheme
import java.util.Locale

/**
 * Breath strength: blow at the screen of a phone lying flat; the barometer sees the glass flex. Reported as
 * blow strength against your own baseline on this phone, never as peak flow or lung function: across people
 * it didn't track a peak-flow meter (tools/barotest/README.md).
 */
@Composable
fun BlowStrengthScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = remember { BlowSession(context) }
    val store = remember { BlowStrengthStore(context) }
    val live by session.live.collectAsState()
    var run by remember { mutableStateOf(0) }                       // bumps on every start, keys the save below
    var comparison by remember { mutableStateOf<BlowStrengthStore.Comparison?>(null) }
    fun start() {
        run++
        comparison = null
        session.start()
    }

    DisposableEffect(session) {
        session.monitor()
        onDispose { session.release() }
    }

    // Save each finished run once, only if it's usable.
    LaunchedEffect(live.summary, run) {
        val s = live.summary ?: return@LaunchedEffect
        val strength = s.strengthPa
        comparison = if (strength != null && (s.quality == BlowDetector.Quality.GOOD || s.quality == BlowDetector.Quality.OK)) {
            store.record(strength)
        } else null
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Text("Breath strength", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            when {
                !session.hasBarometer -> Text(
                    "This phone has no barometer, so breath strength isn't available on it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                live.phase == BlowSession.Phase.RUNNING -> Running(live)
                live.phase == BlowSession.Phase.DONE && live.summary != null ->
                    Result(live, live.summary!!, comparison, onRetake = ::start, onDone = onBack)
                else -> Setup(live.flat, store, onStart = ::start)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Setup(flat: Boolean, store: BlowStrengthStore, onStart: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text(
        "How hard can you blow? Compared with your own earlier results on this phone.",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(16.dp))
    ApolloCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Step(1, "Lay the phone flat on a table, screen up. No case pressing on the screen.")
            Step(2, "Keep your hands off it. A touch looks a hundred times bigger than a blow.")
            Step(3, "Lay a finger flat on the middle of the screen, put your lips just above it, then lift the finger away.")
            Step(4, "Make a round \"O\" with your lips, about a finger wide. Keep it the same every time.")
            Step(5, "When the screen says Blow now, take a full breath and blow as hard and fast as you can. Four blows, 9 s apart.")
        }
    }
    Spacer(Modifier.height(16.dp))
    if (flat) {
        StatusChip(text = "Phone is flat")
    } else {
        StatusChip(
            text = "Lay the phone flat to start",
            showCheck = false,
            background = ApolloTheme.colors.elevated,
            contentColor = ApolloTheme.colors.onElevated,
        )
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = onStart, enabled = flat, modifier = Modifier.fillMaxWidth()) { Text("Start (about 40 s)") }
    Spacer(Modifier.height(12.dp))
    val sessions = store.sessions().size
    Text(
        if (sessions < BlowStrengthStore.MIN_FOR_BASELINE) {
            "Your first ${BlowStrengthStore.MIN_FOR_BASELINE} results build your baseline ($sessions so far)."
        } else {
            "$sessions results on this phone. Today is compared with your last ${BlowStrengthStore.BASELINE_SESSIONS}."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Disclaimer()
}

@Composable
private fun Step(n: Int, text: String) {
    Row {
        Text("$n.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = ApolloTheme.colors.respiratory, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun Running(live: BlowSession.Live) {
    val blowing = live.cue.startsWith("Blow now")
    Spacer(Modifier.height(24.dp))
    Text(
        live.cue,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (blowing) ApolloTheme.colors.respiratory else MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(
        progress = { (live.elapsedS / BlowSession.DURATION_S).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
    ApolloCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("Pressure change", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            Text(
                String.format(Locale.US, "%+.0f Pa", live.deviationPa),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Blows detected: ${live.blowsSoFar}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    if (live.touchSeen) Warning("That looked like a touch. Keep your hands off the phone.")
    if (live.movedDuringTest) Warning("The phone moved. Keep it flat on the table.")
}

@Composable
private fun Result(
    live: BlowSession.Live,
    summary: BlowDetector.Summary,
    comparison: BlowStrengthStore.Comparison?,
    onRetake: () -> Unit,
    onDone: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    val strength = summary.strengthPa
    ApolloCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("Breath strength", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            when {
                strength == null -> Text(
                    "Fewer than 3 blows were detected. Blow from closer (a finger's width) and as hard as you can.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                comparison?.percentOfBaseline != null -> {
                    Text("${comparison.percentOfBaseline}% of your baseline", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        String.format(Locale.US, "%.0f Pa today · baseline %.0f Pa (median of your recent results)", strength, comparison.baselinePa),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                comparison != null -> {
                    Text(String.format(Locale.US, "%.0f Pa", strength), style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "Building your baseline (${comparison.sessionsBefore + 1} of ${BlowStrengthStore.MIN_FOR_BASELINE}). " +
                            "Pascals mean little on their own; the comparison with your own results is the point.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Text(
                    String.format(Locale.US, "%.0f Pa, but your blows varied a lot (%.0f%%), so this one isn't saved. Try again with the same mouth shape and distance each time.",
                        strength, summary.spreadPct),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.height(12.dp))
            QualityChip(summary.quality)
            if (summary.blows.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Blows: " + summary.blows.joinToString(" · ") { String.format(Locale.US, "%.0f Pa", it.peakPa) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    if (summary.touches > 0) Warning("${summary.touches} touch${if (summary.touches > 1) "es" else ""} ignored.")
    if (live.movedDuringTest) Warning("The phone moved during the test, so treat this result with caution.")
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("Retake") }
        Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
    }
    Spacer(Modifier.height(16.dp))
    Disclaimer()
}

@Composable
private fun QualityChip(q: BlowDetector.Quality) {
    when (q) {
        BlowDetector.Quality.GOOD -> StatusChip(text = "Consistent blows")
        BlowDetector.Quality.OK -> StatusChip(text = "Fairly consistent", showCheck = false)
        else -> StatusChip(
            text = if (q == BlowDetector.Quality.TOO_FEW) "Retake" else "Inconsistent, retake",
            showCheck = false,
            background = ApolloTheme.colors.elevated,
            contentColor = ApolloTheme.colors.onElevated,
        )
    }
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = ApolloTheme.colors.onElevated,
        modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Disclaimer() {
    Text(
        "Not a lung function test. It tracks how hard you blow compared with yourself on this phone, " +
            "and can't diagnose anything. If breathing is hard, contact a clinician.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
