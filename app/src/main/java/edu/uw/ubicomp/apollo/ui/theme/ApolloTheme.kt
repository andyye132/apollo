package edu.uw.ubicomp.apollo.ui.theme

// Apollo — custom theme over Material 3, matching the "Apollo Health App" design canvas.
//
// Fonts: Plus Jakarta Sans via downloadable Google Fonts. Copy font_certs.xml
// (R.array.com_google_android_gms_fonts_certs) from the Compose samples into res/values.

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.uw.ubicomp.apollo.R

// ---------- Color scheme (Material 3 roles) ----------

private val LightScheme = lightColorScheme(
    primary = Color(0xFFE2654D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFBE1DB),
    onPrimaryContainer = Color(0xFFB2472F),
    secondary = Color(0xFFB07A00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFF0B8),
    onSecondaryContainer = Color(0xFF4D3400),
    tertiary = Color(0xFFE5566D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE1E3),
    onTertiaryContainer = Color(0xFF5C0A1E),
    background = Color(0xFFF5F1EB),
    onBackground = Color(0xFF221E1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF221E1B),
    surfaceVariant = Color(0xFFF2EDE6),
    onSurfaceVariant = Color(0xFF6B635C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF7F2),
    surfaceContainer = Color(0xFFF5F1EB),
    surfaceContainerHigh = Color(0xFFEFE9E1),
    outline = Color(0xFF8A8178),
    outlineVariant = Color(0xFFE5DFD6),
    inverseSurface = Color(0xFF362F2A),
    inverseOnSurface = Color(0xFFFAF2ED),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFB3C1),
    onPrimary = Color(0xFF5C0A1E),
    primaryContainer = Color(0xFF8E2A40),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = Color(0xFFFFDF8A),
    onSecondary = Color(0xFF4D3400),
    tertiary = Color(0xFFFFB0BC),
    onTertiary = Color(0xFF5C0A1E),
    background = Color(0xFF1C1214),
    onBackground = Color(0xFFF3E3E1),
    surface = Color(0xFF1C1214),
    onSurface = Color(0xFFF3E3E1),
    onSurfaceVariant = Color(0xFFD8C3C0),
    surfaceContainerLow = Color(0xFF261A1C),
    surfaceContainer = Color(0xFF2C1F21),
    surfaceContainerHigh = Color(0xFF38282B),
    outline = Color(0xFFA68E8B),
    outlineVariant = Color(0xFF4A3A3C),
)

// ---------- Extended colors (things M3 has no role for) ----------

@Immutable
data class ApolloColors(
    val headerGradient: List<Color>,   // pastel pink → peach → pastel yellow
    val headerScrim: Color,            // translucent cards/chips on the gradient
    val normal: Color, val onNormal: Color,
    val elevated: Color, val onElevated: Color,
    val zoneLow: Color, val zoneNormal: Color, val zoneRaised: Color,
    val zoneHigh: Color, val zoneSevere: Color,
    val heart: Color, val temperature: Color, val bloodPressure: Color,
    val oxygen: Color, val respiratory: Color,
)

private val LightApollo = ApolloColors(
    headerGradient = listOf(Color(0xFFFFD3DD), Color(0xFFFFE2D3), Color(0xFFFFF0B0)),
    headerScrim = Color(0xBDFFFFFF),
    normal = Color(0xFFD7F5DD), onNormal = Color(0xFF0F5223),
    elevated = Color(0xFFFFE7B8), onElevated = Color(0xFF5C3B00),
    zoneLow = Color(0xFFF4A9BC), zoneNormal = Color(0xFF4CC38A), zoneRaised = Color(0xFFFFD15C),
    zoneHigh = Color(0xFFFFAE57), zoneSevere = Color(0xFFF26B5E),
    heart = Color(0xFFB3261E), temperature = Color(0xFF8A4E00), bloodPressure = Color(0xFF7A2FB5),
    oxygen = Color(0xFF0B5CAD), respiratory = Color(0xFF3E3BB8),
)

private val DarkApollo = LightApollo.copy(
    headerGradient = listOf(Color(0xFF1D2C4A), Color(0xFF282750), Color(0xFF35245A)),
    headerScrim = Color(0x33FFFFFF),
    normal = Color(0xFF1F4D2C), onNormal = Color(0xFFB6EFC4),
    elevated = Color(0xFF5A4100), onElevated = Color(0xFFFFDE9C),
    heart = Color(0xFFFFB4AB), temperature = Color(0xFFFFB960), bloodPressure = Color(0xFFFFB0BC),
    oxygen = Color(0xFFA6CBFF), respiratory = Color(0xFFFFB3C1),
)

val LocalApolloColors = staticCompositionLocalOf { LightApollo }

// ---------- Typography ----------

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)
private val jakarta = GoogleFont("Plus Jakarta Sans")

val JakartaFamily = FontFamily(
    Font(googleFont = jakarta, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = jakarta, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = jakarta, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = jakarta, fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = jakarta, fontProvider = provider, weight = FontWeight.ExtraBold),
)

private val base = Typography()

val ApolloTypography = Typography(
    displayMedium = base.displayMedium.copy(fontFamily = JakartaFamily, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, letterSpacing = (-1).sp), // reading value
    headlineLarge = base.headlineLarge.copy(fontFamily = JakartaFamily, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = (-0.6).sp), // "Health"
    titleLarge = base.titleLarge.copy(fontFamily = JakartaFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp),    // top app bar
    titleMedium = base.titleMedium.copy(fontFamily = JakartaFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp),  // card headers
    titleSmall = base.titleSmall.copy(fontFamily = JakartaFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp),    // list item titles
    bodyLarge = base.bodyLarge.copy(fontFamily = JakartaFamily, fontSize = 15.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = JakartaFamily, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = base.bodySmall.copy(fontFamily = JakartaFamily, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = base.labelLarge.copy(fontFamily = JakartaFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp),    // buttons, chips
    labelMedium = base.labelMedium.copy(fontFamily = JakartaFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    labelSmall = base.labelSmall.copy(fontFamily = JakartaFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp),
)

// ---------- Shapes ----------

val ApolloShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),   // status chips, value tooltip
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),      // FAB, inset boxes
    large = RoundedCornerShape(24.dp),       // cards
    extraLarge = RoundedCornerShape(32.dp),  // header bottom corners
)

// ---------- Theme entry point ----------

@Composable
fun ApolloTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalApolloColors provides if (darkTheme) DarkApollo else LightApollo) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = ApolloTypography,
            shapes = ApolloShapes,
            content = content,
        )
    }
}

object ApolloTheme {
    val colors: ApolloColors
        @Composable get() = LocalApolloColors.current
}

// ---------- Gradient header ----------

/** The pastel pink → yellow header used at the top of every screen. Draws behind the status bar. */
@Composable
fun GradientHeader(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val brush = Brush.linearGradient(
        colors = ApolloTheme.colors.headerGradient,
        start = Offset.Zero,
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY), // 135° diagonal
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            .background(brush)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        content = content,
    )
}
