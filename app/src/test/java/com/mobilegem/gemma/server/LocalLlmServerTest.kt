package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeTextGenerator
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
    fun corsPreflightForChatCompletionsFromWebViewOriginSucceeds() = testApplication {
        application {
            installLlmRoutes(
                ChatCompletionHandler(FakeTextGenerator(emptyList())),
                "gemma",
            )
        }
        // Reproduces the real WebView preflight: the browser sends an OPTIONS
        // request before the JSON POST. This must succeed (with an
        // Access-Control-Allow-Origin header) or the chat is blocked by CORS.
        val preflight = client.options("/v1/chat/completions") {
            header(HttpHeaders.Origin, "https://appassets.androidplatform.net")
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Post.value)
            // The OpenAI SDK (pi-ai) attaches these on the real request, so the
            // browser asks for them in the preflight. They must all be allowed.
            header(
                HttpHeaders.AccessControlRequestHeaders,
                "authorization,content-type,x-stainless-lang,x-stainless-os,x-stainless-retry-count",
            )
        }
        assertThat(preflight.status).isEqualTo(HttpStatusCode.OK)
        assertThat(preflight.headers["Access-Control-Allow-Origin"]).isNotNull()
    }
}
