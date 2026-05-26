# MobileGem — Engineering Architecture Report

> **Generated:** 2026-05-26  
> **Project:** MobileGem — On-Device Android AI Chat with Memory & Skills  
> **Package:** `com.mobilegem.gemma`  
> **Authors / Fork:** Based on `@mariozechner/pi-web-ui` / `@earendil-works/pi-web-ui` (Mario Zechner / earendil-works)

---

## 1. Executive Summary

MobileGem is a **native Android application** that runs Google's **Gemma 4** large language model entirely on-device (offline) using **LiteRT-LM** (Google's on-device inference runtime). It embeds a **pi-web-ui** web-based chat interface inside an Android `WebView`, exposing the local LLM as an **OpenAI-compatible HTTP server** (`127.0.0.1:8765/v1`) via an embedded **Ktor** server. This avoids fragile JS↔native bridging the WebView simply makes HTTP `fetch` calls to localhost.

The app is organized into **three bottom-navigation tabs**:
- **Chat** — full-featured chat UI (HTML/TS web app)
- **Memory** — projects, sessions, reusable skills, and long-term memory with vector retrieval
- **Settings** — model import, backend (CPU/GPU), temperature, file-logging toggle

A unique feature is the **Memory subsystem** (Plan 2), which uses **Room (SQLite)** + **cosine-similarity retrieval** over on-device embeddings (MediaPipe TextEmbedder) to inject relevant memories and skill instructions into every chat prompt automatically.

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           ANDROID APP (Kotlin)                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                    Chat Tab — Settings Tab                          │  │
│  │   [Jetpack Compose Screens]                                         │  │
│  │   • SettingsScreen │ MemoryScreen │ ChatScreen                       │  │
│  │   • ViewModels: SettingsViewModel │ MemoryViewModel                  │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                 ↕                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │          AppContainer (manual service locator / DI)                 │  │
│  │  • MemoryDatabase (Room) • SettingsRepository (DataStore)            │  │
│  │  • InferenceController • SkillRepository • ActiveSessionHolder       │  │
│  │  • MemoryRetriever (cosine sim) • FileLogger                         │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                 ↕                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                     Inference Layer                                   │  │
│  │  ┌─────────────────┐   ┌─────────────────┐   ┌───────────────────┐  │
│  │  │ LiteRtLmText    │   │ MediaPipeText   │   │ LocalLlmServer    │  │
│  │  │ Generator       │   │ Embedder        │   │ (Ktor 127.0.0.1:  │  │
│  │  │                 │   │                 │   │   8765)             │  │
│  │  └─────────────────┘   └─────────────────┘   └───────────────────┘  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                 ↕                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │                         WebView (Chat Tab)                          │  │
│  │   WebViewAssetLoader → https://appassets.../assets/webui/          │  │
│  │   ┌──────────────────────────────────────────────────────────────┐  │  │
│  │   │                     pi-web-ui ChatPanel                       │  │  │
│  │   │  TypeScript + Vite + lit • IndexedDB-backed sessions          │  │  │
│  │   │  fetch http://127.0.0.1:8765/v1 → OpenAI SSE stream           │  │  │
│  │   └──────────────────────────────────────────────────────────────┘  │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Technology Stack (Deep-Dive)

### 3.1 Android / Kotlin Layer

| Technology | Version | Purpose |
|---|---|---|
| **Kotlin** | 2.3.21 | Primary language |
| **Android Gradle Plugin** | 9.2.1 | Build system |
| **Jetpack Compose** | BOM 2024.09.03 | Declarative UI |
| **Jetpack Navigation** | 2.8.0 | Bottom-nav screen routing |
| **DataStore Preferences** | 1.1.1 | Key-value settings persistence |
| **Room (KSP)** | 2.7.2 | SQLite ORM for memory database |
| **Ktor Server** | 2.3.12 | Embedded HTTP / SSE server |
| **kotlinx.serialization** | 1.7.1 | JSON DTOs |
| **kotlinx.coroutines** | 1.8.1 | Structured concurrency |
| **LiteRT-LM** | 0.12.0 | Google's on-device LLM inference engine |
| **MediaPipe Tasks Text** | 0.10.35 | On-device text embedding model |

### 3.2 Web / TypeScript Layer

| Technology | Version | Purpose |
|---|---|---|
| **Vite** | 5.4.0 | Bundler (ES modules, tree-shaking) |
| **TypeScript** | 5.5.4 | Type-safe compilation |
| **lit** | 3.3.1 (latest 3.3.3) | Web components base library (5.6M weekly downloads) |
| **mini-lit** | 0.2.0 | Lightweight `lit` subset used by pi-web-ui |
| **pi-web-ui** | 0.73.1 | Pre-built chat UI components (see deprecation note below) |
| **pi-agent-core** | 0.73.1 | Agent state machine (same namespace as pi-web-ui) |
| **@earendil-works/pi-web-ui** (successor) | 0.75.3 | Migration target — same API, new org (2,180 weekly downloads) |

### 3.3 Key External Libraries

#### LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android:0.12.0`)
**LiteRT** is Google's **successor to TensorFlow Lite**, launched in 2025–2026 as the unified on-device ML & GenAI framework. LiteRT-LM is the **LLM-specific sub-project** built on top of LiteRT, providing:
- `Engine` — loads a `.litertlm` model binary
- `EngineConfig` — model path, CPU/GPU backend, cache dir
- `Conversation` — a single multi-turn chat instance
- `SamplerConfig` — temperature, topK, topP, seed control
- **Streaming** — `sendMessageAsync(prompt)` returns a `Flow<Content>`

Key LiteRT-LM versions (from Maven Central):
| Version | Date | Notes |
|---|---|---|
| 0.12.0 | May 18, 2026 | Latest stable |
| 0.11.0 | May 04, 2026 | Minor update |
| 0.10.0 | Apr 02, 2026 | Earlier stable |
| 0.0.0-alpha06 | Nov 14, 2025 | Initial alpha |

LiteRT-LM supports **GPU (OpenCL/OpenGL)**, **CPU**, and **NPU** (Google Tensor, Qualcomm, MediaTek) backends. Mobile uses `OpenGL` / `OpenCL` for GPU acceleration on Android.

> **Note:** The `.litertlm` format is Google's on-device quantized model format (similar to GGUF but optimized for LiteRT). Users import these files via Android's Storage Access Framework (SAF). The LiteRT runtime requires a **writable cache directory** — without it, many versions fail with opaque `INTERNAL` errors.

#### MediaPipe TextEmbedder (`com.google.mediapipe:tasks-text:0.10.35`)
[MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/guide) is Google's **open-source on-device ML framework** (June 2019 GA, now under **Google AI Edge**). It provides real-time computer vision, NLP, and audio solutions across **Android, iOS, Python, JavaScript, and Web**.

For MobileGem, the **`TextEmbedder`** task produces **768-dimensional** `FloatArray` embeddings from text, backed by `text_embedder.tflite` (bundled in `assets/`). MediaPipe TextEmbedder supports:
- **Universal Sentence Encoder** architecture (lightweight on-device variant)
- Cosine similarity and L2 distance metrics
- Batch embedding

Usage in MobileGem:
1. Embedding user queries for memory retrieval (`MemoryRetriever.retrieve()`)
2. Embedding extracted facts for long-term memory storage (`SelfLearningExtractor`)
3. Cosine-similarity ranking in `MemoryRetriever`

> **Wikipedia:** *"MediaPipe includes a multitude of different components that all work together to create a general purpose computer vision framework. Each component works in its own unique way with different architectures."* In May 2025, MediaPipe transitioned to **MediaPipe Solutions**, expanding LLM inference capabilities under Google AI Edge.

| Platform | CPU | GPU | NPU (Android) |
|---|---|---|---|
| Android | ✅ | OpenCL / OpenGL | Google Tensor, Qualcomm, MediaTek |
| iOS | ✅ | Metal | Apple Neural Engine |
| Web | ✅ | WebGPU | — |

#### pi-web-ui (`@mariozechner/pi-web-ui` → deprecated)
A **reusable chat UI web-components library** (web components built with `lit`) that wraps:
- `ChatPanel` — high-level component with artifacts panel
- `Agent` — state machine (messages, model, tools, thinking level)
- `IndexedDBStorageBackend` — browser-side session persistence
- `defaultConvertToLlm` — message transformer for various LLM APIs
- **Provider support** — OpenAI-compatible, Anthropic, Google, Mistral, Azure

> **Deprecation note:** The `@mariozechner/*` packages are **deprecated** in favor of `@earendil-works/pi-web-ui`, `@earendil-works/pi-ai`, and `@earendil-works/pi-agent-core`. The earendil-works fork (v0.75.3 as of May 2026) is actively maintained with the same API and added fixes. Migration path: swap import prefixes and update `package.json` dependencies — no code changes required beyond import paths. MobileGem currently pins the legacy `@mariozechner/*` versions.

---

## 4. File Tree

```
MobileGem/
├── build.gradle.kts                    # Root build (plugins)
├── gradle.properties
├── settings.gradle.kts
│
├── webui/
│   ├── package.json                    # npm deps (pi-web-ui, lit, vite)
│   ├── tsconfig.json                   # ES2022 / strict / decorators
│   ├── vite.config.ts                  # base: "./", outDir: "dist"
│   ├── index.html                      # <div id="app">
│   └── src/
│       └── main.ts                     # Entry: ChatPanel + local provider
│
└── app/
    ├── build.gradle.kts                # Android module + KSP + deps
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/
        │   │   ├── text_embedder.tflite    # MediaPipe embedding model
        │   │   └── webui/                  # Vite dist copied here
        │   ├── java/com/mobilegem/gemma/
        │   │   ├── GemmaApp.kt             # Application class ( DI root )
        │   │   ├── MainActivity.kt         # Compose setContent + ViewModels
        │   │   ├── AppContainer.kt         # Manual service locator
        │   │   ├── inference/
        │   │   │   ├── TextGenerator.kt        # Interface
        │   │   │   ├── LiteRtLmTextGenerator.kt # LiteRT-LM wrapper
        │   │   │   ├── Embedder.kt             # Interface
        │   │   │   ├── MediaPipeTextEmbedder.kt # MP wrapper
        │   │   │   ├── LazyEmbedder.kt         # Lazy init wrapper
        │   │   │   └── InferenceController.kt   # Model load + server start
        │   │   ├── logging/
        │   │   │   ├── AppLog.kt           # Global facade
        │   │   │   ├── FileLogger.kt       # JSONL channel writer
        │   │   │   └── AppLogger.kt        # Interface
        │   │   ├── memory/
        │   │   │   ├── db/
        │   │   │   │   ├── MemoryDatabase.kt         # Room database
        │   │   │   │   ├── Entities.kt                 # Project, Session, etc.
        │   │   │   │   ├── Converters.kt              # FloatArray ↔ ByteArray
        │   │   │   │   ├── CoreDao.kt, SkillDao.kt, MemoryDao.kt
        │   │   │   ├── MemoryRepository.kt              # CRUD + ConversationPersister
        │   │   │   ├── SkillRepository.kt               # Enabled skills
        │   │   │   ├── LongTermMemoryRepository.kt      # MemoryEntry CRUD
        │   │   │   ├── MemoryRetriever.kt               # Cosine-sim search
        │   │   │   ├── ContextAugmenter.kt interface & MemoryContextAugmenter
        │   │   │   ├── SelfLearningExtractor.kt         # LLM-driven fact extraction
        │   │   │   ├── FactListParser.kt                # JSON array parser
        │   │   │   ├── ActiveSessionHolder.kt           # Shared current session
        │   │   │   └── VectorMath.kt                    # Cosine similarity
        │   │   ├── model/
        │   │   │   ├── ContentSource.kt, UriContentSource.kt, ModelFileManager.kt
        │   │   ├── server/
        │   │   │   ├── OpenAiDto.kt               # OpenAI JSON DTOs
        │   │   │   ├── GemmaPromptBuilder.kt      # messages[] → Gemma template
        │   │   │   ├── ChatCompletionHandler.kt   # SSE or non-stream handler
        │   │   │   └── LocalLlmServer.kt          # Ktor CIO routes
        │   │   ├── settings/
        │   │   │   ├── AppSettings.kt, SettingsRepository.kt
        │   │   └── ui/
        │   │       ├── chat/ChatScreen.kt
        │   │       ├── memory/MemoryScreen.kt, MemoryViewModel.kt
        │   │       ├── navigation/AppScaffold.kt
        │   │       └── settings/SettingsScreen.kt, SettingsViewModel.kt
        │   └── res/xml/
        │       ├── network_security_config.xml  # Cleartext 127.0.0.1
        │       └── file_paths.xml               # FileProvider paths
        └── test/java/... (40+ unit tests via Robolectric + Truth)
```

---

## 5. Core Systems (with Code Snippets)

### 5.1 pi-web-ui Web App (`webui/src/main.ts`)

The web layer is minimal — it bootstraps the pre-built `ChatPanel` and configures a single custom OpenAI-compatible provider pointing at the local Ktor server.

```typescript
// webui/src/main.ts
import { Agent } from "@mariozechner/pi-agent-core";
import { registerBuiltInApiProviders } from "@mariozechner/pi-ai";
import {
  AppStorage, ChatPanel, CustomProvidersStore,
  IndexedDBStorageBackend, ProviderKeysStore, SessionsStore,
  SettingsStore, defaultConvertToLlm, setAppStorage,
} from "@mariozechner/pi-web-ui";
import "@mariozechner/pi-web-ui/app.css";

const LOCAL_BASE_URL = "http://127.0.0.1:8765/v1";
const PROVIDER_ID    = "mobilegem-local";
const FALLBACK_MODEL = "gemma";

async function resolveModelId(): Promise<string> {
  try {
    const res = await fetch(`${LOCAL_BASE_URL}/models`);
    const body = await res.json() as { data?: Array<{ id?: string }> };
    return body.data?.[0]?.id ?? FALLBACK_MODEL;
  } catch (err) {
    console.warn("Falling back:", err);
    return FALLBACK_MODEL;
  }
}

function buildLocalModel(modelId: string): Model<"openai-completions"> {
  return {
    id: modelId, name: modelId, api: "openai-completions",
    provider: PROVIDER_ID, baseUrl: LOCAL_BASE_URL,
    reasoning: false, input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 8192, maxTokens: 2048,
  };
}

async function initStorage(): Promise<AppStorage> {
  const settings      = new SettingsStore();
  const providerKeys  = new ProviderKeysStore();
  const sessions      = new SessionsStore();
  const customProv    = new CustomProvidersStore();
  const backend = new IndexedDBStorageBackend({
    dbName: "mobilegem-webui", version: 1,
    stores: [settings.getConfig(), providerKeys.getConfig(),
               sessions.getConfig(), SessionsStore.getMetadataConfig(),
               customProv.getConfig()],
  });
  [settings, providerKeys, sessions, customProv].forEach { it.setBackend(backend) };
  const storage = new AppStorage(settings, providerKeys, sessions, customProv, backend);
  setAppStorage(storage);
  return storage;
}

async function initApp(): Promise<void> {
  registerBuiltInApiProviders();
  const storage = await initStorage();
  // Seed a placeholder key so the UI never prompts for one.
  if (!await storage.providerKeys.get(PROVIDER_ID))
    await storage.providerKeys.set(PROVIDER_ID, "local");

  const model = buildLocalModel(await resolveModelId());
  const agent = new Agent({
    initialState: {
      systemPrompt: "You are a helpful assistant running on-device.",
      model, thinkingLevel: "off", messages: [], tools: [],
    },
    convertToLlm: defaultConvertToLlm,
    getApiKey: async () => "local",
  });

  const chatPanel = new ChatPanel();
  await chatPanel.setAgent(agent);
  document.getElementById("app")!.replaceChildren(chatPanel);
}
void initApp();
```

**Key invariants preserved:**
- (a) `baseUrl` is always `http://127.0.0.1:8765/v1`
- (b) `modelId` is dynamically queried from `/v1/models`
- A placeholder API key `"local"` is seeded so `AgentInterface` never blocks on an API-key dialog.

---

### 5.2 LiteRT-LM Text Generator (`inference/LiteRtLmTextGenerator.kt`)

This is the **only file** in the entire codebase that touches Google's LiteRT-LM native Kotlin API. It wraps an `Engine`/`Conversation` and exposes a Kotlin `Flow<String>` for streaming tokens.

```kotlin
class LiteRtLmTextGenerator private constructor(
    private val engine: Engine,
) : TextGenerator, Closeable {

    override fun generate(prompt: String, temperature: Float): Flow<String> = flow {
        val conversationConfig = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = 64, topP = 0.95,
                temperature = temperature.toDouble(), seed = 0,
            ),
        )
        engine.createConversation(conversationConfig).use { conversation ->
            conversation.sendMessageAsync(prompt).collect { message ->
                for (content in message.contents.contents) {
                    if (content is Content.Text) {
                        emit(content.text)
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { engine.close() }
    }

    companion object {
        fun create(
            modelPath: String,
            backend: InferenceBackend,
            cacheDir: File? = null,
        ): LiteRtLmTextGenerator {
            cacheDir?.mkdirs()
            val backendInstance = when (backend) {
                InferenceBackend.GPU -> Backend.GPU()
                InferenceBackend.CPU -> Backend.CPU()
            }
            val engineConfig = if (cacheDir != null) {
                EngineConfig(modelPath = modelPath, backend = backendInstance,
                             cacheDir = cacheDir.absolutePath)
            } else {
                EngineConfig(modelPath = modelPath, backend = backendInstance)
            }
            val engine = Engine(engineConfig).also { it.initialize() }
            return LiteRtLmTextGenerator(engine)
        }
    }
}
```

**Important design note:** The `cacheDir` parameter is strongly recommended on Android. Without it, many LiteRT-LM runtime versions fail with opaque `INTERNAL` errors because the runtime needs a writable directory for compiled kernel artifacts.

---

### 5.3 Embedded Ktor Server (`server/LocalLlmServer.kt`)

The local server exposes two OpenAI-compatible endpoints:
- `GET /v1/models` — lists the currently active model
- `POST /v1/chat/completions` — streaming (SSE) or non-streaming chat completion

```kotlin
fun Application.installLlmRoutes(handler: ChatCompletionHandler, modelId: String) {
    install(ContentNegotiation) { json(jsonFormat) }
    install(CORS) {
        anyHost()
        allowHeader("Content-Type")
        allowHeader("Authorization")
    }
    routing {
        get("/v1/models") {
            call.respond(mapOf(
                "object" to "list",
                "data" to listOf(mapOf(
                    "id" to modelId, "object" to "model", "owned_by" to "local"
                )),
            ))
        }
        post("/v1/chat/completions") {
            val request = jsonFormat.decodeFromString(
                ChatCompletionRequest.serializer(), call.receiveText()
            )
            if (request.stream) {
                call.respondTextWriter(ContentType.Text.EventStream) {
                    handler.streamSse(request).collect { payload ->
                        write(payload); flush()
                    }
                }
            } else {
                call.respond(HttpStatusCode.OK, handler.complete(request))
            }
        }
    }
}

class LocalLlmServer(private val port: Int = 8765) {
    private var server: ApplicationEngine? = null
    fun start(handler: ChatCompletionHandler, modelId: String) {
        stop()
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            installLlmRoutes(handler, modelId)
        }.also { it.start(wait = false) }
    }
    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        server = null
    }
    val isRunning: Boolean get() = server != null
    val baseUrl: String get() = "http://127.0.0.1:$port/v1"
}
```

---

### 5.4 Gemma Prompt Builder (`server/GemmaPromptBuilder.kt`)

OpenAI's `messages[]` format is incompatible with Gemma's instruction-tuned template. This utility renders the conversation into Gemma's turn-based format, concatenating all system messages into the first user turn.

```kotlin
object GemmaPromptBuilder {

    fun build(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        val systemText = messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }
        var systemConsumed = false

        for (msg in messages.filter { it.role != "system" }) {
            when (msg.role) {
                "assistant" ->
                    sb.append("<start_of_turn>model\n")
                      .append(msg.content).append("<end_of_turn>\n")
                else -> {
                    val content = if (!systemConsumed && systemText != null) {
                        systemConsumed = true
                        "$systemText\n\n${msg.content}"
                    } else msg.content
                    sb.append("<start_of_turn>user\n")
                      .append(content).append("<end_of_turn>\n")
                }
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }
}
```

**Template:**
```
<start_of_turn>user
{system?}

{user_msg}<end_of_turn>
<start_of_turn>model
{assistant_msg}<end_of_turn>
<start_of_turn>model
```

---

### 5.5 Chat Completion Handler (`server/ChatCompletionHandler.kt`)

The handler is the **bridge** between the Ktor server and LiteRT-LM. It also integrates the **memory subsystem** by optionally injecting system context and persisting the conversation transcript.

```kotlin
class ChatCompletionHandler(
    private val generator: TextGenerator,
    private val augmenter: ContextAugmenter? = null,
    private val persister: ConversationPersister? = null,
    private val activeSession: ActiveSessionHolder? = null,
) {
    private val json = Json { encodeDefaults = true }

    fun streamSse(request: ChatCompletionRequest): Flow<String> = flow {
        val id = "chatcmpl-${System.nanoTime()}"
        val created = System.currentTimeMillis() / 1000
        val temp = request.temperature ?: 0.8f
        val messages = augmentedMessages(request.messages)
        val prompt = GemmaPromptBuilder.build(messages)

        emit(sseChunk(id, created, request.model, Delta(role = "assistant"), null))
        val answer = StringBuilder()
        generator.generate(prompt, temp).collect { token ->
            answer.append(token)
            emit(sseChunk(id, created, request.model, Delta(content = token), null))
        }
        emit(sseChunk(id, created, request.model, Delta(), "stop"))
        emit("data: [DONE]\n\n")
        persist(request.messages, answer.toString())
    }

    /** Injects skills/memory as a leading system message when a session is active. */
    private suspend fun augmentedMessages(original: List<ChatMessage>): List<ChatMessage> {
        val session = activeSession?.current() ?: return original
        val context = augmenter?.systemContextFor(session.projectId,
            original.lastOrNull { it.role == "user" }?.content ?: "") ?: return original
        return listOf(ChatMessage("system", context)) + original
    }

    private suspend fun persist(original: List<ChatMessage>, answer: String) {
        val session = activeSession?.current() ?: return
        persister?.persistConversation(
            session.sessionId, original + ChatMessage("assistant", answer)
        )
    }

    private fun sseChunk(...): String { /* ... */ }
}
```

---

### 5.6 Memory Database Schema (`memory/db/Entities.kt`)

The Room database defines five entities with foreign-key cascades:

| Entity | Table | Key Relationships |
|---|---|---|
| **Project** | `projects` | Root container |
| **Session** | `sessions` | FK → `Project(id)`, CASCADE delete |
| **StoredMessage** | `messages` | FK → `Session(id)`, CASCADE delete |
| **Skill** | `skills` | `projectId == null` → global skill |
| **MemoryEntry** | `memory_entries` | stores `FloatArray` embedding + `sourceSessionId` |

```kotlin
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val createdAt: Long, val updatedAt: Long,
)

@Entity(tableName = "sessions",
    foreignKeys = [ForeignKey(entity = Project::class, parentColumns = ["id"],
        childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId")])
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long, val title: String,
    val createdAt: Long, val updatedAt: Long,
)

@Entity(tableName = "memory_entries", indices = [Index("projectId")])
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long?,          // null = global memory
    val content: String,
    val embedding: FloatArray,      // 768-dim from MediaPipe
    val sourceSessionId: Long?,
    val createdAt: Long,
) { /* manual equals/hashCode for FloatArray */ }
```

---

### 5.7 Memory Retrieval Pipeline

```text
User query in Chat
       │
       ▼
┌──────────────────┐
│ MediaPipe Embed  │  ──embed("latest user message")──► FloatArray[768]
└──────────────────┘
       │
       ▼
┌──────────────────┐
│  MemoryRetriever │  candidates = ltm.entriesForProjectScope(projectId)
│                  │  scored = cosineSimilarity(queryVec, each.embedding)
│  topK = 4        │  sortedDescending → take(4)
└──────────────────┘
       │
       ▼
┌──────────────────┐
│ ContextAugmenter │  "Active skills: ...\n\nRelevant long-term memory: ..."
└──────────────────┘
       │
       ▼
  injected as system message → ChatCompletionHandler → GemmaPromptBuilder
```

**Cosine Similarity Implementation:** `memory/VectorMath.kt`
```kotlin
object VectorMath {
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0f || normB == 0f) return 0f
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
```

---

### 5.8 Self-Learning / Fact Extraction (`memory/SelfLearningExtractor.kt`)

When the user taps **"End & Learn"** in Memory → Sessions, the app:
1. Reads the stored transcript for that session
2. Sends it back to Gemma with a special extraction prompt
3. Parses the returned JSON array of durable facts
4. Embeds each fact via MediaPipe
5. Stores them as new `MemoryEntry` rows

```kotlin
class SelfLearningExtractor(
    private val generator: TextGenerator,
    private val embedder: Embedder,
    private val ltm: LongTermMemoryRepository,
) {
    suspend fun extractAndStore(
        projectId: Long, sessionId: Long, transcript: List<ChatMessage>,
    ): List<MemoryEntry> {
        val prompt = buildExtractionPrompt(transcript)
        val output = generator.generate(prompt, temperature = 0.2f).toList().joinToString("")
        val facts = FactListParser.parse(output)

        return facts.map { fact ->
            val embedding = embedder.embed(fact)
            val id = ltm.store(projectId, content = fact,
                               embedding = embedding, sourceSessionId = sessionId)
            MemoryEntry(id, projectId, fact, embedding, sessionId, 0)
        }
    }

    private fun buildExtractionPrompt(transcript: List<ChatMessage>): String =
        "<start_of_turn>user\n" +
            "Read the following conversation and extract durable, factual things " +
            "worth remembering ... Respond with ONLY a JSON array of short fact strings.\n\n" +
            "Conversation:\n" + transcript.joinToString("\n") { "${it.role}: ${it.content}" } +
            "<end_of_turn>\n<start_of_turn>model\n"
}
```

**Parser:** `FactListParser.kt`
```kotlin
object FactListParser {
    fun parse(raw: String): List<String> {
        val start = raw.indexOf('[')
        val end   = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            Json.decodeFromString<List<String>>(raw.substring(start, end + 1))
        }.getOrDefault(emptyList()).map { it.trim() }.filter { it.isNotBlank() }
    }
}
```

---

### 5.9 App Container — Manual Service Locator (`AppContainer.kt`)

There is **no Hilt / Dagger**. Dependencies are wired manually in a single `AppContainer` constructed once in `GemmaApp.onCreate()`.

```kotlin
class AppContainer(context: Context) {
    val settingsRepository = SettingsRepository(context)
    val modelFileManager = ModelFileManager(File(context.filesDir, "models"))

    val backgroundScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cached atomic flag so logging never blocks the hot path. */
    private val loggingEnabledFlag = AtomicBoolean(AppSettings.DEFAULT.loggingEnabled)
    init {
        backgroundScope.launch {
            settingsRepository.settings.collect {
                loggingEnabledFlag.set(it.loggingEnabled)
            }
        }
    }
    val fileLogger: FileLogger = FileLogger(
        logsDir = File(context.filesDir, "logs"),
        enabledProvider = { loggingEnabledFlag.get() },
        scope = backgroundScope,
    )

    private val database = MemoryDatabase.create(context)
    val memoryRepository   = MemoryRepository(database.coreDao())
    val skillRepository    = SkillRepository(database.skillDao())
    val longTermMemoryRepo = LongTermMemoryRepository(database.memoryDao())
    val activeSessionHolder = ActiveSessionHolder()

    /** Lazy-init embedder (loads MediaPipe model on first use, not startup). */
    val embedder: Embedder = LazyEmbedder {
        MediaPipeTextEmbedder.create(context)
    }

    private val retriever = MemoryRetriever(embedder, longTermMemoryRepo)

    val inferenceController = InferenceController(
        activeSession = activeSessionHolder,
        augmenter = MemoryContextAugmenter(skillRepository, retriever),
        persister = memoryRepository,
        generatorFactory = { modelPath, backend ->
            LiteRtLmTextGenerator.create(modelPath, backend,
                cacheDir = File(context.cacheDir, "litertlm"))
        },
    )

    fun selfLearningExtractor(): SelfLearningExtractor? {
        val gen = inferenceController.currentGenerator() ?: return null
        return SelfLearningExtractor(gen, embedder, longTermMemoryRepo)
    }
}
```

---

### 5.10 Logging System (`logging/`)

A **structured JSON Lines** (`*.jsonl`) file logger that writes in background using a Kotlin `Channel` (non-blocking). Logcat mirroring runs unconditionally; file output is toggled via an `AtomicBoolean` cached from DataStore preferences.

```kotlin
// Global facade — safe to call from anywhere, even without DI
object AppLog {
    @Volatile private var impl: AppLogger = NoOpLogger
    fun install(logger: AppLogger) { impl = logger }
    fun event(category: String, message: String, vararg data: Pair<String, Any?>) =
        impl.log(LogLevel.INFO, category, message, data.toMap())
    fun error(category: String, message: String, t: Throwable? = null,
              vararg data: Pair<String, Any?>) =
        impl.log(LogLevel.ERROR, category, message, data.toMap(), t)
    fun flush() = impl.flush()
    fun close() = impl.close()
}
```

---

## 6. Security & Network Configurations

### 6.1 Cleartext to Localhost

Android 9+ blocks cleartext HTTP by default. The app overrides this for `127.0.0.1` and `localhost` via `res/xml/network_security_config.xml`:

```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

The manifest references this file (`android:networkSecurityConfig="@xml/network_security_config"`) so the WebView can `fetch()` the local Ktor server over plain HTTP.

### 6.2 WebView Isolation

- The WebView loads from `https://appassets.androidplatform.net/assets/webui/index.html` via `WebViewAssetLoader`, giving it a secure-context origin.
- `mixedContentMode = MIXED_CONTENT_COMPATIBILITY_MODE` allows the secure WebView to also reach `http://127.0.0.1`.

---

## 7. Build & Deployment

### 7.1 Web Build
```bash
cd webui
npm install
npm run build          # → dist/ (hashed JS/CSS chunks)
```

### 7.2 Android Build
```bash
./gradlew :app:assembleDebug
# The `syncWebUi` task (registered in app/build.gradle.kts) copies webui/dist
# → app/src/main/assets/webui automatically before every `preBuild`.
```

### 7.3 Key Gradle Task
```kotlin
// app/build.gradle.kts
val syncWebUi by tasks.registering(Copy::class) {
    from(rootProject.file("webui/dist"))
    into(layout.projectDirectory.dir("src/main/assets/webui"))
}
tasks.named("preBuild") { dependsOn(syncWebUi) }
```

---

## 8. Testing Strategy

All JVM unit tests run via **Robolectric** (no emulator needed) using:
- **JUnit 4**
- **Truth** (assertions)
- **MockK** (mocks)
- **kotlinx-coroutines-test** (`runTest`)

Test coverage spans:
- DataStore settings persistence (`SettingsRepositoryTest`)
- Model file manager (`ModelFileManagerTest`)
- OpenAI DTO serialization (`OpenAiDtoTest`)
- Prompt builder (`GemmaPromptBuilderTest`)
- Chat handler with memory (`ChatCompletionHandlerMemoryTest`)
- Ktor server routes (`LocalLlmServerTest`)
- Memory DAOs (`CoreDaoTest`, `SkillMemoryDaoTest`)
- Vector math (`VectorMathTest`)
- Memory retrieval (`MemoryRetrieverTest`)
- Self-learning (`SelfLearningExtractorTest`, `FactListParserTest`)
- Memory ViewModel (`MemoryViewModelTest`)
- Logging (`FileLoggerTest`)

**Run all tests:**
```bash
./gradlew :app:testDebugUnitTest
```

---

## 9. Design Critique & Improvement Recommendations

### 9.1 What the Design Does Well

1. **Clean architectural boundary** — The entire LLM interaction is hidden behind a single `TextGenerator` interface. Swapping LiteRT-LM for llama.cpp or another runtime is a one-file change.
2. **HTTP-over-localhost bridge** — Using a local Ktor server instead of a JS↔native bridge is robust, language-agnostic, and debuggable with `curl`.
3. **TDD / Red-Green-Refactor discipline** — The implementation plans show test-first development with clear red-to-green steps. 40+ unit tests cover the critical paths.
4. **Memory subsystem is architecturally clean** — Separation of `MemoryRepository`, `SkillRepository`, `LongTermMemoryRepository`, `MemoryRetriever`, `ContextAugmenter`, and `SelfLearningExtractor` gives clear Single Responsibility.
5. **Lazy embedder** — `LazyEmbedder` defers MediaPipe model loading off the startup path, improving cold-start time.
6. **Atomic logging toggle** — Reading a cached `AtomicBoolean` on every log event instead of querying DataStore / Flow is a smart micro-optimization for the hot path.
7. **Gemma prompt builder handles multiple system messages** — Folding all `system` messages into the first user turn is elegant and LLM-format-agnostic.

### 9.2 Areas of Concern & Suggested Improvements

#### A. Memory Retrieval Scalability
**Concern:** `MemoryRetriever` loads **all** entries for a project scope into memory, embeds the query, then scores every candidate in Kotlin (O(N)). With thousands of memories, this will be slow and memory-intensive.

**Recommendations:**
- Add a **local vector index** (e.g., HNSW via `nmslib` or `faiss` Android builds, or a custom KD-tree if dimensionality is fixed).
- Alternatively, implement **SQLite FTS5** for keyword pre-filtering before vector scoring, reducing the candidate set.
- Consider **quantization** of embeddings (e.g., store `ByteArray` instead of `FloatArray`) to halve DB I/O.

#### B. No Token Counting / Context Window Management
**Concern:** The prompt builder does not enforce the model's `contextWindow` (8192 tokens). Long conversations or many injected memories will silently truncate or crash.

**Recommendations:**
- Integrate a **token counter** (even a rough byte-based heuristic, since Gemma uses SentencePiece). Before calling `GemmaPromptBuilder.build()`, truncate old turns from the message list until the estimated token count fits within `contextWindow - maxTokens`.
- Expose a "Memory summary" mechanism: when context is full, summarize older turns with a smaller LLM call and replace them with a summary.

#### C. WebView↔Session Synchronization Is Brittle
**Concern:** The `ChatScreen` recreates its `WebView` using `key(active?.sessionId)`. This means **session switch = full page reload**, losing scroll position and any transient UI state (e.g., the user typing an unsent message).

**Recommendations:**
- Instead of destroying the WebView, send a **JavaScript event** into the running `ChatPanel` to switch sessions. `pi-web-ui` supports custom messages; consider extending `Agent` state via `agent.state.messages = [...]`.
- Alternatively, persist unsent drafts in `IndexedDB` and restore them on load.

#### D. LiteRT-LM `Conversation` Is Not Reused
**Concern:** Every HTTP request creates a fresh `engine.createConversation()`. This discards the KV-cache, making multi-turn inference slower and more battery-intensive than necessary.

**Recommendations:**
- **Keep a `Conversation` alive per active session** (or per model) in `InferenceController`. Map `sessionId → Conversation` and reuse it across requests. This is the single biggest latency optimization available.
- Ensure `Conversation` thread-safety; LiteRT-LM's docs suggest it may be safe for sequential use but document the constraint.

#### E. Self-Learning Prompt Is Fragile
**Concern:** The extraction prompt relies on the model emitting **well-formed JSON**. Gemma is not fine-tuned for instruction following in the same way as GPT-4; malformed arrays or extra prose will silently produce zero facts.

**Recommendations:**
- Add a **retry loop** with temperature ramp (e.g., attempt 3x at increasing temperatures).
- Consider using **LiteRT-LM's structured output / grammar constraints** if the runtime supports JSON-mode or token masking. If not, add a fallback regex parser for line-delimited facts.
- Log raw model output when parsing fails so users can debug.

#### F. Security Hardening
**Concern:** The Ktor server binds to `127.0.0.1` with **permissive CORS (`anyHost()`)** and **no authentication**. On a rooted device or with port-forwarding, any process on the phone can send arbitrary prompts to the LLM.

**Recommendations:**
- Bind to the Unix loopback only (`127.0.0.1`) — already done.
- Restrict `CORS` to the WebView origin (`https://appassets.androidplatform.net`).
- Add a **shared-secret header** check (e.g., expect `X-MobileGem-Auth: <random token>`) and inject that token into the web app's `fetch` calls. The token can be generated at app startup and passed through a `JavascriptInterface` or Cookie.

#### G. No Multi-Model Support
**Concern:** `InferenceController` holds a single `current: TextGenerator?`. Users cannot switch between a fast "thinking" model and a large "creative" model without unload/reload overhead.

**Recommendations:**
- Generalize to a **model pool**: `Map<String, TextGenerator>` with LRU eviction. Each entry retains its `Conversation` alive.
- Expose model-switching UI in Settings.

#### H. MediaPipe TextEmbedder Is Separate from Chat Model
**Concern:** Embeddings come from a **different model** (MediaPipe) than the chat model (Gemma). Semantic drift between the two vector spaces may reduce retrieval quality.

**Recommendations:**
- Investigate whether **LiteRT-LM itself supports embeddings** (an `engine.embed(text)` API was hypothesized in comments but not available in the resolved artifact). If it becomes available, switch to model-native embeddings for perfect alignment.
- If not, evaluate whether MediaPipe's universal-sentence-encoder variant is adequate for short factual memories; consider fine-tuning if quality is poor.

#### I. Dependency on Deprecated npm Packages
**Concern:** `@mariozechner/pi-web-ui` is deprecated in favor of `@earendil-works/pi-web-ui`.

**Recommendations:**
- Plan migration to the new package namespace. Verify API compatibility (the deprecation message implies same API).
- Pin to exact versions and lockfile (`package-lock.json`) to avoid surprise breakage.

#### J. The `.litertlm` Model File Is Large
**Concern:** A Gemma 4-bit quantized model is still ~2–4 GB. Users must manually import it via SAF, and the app stores it in `filesDir` without compression or download manager integration.

**Recommendations:**
- Add a **download manager** with resume capability (using Android's `DownloadManager` or OkHttp + partial content).
- Implement **model compression / decompression** (e.g., `zstd`) if Google's format permits.
- Warn users about storage requirements upfront.

#### K. WebView Asset Loader Path Is Hardcoded
**Concern:** `ChatScreen.kt` hardcodes `https://appassets.androidplatform.net/assets/webui/index.html`. This is a standard Android WebView asset loader domain, but brittle if the asset path changes.

**Recommendations:**
- Extract to a constants file or `BuildConfig` field.

---

## 10. Quick Reference for New Engineers

### Adding a new memory search algorithm
1. Implement a new `Embedder` (or reuse `MediaPipeTextEmbedder`)
2. Replace `MemoryRetriever` or subclass it
3. Update `AppContainer` to inject the new retriever into `MemoryContextAugmenter`
4. Add unit tests in `MemoryRetrieverTest` or a new `*Test.kt`

### Swapping the inference engine (e.g., for llama.cpp)
1. Create a new file under `inference/YourTextGenerator.kt` implementing `TextGenerator`
2. Update `AppContainer.generatorFactory` to instantiate your class instead of `LiteRtLmTextGenerator`
3. Verify `close()` is called in `InferenceController.unload()` — if your engine implements `Closeable`, it already is.

### Changing the prompt template for a different model
1. Edit `server/GemmaPromptBuilder.kt`
2. If the model supports system messages natively (e.g., Llama-3), refactor `build()` to emit `<|system|>` / `<|user|>` / `<|assistant|>` tokens instead of Gemma's `<start_of_turn>`.
3. Update `GemmaPromptBuilderTest` to match.

### Adding a new Android permission
1. Add to `AndroidManifest.xml`
2. If runtime permission (Android 6+), request it in `MainActivity` or the relevant screen before the feature is used.

---

## 12. External References & Further Reading

### 12.1 Official Documentation

| Topic | URL | Status |
|---|---|---|
| **LiteRT** (successor to TensorFlow Lite) | [GitHub: google-ai-edge/LiteRT](https://github.com/google-ai-edge/LiteRT) | ✅ Live |
| **LiteRT-LM Maven** (`litertlm-android`) | [Maven Central v0.12.0](https://mvnrepository.com/artifact/com.google.ai.edge.litertlm/litertlm-android) | ✅ Live |
| **MediaPipe Solutions Guide** | [ai.google.dev/edge/mediapipe/solutions/guide](https://ai.google.dev/edge/mediapipe/solutions/guide) | ✅ Live |
| **MediaPipe Wikipedia** | [wikipedia.org/wiki/MediaPipe](https://en.wikipedia.org/wiki/MediaPipe) | ✅ Live |
| **pi-web-ui (successor)** | [npm: @earendil-works/pi-web-ui](https://www.npmjs.com/package/@earendil-works/pi-web-ui) | ✅ Live (v0.75.3) |
| **lit (npm)** | [npm: lit](https://www.npmjs.com/package/lit) | ✅ Live (v3.3.3) |
| **Ktor Server** | [ktor.io/docs/server.html](https://ktor.io/docs/server.html) | ✅ Live |
| **Jetpack Compose** | [developer.android.com/jetpack/compose](https://developer.android.com/jetpack/compose) | ✅ Live |
| **Room Persistence** | [developer.android.com/training/data-storage/room](https://developer.android.com/training/data-storage/room) | ✅ Live |
| **DataStore Preferences** | [developer.android.com/topic/libraries/architecture/datastore](https://developer.android.com/topic/libraries/architecture/datastore) | ✅ Live |

### 12.2 Migration Notes

**From `@mariozechner/*` to `@earendil-works/*`:**

```bash
# Old (deprecated)
npm i @mariozechner/pi-web-ui @mariozechner/pi-agent-core @mariozechner/pi-ai

# New (recommended)
npm i @earendil-works/pi-web-ui @earendil-works/pi-agent-core @earendil-works/pi-ai
```

```typescript
// Old imports
import { Agent } from "@mariozechner/pi-agent-core";
import { ChatPanel } from "@mariozechner/pi-web-ui";

// New imports (API identical)
import { Agent } from "@earendil-works/pi-agent-core";
import { ChatPanel } from "@earendil-works/pi-web-ui";
```

The earendil-works fork (v0.75.3 as of May 2026) receives active maintenance while the mariozechner packages are deprecated. Weekly download stats (as of May 2026):
- `@mariozechner/pi-web-ui`: ~6,300 / week (legacy traffic)
- `@earendil-works/pi-web-ui`: ~2,180 / week (growing successor)

### 12.3 Version Reference Snippet

```kotlin
// app/build.gradle.kts — verified dependency versions
// Last verified against Maven Central / npm: 2026-05-26

dependencies {
    // Compose BOM (checked: Sept 2024)
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    
    // LiteRT-LM (latest stable: 0.12.0, May 2026)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.12.0")
    
    // MediaPipe Text (latest bundled: 0.10.35)
    implementation("com.google.mediapipe:tasks-text:0.10.35")
    
    // Room (latest stable: 2.7.2, May 2026)
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    
    // Ktor Server (stable: 2.3.12)
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-cors:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    
    // Coroutines & Serialization (stable)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
}
```

---

*End of Report — MobileGem Engineering Architecture Report v1.1*  
*Last updated: 2026-05-26 with live web-fetch verification*
