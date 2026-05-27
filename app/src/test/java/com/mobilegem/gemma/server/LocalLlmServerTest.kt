package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeTextGenerator
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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

    @Test
    fun corsAllowsOnlyTheWebViewOrigin() = testApplication {
        application {
            installLlmRoutes(
                ChatCompletionHandler(FakeTextGenerator(emptyList())),
                "gemma",
            )
        }
        val allowed = client.get("/v1/models") {
            header("Origin", "https://appassets.androidplatform.net")
        }
        val disallowed = client.get("/v1/models") {
            header("Origin", "https://evil.example.com")
        }
        assertThat(allowed.headers["Access-Control-Allow-Origin"])
            .isEqualTo("https://appassets.androidplatform.net")
        assertThat(disallowed.headers["Access-Control-Allow-Origin"]).isNull()
    }

    @Test
    fun chatCompletionsRejectsRequestsWithoutBearerToken() = testApplication {
        application {
            installLlmRoutes(
                handler = ChatCompletionHandler(FakeTextGenerator(listOf("hi"))),
                modelId = "gemma",
                expectedToken = "secret-xyz",
            )
        }
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gemma","messages":[{"role":"user","content":"hi"}]}""")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun chatCompletionsAcceptsRequestsWithValidBearerToken() = testApplication {
        application {
            installLlmRoutes(
                handler = ChatCompletionHandler(FakeTextGenerator(listOf("hi"))),
                modelId = "gemma",
                expectedToken = "secret-xyz",
            )
        }
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer secret-xyz")
            setBody("""{"model":"gemma","stream":true,"messages":[{"role":"user","content":"hi"}]}""")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.bodyAsText()).contains("data: [DONE]")
    }
}
