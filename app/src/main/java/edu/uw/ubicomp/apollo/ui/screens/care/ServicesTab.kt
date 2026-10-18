package edu.uw.ubicomp.apollo.ui.screens.care

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import edu.uw.ubicomp.apollo.ui.components.ApolloCard
import edu.uw.ubicomp.apollo.ui.components.apolloFilterChipColors

private enum class LocationState { NOT_ASKED, GRANTED, DENIED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val initiallyGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    var locationState by remember { mutableStateOf(if (initiallyGranted) LocationState.GRANTED else LocationState.NOT_ASKED) }
    var showSheet by remember { mutableStateOf(!initiallyGranted) }
    var showManualSearch by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationState = if (granted) LocationState.GRANTED else LocationState.DENIED
        showSheet = false
        showManualSearch = false
    }

    if (showSheet) {
        LocationPermissionSheet(
            onAllowWhileUsing = { permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
            onOnlyThisTime = { permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
            onNotNow = { showSheet = false; showManualSearch = true },
            onDismissRequest = { showSheet = false; showManualSearch = true },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        when {
            locationState == LocationState.GRANTED -> PlacesContent(context = context.applicationContext)
            showManualSearch -> ManualLocationSearch(
                onUseMyLocation = { showManualSearch = false; showSheet = true },
            )
            locationState == LocationState.DENIED -> LocationOffState(
                onRetry = { showSheet = true },
            )
            else -> Spacer(Modifier.height(1.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ManualLocationSearch(onUseMyLocation: () -> Unit) {
    var query by remember { mutableStateOf("") }
    Spacer(Modifier.height(20.dp))
    Text("Search by ZIP or city", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("ZIP code or city") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(Modifier.height(12.dp))
    Button(onClick = onUseMyLocation, modifier = Modifier.fillMaxWidth()) {
        Text("Use my location")
    }
    if (query.isNotBlank()) {
        Spacer(Modifier.height(20.dp))
        Text(
            "Showing sample results near \"$query\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        PlacesList(context = null)
    }
}

@Composable
private fun LocationOffState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.LocationOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(40.dp).width(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Location is off, turn it on to see nearby places",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun PlacesContent(context: android.content.Context) {
    Spacer(Modifier.height(16.dp))
    MapPreviewCard()
    Spacer(Modifier.height(20.dp))
    PlacesList(context = context)
}

@Composable
private fun PlacesList(context: android.content.Context?) {
    var selectedType by remember { mutableStateOf(PlaceType.ALL) }
    var openNowOnly by remember { mutableStateOf(false) }
    var sortByNearest by remember { mutableStateOf(true) }
    var explainerExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlaceType.entries.forEach { type ->
            FilterChip(
                selected = selectedType == type,
                onClick = { selectedType = type },
                label = { Text(type.label) },
                colors = apolloFilterChipColors(),
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = openNowOnly, onValueChange = { openNowOnly = it }, role = Role.Switch),
    ) {
        Text("Open now", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.weight(1f))
        Switch(checked = openNowOnly, onCheckedChange = null)
    }

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Sort:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = sortByNearest, onClick = { sortByNearest = true }, label = { Text("Nearest") }, colors = apolloFilterChipColors())
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = !sortByNearest, onClick = { sortByNearest = false }, label = { Text("Wait time") }, colors = apolloFilterChipColors())
    }

    Spacer(Modifier.height(16.dp))
    WhichShouldIChooseExplainer(expanded = explainerExpanded, onToggle = { explainerExpanded = !explainerExpanded })

    Spacer(Modifier.height(16.dp))
    val filtered = remember(selectedType, openNowOnly, sortByNearest) {
        samplePlaces
            .filter { selectedType == PlaceType.ALL || it.type == selectedType }
            .filter { !openNowOnly || it.isOpenNow }
            .let { list ->
                if (sortByNearest) list.sortedBy { it.distanceMi }
                else list.sortedBy { it.waitMinutes ?: Int.MAX_VALUE }
            }
    }

    if (filtered.isEmpty()) {
        Spacer(Modifier.height(20.dp))
        Text(
            "No places found, try widening the filters",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        filtered.forEach { place ->
            PlaceCard(place = place, context = context)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun WhichShouldIChooseExplainer(expanded: Boolean, onToggle: () -> Unit) {
    ApolloCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = expanded, onValueChange = { onToggle() }, role = Role.Button)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Which should I choose?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "ER: life-threatening or severe symptoms, any time of day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Urgent care: non-emergency issues that still need same-day attention.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Virtual visit: quick questions or follow-ups you can do from home.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun PlaceCard(place: Place, context: android.content.Context?) {
    ApolloCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(place.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChipLike(text = place.type.label)
                Spacer(Modifier.width(8.dp))
                if (!place.isVirtual) {
                    Text(
                        "${place.distanceMi} mi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                (if (place.isOpenNow) "Open now · " else "Closed · ") + place.hoursText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (place.waitMinutes != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Estimated wait: ${place.waitMinutes} min",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(place.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))

            if (place.isVirtual) {
                Button(
                    onClick = { context?.let { openVirtualVisit(it, place) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start a virtual visit") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { context?.let { callPlace(it, place) } },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.height(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Call")
                    }
                    OutlinedButton(
                        onClick = { context?.let { openDirections(it, place) } },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Directions, contentDescription = null, modifier = Modifier.height(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Directions")
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistChipLike(text: String) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun callPlace(context: android.content.Context, place: Place) {
    val uri = Uri.parse("tel:" + place.phone.filter { it.isDigit() })
    context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openDirections(context: android.content.Context, place: Place) {
    val uri = Uri.parse("geo:0,0?q=" + Uri.encode(place.name))
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openVirtualVisit(context: android.content.Context, place: Place) {
    val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(place.name))
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
