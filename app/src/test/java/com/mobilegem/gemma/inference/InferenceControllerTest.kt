package com.mobilegem.gemma.inference

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.server.LocalLlmServer
import com.mobilegem.gemma.settings.InferenceBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InferenceControllerTest {

    @Test
    fun loadModelStartsServerAndExposesState() = runTest {
        val controller = InferenceController(
            server = LocalLlmServer(port = 0),
            generatorFactory = { _, _ -> FakeTextGenerator(listOf("hi")) },
        )
        assertThat(controller.state.first().loadedModelName).isNull()

        controller.loadModel("/data/models/gemma-4-E2B-it.litertlm", InferenceBackend.CPU)

        val state = controller.state.first()
        assertThat(state.loadedModelName).isEqualTo("gemma-4-E2B-it.litertlm")
        assertThat(state.serverRunning).isTrue()
        assertThat(controller.server.baseUrl).startsWith("http://127.0.0.1:")
    }

    @Test
    fun unloadStopsServer() = runTest {
        val controller = InferenceController(
            server = LocalLlmServer(port = 0),
            generatorFactory = { _, _ -> FakeTextGenerator(listOf("hi")) },
        )
        controller.loadModel("/data/models/m.litertlm", InferenceBackend.CPU)
        controller.unload()
        val state = controller.state.first()
        assertThat(state.loadedModelName).isNull()
        assertThat(state.serverRunning).isFalse()
    }
}
