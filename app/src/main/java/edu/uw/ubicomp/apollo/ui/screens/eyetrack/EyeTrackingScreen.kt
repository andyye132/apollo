package edu.uw.ubicomp.apollo.ui.screens.eyetrack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import edu.uw.ubicomp.apollo.sensing.vision.EyeTrackingController
import edu.uw.ubicomp.apollo.sensing.vision.EyeTrackingService
import edu.uw.ubicomp.apollo.sensing.vision.PassiveEyeTrackingState
import edu.uw.ubicomp.apollo.sensing.vision.PassiveStatus
import edu.uw.ubicomp.apollo.ui.components.StatusChip
import edu.uw.ubicomp.apollo.ui.theme.ApolloTheme

/**
 * Real eye tracking: front camera -> MediaPipe Face Landmarker -> EyeMetrics, ported from
 * prototypes/eyetrack/. The foreground view below runs only while this screen is open; "Passive
 * tracking" starts prototypes/eyetrack/README.md's camera foreground service, which keeps running
 * in the background (persistent notification, green camera dot) until stopped. See docs/DESIGN.md
 * "Vision: eye tracking" for the measurement's limits (no saccade speed, no screen gaze point, not
 * a diagnosis) — the passive "elevated eye movement" flag is exploratory for the same reason.
 */
@Composable
fun EyeTrackingScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Eye tracking", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        }

        when {
            hasPermission -> EyeTrackingContent()
            permissionDenied -> PermissionDeniedState(
                onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
            else -> PermissionRationale(
                onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
        }
    }
}

@Composable
private fun PermissionRationale(onAllow: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 12.dp)) {
        Text(
            "Blink rate and gaze shifts, from the front camera.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Apollo needs the camera to find your eyes. Nothing is recorded or saved — the camera only runs while this screen is open.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
            Text("Allow camera access")
        }
    }
}

@Composable
private fun PermissionDeniedState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Camera access is off, turn it on to try eye tracking",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun EyeTrackingContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val passiveState by PassiveEyeTrackingState.state.collectAsState()
    // CameraX's ProcessCameraProvider is a process-wide singleton, and both this screen's live
    // preview and EyeTrackingService call provider.unbindAll() before binding. Running both at
    // once makes each one silently kill the other's camera session. So: while passive tracking
    // is active, don't start a second camera session here — just show its live numbers instead.
    val passiveActive = passiveState.status == PassiveStatus.RUNNING ||
        passiveState.status == PassiveStatus.PAUSED_CAMERA_IN_USE ||
        passiveState.status == PassiveStatus.PAUSED_SCREEN_OFF

    var state by remember { mutableStateOf(EyeTrackingController.State()) }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner, passiveActive) {
        if (passiveActive) {
            onDispose {}
        } else {
            val controller = EyeTrackingController(context)
            controller.start(lifecycleOwner, previewView) { newState -> state = newState }
            onDispose { controller.stop() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        if (passiveActive) {
            Text(
                "Passive tracking is already using the camera in the background. Showing its live " +
                    "numbers below — stop it first to use the live view here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricCard(
                    label = "Blink rate",
                    value = passiveState.lastMinute?.blinkRatePerMin?.toInt()?.toString() ?: "–",
                    unit = "per min",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    label = "Gaze shifts",
                    value = passiveState.lastMinute?.shiftRatePerMin?.toInt()?.toString() ?: "–",
                    unit = "per min",
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (state.error != null) {
            Text(
                state.error ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    if (!state.faceDetected) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            StatusChip(
                                text = "Looking for your face…",
                                showCheck = false,
                                background = ApolloTheme.colors.headerScrim,
                                contentColor = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricCard(
                    label = "Blink rate",
                    value = state.blinksPerMinute.toInt().toString(),
                    unit = "per min",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    label = "Gaze shifts",
                    value = state.shiftsPerMinute.toInt().toString(),
                    unit = "per min",
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            StatusChip(
                text = if (state.faceDetected) "Good signal" else "Weak signal, face not visible",
                showCheck = state.faceDetected,
                background = if (state.faceDetected) ApolloTheme.colors.normal else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (state.faceDetected) ApolloTheme.colors.onNormal else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Exploratory only. These numbers are a relative signal against your own baseline over time, " +
                "not a measure of attention, overwhelm, or anything diagnostic.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PassiveTrackingSection()

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PassiveTrackingSection() {
    val context = LocalContext.current
    val uiState by PassiveEyeTrackingState.state.collectAsState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Start regardless of the result — per the README, without POST_NOTIFICATIONS the service
        // still runs, the notification is just hidden in the drawer.
        startPassiveService(context)
    }

    Spacer(Modifier.height(28.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(20.dp))
    Text("Passive tracking", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(8.dp))
    Text(
        "Keeps counting blinks and gaze shifts in the background, to flag when your eyes are moving " +
            "a lot more than usual — the way video-call apps keep the camera on. Android keeps a " +
            "notification and the green camera dot up the whole time; neither can be hidden, and it " +
            "stops if you reboot or swipe the app away.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    when (uiState.status) {
        PassiveStatus.STOPPED, PassiveStatus.ERROR -> {
            if (uiState.status == PassiveStatus.ERROR) {
                Text(
                    uiState.errorMessage ?: "Something went wrong.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startPassiveService(context)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start passive tracking") }
        }
        else -> {
            val (statusText, isGood) = when (uiState.status) {
                PassiveStatus.RUNNING -> "Running in the background" to true
                PassiveStatus.PAUSED_CAMERA_IN_USE -> "Paused: camera in use by another app" to false
                PassiveStatus.PAUSED_SCREEN_OFF -> "Paused: screen off" to false
                else -> "" to false
            }
            StatusChip(
                text = statusText,
                showCheck = isGood,
                background = if (isGood) ApolloTheme.colors.normal else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isGood) ApolloTheme.colors.onNormal else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            uiState.lastMinute?.let { minute ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "Last minute: ${minute.blinkRatePerMin.toInt()} blinks/min, ${minute.shiftRatePerMin.toInt()} gaze shifts/min",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (uiState.elevatedEyeMovement) {
                Spacer(Modifier.height(10.dp))
                StatusChip(
                    text = "More eye movement than usual this session",
                    showCheck = false,
                    background = ApolloTheme.colors.elevated,
                    contentColor = ApolloTheme.colors.onElevated,
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { stopPassiveService(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Stop passive tracking")
            }
        }
    }
}

private fun startPassiveService(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, EyeTrackingService::class.java))
}

private fun stopPassiveService(context: Context) {
    context.startService(Intent(context, EyeTrackingService::class.java).setAction(EyeTrackingService.ACTION_STOP))
}

@Composable
private fun MetricCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.displayMedium.copy(fontSize = 32.headerSp()), color = MaterialTheme.colorScheme.onBackground)
                Text(
                    unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                )
            }
        }
    }
}

private fun Int.headerSp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
