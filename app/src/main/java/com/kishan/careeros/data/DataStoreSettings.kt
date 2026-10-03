package com.kishan.careeros.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.kishan.careeros.domain.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

private val Context.settings by preferencesDataStore("settings")
class DataStoreSettings @Inject constructor(@ApplicationContext private val context: Context): AppSettings {
    private val dark = booleanPreferencesKey("dark_theme")
    override val darkTheme = context.settings.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map { it[dark] ?: true }
    override suspend fun setDarkTheme(enabled: Boolean) { context.settings.edit { it[dark] = enabled } }
}
