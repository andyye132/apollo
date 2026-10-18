# Apollo Health App — Android UI Engineering Specification

**Document Version:** 1.0  
**Target Platform:** Android (Jetpack Compose)  
**Design Aesthetic:** Warm Minimalist Editorial / Soft Humanist Digital Health  
**Primary Visual Marker:** Smooth top-down sunset gradient (`#EA5A47` coral to `#FDBB5D` warm pastel yellow) dissipating into a warm cream base canvas (`#F7F5F0`).

---

## 1. System Philosophy & Visual Rules

1. **Warm Canvas, Not Pure White:** Never use pure `#FFFFFF` as the main app background. Use `#F7F5F0` (warm stone/cream) for background canvas and save `#FFFFFF` strictly for elevated card surfaces.
2. **Snug Typography Hierarchy:** Clean, heavy sans-serif typography with tight letter tracking (`-0.5.sp` on hero headers) and condensed vertical rhythm.
3. **Soft Elevation Over Drop Shadows:** Avoid dark or harsh elevation shadows (`elevation = 0.dp` or `1.dp` max). Rely instead on card boundary contrast and subtle borders (`BorderStroke(1.dp, SoftBorder)`).
4. **Edge-to-Edge System Bars:** The sunset gradient **must** bleed seamlessly behind the status bar via `Modifier.statusBarsPadding()`. The floating pill bottom navigation must float above the navigation bar via `Modifier.navigationBarsPadding()`.

---

## 2. Design Tokens

### 2.1 Colors
```kotlin
package com.apollo.health.ui.theme

import androidx.compose.ui.graphics.Color

// Top Atmospheric Sunset Gradient
val SunsetCoral = Color(0xFFEA5A47)       // Deep coral red
val SunsetYellow = Color(0xFFFDBB5D)      // Soft pastel warm yellow
val SunsetFadeEnd = Color(0x00F7F5F0)     // Fully transparent into WarmCanvas

// Canvas & Surfaces
val WarmCanvas = Color(0xFFF7F5F0)        // Base screen cream background
val CardSurface = Color(0xFFFFFFFF)       // Primary card container fill
val WarmCardTint = Color(0xFFFDF0ED)      // Soft peach highlight banner fill
val NeutralPill = Color(0xFFECEAE4)       // Segmented control & search track fill

// Text & Content
val TextPrimary = Color(0xFF1B1A18)       // Near-black warm charcoal
val TextSecondary = Color(0xFF706C64)     // Neutral warm muted text
val TextDisabled = Color(0xFFA5A29B)      // Secondary captions and disabled labels

// Accents & Signals
val AccentCoral = Color(0xFFE86A58)       // Primary CTA buttons, sparklines, timer arcs
val AccentCoralDark = Color(0xFFCE503E)   // Pressed button state
val SageGreen = Color(0xFF4FA070)         // Ready status, healthy metrics, steps bar
val SageGreenContainer = Color(0xFFEBF6EC) // Pill badge background for ready status
val AmberWarning = Color(0xFFD48B38)      // Cooling / attention badge color
val AmberContainer = Color(0xFFFDF3E7)    // Cooling / warning badge container
val SoftBorder = Color(0xFFE8E5DF)        // Card and divider stroke
```

### 2.2 Typography
```kotlin
package com.apollo.health.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ApolloTypography = Typography(
    // Screen Hero Headers (e.g. "Today at a glance", "Ready when your phone is.")
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
        color = TextPrimary
    ),
    // Section Headers (e.g. "Choose a measurement", "Latest readings")
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
        color = TextPrimary
    ),
    // Card Metric Displays (e.g. "72", "6,482", "0:42")
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1.0).sp,
        color = TextPrimary
    ),
    // Standard Body
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextSecondary
    ),
    // Tiny Section Tags / Monospace Dates (e.g. "SATURDAY, OCTOBER 17")
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.sp,
        color = TextSecondary
    )
)
```

---

## 3. Core Architecture & Reusable Components

### 3.1 Base Screen Container with Dissipating Sunset Gradient
This container standardizes the status bar inset, header typography, action button, and the requested top gradient.

```kotlin
package com.apollo.health.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.apollo.health.ui.theme.*

@Composable
fun ApolloScaffold(
    title: String,
    dateTag: String? = null,
    onSettingsClick: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmCanvas)
    ) {
        // 1. Atmospheric Sunset Gradient (Top ~35% of screen)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.36f)
                .background(
                    Brush.verticalGradient(
                        0.00f to SunsetCoral,
                        0.55f to SunsetYellow,
                        1.00f to SunsetFadeEnd
                    )
                )
        )

        // 2. Main Content
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { bottomBar?.invoke() },
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    if (navigationIcon != null) {
                        navigationIcon()
                    } else if (dateTag != null) {
                        Text(
                            text = dateTag.uppercase(),
                            style = ApolloTypography.labelSmall
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            style = ApolloTypography.displayMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (onSettingsClick != null) {
                            IconButton(
                                onClick = onSettingsClick,
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(CardSurface, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = "Settings",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content
            )
        }
    }
}
```

### 3.2 Standard Warm Card Container
```kotlin
@Composable
fun WarmCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = CardSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SoftBorder.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            content = content
        )
    }
}
```

### 3.3 Status Badges (Ready / Cooling / Optimal)
```kotlin
enum class BadgeType { READY, COOLING, NEUTRAL }

@Composable
fun StatusBadge(
    text: String,
    type: BadgeType = BadgeType.READY
) {
    val (bgColor, textColor) = when (type) {
        BadgeType.READY -> SageGreenContainer to SageGreen
        BadgeType.COOLING -> AmberContainer to AmberWarning
        BadgeType.NEUTRAL -> NeutralPill to TextSecondary
    }

    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = text,
                style = ApolloTypography.labelSmall.copy(
                    letterSpacing = 0.sp,
                    color = textColor
                )
            )
        }
    }
}
```

### 3.4 Terracotta CTA Action Button
```kotlin
@Composable
fun PrimaryTerracottaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AccentCoral),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                style = ApolloTypography.titleLarge.copy(
                    fontSize = 16.sp,
                    color = Color.White
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "→", color = Color.White, fontSize = 18.sp)
        }
    }
}
```

### 3.5 Floating Bottom Navigation Bar
```kotlin
data class NavItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun ApolloFloatingBottomBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
        color = CardSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, SoftBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                Column(
                    modifier = Modifier
                        .androidx.compose.foundation.clickable { onSelect(index) }
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (isSelected) AccentCoral else TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.label,
                        style = ApolloTypography.labelSmall.copy(
                            fontSize = 11.sp,
                            letterSpacing = 0.sp,
                            color = if (isSelected) AccentCoral else TextSecondary
                        )
                    )
                }
            }
        }
    }
}
```

---

## 4. Analytical UI Elements: Sparklines & Countdown Rings

### 4.1 Minimalist Line Chart (Sparkline)
```kotlin
@Composable
fun Sparkline(
    points: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = AccentCoral,
    fillUnder: Boolean = true
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val strokeWidth = 2.dp.toPx()
        val width = size.width
        val height = size.height

        val minVal = points.minOrNull() ?: 0f
        val maxVal = points.maxOrNull() ?: 100f
        val range = if (maxVal - minVal == 0f) 1f else maxVal - minVal

        val path = androidx.compose.ui.graphics.Path()
        val fillPath = androidx.compose.ui.graphics.Path()

        points.forEachIndexed { i, pt ->
            val x = i * (width / (points.size - 1))
            val y = height - ((pt - minVal) / range) * (height - strokeWidth) - strokeWidth / 2
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo(width, height)
        fillPath.close()

        if (fillUnder) {
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.15f), Color.Transparent)
                )
            )
        }

        drawPath(
            path = path,
            color = lineColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = strokeWidth,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
    }
}
```

### 4.2 Circular Countdown Timer (Preparation Screen)
```kotlin
@Composable
fun CircularCountdownTimer(
    progress: Float, // 0.0f to 1.0f
    timeText: String,
    subText: String,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(240.dp)
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 8.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            // Neutral Background Arc
            drawCircle(
                color = SoftBorder,
                style = stroke
            )
            // Active Countdown Arc
            drawArc(
                color = AccentCoral,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = stroke
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = timeText,
                style = ApolloTypography.headlineLarge.copy(fontSize = 50.sp)
            )
            Text(
                text = subText,
                style = ApolloTypography.bodyMedium
            )
        }
    }
}
```

---

## 5. Screen Assembly Example (Today at a Glance)

Claude can build the entire primary dashboard using the components defined above:

```kotlin
@Composable
fun TodayScreen(
    onNavigateToMeasure: () -> Unit
) {
    ApolloScaffold(
        title = "Today\nat a glance",
        dateTag = "Saturday, October 17",
        onSettingsClick = { /* Handle settings */ },
        bottomBar = {
            // Include ApolloFloatingBottomBar here
        }
    ) {
        // 1. Daily Readiness Highlight Card
        WarmCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Daily readiness",
                    style = ApolloTypography.titleLarge
                )
                StatusBadge(text = "Optimal", type = BadgeType.READY)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your body is primed for today. High heart rate variability and good rest have you ready to go.",
                    style = ApolloTypography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                // 3D Apple/Heart Mascot Asset Slot
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .background(WarmCardTint, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "🍎", fontSize = 32.sp)
                }
            }
        }

        // 2. Metrics Split Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Metric A: Heart Rate
            Box(modifier = Modifier.weight(1f)) {
                WarmCard {
                    Text(
                        text = "Heart rate",
                        style = ApolloTypography.titleLarge.copy(fontSize = 16.sp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "72",
                            style = ApolloTypography.headlineLarge
                        )
                        Text(
                            text = " bpm",
                            style = ApolloTypography.bodyMedium,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Sparkline(
                        points = listOf(65f, 72f, 68f, 74f, 71f, 76f, 72f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                    )
                }
            }

            // Metric B: Body Battery
            Box(modifier = Modifier.weight(1f)) {
                WarmCard {
                    Text(
                        text = "Body battery",
                        style = ApolloTypography.titleLarge.copy(fontSize = 16.sp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "84",
                            style = ApolloTypography.headlineLarge
                        )
                        Text(
                            text = " %",
                            style = ApolloTypography.bodyMedium,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    LinearProgressIndicator(
                        progress = { 0.84f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = AccentCoral,
                        trackColor = SoftBorder
                    )
                }
            }
        }

        // 3. Bottom Primary Action
        PrimaryTerracottaButton(
            text = "Take a measurement",
            onClick = onNavigateToMeasure,
            modifier = Modifier.padding(top = 8.dp, bottom = 80.dp)
        )
    }
}
```

---

## 6. Prompting Guide for Claude

When delegating screen generation to Claude with this document, use this prompt structure:

> *"Please implement the **[Screen Name, e.g. Phone Readiness Screen]** in Kotlin Jetpack Compose according to the `Apollo Health UI Implementation Guide`. Ensure the top bar uses `ApolloScaffold` with the sunset gradient fade (`SunsetCoral` to `SunsetYellow` fading into `WarmCanvas`). All cards must use `WarmCard` with 0dp elevation and soft borders. Match the typographic scale and badge colors specified in the document."*