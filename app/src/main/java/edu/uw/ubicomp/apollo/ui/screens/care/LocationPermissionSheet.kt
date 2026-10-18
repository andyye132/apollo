package edu.uw.ubicomp.apollo.ui.screens.care

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.uw.ubicomp.apollo.ui.components.SunMascot

/**
 * Soft, pre-permission rationale sheet shown before the real Android location dialog. Accessible
 * tap targets are 48dp+ throughout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPermissionSheet(
    onAllowWhileUsing: () -> Unit,
    onOnlyThisTime: () -> Unit,
    onNotNow: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SunMascot(size = 56.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                "Find care near you",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Allow Apollo to use your location to show nearby hospitals, clinics, and labs. We only use it while the app is open.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onAllowWhileUsing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Allow while using the app") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onOnlyThisTime,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Only this time") }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onNotNow,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Not now") }
            Spacer(Modifier.height(8.dp))
        }
    }
}
