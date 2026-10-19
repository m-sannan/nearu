package app.nearu.finder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Paper = Color(0xFFFFF8F0)
val Ink = Color(0xFF1A1A1A)
val Muted = Color(0xFF5E584F)
val Line = Color(0xFFE6DDD0)
val Signal = Color(0xFFC2410C)
val Good = Color(0xFF166534)
val Bad = Color(0xFF9F1239)
val Amber = Color(0xFFA16207)

private val Colors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Signal,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    error = Bad,
    onError = Color.White,
)

@Composable
fun NearUTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
