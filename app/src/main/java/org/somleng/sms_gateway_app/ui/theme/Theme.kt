package org.somleng.sms_gateway_app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun SomlengTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context)
    } else {
        lightColorScheme(
            primary = SomlengPrimary,
            secondary = SomlengSecondary,
            error = SomlengError
        )
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = SomlengTypography,
        shapes = SomlengShapes,
        content = content
    )
}
