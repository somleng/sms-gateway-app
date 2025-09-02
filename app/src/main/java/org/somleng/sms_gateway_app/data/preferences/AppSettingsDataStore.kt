package org.somleng.sms_gateway_app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

private val DEVICE_KEY = stringPreferencesKey("device_key")

class AppSettingsDataStore(private val context: Context) {
    val deviceKeyFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[DEVICE_KEY]
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
}
