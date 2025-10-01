package org.somleng.sms_gateway_app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "settings")

private val DEVICE_KEY = stringPreferencesKey("device_key")
private val RECEIVING_ENABLED_KEY = booleanPreferencesKey("receiving_enabled")
private val SENDING_ENABLED_KEY = booleanPreferencesKey("sending_enabled")

class AppSettingsDataStore(private val context: Context) {
    val deviceKeyFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[DEVICE_KEY]
        }

    val receivingEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[RECEIVING_ENABLED_KEY] ?: true
        }

    val sendingEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SENDING_ENABLED_KEY] ?: true
        }

    suspend fun saveDeviceKey(deviceKey: String) {
        context.dataStore.edit { settings ->
            settings[DEVICE_KEY] = deviceKey
        }
    }

    suspend fun clearDeviceKey() {
        context.dataStore.edit { settings ->
            settings.remove(DEVICE_KEY)
        }
    }

    suspend fun setReceivingEnabled(enabled: Boolean) {
        context.dataStore.edit { settings ->
            settings[RECEIVING_ENABLED_KEY] = enabled
        }
    }

    suspend fun setSendingEnabled(enabled: Boolean) {
        context.dataStore.edit { settings ->
            settings[SENDING_ENABLED_KEY] = enabled
        }
    }

    suspend fun isReceivingEnabled(): Boolean {
        return receivingEnabledFlow.first()
    }

    suspend fun isSendingEnabled(): Boolean {
        return sendingEnabledFlow.first()
    }
}
