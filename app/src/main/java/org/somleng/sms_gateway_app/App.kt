package org.somleng.sms_gateway_app

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.somleng.sms_gateway_app.feature.main.MainScreen
import org.somleng.sms_gateway_app.feature.main.MainViewModel
import org.somleng.sms_gateway_app.feature.settings.SettingsScreen
import org.somleng.sms_gateway_app.feature.settings.SettingsViewModel
import org.somleng.sms_gateway_app.ui.components.AppScaffold
import org.somleng.sms_gateway_app.ui.navigation.Routes
import org.somleng.sms_gateway_app.ui.theme.SomlengTheme
import org.somleng.sms_gateway_app.R

@Composable
fun SomlengApp(
    mainViewModel: MainViewModel,
    settingsViewModel: SettingsViewModel
) {
    val navController = rememberNavController()

    val navBackStackEntry = navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry.value?.destination?.route

    val title = when (currentRoute) {
        Routes.MAIN -> stringResource(R.string.app_name)
        Routes.SETTINGS -> stringResource(R.string.settings)
        else -> stringResource(R.string.app_name)
    }

    AppScaffold(
        navController = navController,
        title = title
    ) { topPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.MAIN
        ) {
            composable(Routes.MAIN) {
                MainScreen(
                    viewModel = mainViewModel,
                    modifier = Modifier.padding(top = topPadding)

                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    modifier = Modifier.padding(top = topPadding)
                )
            }
        }
    }
}

