package org.somleng.sms_gateway_app.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.somleng.sms_gateway_app.R
import org.somleng.sms_gateway_app.ui.components.Footer
import org.somleng.sms_gateway_app.ui.components.PhoneNumberField
import org.somleng.sms_gateway_app.ui.components.PrimaryButton
import org.somleng.sms_gateway_app.ui.theme.SomlengTheme

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.ui.collectAsStateWithLifecycle()

    SettingsScreenContent(
        uiState = uiState,
        onPhoneChange = viewModel::onPhoneChange,
        onServerChange = viewModel::onServerChange,
        onSave = viewModel::onSave,
        modifier = modifier
    )
}

@Composable
private fun SettingsScreenContent(
    uiState: SettingsUiState,
    onPhoneChange: (String) -> Unit,
    onServerChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    var advancedExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top
        ) {
            PhoneNumberField(
                value = uiState.phoneNumberInput,
                onValueChange = onPhoneChange,
                label = stringResource(R.string.phone_number_input),
                supportingText = stringResource(R.string.phone_number_hint),
                isError = uiState.phoneNumberInput.isNotEmpty() && !uiState.isPhoneValid,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Advanced Settings - Dev only
            if (uiState.isDev) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedExpanded = !advancedExpanded }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.advanced_settings),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Icon(
                        imageVector = if (advancedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null
                    )
                }

                AnimatedVisibility(visible = advancedExpanded) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = uiState.serverHostInput,
                            onValueChange = onServerChange,
                            label = { Text(stringResource(R.string.server_host_input)) },
                            singleLine = true,
                            supportingText = {
                                Text(
                                    if (uiState.serverHostInput.isNotEmpty() && !uiState.isServerHostValid) {
                                        stringResource(R.string.server_host_error)
                                    } else {
                                        stringResource(R.string.server_host_hint)
                                    }
                                )
                            },
                            isError = uiState.serverHostInput.isNotEmpty() && !uiState.isServerHostValid,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            PrimaryButton(
                text = stringResource(R.string.save),
                onClick = onSave,
                enabled = uiState.canSave,
                isLoading = uiState.isSaving,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Footer(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    SomlengTheme {
        SettingsScreenContent(
            uiState = SettingsUiState(
                phoneNumber = "+85510123456",
                phoneNumberInput = "+85512345678",
                serverHost = "ws://10.0.2.2:8080",
                serverHostInput = "ws://10.0.2.2:8080",
                isDev = true
            ),
            onPhoneChange = {},
            onServerChange = {},
            onSave = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}
