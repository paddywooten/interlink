package com.interlink.app.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "interlink")

data class LinkConfig(
    val secretB64: String? = null,
    val host: String? = null,
    val port: Int = 27341,
    val deviceName: String = "Phone",
)

class Prefs(private val context: Context) {
    private object K {
        val SECRET = stringPreferencesKey("secret")
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val NAME = stringPreferencesKey("name")
        val CLIP_FOCUS = booleanPreferencesKey("clip_focus")
    }

    val config = context.dataStore.data.map { p ->
        LinkConfig(
            secretB64 = p[K.SECRET],
            host = p[K.HOST],
            port = p[K.PORT] ?: 27341,
            deviceName = p[K.NAME] ?: android.os.Build.MODEL ?: "Phone",
        )
    }

    suspend fun snapshot(): LinkConfig = config.first()

    /** [host] nullable: after QR pairing the endpoint is found via UDP discovery. */
    suspend fun savePairing(secretB64: String, host: String?, port: Int) {
        context.dataStore.edit { p ->
            p[K.SECRET] = secretB64
            if (host != null) p[K.HOST] = host else p.remove(K.HOST)
            p[K.PORT] = port
        }
    }

    /** [host] null clears the stored endpoint so UDP discovery takes over. */
    suspend fun updateEndpoint(host: String?, port: Int) {
        context.dataStore.edit { p ->
            if (host != null) p[K.HOST] = host else p.remove(K.HOST)
            p[K.PORT] = port
        }
    }

    suspend fun saveName(name: String) {
        context.dataStore.edit { p -> p[K.NAME] = name }
    }

    suspend fun clearPairing() {
        context.dataStore.edit { p ->
            p.remove(K.SECRET)
            p.remove(K.HOST)
        }
    }
}
