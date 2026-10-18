package edu.uw.ubicomp.apollo.ui.screens.care

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import edu.uw.ubicomp.apollo.ui.components.ApolloCard

/** A stylized, decorative map — not a real map SDK. Pins are illustrative, tied to sample places. */
@Composable
fun MapPreviewCard() {
    ApolloCard {
        Box(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                val streetColor = androidx.compose.ui.graphics.Color(0x14000000)
                drawLine(streetColor, Offset(0f, size.height * 0.3f), Offset(size.width, size.height * 0.22f), strokeWidth = 10f, cap = StrokeCap.Round)
                drawLine(streetColor, Offset(0f, size.height * 0.7f), Offset(size.width, size.height * 0.78f), strokeWidth = 10f, cap = StrokeCap.Round)
                drawLine(streetColor, Offset(size.width * 0.3f, 0f), Offset(size.width * 0.22f, size.height), strokeWidth = 10f, cap = StrokeCap.Round)
                drawLine(streetColor, Offset(size.width * 0.75f, 0f), Offset(size.width * 0.82f, size.height), strokeWidth = 10f, cap = StrokeCap.Round)
            }

            Icon(
                Icons.Filled.LocationOn,
                contentDescription = "Hospital pin",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 40.dp, top = 24.dp).align(Alignment.TopStart),
            )
            Icon(
                Icons.Filled.LocationOn,
                contentDescription = "Clinic pin",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 50.dp, top = 40.dp).align(Alignment.TopEnd),
            )
            Icon(
                Icons.Filled.LocationOn,
                contentDescription = "Pharmacy pin",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 60.dp, bottom = 30.dp).align(Alignment.BottomStart),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 8.dp),
            ) {
                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Box(modifier = Modifier.padding(6.dp)) {
                        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surface) {
                            Box(modifier = Modifier.padding(3.dp))
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            ) {
                Text(
                    "You are here · sample map",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}
