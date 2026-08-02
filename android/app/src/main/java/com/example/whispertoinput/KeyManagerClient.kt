package com.example.whispertoinput

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.example.keymanager.IMistralKeyManager

/**
 * Client for communicating with Key Manager app's service.
 * Handles binding, key retrieval, and error handling.
 */
class KeyManagerClient(private val context: Context) {
    private val TAG = "KeyManagerClient"
    private var service: IMistralKeyManager? = null
    private var bound = false
    private var bindAttempted = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            this@KeyManagerClient.service = IMistralKeyManager.Stub.asInterface(service)
            bound = true
            Log.d(TAG, "Connected to Key Manager service")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            Log.d(TAG, "Disconnected from Key Manager service")
        }
    }

    /**
     * Bind to key-manager service.
     * @return true if binding initiated, false if key-manager not installed
     */
    fun bind(): Boolean {
        if (bound) return true
        if (bindAttempted) return false

        val intent = Intent("com.example.keymanager.MISTRAL_KEY_SERVICE")
        intent.setPackage("com.example.keymanager")

        return try {
            val result = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            bindAttempted = true
            if (!result) {
                Log.w(TAG, "Key Manager app not installed or not accessible")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to Key Manager: ${e.message}")
            false
        }
    }

    /**
     * Get a valid API key from key-manager.
     * @return Valid API key, or null if unavailable
     */
    fun getValidApiKey(): String? {
        if (!bound) {
            Log.w(TAG, "Not bound to Key Manager service")
            return null
        }

        return try {
            service?.validApiKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get API key: ${e.message}")
            null
        }
    }

    /**
     * Check if key-manager has credentials configured.
     * @return true if credentials exist
     */
    fun hasCredentials(): Boolean {
        if (!bound) return false

        return try {
            service?.hasCredentials() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check credentials: ${e.message}")
            false
        }
    }

    /**
     * Unbind from key-manager service.
     */
    fun unbind() {
        if (bound) {
            try {
                context.unbindService(connection)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unbind: ${e.message}")
            }
            bound = false
            service = null
        }
    }

    /**
     * Check if service is currently bound.
     */
    fun isBound(): Boolean = bound
}
