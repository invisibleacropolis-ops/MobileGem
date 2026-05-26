package com.mobilegem.gemma.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = java.io.File(tmp.newFolder("ds"), "settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        repo = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaultsAreReturnedWhenNothingStored() = runTest {
        val settings = repo.settings.first()
        assertThat(settings.activeModelFileName).isNull()
        assertThat(settings.backend).isEqualTo(InferenceBackend.CPU)
        assertThat(settings.temperature).isEqualTo(0.8f)
        assertThat(settings.loggingEnabled).isTrue()
    }

    @Test
    fun writesArePersistedAndReadBack() = runTest {
        repo.setActiveModel("gemma-4-E2B-it.litertlm")
        repo.setBackend(InferenceBackend.GPU)
        repo.setTemperature(0.3f)
        repo.setLoggingEnabled(false)

        val settings = repo.settings.first()
        assertThat(settings.activeModelFileName).isEqualTo("gemma-4-E2B-it.litertlm")
        assertThat(settings.backend).isEqualTo(InferenceBackend.GPU)
        assertThat(settings.temperature).isEqualTo(0.3f)
        assertThat(settings.loggingEnabled).isFalse()
    }
}
