package com.v2ray.ang.ui.compose

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val LightColor = lightColorScheme(
    primary = Color(0xFF13191F), // Graphite
    onPrimary = Color(0xFFEEF2F5), // Soft white
    primaryContainer = Color(0xFFD9E1E6), // Silver
    onPrimaryContainer = Color(0xFF10151A), // Graphite
    secondary = Color(0xFF718A99), // Steel
    onSecondary = Color(0xFFFFFFFF), // White
    secondaryContainer = Color(0xFFE5EAED), // Silver surface
    onSecondaryContainer = Color(0xFF1A2229), // Graphite
    tertiary = Color(0xFF5E7D6D), // Muted forest
    onTertiary = Color(0xFFFFFFFF), // White
    tertiaryContainer = Color(0xFFDCE8E1), // Pale mint
    onTertiaryContainer = Color(0xFF18362A), // Forest
    error = Color(0xFFBA1A1A), // Red
    errorContainer = Color(0xFFFFDAD6), // Light Red
    onError = Color(0xFFFFFFFF), // White
    onErrorContainer = Color(0xFF410002), // Dark Red
    background = Color(0xFFFFFFFF), // White
    onBackground = Color(0xFF1C1B1F), // Near Black
    surface = Color(0xFFFFFFFF), // White
    onSurface = Color(0xFF1C1B1F), // Near Black
    surfaceVariant = Color(0xFFE8EDF0), // Cool gray
    onSurfaceVariant = Color(0xFF53636D), // Steel
    outline = Color(0xFF77858E), // Steel outline
    outlineVariant = Color(0xFFD2DCE1), // Silver line
    inverseSurface = Color(0xFF313033), // Dark Gray
    inverseOnSurface = Color(0xFFF4EFF4), // Very Light Gray
    inversePrimary = Color(0xFFD9E1E6), // Silver
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF53636D), // Steel
    surfaceContainerLowest = Color(0xFFFFFFFF), // White
    surfaceContainerLow = Color(0xFFF7F7F7), // Very Light Gray
    surfaceContainer = Color(0xFFF1F1F1), // Light Gray
    surfaceContainerHigh = Color(0xFFEBEBEB), // Light Gray
    surfaceContainerHighest = Color(0xFFE5E5E5), // Light Gray
)

private val DarkColor = darkColorScheme(
    primary = Color(0xFF7DDFFF), // Silver
    onPrimary = Color(0xFF061625), // Graphite
    primaryContainer = Color(0xFF143C54), // Steel
    onPrimaryContainer = Color(0xFFF4FBFF), // Soft white
    secondary = Color(0xFFA6CADC), // Steel
    onSecondary = Color(0xFF0A2739), // Graphite
    secondaryContainer = Color(0xFF15394D), // Steel surface
    onSecondaryContainer = Color(0xFFD7F3FC), // Silver
    tertiary = Color(0xFF57D6B4), // Muted mint
    onTertiary = Color(0xFF07352B), // Dark Teal
    tertiaryContainer = Color(0xFF0F4038), // Muted forest
    onTertiaryContainer = Color(0xFFCBF6E8), // Pale mint
    error = Color(0xFFFFB4AB), // Light Red
    errorContainer = Color(0xFF93000A), // Dark Red
    onError = Color(0xFF690005), // Deep Red
    onErrorContainer = Color(0xFFFFDAD6), // Light Red
    background = Color(0xFF061320), // Graphite
    onBackground = Color(0xFFF1F8FC), // Soft white
    surface = Color(0xFF0B1C2A), // Graphite
    onSurface = Color(0xFFF1F8FC), // Soft white
    surfaceVariant = Color(0xFF183449), // Panel
    onSurfaceVariant = Color(0xFFB0C9D8), // Muted text
    outline = Color(0xFF3B708A), // Steel outline
    outlineVariant = Color(0xFF245168), // Subtle outline
    inverseSurface = Color(0xFFE6E1E5), // Light Gray
    inverseOnSurface = Color(0xFF1C1B1F), // Near Black
    inversePrimary = Color(0xFF8CCFEC), // Steel
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF7DDFFF), // Silver
    surfaceContainerLowest = Color(0xFF050D17), // Deep graphite
    surfaceContainerLow = Color(0xFF0A1B2A), // Graphite panel
    surfaceContainer = Color(0xFF102A3B), // Card
    surfaceContainerHigh = Color(0xFF183A50), // Raised card
    surfaceContainerHighest = Color(0xFF214C64), // Highest card
)

// Semantic Colors
val colorPing = Color(0xFF91AA9D) // Muted mint
val colorPingRed = Color(0xFFC78F96) // Muted rose
val colorConfigType = Color(0xFF718A99) // Steel
val colorFabActive = Color(0xFFD9E1E6) // Silver
val colorFabInactiveLight = Color(0xFF9C9C9C) // Gray
val colorFabInactiveDark = Color(0xFF646464) // Dark Gray
val dividerColorLight = Color(0xFFE0E0E0) // Light Gray
val dividerColorDark = Color(0xFF424242) // Dark Gray

// Toast Colors 85%
val toastNormalBgLight = Color(0xD9353A3E) // Dark Gray
val toastNormalBgDark = Color(0xD94A4F54) // Darker Gray
val toastSuccessBg = Color(0xD9388E3C) // Green
val toastErrorBg = Color(0xD9D50000) // Red
val toastInfoBg = Color(0xD9475A67) // Steel
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

object ThemeManager {
    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(false)
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    // Dynamic system colors are disabled to keep Siberia Wolf branding identical on every device.
    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, false)
        _dynamicColorEnabled.value = false
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, false)
        _dynamicColorEnabled.value = false
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    return when (mode) {
        "1" -> false
        "2" -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { false }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColor else LightColor
    val snackbarController = rememberAppSnackbarController()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = colorScheme
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}

/**
 * Фирменная «арктическая» палитра Siberia Connect — те же цвета, что в кабинете (Windows / Mini App / Android WebView):
 * фон #061320, карточки #102A3B, акцент #7DDFFF. Системные динамические цвета по умолчанию отключены, чтобы брендовая палитра оставалась единой.
 */
object SiberiaColors {
    // Aurora Signature: the same optical hierarchy as the website and Mini App.
    val Bg = Color(0xFF061320)
    val Card = Color(0xFF102A3B)
    val Border = Color(0xFF3B708A)
    val Accent = Color(0xFF7DDFFF)
    val AccentDeep = Color(0xFFA6CADC)
    val Text = Color(0xFFF1F8FC)
    val Muted = Color(0xFFB0C9D8)
    val Ok = Color(0xFF57D6B4)
    val Err = Color(0xFFFF6B7A)
}

private val ArcticScheme = darkColorScheme(
    primary = SiberiaColors.Accent,
    onPrimary = Color(0xFF061625),
    primaryContainer = Color(0xFF143C54),
    onPrimaryContainer = SiberiaColors.Text,
    secondary = SiberiaColors.AccentDeep,
    onSecondary = Color(0xFF10151A),
    tertiary = SiberiaColors.Ok,
    error = SiberiaColors.Err,
    onError = Color(0xFF2A0008),
    background = SiberiaColors.Bg,
    onBackground = SiberiaColors.Text,
    surface = SiberiaColors.Bg,
    onSurface = SiberiaColors.Text,
    surfaceVariant = SiberiaColors.Card,
    onSurfaceVariant = SiberiaColors.Muted,
    outline = SiberiaColors.Border,
    outlineVariant = Color(0xFF245168),
    surfaceContainer = SiberiaColors.Card,
    surfaceContainerHigh = Color(0xFF183A50),
)

@Composable
fun SiberiaArcticTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // тёмный фон — светлые значки в статус-баре и панели навигации
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(colorScheme = ArcticScheme) {
        Box(Modifier.fillMaxSize().background(SiberiaColors.Bg)) { content() }
    }
}
