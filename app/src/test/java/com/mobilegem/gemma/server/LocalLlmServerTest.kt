package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeTextGenerator
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test

class LocalLlmServerTest {

    @Test
    fun modelsEndpointListsTheActiveModel() = testApplication {
        application { installLlmRoutes(ChatCompletionHandler(FakeTextGenerator(emptyList())), "gemma-4-E2B") }
        val body = client.get("/v1/models").bodyAsText()
        assertThat(body).contains("gemma-4-E2B")
    }

    @Test
    fun streamingChatCompletionReturnsSse() = testApplication {
        application {
            installLlmRoutes(
                ChatCompletionHandler(FakeTextGenerator(listOf("ok"))), "gemma-4-E2B",
            )
        }
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gemma","stream":true,"messages":[{"role":"user","content":"hi"}]}""")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val text = response.bodyAsText()
        assertThat(text).contains("\"content\":\"ok\"")
        assertThat(text).contains("data: [DONE]")
    }
}
