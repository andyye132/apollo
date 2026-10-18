package edu.uw.ubicomp.apollo.ui.screens.care

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.uw.ubicomp.apollo.ui.theme.ApolloTheme
import edu.uw.ubicomp.apollo.ui.theme.GradientHeader

private enum class CareTab(val label: String) {
    MEDS("Meds and Vitamins"),
    SERVICES("Services & Hospitals"),
}

/**
 * Care: find what you need and where to get it. No payments, cart, checkout, pricing, or
 * insurance anywhere — Apollo hands off to retailers, delivery apps, and maps/phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareScreen(modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(CareTab.MEDS) }
    var openCategoryId by remember { mutableStateOf<String?>(null) }

    val categoryId = openCategoryId
    if (categoryId != null) {
        BackHandler { openCategoryId = null }
        ProductListScreen(categoryId = categoryId, onBack = { openCategoryId = null }, modifier = modifier)
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        GradientHeader {
            CareHeaderContent()
        }

        Spacer(Modifier.height(16.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
            CareTab.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = CareTab.entries.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(entry.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        when (tab) {
            CareTab.MEDS -> MedsTab(onOpenCategory = { id -> openCategoryId = id })
            CareTab.SERVICES -> ServicesTab()
        }
    }
}

private fun Int.headerSp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

@Composable
private fun CareHeaderContent() {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "CARE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Care",
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 34.headerSp()),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Find what you need, and where to get it.",
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
