package edu.uw.ubicomp.apollo.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import edu.uw.ubicomp.apollo.ui.components.ApolloBottomNav
import edu.uw.ubicomp.apollo.ui.components.ApolloCard
import edu.uw.ubicomp.apollo.ui.components.ApolloDestination
import edu.uw.ubicomp.apollo.ui.components.StatusChip
import edu.uw.ubicomp.apollo.sensing.vision.PassiveEyeTrackingState
import edu.uw.ubicomp.apollo.sensing.vision.PassiveStatus
import edu.uw.ubicomp.apollo.ui.screens.care.CareScreen
import edu.uw.ubicomp.apollo.ui.screens.eyetrack.EyeTrackingScreen
import edu.uw.ubicomp.apollo.ui.theme.ApolloTheme
import edu.uw.ubicomp.apollo.ui.theme.GradientHeader

/**
 * The app's home screen. Nav is Check In / Care / Ask Apollo (see ApolloBottomNav) — only Check
 * In has real content so far. It's split into two clearly separated sections:
 *   - "Take a test": the active measurements (temperature, heart rate, lungs) someone chooses to run.
 *   - "All day": passive, continuously-tracked data (steps, elevation). No Journal screen.
 */
@Composable
fun TodayScreen() {
    var selected by remember { mutableStateOf(ApolloDestination.CHECK_IN) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { ApolloBottomNav(selected = selected, onSelect = { selected = it }) },
    ) { innerPadding ->
        when (selected) {
            ApolloDestination.CHECK_IN -> CheckInContent(Modifier.padding(innerPadding))
            ApolloDestination.CARE -> CareScreen(Modifier.padding(innerPadding))
            ApolloDestination.ASK_APOLLO -> ComingSoon(
                title = selected.label,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun CheckInContent(modifier: Modifier = Modifier) {
    var showEyeTracking by remember { mutableStateOf(false) }
    var testsExpanded by remember { mutableStateOf(true) }
    var allDayExpanded by remember { mutableStateOf(true) }

    if (showEyeTracking) {
        BackHandler { showEyeTracking = false }
        EyeTrackingScreen(onBack = { showEyeTracking = false }, modifier = modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        CheckInHeader()

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            CollapsibleSectionHeader(
                title = "Take a test",
                expanded = testsExpanded,
                onToggle = { testsExpanded = !testsExpanded },
            )
            AnimatedVisibility(visible = testsExpanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    MeasurementOptionCard(
                        icon = Icons.Filled.Thermostat,
                        title = "Temperature",
                        subtitle = "60 s preparation + 90 s contact",
                        chipText = "Phone ready",
                    )
                    Spacer(Modifier.height(12.dp))
                    MeasurementOptionCard(
                        icon = Icons.Filled.Favorite,
                        title = "Heart rate",
                        subtitle = "20 s capture · camera + flash",
                        chipText = "Last: 72 bpm",
                    )
                    Spacer(Modifier.height(12.dp))
                    MeasurementOptionCard(
                        icon = Icons.Filled.Air,
                        title = "Lungs",
                        subtitle = "Forced exhale into the mic — peak flow didn't pan out, still exploring",
                        chipText = "Coming soon",
                        chipShowsCheck = false,
                        chipBackground = MaterialTheme.colorScheme.surfaceVariant,
                        chipContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    MeasurementOptionCard(
                        icon = Icons.Filled.RemoveRedEye,
                        title = "Eye tracking",
                        subtitle = "Blink rate and gaze shifts, front camera — foreground prototype",
                        chipText = "Try it",
                        chipShowsCheck = false,
                        chipBackground = MaterialTheme.colorScheme.primaryContainer,
                        chipContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = { showEyeTracking = true },
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Taking both? The temperature test comes first because the camera flash warms your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))

            CollapsibleSectionHeader(
                title = "All day",
                expanded = allDayExpanded,
                onToggle = { allDayExpanded = !allDayExpanded },
            )
            AnimatedVisibility(visible = allDayExpanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    StepsCard()
                    Spacer(Modifier.height(16.dp))
                    ElevationChangeCard()
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        BlinkRateCard(modifier = Modifier.weight(1f))
                        GazeShiftsCard(modifier = Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CollapsibleSectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "Collapse $title" else "Expand $title", onClick = onToggle),
    ) {
        SectionLabel(title)
        Spacer(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComingSoon(title: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "$title is coming soon.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun CheckInHeader() {
    GradientHeader {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "SATURDAY, OCTOBER 17",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Today",
                    style = MaterialTheme.typography.displayMedium.copy(fontSize = 34.sp()),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Good morning, Alex. Here's your day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(shape = CircleShape, color = ApolloTheme.colors.headerScrim, shadowElevation = 2.dp) {
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }
}

/** Local helper since ApolloTypography's displayMedium is tuned for the reading value, not this title. */
private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

@Composable
private fun MeasurementOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    chipText: String,
    chipShowsCheck: Boolean = true,
    chipBackground: Color = ApolloTheme.colors.normal,
    chipContentColor: Color = ApolloTheme.colors.onNormal,
    onClick: (() -> Unit)? = null,
) {
    ApolloCard(
        modifier = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                StatusChip(
                    text = chipText,
                    background = chipBackground,
                    contentColor = chipContentColor,
                    showCheck = chipShowsCheck,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepsCard() {
    ApolloCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.DirectionsWalk, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    "Steps",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                Text("TODAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("6,482", style = MaterialTheme.typography.displayMedium.copy(fontSize = 32.sp()), color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "steps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.65f)
                        .height(6.dp)
                        .background(ApolloTheme.colors.zoneNormal, RoundedCornerShape(50)),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Since first open today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ElevationChangeCard() {
    ApolloCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            Icon(Icons.Filled.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Elevation change", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                Text("Since this screen opened", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "+12 m · ≈ 4 floors",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

/** Live, if passive tracking is running; otherwise prompts to start it. See PassiveEyeTrackingState. */
@Composable
private fun BlinkRateCard(modifier: Modifier = Modifier) {
    val passiveState by PassiveEyeTrackingState.state.collectAsState()
    EyeStatCard(
        label = "Blinks",
        value = passiveState.lastMinute?.blinkRatePerMin?.toInt()?.toString(),
        status = passiveState.status,
        modifier = modifier,
    )
}

@Composable
private fun GazeShiftsCard(modifier: Modifier = Modifier) {
    val passiveState by PassiveEyeTrackingState.state.collectAsState()
    EyeStatCard(
        label = "Gaze shifts",
        value = passiveState.lastMinute?.shiftRatePerMin?.toInt()?.toString(),
        status = passiveState.status,
        modifier = modifier,
    )
}

@Composable
private fun EyeStatCard(label: String, value: String?, status: PassiveStatus, modifier: Modifier = Modifier) {
    val isTracking = status == PassiveStatus.RUNNING ||
        status == PassiveStatus.PAUSED_CAMERA_IN_USE ||
        status == PassiveStatus.PAUSED_SCREEN_OFF
    val caption = when {
        value != null -> "From passive tracking"
        isTracking -> "Collecting…"
        else -> "Start passive tracking to see this"
    }
    ApolloCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.RemoveRedEye, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value ?: "–",
                    style = MaterialTheme.typography.displayMedium.copy(fontSize = 28.sp()),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "per min",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 5.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TodayScreenPreview() {
    ApolloTheme {
        TodayScreen()
    }
}

