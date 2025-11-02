package org.somleng.sms_gateway_app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "settings")

private object Keys {
    val DEVICE_KEY = stringPreferencesKey("device_key")
    val RECEIVING_ENABLED = booleanPreferencesKey("receiving_enabled")
    val SENDING_ENABLED = booleanPreferencesKey("sending_enabled")
    val PHONE_NUMBER = stringPreferencesKey("phone_number")
}

class SettingsDataStore(private val context: Context) {
    
    private val safeData: Flow<Preferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    val deviceKey: Flow<String?> = safeData.map { preferences ->
        preferences[Keys.DEVICE_KEY]
    }

    val receivingEnabled: Flow<Boolean> = safeData.map { preferences ->
        preferences[Keys.RECEIVING_ENABLED] ?: true
    }

    val sendingEnabled: Flow<Boolean> = safeData.map { preferences ->
        preferences[Keys.SENDING_ENABLED] ?: true
    }

    val phoneNumber: Flow<String?> = safeData.map { preferences ->
        preferences[Keys.PHONE_NUMBER]
    }

    suspend fun setDeviceKey(deviceKey: String) {
        context.dataStore.edit { settings ->
            settings[Keys.DEVICE_KEY] = deviceKey
        }
    }

    suspend fun clearDeviceKey() {
        context.dataStore.edit { settings ->
            settings.remove(Keys.DEVICE_KEY)
        }
    }

    suspend fun setReceivingEnabled(enabled: Boolean) {
        context.dataStore.edit { settings ->
            settings[Keys.RECEIVING_ENABLED] = enabled
        }
    }

    suspend fun setSendingEnabled(enabled: Boolean) {
        context.dataStore.edit { settings ->
            settings[Keys.SENDING_ENABLED] = enabled
        }
    }

    suspend fun setPhoneNumber(phoneNumber: String) {
        context.dataStore.edit { settings ->
            settings[Keys.PHONE_NUMBER] = phoneNumber
        }
    }

    suspend fun clearPhoneNumber() {
        context.dataStore.edit { settings ->
            settings.remove(Keys.PHONE_NUMBER)
        }
    }

    suspend fun getDeviceKey(): String? {
        return deviceKey.first()
    }

    suspend fun isReceivingEnabled(): Boolean {
        return receivingEnabled.first()
    }

    suspend fun isSendingEnabled(): Boolean {
        return sendingEnabled.first()
    }

    suspend fun getPhoneNumber(): String? {
        return phoneNumber.first()
    }
}

