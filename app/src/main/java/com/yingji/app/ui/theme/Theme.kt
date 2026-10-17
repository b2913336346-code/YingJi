package com.yingji.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.yingji.app.domain.model.ThemeMode

private val LightColorScheme = lightColorScheme(
    primary = YingJiLightPrimary,
    onPrimary = YingJiLightOnPrimary,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFE7EAEC),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF3F474E),
    secondary = androidx.compose.ui.graphics.Color(0xFF626A70),
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFECEEEF),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF41474C),
    tertiary = androidx.compose.ui.graphics.Color(0xFF626A70),
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFECEEEF),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF41474C),
    background = YingJiLightBackground,
    surface = YingJiLightSurface,
    surfaceVariant = YingJiLightSurfaceVariant,
    surfaceDim = androidx.compose.ui.graphics.Color(0xFFE3E5E7),
    surfaceBright = androidx.compose.ui.graphics.Color.White,
    surfaceContainerLowest = androidx.compose.ui.graphics.Color.White,
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF8F8F8),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFF0F1F2),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFE8EAEC),
    outline = YingJiLightOutline,
    outlineVariant = YingJiLightOutline,
)
private val DarkColorScheme = darkColorScheme(
    primary = YingJiDarkPrimary,
    onPrimary = YingJiDarkOnPrimary,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF343A3F),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFE0E3E5),
    secondary = androidx.compose.ui.graphics.Color(0xFFBFC5C9),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF272D31),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF30363A),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFD9DDE0),
    tertiary = androidx.compose.ui.graphics.Color(0xFFBFC5C9),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF272D31),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF30363A),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFD9DDE0),
    background = YingJiDarkBackground,
    onBackground = androidx.compose.ui.graphics.Color.White,
    surface = YingJiDarkSurface,
    onSurface = androidx.compose.ui.graphics.Color(0xFFE5E7EB),
    surfaceVariant = YingJiDarkSurfaceVariant,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9CA3AF),
    surfaceDim = androidx.compose.ui.graphics.Color(0xFF0E1012),
    surfaceBright = androidx.compose.ui.graphics.Color(0xFF34383C),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF0D0F10),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF17191C),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF1A1D20),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF202428),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF282D31),
    outline = YingJiDarkOutline,
    outlineVariant = YingJiDarkOutline,
)

private val YingJiShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun YingJiTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = yingJiTypography(darkTheme),
        shapes = YingJiShapes,
        content = content,
    )
}
