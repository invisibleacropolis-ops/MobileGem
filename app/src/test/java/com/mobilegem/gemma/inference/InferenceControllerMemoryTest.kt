package com.mobilegem.gemma.inference

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.server.LocalLlmServer
import com.mobilegem.gemma.settings.InferenceBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InferenceControllerMemoryTest {

    @Test
    fun loadModelWorksWithMemoryHooksWiredAndExposesGenerator() = runTest {
        val controller = InferenceController(
            server = LocalLlmServer(port = 0),
            generatorFactory = { _, _ -> FakeTextGenerator(listOf("hi")) },
            activeSession = ActiveSessionHolder(),
            augmenter = null,
            persister = null,
        )
        assertThat(controller.currentGenerator()).isNull()

        controller.loadModel("/data/models/m.litertlm", InferenceBackend.CPU)

        assertThat(controller.state.first().serverRunning).isTrue()
        assertThat(controller.currentGenerator()).isNotNull()
    }
}
