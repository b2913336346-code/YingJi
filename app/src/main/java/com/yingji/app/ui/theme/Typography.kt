package com.yingji.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val ModernSansFamily = FontFamily.SansSerif

fun yingJiTypography(darkTheme: Boolean): Typography {
    val titleColor = if (darkTheme) Color.White else Color(0xFF111827)
    val bodyColor = if (darkTheme) Color(0xFFE5E7EB) else Color(0xFF374151)
    val supportingColor = if (darkTheme) Color(0xFF9CA3AF) else Color(0xFF6B7280)

    return Typography(
        displayLarge = modernTextStyle(32, 40, FontWeight.Medium, titleColor),
        displayMedium = modernTextStyle(28, 36, FontWeight.Medium, titleColor),
        displaySmall = modernTextStyle(24, 32, FontWeight.Medium, titleColor),
        headlineLarge = modernTextStyle(24, 32, FontWeight.Medium, titleColor),
        headlineMedium = modernTextStyle(24, 32, FontWeight.Medium, titleColor),
        headlineSmall = modernTextStyle(20, 28, FontWeight.Medium, titleColor),
        titleLarge = modernTextStyle(24, 32, FontWeight.Medium, titleColor),
        titleMedium = modernTextStyle(20, 28, FontWeight.Medium, titleColor),
        titleSmall = modernTextStyle(14, 20, FontWeight.Medium, titleColor),
        bodyLarge = modernTextStyle(16, 24, FontWeight.Normal, bodyColor),
        bodyMedium = modernTextStyle(16, 24, FontWeight.Normal, bodyColor),
        bodySmall = modernTextStyle(14, 20, FontWeight.Normal, supportingColor),
        labelLarge = modernTextStyle(14, 20, FontWeight.Medium, Color.Unspecified),
        labelMedium = modernTextStyle(12, 16, FontWeight.Medium, Color.Unspecified),
        labelSmall = modernTextStyle(12, 16, FontWeight.Medium, supportingColor),
    )
}

private fun modernTextStyle(
    fontSize: Int,
    lineHeight: Int,
    fontWeight: FontWeight,
    color: Color,
) = TextStyle(
    fontFamily = ModernSansFamily,
    fontWeight = fontWeight,
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
    color = color,
)
