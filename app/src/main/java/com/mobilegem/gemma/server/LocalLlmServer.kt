package com.mobilegem.gemma.server

import com.mobilegem.gemma.logging.AppLog
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val jsonFormat = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
private data class ModelInfo(
    val id: String,
    @SerialName("object") val obj: String = "model",
    @SerialName("owned_by") val ownedBy: String = "local",
)

@Serializable
private data class ModelList(
    @SerialName("object") val obj: String = "list",
    val data: List<ModelInfo>,
)

fun Application.installLlmRoutes(handler: ChatCompletionHandler, modelId: String) {
    install(ContentNegotiation) { json(jsonFormat) }
    install(CORS) {
        anyHost()
        allowHeader("Content-Type")
        allowHeader("Authorization")
    }
    routing {
        get("/v1/models") {
            AppLog.event("server", "server.models.list", "modelId" to modelId)
            call.respond(ModelList(data = listOf(ModelInfo(id = modelId))))
        }
        post("/v1/chat/completions") {
            try {
                val request = jsonFormat.decodeFromString(
                    ChatCompletionRequest.serializer(), call.receiveText(),
                )
                AppLog.event(
                    "server", "server.chat.request",
                    "stream" to request.stream,
                    "messageCount" to request.messages.size,
                )
                if (request.stream) {
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        handler.streamSse(request).collect { payload ->
                            write(payload)
                            flush()
                        }
                    }
                } else {
                    call.respond(HttpStatusCode.OK, handler.complete(request))
                }
            } catch (t: Throwable) {
                AppLog.error("server", "server.chat.error", t)
                throw t
            }
        }
    }
}

/** Owns the running HTTP server. The active model can be swapped by rebuilding. */
class LocalLlmServer(private val port: Int = 8765) {

    private var server: ApplicationEngine? = null

    fun start(handler: ChatCompletionHandler, modelId: String) {
        stop()
        AppLog.event("server", "server.start", "port" to port, "modelId" to modelId)
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            installLlmRoutes(handler, modelId)
        }.also { it.start(wait = false) }
    }

    fun stop() {
        if (server != null) {
            AppLog.event("server", "server.stop")
        }
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        server = null
    }

    fun isRunning(): Boolean = server != null

    val baseUrl: String get() = "http://127.0.0.1:$port/v1"
}
