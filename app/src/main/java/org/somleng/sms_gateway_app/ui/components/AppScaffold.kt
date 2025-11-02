package org.somleng.sms_gateway_app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import org.somleng.sms_gateway_app.R
import org.somleng.sms_gateway_app.ui.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    navController: NavController,
    title: String,
    content: @Composable (androidx.compose.ui.unit.Dp) -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val navBackStackEntry = navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry.value?.destination?.route

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == Routes.MAIN,
                    onClick = { navController.navigate(Routes.MAIN) },
                    icon = {
                        Icon(
                            imageVector = if (currentRoute == Routes.MAIN) {
                                Icons.Filled.Home
                            } else {
                                Icons.Outlined.Home
                            },
                            contentDescription = stringResource(R.string.main)
                        )
                    },
                    label = { Text(stringResource(R.string.main)) }
                )
                NavigationBarItem(
                    selected = currentRoute == Routes.SETTINGS,
                    onClick = { navController.navigate(Routes.SETTINGS) },
                    icon = {
                        Icon(
                            imageVector = if (currentRoute == Routes.SETTINGS) {
                                Icons.Filled.Settings
                            } else {
                                Icons.Outlined.Settings
                            },
                            contentDescription = stringResource(R.string.settings_label)
                        )
                    },
                    label = { Text(stringResource(R.string.settings_label)) }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        content(innerPadding.calculateTopPadding())
    }
}

