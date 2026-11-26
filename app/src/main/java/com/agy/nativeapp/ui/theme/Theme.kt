package com.agy.nativeapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = AgyPrimaryDark,
    secondary = AgySecondaryDark,
    tertiary = AgyTertiaryDark,
    error = AgyErrorDark,
    background = AgyBgDark,
    surface = AgySurfaceDark,
    surfaceVariant = AgySurfaceVariantDark,
    onBackground = AgyOnSurfaceDark,
    onSurface = AgyOnSurfaceDark,
    onSurfaceVariant = AgyOnSurfaceMutedDark,
    outline = AgyOutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = AgyPrimaryLight,
    secondary = AgySecondaryLight,
    tertiary = AgyTertiaryLight,
    error = AgyErrorLight,
    background = AgyBgLight,
    surface = AgySurfaceLight,
    surfaceVariant = AgySurfaceVariantLight,
    onBackground = AgyOnSurfaceLight,
    onSurface = AgyOnSurfaceLight,
    onSurfaceVariant = AgyOnSurfaceMutedLight,
    outline = AgyOutlineLight
)

@Composable
fun AgyNativeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AgyTypography,
        content = content
    )
}
