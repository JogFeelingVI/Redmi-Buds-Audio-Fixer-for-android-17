package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
    darkColorScheme(
        primary = CrimsonPrimaryDark,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF93000A),
        onPrimaryContainer = Color(0xFFFFDAD6),
        secondary = CyanAccentDark,
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF004F58),
        onSecondaryContainer = Color(0xFF97F0FF),
        tertiary = Color(0xFFFFB4AB),
        background = DarkBackground,
        onBackground = DarkOnBackground,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant
    )

private val LightColorScheme =
    lightColorScheme(
        primary = CrimsonPrimaryLight,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDAD6),
        onPrimaryContainer = Color(0xFF410002),
        secondary = CyanAccentLight,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFF97F0FF),
        onSecondaryContainer = Color(0xFF001F24),
        tertiary = Color(0xFF904A42),
        background = LightBackground,
        onBackground = LightOnBackground,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant
    )

@Composable
fun RedmiBudsAudioFixerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Set false to ensure consistent Redmi brand aesthetic
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
