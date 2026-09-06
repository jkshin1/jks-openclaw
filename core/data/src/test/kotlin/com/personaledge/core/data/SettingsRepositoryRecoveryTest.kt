package com.personaledge.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryRecoveryTest {
    @Test
    fun `io failure emits safe defaults then observes later durable settings`() = runTest {
        val dataStore = FailFirstReadDataStore()
        val repository = SettingsRepository(dataStore)
        repository.setRouteLookupEnabled(true)

        val observed = repository.settings.take(2).toList()

        assertFalse(observed[0].routeLookupEnabled)
        assertTrue(observed[1].routeLookupEnabled)
        assertEquals(2, dataStore.subscriptions)
    }

    @Test
    fun `io retry delay grows without exceeding thirty seconds`() {
        assertEquals(1_000L, settingsIoRetryDelayMillis(0L))
        assertEquals(16_000L, settingsIoRetryDelayMillis(4L))
        assertEquals(30_000L, settingsIoRetryDelayMillis(5L))
        assertEquals(30_000L, settingsIoRetryDelayMillis(Long.MAX_VALUE))
    }

    @Test
    fun `strict OpenClaw recovery read preserves DataStore uncertainty`() {
        val repository = SettingsRepository(FailEveryReadDataStore())

        assertThrows(IOException::class.java) {
            runBlocking { repository.currentOpenClawGatewayEnabledForRecovery() }
        }
    }

    @Test
    fun `strict OpenClaw recovery read does not disguise malformed durable state as false`() {
        val malformed = emptyPreferences().toMutablePreferences().apply {
            this[booleanPreferencesKey("openclaw_gateway_enabled")] = true
            this[stringPreferencesKey("openclaw_gateway_trust_mode")] = "BROKEN"
        }
        val repository = SettingsRepository(StaticDataStore(malformed))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.currentOpenClawGatewayEnabledForRecovery() }
        }
        assertFalse(runBlocking { repository.current().openClawGateway.enabled })
    }

    private class FailFirstReadDataStore : DataStore<Preferences> {
        private var current: Preferences = emptyPreferences()
        var subscriptions: Int = 0
            private set

        override val data: Flow<Preferences> = flow {
            subscriptions += 1
            if (subscriptions == 1) throw IOException("content-free test failure")
            emit(current)
        }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            current = transform(current)
            return current
        }
    }

    private class FailEveryReadDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow {
            throw IOException("content-free test failure")
        }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = transform(emptyPreferences())
    }

    private class StaticDataStore(
        private var current: Preferences,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { emit(current) }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            current = transform(current)
            return current
        }
    }
}
