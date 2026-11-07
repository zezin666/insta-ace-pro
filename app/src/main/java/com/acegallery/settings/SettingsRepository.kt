package com.acegallery.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {

    val cameraHost: Flow<String> = dataStore.data.map { preferences ->
        preferences[HOST_KEY] ?: DEFAULT_HOST
    }

    suspend fun setCameraHost(host: String) {
        dataStore.edit { preferences ->
            preferences[HOST_KEY] = host
        }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.42.1"
        private val HOST_KEY = stringPreferencesKey("camera_host")
    }
}
