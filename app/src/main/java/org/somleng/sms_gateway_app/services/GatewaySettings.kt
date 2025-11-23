package org.somleng.sms_gateway_app.services

import android.content.Context
import org.somleng.sms_gateway_app.data.preferences.SettingsDataStore

/**
 * Interface for accessing gateway settings.
 */
interface GatewaySettings {
    suspend fun isReceivingEnabled(): Boolean
    suspend fun isSendingEnabled(): Boolean
    suspend fun getServerHost(): String?
    suspend fun getPhoneNumber(): String?
    suspend fun getDeviceKey(): String?
}

class DataStoreGatewaySettings(private val context: Context) : GatewaySettings {
    private val settingsDataStore = SettingsDataStore(context)

    override suspend fun isReceivingEnabled(): Boolean {
        return settingsDataStore.isReceivingEnabled()
    }

    override suspend fun isSendingEnabled(): Boolean {
        return settingsDataStore.isSendingEnabled()
    }

    override suspend fun getServerHost(): String? {
        return settingsDataStore.getServerHost()
    }

    override suspend fun getPhoneNumber(): String? {
        return settingsDataStore.getPhoneNumber()
    }

    override suspend fun getDeviceKey(): String? {
        return settingsDataStore.getDeviceKey()
    }
}


