package org.somleng.sms_gateway_app.services

import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Interface for fetching device tokens (e.g., Firebase Cloud Messaging tokens).
 */
interface DeviceTokenProvider {
    suspend fun fetchToken(): Result<String>
}

/**
 * Default implementation that fetches Firebase Cloud Messaging tokens.
 */
class FirebaseDeviceTokenProvider : DeviceTokenProvider {
    override suspend fun fetchToken(): Result<String> {
        return suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (continuation.isActive) {
                        continuation.resume(Result.success(token.orEmpty()))
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(error))
                    }
                }
        }
    }
}

