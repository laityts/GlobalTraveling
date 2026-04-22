package com.kankan.globaltraveling.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun ShadowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)
        else -> lightColorScheme(primary = Purple40, secondary = PurpleGrey40, tertiary = Pink40)
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}