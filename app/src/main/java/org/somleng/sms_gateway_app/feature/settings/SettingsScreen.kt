package org.somleng.sms_gateway_app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
        onSave = viewModel::onSave,
        modifier = modifier
    )
}

@Composable
private fun SettingsScreenContent(
    uiState: SettingsUiState,
    onPhoneChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                value = uiState.phoneNumber,
                onValueChange = onPhoneChange,
                label = stringResource(R.string.phone_number),
                supportingText = stringResource(R.string.phone_number_helper),
                isError = uiState.phoneNumber.isNotEmpty() && !uiState.isValid,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

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
                phoneNumber = "+85512345678",
                isValid = true,
                hasChanges = true,
                savedPhoneNumber = "+85510123456",
            ),
            onPhoneChange = {},
            onSave = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}
