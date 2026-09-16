package dev.sshrelay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ssh_relay_settings")

/** Persists the non-secret settings as one JSON blob. */
class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        SettingsCodec.decode(prefs[KEY_SETTINGS])
    }

    suspend fun save(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_SETTINGS] = SettingsCodec.encode(settings)
        }
    }

    private companion object {
        val KEY_SETTINGS = stringPreferencesKey("settings_json")
    }
}
