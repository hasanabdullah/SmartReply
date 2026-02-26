package com.personal.smartreply.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val TONE_DESCRIPTION = stringPreferencesKey("tone_description")
        val PERSONAL_FACTS = stringPreferencesKey("personal_facts")
        val PERSONA = stringPreferencesKey("persona")
    }

    val apiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.API_KEY] ?: ""
    }

    val model: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.MODEL] ?: "claude-haiku-4-5"
    }

    val toneDescription: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.TONE_DESCRIPTION] ?: ""
    }

    val personalFacts: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.PERSONAL_FACTS] ?: ""
    }

    val persona: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.PERSONA] ?: "CASUAL"
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { it[Keys.API_KEY] = key.trim() }
    }

    suspend fun setModel(model: String) {
        context.dataStore.edit { it[Keys.MODEL] = model }
    }

    suspend fun setToneDescription(tone: String) {
        context.dataStore.edit { it[Keys.TONE_DESCRIPTION] = tone }
    }

    suspend fun setPersonalFacts(facts: String) {
        context.dataStore.edit { it[Keys.PERSONAL_FACTS] = facts }
    }

    suspend fun setPersona(persona: String) {
        context.dataStore.edit { it[Keys.PERSONA] = persona }
    }
}
