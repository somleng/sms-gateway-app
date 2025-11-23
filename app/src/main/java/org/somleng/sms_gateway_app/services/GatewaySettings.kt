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
}

/**
 * Default implementation backed by SettingsDataStore.
 */
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
}

