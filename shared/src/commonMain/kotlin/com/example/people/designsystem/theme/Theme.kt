package com.example.people.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Shared Material 3 Theme for the People application.
 * Automatically adapts to system light and dark themes.
 */
@Composable
fun PeopleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PeopleTypography,
        shapes = PeopleShapes,
        content = content
    )
}
