package com.gostafa.raven.core.designsystem

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gostafa.raven.R

internal val RavenColors = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFFF2F2F2),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color.Black,
    onSurfaceVariant = Color(0xFFA3A3A3),
    outline = Color(0xFF303030),
    outlineVariant = Color(0xFF303030),
    primaryContainer = Color.Black, onPrimaryContainer = Color.White,
    inversePrimary = Color.Black,
    secondaryContainer = Color(0xFF141414), onSecondaryContainer = Color.White,
    tertiary = Color.White, onTertiary = Color.Black,
    tertiaryContainer = Color.Black, onTertiaryContainer = Color.White,
    surfaceTint = Color.Transparent,
    inverseSurface = Color.White, inverseOnSurface = Color.Black,
    scrim = Color.Black,
    error = Color(0xFFF87171), onError = Color.Black,
    errorContainer = Color.Black, onErrorContainer = Color(0xFFF87171),
    surfaceBright = Color.Black, surfaceDim = Color.Black,
    surfaceContainer = Color.Black, surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black, surfaceContainerLow = Color.Black,
    surfaceContainerLowest = Color.Black,
)

@Composable
fun RavenTheme(content: @Composable () -> Unit) {
    val display = if (LocalConfiguration.current.locales[0].language == "ar") FontFamily.SansSerif
        else FontFamily(Font(R.font.fredericka_the_great))
    val base = Typography()
    val typography = base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = display, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
        headlineLarge = base.headlineLarge.copy(fontFamily = display, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = display, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = display, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    )
    CompositionLocalProvider(LocalTonalElevationEnabled provides false) {
        MaterialTheme(
            colorScheme = RavenColors, typography = typography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp),
                extraLarge = RoundedCornerShape(12.dp),
            ),
        ) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}
