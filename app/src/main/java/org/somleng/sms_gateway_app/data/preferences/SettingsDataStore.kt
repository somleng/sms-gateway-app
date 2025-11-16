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

// Declare an extension property on Context
private val Context.dataStore by preferencesDataStore(name = "settings")

private object Keys {
    val DEVICE_KEY = stringPreferencesKey("device_key")
    val PHONE_NUMBER = stringPreferencesKey("phone_number")
    val SERVER_HOST = stringPreferencesKey("server_host")
    val RECEIVING_ENABLED = booleanPreferencesKey("receiving_enabled")
    val SENDING_ENABLED = booleanPreferencesKey("sending_enabled")
}

class SettingsDataStore(private val context: Context) {
    private val dataFlow: Flow<Preferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }


    val deviceKey: Flow<String?> = preferenceOf(Keys.DEVICE_KEY)
    val phoneNumber: Flow<String?> = preferenceOf(Keys.PHONE_NUMBER)
    val serverHost: Flow<String?> = preferenceOf(Keys.SERVER_HOST)
    val receivingEnabled: Flow<Boolean> = preferenceOfWithDefault(Keys.RECEIVING_ENABLED, true)
    val sendingEnabled: Flow<Boolean> = preferenceOfWithDefault(Keys.SENDING_ENABLED, true)

    suspend fun setDeviceKey(deviceKey: String) = setPreference(Keys.DEVICE_KEY, deviceKey)
    suspend fun getDeviceKey(): String? = deviceKey.first()
    suspend fun clearDeviceKey() = clearPreference(Keys.DEVICE_KEY)

    suspend fun setReceivingEnabled(enabled: Boolean) =
        setPreference(Keys.RECEIVING_ENABLED, enabled)

    suspend fun isReceivingEnabled(): Boolean = receivingEnabled.first()

    suspend fun setSendingEnabled(enabled: Boolean) =
        setPreference(Keys.SENDING_ENABLED, enabled)

    suspend fun isSendingEnabled(): Boolean = sendingEnabled.first()

    suspend fun setPhoneNumber(phoneNumber: String) =
        setPreference(Keys.PHONE_NUMBER, phoneNumber)

    suspend fun getPhoneNumber(): String? = phoneNumber.first()

    suspend fun setServerHost(serverHost: String) =
        setPreference(Keys.SERVER_HOST, serverHost)

    suspend fun getServerHost(): String? = serverHost.first()

    private fun <T> preferenceOf(key: Preferences.Key<T>): Flow<T?> {
        return dataFlow.map { preferences -> preferences[key] }
    }

    private fun <T> preferenceOfWithDefault(key: Preferences.Key<T>, defaultValue: T): Flow<T> {
        return dataFlow.map { preferences -> preferences[key] ?: defaultValue }
    }

    private suspend fun <T> setPreference(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { settings ->
            settings[key] = value
        }
    }

    private suspend fun clearPreference(key: Preferences.Key<*>) {
        context.dataStore.edit { settings ->
            settings.remove(key)
        }
    }
}
