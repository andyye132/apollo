package edu.uw.ubicomp.apollo.ui.screens.care

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.uw.ubicomp.apollo.ui.components.ApolloCard
import edu.uw.ubicomp.apollo.ui.components.StatusChip
import edu.uw.ubicomp.apollo.ui.components.apolloFilterChipColors
import edu.uw.ubicomp.apollo.ui.theme.ApolloTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductListScreen(categoryId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val detail = remember(categoryId) { categoryDetailFor(categoryId) }
    var retailerFilter by remember { mutableStateOf<Retailer?>(null) }
    var sortNearest by remember { mutableStateOf(true) }
    val context = LocalContext.current

    val filtered = remember(detail, retailerFilter, sortNearest) {
        detail.products
            .filter { retailerFilter == null || it.retailer == retailerFilter }
            .let { list -> if (sortNearest) list.sortedBy { it.distanceMi ?: Double.MAX_VALUE } else list.sortedBy { it.name } }
    }
    val grouped = remember(filtered) {
        Retailer.entries.mapNotNull { retailer ->
            val products = filtered.filter { it.retailer == retailer }
            if (products.isEmpty()) null else retailer to products
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(detail.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(detail.whatItsFor, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            PharmacistNote()

            Spacer(Modifier.height(20.dp))
            Text(
                "This is sample data for prototyping — not real inventory or pricing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = retailerFilter == null,
                    onClick = { retailerFilter = null },
                    label = { Text("All stores") },
                    colors = apolloFilterChipColors(),
                )
                Retailer.entries.forEach { retailer ->
                    FilterChip(
                        selected = retailerFilter == retailer,
                        onClick = { retailerFilter = retailer },
                        label = { Text(retailer.displayName) },
                        colors = apolloFilterChipColors(),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sort:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = sortNearest, onClick = { sortNearest = true }, label = { Text("Nearest") }, colors = apolloFilterChipColors())
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = !sortNearest, onClick = { sortNearest = false }, label = { Text("A–Z") }, colors = apolloFilterChipColors())
            }

            Spacer(Modifier.height(20.dp))

            if (grouped.isEmpty()) {
                Text(
                    "No products match these filters.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            grouped.forEach { (retailer, products) ->
                Text(retailer.displayName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(10.dp))
                products.forEach { product ->
                    ProductRow(product = product, context = context)
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                "Availability and delivery are provided by the retailers and delivery apps. Apollo doesn't process payments.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PharmacistNote() {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Check with a pharmacist if you take other medication.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun ProductRow(product: Product, context: Context) {
    ApolloCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(product.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(4.dp))
            Text(
                "${product.retailer.displayName} · ${product.sizeOrCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (product.distanceMi != null && product.distanceMi <= 1.0) {
                    StatusChip(
                        text = "Nearby • ${product.distanceMi} mi",
                        showCheck = false,
                        background = ApolloTheme.colors.normal,
                        contentColor = ApolloTheme.colors.onNormal,
                    )
                }
                StatusChip(
                    text = product.inStockLabel,
                    showCheck = product.inStockLabel == "In stock nearby",
                    background = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { openFindInStore(context, product) }, modifier = Modifier.fillMaxWidth()) {
                Text("Find in store")
            }
            Spacer(Modifier.height(8.dp))
            Text("Get it delivered", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { openDelivery(context, product, "https://www.doordash.com/search/?query=") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                ) { Text("DoorDash") }
                Button(
                    onClick = { openDelivery(context, product, "https://www.ubereats.com/search?q=") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.weight(1f),
                ) { Text("Uber Eats") }
            }
        }
    }
}

private fun openFindInStore(context: Context, product: Product) {
    val query = "${product.retailer.displayName} ${product.name}"
    val geoUri = Uri.parse("geo:0,0?q=" + Uri.encode(query))
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
    } catch (e: ActivityNotFoundException) {
        val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query))
        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
    }
}

private fun openDelivery(context: Context, product: Product, searchBaseUrl: String) {
    val query = "${product.retailer.displayName} ${product.name}"
    val uri = Uri.parse(searchBaseUrl + Uri.encode(query))
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}
