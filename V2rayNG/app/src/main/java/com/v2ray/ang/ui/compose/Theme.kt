package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val LightColor = lightColorScheme(
    primary = Color(0xFF000000), // Black
    onPrimary = Color(0xFFFFFFFF), // White
    primaryContainer = Color(0xFFE0E0E0), // Light Gray
    onPrimaryContainer = Color(0xFF000000), // Black
    secondary = Color(0xFF718A99), // Steel
    onSecondary = Color(0xFFFFFFFF), // White
    secondaryContainer = Color(0xFFFFE8D6), // Pale Orange
    onSecondaryContainer = Color(0xFF2B1700), // Dark Brown
    tertiary = Color(0xFF009966), // Green
    onTertiary = Color(0xFFFFFFFF), // White
    tertiaryContainer = Color(0xFFA0F2D0), // Light Green
    onTertiaryContainer = Color(0xFF00201A), // Dark Teal
    error = Color(0xFFBA1A1A), // Red
    errorContainer = Color(0xFFFFDAD6), // Light Red
    onError = Color(0xFFFFFFFF), // White
    onErrorContainer = Color(0xFF410002), // Dark Red
    background = Color(0xFFFFFFFF), // White
    onBackground = Color(0xFF1C1B1F), // Near Black
    surface = Color(0xFFFFFFFF), // White
    onSurface = Color(0xFF1C1B1F), // Near Black
    surfaceVariant = Color(0xFFE7E0EC), // Light Purple Gray
    onSurfaceVariant = Color(0xFF49454F), // Dark Gray
    outline = Color(0xFF79747E), // Medium Gray
    outlineVariant = Color(0xFFCAC4D0), // Light Gray
    inverseSurface = Color(0xFF313033), // Dark Gray
    inverseOnSurface = Color(0xFFF4EFF4), // Very Light Gray
    inversePrimary = Color(0xFFC0C0C0), // Silver Gray
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF000000), // Black
    surfaceContainerLowest = Color(0xFFFFFFFF), // White
    surfaceContainerLow = Color(0xFFF7F7F7), // Very Light Gray
    surfaceContainer = Color(0xFFF1F1F1), // Light Gray
    surfaceContainerHigh = Color(0xFFEBEBEB), // Light Gray
    surfaceContainerHighest = Color(0xFFE5E5E5), // Light Gray
)

private val DarkColor = darkColorScheme(
    primary = Color(0xFFD9E1E6), // Silver
    onPrimary = Color(0xFF10151A), // Graphite
    primaryContainer = Color(0xFF26313A), // Steel
    onPrimaryContainer = Color(0xFFEEF2F5), // Soft white
    secondary = Color(0xFF718A99), // Steel
    onSecondary = Color(0xFF10151A), // Graphite
    secondaryContainer = Color(0xFF26343D), // Steel surface
    onSecondaryContainer = Color(0xFFD9E1E6), // Silver
    tertiary = Color(0xFF91AA9D), // Muted mint
    onTertiary = Color(0xFF00382E), // Dark Teal
    tertiaryContainer = Color(0xFF263B35), // Muted forest
    onTertiaryContainer = Color(0xFFA0F2D0), // Light Green
    error = Color(0xFFFFB4AB), // Light Red
    errorContainer = Color(0xFF93000A), // Dark Red
    onError = Color(0xFF690005), // Deep Red
    onErrorContainer = Color(0xFFFFDAD6), // Light Red
    background = Color(0xFF0B0D10), // Graphite
    onBackground = Color(0xFFEEF2F5), // Soft white
    surface = Color(0xFF0B0D10), // Graphite
    onSurface = Color(0xFFEEF2F5), // Soft white
    surfaceVariant = Color(0xFF1A2229), // Panel
    onSurfaceVariant = Color(0xFFA9B5BD), // Muted text
    outline = Color(0xFF53636D), // Steel outline
    outlineVariant = Color(0xFF29343B), // Subtle outline
    inverseSurface = Color(0xFFE6E1E5), // Light Gray
    inverseOnSurface = Color(0xFF1C1B1F), // Near Black
    inversePrimary = Color(0xFF000000), // Black
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFFC0C0C0), // Silver Gray
    surfaceContainerLowest = Color(0xFF080A0D), // Deep graphite
    surfaceContainerLow = Color(0xFF10151A), // Graphite panel
    surfaceContainer = Color(0xFF13191F), // Card
    surfaceContainerHigh = Color(0xFF1A2229), // Raised card
    surfaceContainerHighest = Color(0xFF222D35), // Highest card
)

// Semantic Colors
val colorPing = Color(0xFF91AA9D) // Muted mint
val colorPingRed = Color(0xFFFF0099) // Pink Red
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
val toastInfoBg = Color(0xD93F51B5) // Indigo Blue
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

object ThemeManager {
    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, true)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, true)
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
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColor
        else -> LightColor
    }
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
 * фон #0B0D10, карточки #13191F, акцент #D9E1E6. Для нативных экранов приложения (активация, блокировка).
 */
object SiberiaColors {
    val Bg = Color(0xFF0B0D10)
    val Card = Color(0xFF13191F)
    val Border = Color(0xFF53636D)
    val Accent = Color(0xFFD9E1E6)
    val AccentDeep = Color(0xFF718A99)
    val Text = Color(0xFFEEF2F5)
    val Muted = Color(0xFFA9B5BD)
    val Ok = Color(0xFF91AA9D)
    val Err = Color(0xFFFF6B7A)
}

private val ArcticScheme = darkColorScheme(
    primary = SiberiaColors.Accent,
    onPrimary = Color(0xFF03121F),
    primaryContainer = Color(0xFF0B3A66),
    onPrimaryContainer = SiberiaColors.Text,
    secondary = SiberiaColors.AccentDeep,
    onSecondary = Color.White,
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
    outlineVariant = Color(0xFF123556),
    surfaceContainer = SiberiaColors.Card,
    surfaceContainerHigh = Color(0xFF16305A),
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
