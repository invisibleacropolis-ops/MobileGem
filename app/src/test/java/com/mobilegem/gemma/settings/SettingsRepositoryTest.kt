package com.mobilegem.gemma.settings

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private val repo = SettingsRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun defaultsAreReturnedWhenNothingStored() = runTest {
        val settings = repo.settings.first()
        assertThat(settings.activeModelFileName).isNull()
        assertThat(settings.backend).isEqualTo(InferenceBackend.CPU)
        assertThat(settings.temperature).isEqualTo(0.8f)
    }

    @Test
    fun writesArePersistedAndReadBack() = runTest {
        repo.setActiveModel("gemma-4-E2B-it.litertlm")
        repo.setBackend(InferenceBackend.GPU)
        repo.setTemperature(0.3f)

        val settings = repo.settings.first()
        assertThat(settings.activeModelFileName).isEqualTo("gemma-4-E2B-it.litertlm")
        assertThat(settings.backend).isEqualTo(InferenceBackend.GPU)
        assertThat(settings.temperature).isEqualTo(0.3f)
    }
}
