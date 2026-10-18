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
// Tokens from apollo_health_ui_implementation_guide.md: warm cream canvas, coral/sunset accent,
// sage-green "ready" and amber "cooling" signal colors.

private val LightScheme = lightColorScheme(
    primary = Color(0xFFE86A58), // AccentCoral
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFDF0ED), // WarmCardTint
    onPrimaryContainer = Color(0xFFCE503E), // AccentCoralDark
    secondary = Color(0xFFD48B38), // AmberWarning
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFDF3E7), // AmberContainer
    onSecondaryContainer = Color(0xFF6B4A1D),
    tertiary = Color(0xFF4FA070), // SageGreen
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEBF6EC), // SageGreenContainer
    onTertiaryContainer = Color(0xFF1F5A38),
    background = Color(0xFFF7F5F0), // WarmCanvas
    onBackground = Color(0xFF1B1A18), // TextPrimary
    surface = Color(0xFFFFFFFF), // CardSurface
    onSurface = Color(0xFF1B1A18),
    surfaceVariant = Color(0xFFECEAE4), // NeutralPill
    onSurfaceVariant = Color(0xFF706C64), // TextSecondary
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFAF7),
    surfaceContainer = Color(0xFFF7F5F0),
    surfaceContainerHigh = Color(0xFFEFEDE7),
    outline = Color(0xFFA5A29B), // TextDisabled
    outlineVariant = Color(0xFFE8E5DF), // SoftBorder
    inverseSurface = Color(0xFF2F2D29),
    inverseOnSurface = Color(0xFFF7F5F0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF0917F),
    onPrimary = Color(0xFF4A160D),
    primaryContainer = Color(0xFF6B2A1E),
    onPrimaryContainer = Color(0xFFFDF0ED),
    secondary = Color(0xFFE8B274),
    onSecondary = Color(0xFF442C06),
    tertiary = Color(0xFF8FCBA3),
    onTertiary = Color(0xFF0C3A20),
    background = Color(0xFF1A1816),
    onBackground = Color(0xFFEDEAE4),
    surface = Color(0xFF1A1816),
    onSurface = Color(0xFFEDEAE4),
    onSurfaceVariant = Color(0xFFCFCAC1),
    surfaceContainerLow = Color(0xFF231F1C),
    surfaceContainer = Color(0xFF292420),
    surfaceContainerHigh = Color(0xFF342E29),
    outline = Color(0xFF938E85),
    outlineVariant = Color(0xFF433D37),
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

// headerGradient: [SunsetCoral, SunsetYellow, fade-to-transparent] — see GradientHeader, which
// places the middle stop at 55% per the guide's dissipating-sunset treatment.
private val LightApollo = ApolloColors(
    headerGradient = listOf(Color(0xFFEA5A47), Color(0xFFFDBB5D), Color(0x00F7F5F0)),
    headerScrim = Color(0xBDFFFFFF),
    normal = Color(0xFFEBF6EC), onNormal = Color(0xFF4FA070), // SageGreenContainer / SageGreen
    elevated = Color(0xFFFDF3E7), onElevated = Color(0xFFD48B38), // AmberContainer / AmberWarning
    zoneLow = Color(0xFFF4A9BC), zoneNormal = Color(0xFF4FA070), zoneRaised = Color(0xFFFDBB5D),
    zoneHigh = Color(0xFFD48B38), zoneSevere = Color(0xFFEA5A47),
    heart = Color(0xFFCE503E), temperature = Color(0xFF8A4E00), bloodPressure = Color(0xFF7A2FB5),
    oxygen = Color(0xFF0B5CAD), respiratory = Color(0xFF3E3BB8),
)

private val DarkApollo = LightApollo.copy(
    headerGradient = listOf(Color(0xFF6B2A1E), Color(0xFF5A431E), Color(0x001A1816)),
    headerScrim = Color(0x33FFFFFF),
    normal = Color(0xFF1F4A32), onNormal = Color(0xFF8FCBA3),
    elevated = Color(0xFF4A3414), onElevated = Color(0xFFE8B274),
    heart = Color(0xFFF0917F), temperature = Color(0xFFFFB960), bloodPressure = Color(0xFFFFB0BC),
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

/**
 * The dissipating sunset gradient used at the top of every screen: coral fading through warm
 * yellow into the cream canvas, per apollo_health_ui_implementation_guide.md section 1.4/3.1.
 * Draws behind the status bar.
 */
@Composable
fun GradientHeader(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ApolloTheme.colors.headerGradient
    val brush = Brush.verticalGradient(
        0.00f to colors[0],
        0.55f to colors[1],
        1.00f to colors[2],
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
