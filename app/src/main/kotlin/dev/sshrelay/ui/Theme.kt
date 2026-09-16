package dev.sshrelay.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E9B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F7),
    onPrimaryContainer = Color(0xFF0A2E4E),
    secondary = Color(0xFF2E7D5B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEBDC),
    onSecondaryContainer = Color(0xFF0B3A29),
    error = Color(0xFFB3261E),
    surfaceVariant = Color(0xFFE7EDF3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCBF5),
    onPrimary = Color(0xFF00304F),
    primaryContainer = Color(0xFF12496F),
    onPrimaryContainer = Color(0xFFD3E4F7),
    secondary = Color(0xFF8FD5B4),
    onSecondary = Color(0xFF06301F),
)

@Composable
fun SshRelayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
