package fr.parisfogofwar.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(primary = Color(0xFF315C8C), secondary = Color(0xFF745A00))
private val DarkColors = darkColorScheme(primary = Color(0xFFA8C8F5), secondary = Color(0xFFE4C56D))

@Composable
fun LeFlaneurTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
