package com.gcmodai.app.data.user

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 登录会话的本地持久化。
 *
 * 单独用一个 DataStore 文件（user_auth），不跟聊天设置混在一起：
 * 退出登录时直接清空即可，不会误伤用户设置。
 */
class UserAuthStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dataStore: DataStore<Preferences>
        get() = instance ?: synchronized(UserAuthStore::class) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    val sessionFlow: Flow<UserSession?> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences ->
            preferences[SESSION_KEY]?.let { raw ->
                runCatching { json.decodeFromString(UserSession.serializer(), raw) }.getOrNull()
            }
        }

    suspend fun save(session: UserSession) {
        val raw = json.encodeToString(UserSession.serializer(), session)
        dataStore.edit { it[SESSION_KEY] = raw }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(SESSION_KEY) }
    }

    private companion object {
        const val STORE_NAME = "user_auth"
        val SESSION_KEY = stringPreferencesKey("session")

        @Volatile
        var instance: DataStore<Preferences>? = null

        fun create(context: Context): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(STORE_NAME) },
        )
    }
}
