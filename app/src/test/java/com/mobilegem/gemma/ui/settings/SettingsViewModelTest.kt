package com.mobilegem.gemma.ui.settings

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeTextGenerator
import com.mobilegem.gemma.inference.InferenceController
import com.mobilegem.gemma.model.ContentSource
import com.mobilegem.gemma.model.ModelFileManager
import com.mobilegem.gemma.server.LocalLlmServer
import com.mobilegem.gemma.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    @get:Rule val tmp = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun source(name: String) = object : ContentSource {
        override val displayName = name
        override fun openStream(): InputStream = ByteArrayInputStream(byteArrayOf(1))
    }

    private fun newViewModel(): SettingsViewModel {
        val storeFile = java.io.File(tmp.newFolder("ds"), "settings.preferences_pb")
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { storeFile },
        )
        return SettingsViewModel(
            settingsRepository = SettingsRepository(dataStore),
            modelFileManager = ModelFileManager(tmp.newFolder("models")),
            inferenceController = InferenceController(
                server = LocalLlmServer(port = 0),
                generatorFactory = { _, _ -> FakeTextGenerator(listOf("x")) },
            ),
        )
    }

    @Test
    fun importingAModelAddsItToTheInstalledList() = runTest(testDispatcher) {
        val vm = newViewModel()
        vm.importModel(source("gemma-4-E2B-it.litertlm")).join()
        assertThat(vm.uiState.first().installedModels).contains("gemma-4-E2B-it.litertlm")
    }

    @Test
    fun selectingAModelPersistsItAndLoadsInference() = runTest(testDispatcher) {
        val vm = newViewModel()
        vm.importModel(source("gemma-4-E2B-it.litertlm")).join()
        vm.selectModel("gemma-4-E2B-it.litertlm").join()

        val state = vm.uiState.first()
        assertThat(state.activeModel).isEqualTo("gemma-4-E2B-it.litertlm")
        assertThat(state.modelLoaded).isTrue()
    }
}
