package edu.uw.ubicomp.apollo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.uw.ubicomp.apollo.ui.theme.ApolloTheme

/** A small "Good signal" / "Ready" pill with a leading checkmark, used across measurement cards. */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = ApolloTheme.colors.normal,
    contentColor: Color = ApolloTheme.colors.onNormal,
    showCheck: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(background, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        if (showCheck) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = contentColor, modifier = Modifier.size(14.dp))
        }
        Text(
            text = text,
            color = contentColor,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = if (showCheck) 4.dp else 0.dp),
        )
    }
}
