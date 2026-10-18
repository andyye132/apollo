package edu.uw.ubicomp.apollo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ApolloDestination(val label: String, val icon: ImageVector) {
    CHECK_IN("Check-up", Icons.Filled.Home),
    CARE("Care", Icons.Filled.Favorite),
    ASK_APOLLO("Ask Apollo", Icons.Filled.WbSunny),
}

/** The floating pill bottom nav: Check-up, Care, Ask Apollo (sun mascot). */
@Composable
fun ApolloBottomNav(
    selected: ApolloDestination,
    onSelect: (ApolloDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 12.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ApolloDestination.entries.forEach { destination ->
                NavItem(
                    destination = destination,
                    isSelected = destination == selected,
                    onClick = { onSelect(destination) },
                )
            }
        }
    }
}

@Composable
private fun NavItem(destination: ApolloDestination, isSelected: Boolean, onClick: () -> Unit) {
    val color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clickable(onClickLabel = destination.label, onClick = onClick),
    ) {
        if (destination == ApolloDestination.ASK_APOLLO) {
            SunMascot(color = color, size = 24.dp)
        } else {
            Icon(destination.icon, contentDescription = null, tint = color)
        }
        Text(text = destination.label, color = color, fontSize = 11.sp)
    }
}
