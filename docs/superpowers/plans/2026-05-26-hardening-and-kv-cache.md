# MobileGem Hardening & KV-Cache Reuse — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the existing MobileGem app along five axes — security (CORS + shared-secret auth), self-learning robustness (retry, fallback parser, raw-output logging), context-window management (token estimator + truncation), DB I/O (embedding quantization), and performance (per-session `Conversation` reuse for KV-cache benefit) — without altering the externally observable behaviour for callers that already work.

**Architecture:** Each task is a focused, independent change layered on top of the existing Plan 1 + Plan 2 foundation. The chat-handler / LiteRT-LM seam is extended with an optional `SessionedTextGenerator` interface so KV-cache reuse can be added inside `LiteRtLmTextGenerator` without breaking the existing `TextGenerator` contract or any existing test. Storage gains quantized embeddings with a Room schema bump (destructive migration is acceptable while the app has no production users). Security gains a per-launch token enforced server-side and injected into the WebView via a small `@JavascriptInterface` bridge.

**Tech Stack:** Kotlin 2.3.21, AGP 8.10.1, Gradle 8.11.1, Room 2.7.2, Ktor 2.3.12, Compose BOM 2024.09.03, kotlinx-serialization, kotlinx-coroutines-test, MediaPipe tasks-text 0.10.35. LiteRT-LM `com.google.ai.edge.litertlm:litertlm-android:0.12.0`.

**Prerequisite:** Working build at `main` with 58 unit tests green (commit `d0a929f` or later). Plan 1 (foundation), Plan 2 (memory subsystem), the logging subsystem, the engine `cacheDir` fix, and the scrollable-Settings + in-app-log-viewer UI fix are all already implemented and merged.

**Conventions:**
- Run unit tests with `./gradlew :app:testDebugUnitTest` from the repo root.
- Build the app with `cd webui && npm run build && cd .. && ./gradlew :app:assembleDebug`.
- App module is `app/`, package root `com.mobilegem.gemma`.
- Git identity is configured on this branch — plain `git commit` works.

**Out of scope (deferred — need brainstorming before planning):**
- **A.index** — full HNSW / FTS5 vector index. This task only does quantization.
- **C** — WebView session sync without WebView reload. Needs pi-web-ui API study.
- **G** — Multi-model pool with LRU. Needs UX decisions.
- **H** — Native (LiteRT-LM) embedder. Investigation only; nothing to implement until LiteRT-LM ships embeddings.
- **I** — pi-web-ui namespace migration to `@earendil-works/pi-web-ui`. Needs compat verification first.

---

## Phase 1 — Easy wins (security + cleanup)

## Task 1: Extract the WebView asset URL to a constant

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/ui/chat/ChatConfig.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/ui/chat/ChatScreen.kt`

No unit test — this is a pure rename refactor. The build-and-tests-still-pass gate is sufficient.

- [ ] **Step 1: Create the constants file**

`app/src/main/java/com/mobilegem/gemma/ui/chat/ChatConfig.kt`:
```kotlin
package com.mobilegem.gemma.ui.chat

/**
 * Centralized constants for the embedded chat WebView. Extracted from
 * ChatScreen so the URL and origin can be referenced consistently (e.g. for
 * CORS allow-listing on the server side) without string duplication.
 */
object ChatConfig {
    /** Origin under which WebViewAssetLoader serves the bundled web app. */
    const val WEB_UI_ORIGIN = "https://appassets.androidplatform.net"

    /** Full URL of the chat web app's entry point. */
    const val WEB_UI_URL = "$WEB_UI_ORIGIN/assets/webui/index.html"
}
```

- [ ] **Step 2: Replace the hardcoded URL in `ChatScreen.kt`**

In `app/src/main/java/com/mobilegem/gemma/ui/chat/ChatScreen.kt`, find the `loadUrl("https://appassets.androidplatform.net/assets/webui/index.html")` line and replace with:
```kotlin
                        loadUrl(ChatConfig.WEB_UI_URL)
```
Add `import com.mobilegem.gemma.ui.chat.ChatConfig` to the imports (or just use the unqualified `ChatConfig` since it's in the same package — adjust to whichever the file's import style is).

- [ ] **Step 3: Verify the build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 58 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/ui/chat/ChatConfig.kt app/src/main/java/com/mobilegem/gemma/ui/chat/ChatScreen.kt
git commit -m "refactor: extract WebView asset URL to ChatConfig"
```

---

## Task 2: Restrict CORS to the WebView origin only

**Files:**
- Modify: `app/src/main/java/com/mobilegem/gemma/server/LocalLlmServer.kt`
- Modify: `app/src/test/java/com/mobilegem/gemma/server/LocalLlmServerTest.kt`

- [ ] **Step 1: Add a failing test for restricted CORS**

Append this test method INSIDE the existing `LocalLlmServerTest` class in `app/src/test/java/com/mobilegem/gemma/server/LocalLlmServerTest.kt`:

```kotlin
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
```
Add the imports if missing:
```kotlin
import io.ktor.client.request.header
```

- [ ] **Step 2: Run the test, verify it FAILS**

Run: `./gradlew :app:testDebugUnitTest --tests "*LocalLlmServerTest"`
Expected: FAIL on `corsAllowsOnlyTheWebViewOrigin` — the current `CORS { anyHost() }` echoes any Origin (`disallowed` gets a non-null Allow-Origin header).

- [ ] **Step 3: Replace `anyHost()` with the specific origin in `LocalLlmServer.kt`**

In `app/src/main/java/com/mobilegem/gemma/server/LocalLlmServer.kt`, find the `install(CORS) { … }` block and replace it with:
```kotlin
    install(CORS) {
        allowHost("appassets.androidplatform.net", schemes = listOf("https"))
        allowHeader("Content-Type")
        allowHeader("Authorization")
    }
```
(Drop the `anyHost()` call.)

- [ ] **Step 4: Run the test, verify it PASSES**

Run: `./gradlew :app:testDebugUnitTest --tests "*LocalLlmServerTest"`
Expected: `BUILD SUCCESSFUL`, all `LocalLlmServerTest` tests pass (3 tests, including the new CORS test).

- [ ] **Step 5: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 59 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/server/LocalLlmServer.kt app/src/test/java/com/mobilegem/gemma/server/LocalLlmServerTest.kt
git commit -m "feat(server): restrict CORS to WebView origin"
```

---

## Task 3: Shared-secret auth between WebView and local server

A random per-launch token is generated in `AppContainer`, the server requires it on `Authorization: Bearer <token>`, and the WebView reads it via a `@JavascriptInterface` bridge and passes it as the `apiKey` of the OpenAI-compatible provider (the SDK then sends it as the Bearer token automatically).

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/server/AuthToken.kt`
- Create: `app/src/main/java/com/mobilegem/gemma/ui/chat/MobileGemBridge.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/server/LocalLlmServer.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/server/ChatCompletionHandler.kt` (no, untouched — handler is wrapped in route before it sees the request)
- Modify: `app/src/main/java/com/mobilegem/gemma/AppContainer.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/inference/InferenceController.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/ui/chat/ChatScreen.kt`
- Modify: `webui/src/main.ts`
- Modify: `app/src/test/java/com/mobilegem/gemma/server/LocalLlmServerTest.kt`

- [ ] **Step 1: Create the token holder**

`app/src/main/java/com/mobilegem/gemma/server/AuthToken.kt`:
```kotlin
package com.mobilegem.gemma.server

import java.util.UUID

/**
 * A random per-process token used to authenticate requests from the in-app
 * WebView to the local HTTP server. Generated once at [AppContainer]
 * construction; never persisted; rotates on every app launch.
 *
 * The same instance is read both by the server (to validate the
 * Authorization header) and by the WebView bridge (to expose to JS).
 */
class AuthToken(val value: String = UUID.randomUUID().toString())
```

- [ ] **Step 2: Add a failing test for auth enforcement**

Append these two tests to `app/src/test/java/com/mobilegem/gemma/server/LocalLlmServerTest.kt`:

```kotlin
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
```
Add imports if missing:
```kotlin
import io.ktor.http.HttpHeaders
```

- [ ] **Step 3: Run the tests, verify they FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "*LocalLlmServerTest"`
Expected: FAIL — `installLlmRoutes` has no `expectedToken` parameter; the code doesn't compile.

- [ ] **Step 4: Add auth enforcement to `installLlmRoutes`**

Replace the FULL `installLlmRoutes` function (and update `LocalLlmServer.start`) in `app/src/main/java/com/mobilegem/gemma/server/LocalLlmServer.kt`. The relevant fragment (other functions in the file remain unchanged):

```kotlin
fun Application.installLlmRoutes(
    handler: ChatCompletionHandler,
    modelId: String,
    expectedToken: String? = null,
) {
    install(ContentNegotiation) { json(jsonFormat) }
    install(CORS) {
        allowHost("appassets.androidplatform.net", schemes = listOf("https"))
        allowHeader("Content-Type")
        allowHeader("Authorization")
    }
    routing {
        get("/v1/models") {
            if (!checkAuth(expectedToken)) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
            call.respond(
                ModelList(
                    `object` = "list",
                    data = listOf(ModelInfo(id = modelId)),
                ),
            )
        }
        post("/v1/chat/completions") {
            if (!checkAuth(expectedToken)) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            val request = jsonFormat.decodeFromString(
                ChatCompletionRequest.serializer(), call.receiveText(),
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
        }
    }
}

private fun io.ktor.server.routing.RoutingContext.checkAuth(expected: String?): Boolean {
    if (expected == null) return true
    val header = call.request.headers[HttpHeaders.Authorization] ?: return false
    val token = header.removePrefix("Bearer ").trim()
    return token == expected
}
```

If the project's exact Ktor 2.3.12 PipelineContext type differs from `RoutingContext` above, replace the receiver with the actual one — Ktor 2.3.x typically uses `io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>` for route handlers. If `RoutingContext` does not resolve, use:
```kotlin
private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.checkAuth(
    expected: String?,
): Boolean { … }
```

Imports to add at the top:
```kotlin
import io.ktor.http.HttpHeaders
```

Also update `LocalLlmServer.start` to accept and forward the token:
```kotlin
class LocalLlmServer(private val port: Int = 8765) {

    private var server: ApplicationEngine? = null

    fun start(handler: ChatCompletionHandler, modelId: String, expectedToken: String? = null) {
        stop()
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            installLlmRoutes(handler, modelId, expectedToken)
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        server = null
    }

    fun isRunning(): Boolean = server != null

    val baseUrl: String get() = "http://127.0.0.1:$port/v1"
}
```

- [ ] **Step 5: Run the tests, verify they PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*LocalLlmServerTest"`
Expected: all `LocalLlmServerTest` tests pass (5 tests now: original 2 + CORS + 2 auth).

- [ ] **Step 6: Plumb the token through `InferenceController` and `AppContainer`**

In `app/src/main/java/com/mobilegem/gemma/inference/InferenceController.kt`, add an `authToken: String? = null` constructor parameter and pass it into `server.start(...)`. The relevant section:

```kotlin
class InferenceController(
    val server: LocalLlmServer = LocalLlmServer(),
    private val generatorFactory: (modelPath: String, backend: InferenceBackend) -> TextGenerator =
        { path, backend -> LiteRtLmTextGenerator.create(path, backend) },
    private val activeSession: ActiveSessionHolder? = null,
    private val augmenter: ContextAugmenter? = null,
    private val persister: ConversationPersister? = null,
    private val authToken: String? = null,
) {
    // … existing properties & state unchanged …

    @Synchronized
    fun loadModel(modelPath: String, backend: InferenceBackend) {
        unload()
        val name = File(modelPath).name
        val generator = generatorFactory(modelPath, backend)
        current = generator
        val handler = ChatCompletionHandler(
            generator = generator,
            augmenter = augmenter,
            persister = persister,
            activeSession = activeSession,
        )
        server.start(handler, modelId = name, expectedToken = authToken)
        _state.value = InferenceState(loadedModelName = name, serverRunning = true)
    }

    // … unload, currentGenerator unchanged …
}
```

In `app/src/main/java/com/mobilegem/gemma/AppContainer.kt`, add:
```kotlin
    val authToken: AuthToken = AuthToken()
```
right above the `inferenceController` declaration. Then add `authToken = authToken.value,` to the `InferenceController(...)` constructor call (alongside the existing `activeSession`, `augmenter`, `persister`, `generatorFactory` arguments).

- [ ] **Step 7: Create the WebView↔native bridge**

`app/src/main/java/com/mobilegem/gemma/ui/chat/MobileGemBridge.kt`:
```kotlin
package com.mobilegem.gemma.ui.chat

import android.webkit.JavascriptInterface

/**
 * Bridge object exposed to the in-app WebView's JavaScript context as
 * `window.MobileGem`. Provides the per-launch auth token required to talk
 * to the local LLM server. Methods MUST be safe to call from any thread —
 * the WebView invokes JavascriptInterface methods on a dedicated thread.
 */
class MobileGemBridge(private val authTokenProvider: () -> String) {
    @JavascriptInterface
    fun getAuthToken(): String = authTokenProvider()
}
```

- [ ] **Step 8: Wire the bridge into `ChatScreen`**

In `app/src/main/java/com/mobilegem/gemma/ui/chat/ChatScreen.kt`, change the signature to accept the auth token (or read it via a thread-safe holder). The cleanest plumbing is via `MainActivity → AppScaffold → ChatScreen`. Add an `authToken: String` parameter:

```kotlin
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatScreen(
    activeSessionHolder: ActiveSessionHolder,
    authToken: String,
    modifier: Modifier = Modifier,
) {
    // …existing body unchanged, except inside `WebView(context).apply { … }`
    // BEFORE settings.javaScriptEnabled = true, add:
    addJavascriptInterface(MobileGemBridge { authToken }, "MobileGem")
    // The rest (settings, webViewClient, loadUrl) is unchanged.
}
```

Update `app/src/main/java/com/mobilegem/gemma/ui/navigation/AppScaffold.kt`:
- Add `authToken: String` parameter to `AppScaffold(...)`.
- Pass it down: `ChatScreen(activeSessionHolder = activeSessionHolder, authToken = authToken)`.

Update `app/src/main/java/com/mobilegem/gemma/MainActivity.kt`:
- Add `authToken = container.authToken.value` to the `AppScaffold(...)` call.

- [ ] **Step 9: Update the web app to use the bridge token**

In `webui/src/main.ts`, change the `bootstrap()` (or wherever `LOCAL_BASE_URL`/`buildLocalModel` is used) so the provider's `apiKey` reads from the bridge:

```ts
function getAuthToken(): string {
    try {
        const w = window as unknown as { MobileGem?: { getAuthToken?: () => string } };
        return w.MobileGem?.getAuthToken?.() ?? "";
    } catch {
        return "";
    }
}
```
Then, wherever the local provider model is built (currently `buildLocalModel` returns a `Model<"openai-completions">`), set `apiKey: getAuthToken()`. If the existing `apiKey` is something like `"not-needed"` or `"local"`, replace that literal with `getAuthToken()`.

If the existing seeding-of-API-keys path in `main.ts` calls something like `providerKeys.set(...)`, also set that to `getAuthToken()`.

- [ ] **Step 10: Run the full suite + rebuild the web app**

```
cd webui && npm run build && cd ..
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL` for all three, 0 unit test failures.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat(security): per-launch shared-secret auth between WebView and server"
```

---

## Phase 2 — Self-learning robustness

## Task 4: Line-delimited fallback in the fact parser

The model sometimes emits `- fact 1\n- fact 2` instead of a JSON array. Add a fallback that recognizes bulleted / line-delimited output.

**Files:**
- Modify: `app/src/main/java/com/mobilegem/gemma/memory/FactListParser.kt`
- Modify: `app/src/test/java/com/mobilegem/gemma/memory/FactListParserTest.kt`

- [ ] **Step 1: Write the failing tests**

Append these tests INSIDE the existing `FactListParserTest` class:

```kotlin
    @Test
    fun fallsBackToBulletedLines() {
        val raw = """
            Here are the facts:
            - User prefers metric units
            - User is building an Android app
            * Bullets with asterisks also work
        """.trimIndent()
        assertThat(FactListParser.parse(raw))
            .containsExactly(
                "User prefers metric units",
                "User is building an Android app",
                "Bullets with asterisks also work",
            )
    }

    @Test
    fun fallsBackToNumberedLines() {
        val raw = """
            1. User likes Kotlin
            2. User is in Berlin
        """.trimIndent()
        assertThat(FactListParser.parse(raw))
            .containsExactly("User likes Kotlin", "User is in Berlin")
    }

    @Test
    fun ignoresNonFactLinesInFallback() {
        val raw = """
            Sure! Here are some facts:
            - User has a cat
            Hope this helps.
        """.trimIndent()
        assertThat(FactListParser.parse(raw)).containsExactly("User has a cat")
    }
```

- [ ] **Step 2: Run the tests, verify they FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "*FactListParserTest"`
Expected: the three new tests FAIL — current parser returns empty list when no JSON array is present.

- [ ] **Step 3: Add the fallback to `FactListParser.kt`**

Replace the FULL contents of `app/src/main/java/com/mobilegem/gemma/memory/FactListParser.kt` with:

```kotlin
package com.mobilegem.gemma.memory

import kotlinx.serialization.json.Json

object FactListParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val bulletRegex = Regex("""^\s*(?:[-*•]|\d+[.)])\s+(.+?)\s*$""")

    /**
     * Extracts facts from model output, tolerant of multiple formats.
     *
     * Strategy:
     * 1. If the text contains a top-level JSON string array, parse and return it.
     * 2. Otherwise, scan line by line for bullets (`-`, `*`, `•`) or numbered
     *    items (`1.`, `2)`), and return their captured text.
     *
     * All results are trimmed; blanks are dropped.
     */
    fun parse(raw: String): List<String> {
        parseJsonArray(raw)?.let { return it }
        return raw.lineSequence()
            .mapNotNull { line -> bulletRegex.matchEntire(line)?.groupValues?.get(1)?.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun parseJsonArray(raw: String): List<String>? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val slice = raw.substring(start, end + 1)
        val parsed = runCatching { json.decodeFromString<List<String>>(slice) }.getOrNull()
            ?: return null
        val cleaned = parsed.map { it.trim() }.filter { it.isNotBlank() }
        // If the JSON slice yielded nothing useful, fall through to line-mode rather
        // than masking a real list with an empty array.
        return cleaned.ifEmpty { null }
    }
}
```

- [ ] **Step 4: Run the tests, verify they PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*FactListParserTest"`
Expected: all 6 tests pass (3 original + 3 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/memory/FactListParser.kt app/src/test/java/com/mobilegem/gemma/memory/FactListParserTest.kt
git commit -m "feat(memory): fallback to bulleted/numbered lines in fact parser"
```

---

## Task 5: Capturing logger + raw-output logging on empty parse

To verify warn-on-empty-parse behaviour deterministically, introduce a small `CapturingLogger` test fake. Then make `SelfLearningExtractor` warn when the model produced text but the parse yielded zero facts.

**Files:**
- Create: `app/src/test/java/com/mobilegem/gemma/logging/CapturingLogger.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/memory/SelfLearningExtractor.kt`
- Modify: `app/src/test/java/com/mobilegem/gemma/memory/SelfLearningExtractorTest.kt`

- [ ] **Step 1: Create the capturing logger**

`app/src/test/java/com/mobilegem/gemma/logging/CapturingLogger.kt`:
```kotlin
package com.mobilegem.gemma.logging

/**
 * Test-only [AppLogger] that records every event in memory. Install via
 * `AppLog.install(CapturingLogger())` in `@Before`; uninstall in `@After`
 * with `AppLog.uninstall()` so other tests are unaffected.
 */
class CapturingLogger : AppLogger {
    val entries: MutableList<Entry> = mutableListOf()

    data class Entry(
        val level: LogLevel,
        val category: String,
        val message: String,
        val data: Map<String, Any?>,
        val throwable: Throwable?,
    )

    override fun log(
        level: LogLevel,
        category: String,
        message: String,
        data: Map<String, Any?>,
        throwable: Throwable?,
    ) {
        entries += Entry(level, category, message, data, throwable)
    }

    override fun flush() = Unit

    /** True when at least one entry matches the given category + message. */
    fun contains(category: String, message: String): Boolean =
        entries.any { it.category == category && it.message == message }

    /** Returns all entries matching the given category. */
    fun forCategory(category: String): List<Entry> =
        entries.filter { it.category == category }
}
```

- [ ] **Step 2: Write a failing test**

Append this test inside `SelfLearningExtractorTest`:

```kotlin
    @Test
    fun logsRawOutputWhenParseYieldsNothing() = runTest {
        val capturing = com.mobilegem.gemma.logging.CapturingLogger()
        com.mobilegem.gemma.logging.AppLog.install(capturing)
        try {
            val extractor = SelfLearningExtractor(
                generator = FakeTextGenerator(tokens = listOf("Sorry, I couldn't think of any facts.")),
                embedder = FakeEmbedder(emptyMap()),
                ltm = ltm,
            )
            val stored = extractor.extractAndStore(2, 5, listOf(ChatMessage("user", "hi")))
            assertThat(stored).isEmpty()
            val warn = capturing.forCategory("selflearn")
                .firstOrNull { it.message == "parseEmpty" }
            assertThat(warn).isNotNull()
            assertThat(warn!!.data["rawOutput"].toString())
                .contains("Sorry, I couldn't think")
        } finally {
            com.mobilegem.gemma.logging.AppLog.uninstall()
        }
    }
```

- [ ] **Step 3: Run, verify it FAILS**

Run: `./gradlew :app:testDebugUnitTest --tests "*SelfLearningExtractorTest"`
Expected: FAIL on `logsRawOutputWhenParseYieldsNothing` — no such log entry is currently produced.

- [ ] **Step 4: Add the warn log in `SelfLearningExtractor`**

In `app/src/main/java/com/mobilegem/gemma/memory/SelfLearningExtractor.kt`, inside `extractAndStore`, AFTER the existing `val facts = FactListParser.parse(output)` line and BEFORE the `return facts.map { ... }` block, add:

```kotlin
        if (facts.isEmpty() && output.isNotBlank()) {
            com.mobilegem.gemma.logging.AppLog.warn(
                category = "selflearn",
                message = "parseEmpty",
                "projectId" to projectId,
                "sessionId" to sessionId,
                "rawOutput" to output.take(2000),
            )
        }
```

- [ ] **Step 5: Run the tests, verify they PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*SelfLearningExtractorTest"`
Expected: 3 tests pass (2 original + 1 new).

- [ ] **Step 6: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/test/java/com/mobilegem/gemma/logging/CapturingLogger.kt app/src/main/java/com/mobilegem/gemma/memory/SelfLearningExtractor.kt app/src/test/java/com/mobilegem/gemma/memory/SelfLearningExtractorTest.kt
git commit -m "feat(selflearn): log raw output on empty parse; add CapturingLogger test fake"
```

---

## Task 6: Self-learning retry with temperature ramp

If the first extraction attempt parses to zero facts AND the model produced non-blank output (i.e. it tried but failed to format), retry up to 2 more times at higher temperatures. Stop on first non-empty parse.

**Files:**
- Create: `app/src/test/java/com/mobilegem/gemma/inference/ScriptedTextGenerator.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/memory/SelfLearningExtractor.kt`
- Modify: `app/src/test/java/com/mobilegem/gemma/memory/SelfLearningExtractorTest.kt`

- [ ] **Step 1: Create a multi-call test fake**

`app/src/test/java/com/mobilegem/gemma/inference/ScriptedTextGenerator.kt`:
```kotlin
package com.mobilegem.gemma.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Test [TextGenerator] that returns a different output on each successive
 * [generate] invocation. After the script runs out, repeats the last script
 * entry. Useful for verifying retry logic.
 */
class ScriptedTextGenerator(private val scripts: List<String>) : TextGenerator {
    private var callIndex = 0
    val calls: MutableList<Pair<String, Float>> = mutableListOf()

    override fun generate(prompt: String, temperature: Float): Flow<String> {
        calls += prompt to temperature
        val idx = if (callIndex < scripts.size) callIndex else scripts.lastIndex
        callIndex++
        return flowOf(scripts[idx])
    }
}
```

- [ ] **Step 2: Write failing tests**

Append these tests inside `SelfLearningExtractorTest`:

```kotlin
    @Test
    fun retriesOnEmptyParseAndSucceedsOnLaterAttempt() = runTest {
        val generator = com.mobilegem.gemma.inference.ScriptedTextGenerator(
            listOf(
                "I don't have any facts to add.",   // first attempt: empty parse
                """["User likes Kotlin"]""",         // second attempt: success
            ),
        )
        val embedder = FakeEmbedder(mapOf("User likes Kotlin" to floatArrayOf(1f, 0f)))
        val extractor = SelfLearningExtractor(generator, embedder, ltm)

        val stored = extractor.extractAndStore(
            projectId = 2,
            sessionId = 5,
            transcript = listOf(ChatMessage("user", "I love Kotlin")),
        )

        assertThat(stored.map { it.content }).containsExactly("User likes Kotlin")
        assertThat(generator.calls).hasSize(2)
        // Temperatures increase across attempts.
        assertThat(generator.calls[0].second).isLessThan(generator.calls[1].second)
    }

    @Test
    fun givesUpAfterMaxAttempts() = runTest {
        val generator = com.mobilegem.gemma.inference.ScriptedTextGenerator(
            listOf("nope", "still nope", "really nope"),
        )
        val extractor = SelfLearningExtractor(
            generator, FakeEmbedder(emptyMap()), ltm,
        )
        val stored = extractor.extractAndStore(
            2, 5, listOf(ChatMessage("user", "anything")),
        )
        assertThat(stored).isEmpty()
        assertThat(generator.calls).hasSize(3)
    }
```

- [ ] **Step 3: Run, verify they FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "*SelfLearningExtractorTest"`
Expected: FAIL — current extractor invokes the generator exactly once.

- [ ] **Step 4: Implement retry in `SelfLearningExtractor`**

Replace the FULL contents of `app/src/main/java/com/mobilegem/gemma/memory/SelfLearningExtractor.kt` with:

```kotlin
package com.mobilegem.gemma.memory

import com.mobilegem.gemma.inference.Embedder
import com.mobilegem.gemma.inference.TextGenerator
import com.mobilegem.gemma.logging.AppLog
import com.mobilegem.gemma.memory.db.MemoryEntry
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.toList

class SelfLearningExtractor(
    private val generator: TextGenerator,
    private val embedder: Embedder,
    private val ltm: LongTermMemoryRepository,
    /** Temperatures to try in order; length determines the maximum attempt count. */
    private val temperatures: List<Float> = listOf(0.2f, 0.5f, 0.8f),
) {

    /**
     * Extracts durable facts from [transcript] via the on-device model. Retries
     * with progressively higher temperature when the parser yields zero facts
     * AND the model actually produced output (so retries only happen when the
     * model "tried" but mis-formatted). Returns the [MemoryEntry]s stored.
     */
    suspend fun extractAndStore(
        projectId: Long, sessionId: Long, transcript: List<ChatMessage>,
    ): List<MemoryEntry> {
        val prompt = buildExtractionPrompt(transcript)

        var lastOutput = ""
        var facts: List<String> = emptyList()
        for (temperature in temperatures) {
            val output = generator.generate(prompt, temperature).toList().joinToString("")
            lastOutput = output
            facts = FactListParser.parse(output)
            if (facts.isNotEmpty()) break
        }

        if (facts.isEmpty() && lastOutput.isNotBlank()) {
            AppLog.warn(
                category = "selflearn",
                message = "parseEmpty",
                "projectId" to projectId,
                "sessionId" to sessionId,
                "attempts" to temperatures.size,
                "rawOutput" to lastOutput.take(2000),
            )
        }

        return facts.map { fact ->
            val embedding = embedder.embed(fact)
            val id = ltm.store(
                projectId = projectId,
                content = fact,
                embedding = embedding,
                sourceSessionId = sessionId,
            )
            MemoryEntry(
                id = id, projectId = projectId, content = fact,
                embedding = embedding, sourceSessionId = sessionId, createdAt = 0,
            )
        }
    }

    private fun buildExtractionPrompt(transcript: List<ChatMessage>): String {
        val convo = transcript.joinToString("\n") { "${it.role}: ${it.content}" }
        return "<start_of_turn>user\n" +
            "Read the following conversation and extract durable, factual things " +
            "worth remembering about the user or project for future conversations " +
            "(preferences, decisions, persistent context). Ignore one-off chatter. " +
            "Respond with ONLY a JSON array of short fact strings, or [] if there is " +
            "nothing durable.\n\n" +
            "Conversation:\n$convo<end_of_turn>\n" +
            "<start_of_turn>model\n"
    }
}
```

- [ ] **Step 5: Run the tests, verify they PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*SelfLearningExtractorTest"`
Expected: 5 tests pass (2 original + 1 from Task 5 + 2 from this task).

- [ ] **Step 6: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/test/java/com/mobilegem/gemma/inference/ScriptedTextGenerator.kt app/src/main/java/com/mobilegem/gemma/memory/SelfLearningExtractor.kt app/src/test/java/com/mobilegem/gemma/memory/SelfLearningExtractorTest.kt
git commit -m "feat(selflearn): retry extraction with temperature ramp"
```

---

## Phase 3 — Context-window management

## Task 7: Token estimator

A cheap heuristic — SentencePiece-tokenized English averages ~4 characters per token; add a small safety margin.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/server/TokenEstimator.kt`
- Create: `app/src/test/java/com/mobilegem/gemma/server/TokenEstimatorTest.kt`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/mobilegem/gemma/server/TokenEstimatorTest.kt`:
```kotlin
package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun emptyStringIsZeroTokens() {
        assertThat(TokenEstimator.estimate("")).isEqualTo(0)
    }

    @Test
    fun shortStringRoundsUp() {
        // 5 chars / 4 = 1.25 → 2
        assertThat(TokenEstimator.estimate("hello")).isEqualTo(2)
    }

    @Test
    fun estimateGrowsWithLength() {
        val short = TokenEstimator.estimate("hi")
        val medium = TokenEstimator.estimate("hello there friend")
        val long = TokenEstimator.estimate("hello there friend, ".repeat(20))
        assertThat(medium).isGreaterThan(short)
        assertThat(long).isGreaterThan(medium)
    }

    @Test
    fun estimateForMessageIncludesPerTurnOverhead() {
        // Per-turn overhead (template + role tag) is at least 4 tokens.
        val plain = TokenEstimator.estimate("hi")
        val asMsg = TokenEstimator.estimateMessage(ChatMessage("user", "hi"))
        assertThat(asMsg).isAtLeast(plain + 4)
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "*TokenEstimatorTest"`
Expected: FAIL — class unresolved.

- [ ] **Step 3: Implement the estimator**

`app/src/main/java/com/mobilegem/gemma/server/TokenEstimator.kt`:
```kotlin
package com.mobilegem.gemma.server

/**
 * Cheap heuristic SentencePiece-token counter for Gemma-style models. Tuned
 * to overestimate slightly so context-window truncation does not under-fill.
 *
 * - English averages ~4 characters per token; we use `ceil(chars / 4)`.
 * - Per-message overhead accounts for `<start_of_turn>role\n…<end_of_turn>\n`
 *   template wrapping (4 tokens minimum).
 */
object TokenEstimator {

    private const val CHARS_PER_TOKEN = 4
    private const val PER_MESSAGE_OVERHEAD = 4

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }

    fun estimateMessage(message: ChatMessage): Int =
        estimate(message.content) + PER_MESSAGE_OVERHEAD

    fun estimateMessages(messages: List<ChatMessage>): Int =
        messages.sumOf { estimateMessage(it) }
}
```

- [ ] **Step 4: Run, verify PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*TokenEstimatorTest"`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/server/TokenEstimator.kt app/src/test/java/com/mobilegem/gemma/server/TokenEstimatorTest.kt
git commit -m "feat(server): add token estimator heuristic"
```

---

## Task 8: Truncate oldest turns to fit context window

A helper that drops the oldest non-system turns from a `messages` list until the estimated total fits within `maxInputTokens`. Plug it into `ChatCompletionHandler` so long conversations never overflow the context window.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/server/MessageBudget.kt`
- Create: `app/src/test/java/com/mobilegem/gemma/server/MessageBudgetTest.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/server/ChatCompletionHandler.kt`
- Modify: `app/src/test/java/com/mobilegem/gemma/server/ChatCompletionHandlerTest.kt` (no behavioural change to existing tests — just verifying nothing breaks)

- [ ] **Step 1: Write failing tests for the budget helper**

`app/src/test/java/com/mobilegem/gemma/server/MessageBudgetTest.kt`:
```kotlin
package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageBudgetTest {

    @Test
    fun keepsAllWhenWithinBudget() {
        val messages = listOf(
            ChatMessage("user", "hi"),
            ChatMessage("assistant", "hello"),
            ChatMessage("user", "how are you?"),
        )
        val result = MessageBudget.fitWithinBudget(messages, maxInputTokens = 1000)
        assertThat(result).isEqualTo(messages)
    }

    @Test
    fun dropsOldestNonSystemMessagesUntilFits() {
        val long = "x".repeat(400) // ~100 tokens per content
        val messages = listOf(
            ChatMessage("system", "be terse"),
            ChatMessage("user", long),
            ChatMessage("assistant", long),
            ChatMessage("user", long),
            ChatMessage("assistant", long),
            ChatMessage("user", "latest"),
        )
        val result = MessageBudget.fitWithinBudget(messages, maxInputTokens = 250)
        // System always kept; the latest user turn always kept.
        assertThat(result.first().role).isEqualTo("system")
        assertThat(result.last().content).isEqualTo("latest")
        // Older turns were dropped.
        assertThat(result.size).isLessThan(messages.size)
    }

    @Test
    fun alwaysKeepsTheLastUserMessageEvenWhenBudgetIsTiny() {
        val messages = listOf(
            ChatMessage("user", "old"),
            ChatMessage("assistant", "ok"),
            ChatMessage("user", "x".repeat(2000)),
        )
        val result = MessageBudget.fitWithinBudget(messages, maxInputTokens = 1)
        assertThat(result.last().content).isEqualTo(messages.last().content)
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "*MessageBudgetTest"`
Expected: FAIL — class unresolved.

- [ ] **Step 3: Implement `MessageBudget`**

`app/src/main/java/com/mobilegem/gemma/server/MessageBudget.kt`:
```kotlin
package com.mobilegem.gemma.server

/**
 * Trims the oldest non-system turns from a `messages` list until the estimated
 * total fits within [maxInputTokens]. Always keeps:
 *   - All `system` messages (they're typically small + carry critical context).
 *   - The LAST user message (the model can't answer without it).
 *
 * If those non-droppable messages alone exceed the budget, this returns them
 * anyway — the model may truncate internally, but the alternative (dropping
 * the last user turn) is meaningless.
 */
object MessageBudget {

    fun fitWithinBudget(messages: List<ChatMessage>, maxInputTokens: Int): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        if (TokenEstimator.estimateMessages(messages) <= maxInputTokens) return messages

        val systemMessages = messages.filter { it.role == "system" }
        val nonSystem = messages.filter { it.role != "system" }
        if (nonSystem.isEmpty()) return messages

        val lastUserIndex = nonSystem.indexOfLast { it.role == "user" }
            .takeIf { it >= 0 } ?: (nonSystem.size - 1)
        val mustKeep = systemMessages + nonSystem[lastUserIndex]
        val droppable = nonSystem.toMutableList().also { it.removeAt(lastUserIndex) }

        // Walk from the NEWEST droppable backward, keeping while there's budget.
        val budget = maxInputTokens - TokenEstimator.estimateMessages(mustKeep)
        val kept = ArrayDeque<ChatMessage>()
        var remaining = budget
        for (msg in droppable.asReversed()) {
            val cost = TokenEstimator.estimateMessage(msg)
            if (cost > remaining) break
            kept.addFirst(msg)
            remaining -= cost
        }

        // Reassemble: system messages first, then the kept middle, then last user.
        return systemMessages + kept + listOf(nonSystem[lastUserIndex])
    }
}
```

- [ ] **Step 4: Run, verify PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*MessageBudgetTest"`
Expected: 3 tests pass.

- [ ] **Step 5: Plug the budget into `ChatCompletionHandler`**

Replace the FULL contents of `app/src/main/java/com/mobilegem/gemma/server/ChatCompletionHandler.kt` with:

```kotlin
package com.mobilegem.gemma.server

import com.mobilegem.gemma.inference.TextGenerator
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.ConversationPersister
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class ChatCompletionHandler(
    private val generator: TextGenerator,
    private val augmenter: ContextAugmenter? = null,
    private val persister: ConversationPersister? = null,
    private val activeSession: ActiveSessionHolder? = null,
    /** Total context window in tokens (model-dependent). Gemma 4 defaults to 8192. */
    private val contextWindow: Int = 8192,
    /** Reserved budget for the model's own output. */
    private val outputBudget: Int = 1024,
) {

    private val json = Json { encodeDefaults = true }
    private val maxInputTokens: Int get() = contextWindow - outputBudget

    fun streamSse(request: ChatCompletionRequest): Flow<String> = flow {
        val id = "chatcmpl-${System.nanoTime()}"
        val created = System.currentTimeMillis() / 1000
        val temp = request.temperature ?: 0.8f
        val messages = MessageBudget.fitWithinBudget(
            augmentedMessages(request.messages), maxInputTokens,
        )
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

    suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        val temp = request.temperature ?: 0.8f
        val messages = MessageBudget.fitWithinBudget(
            augmentedMessages(request.messages), maxInputTokens,
        )
        val prompt = GemmaPromptBuilder.build(messages)
        val answer = StringBuilder()
        generator.generate(prompt, temp).collect { answer.append(it) }
        persist(request.messages, answer.toString())
        return ChatCompletionResponse(
            id = "chatcmpl-${System.nanoTime()}",
            created = System.currentTimeMillis() / 1000,
            model = request.model,
            choices = listOf(MessageChoice(message = ChatMessage("assistant", answer.toString()))),
        )
    }

    private suspend fun augmentedMessages(original: List<ChatMessage>): List<ChatMessage> {
        val session = activeSession?.current() ?: return original
        val aug = augmenter ?: return original
        val latestUser = original.lastOrNull { it.role == "user" }?.content ?: ""
        val context = aug.systemContextFor(session.projectId, latestUser) ?: return original
        return listOf(ChatMessage("system", context)) + original
    }

    private suspend fun persist(original: List<ChatMessage>, answer: String) {
        val session = activeSession?.current() ?: return
        val p = persister ?: return
        p.persistConversation(session.sessionId, original + ChatMessage("assistant", answer))
    }

    private fun sseChunk(
        id: String, created: Long, model: String, delta: Delta, finish: String?,
    ): String {
        val chunk = ChatCompletionChunk(
            id = id, created = created, model = model,
            choices = listOf(ChunkChoice(delta = delta, finishReason = finish)),
        )
        return "data: ${json.encodeToString(ChatCompletionChunk.serializer(), chunk)}\n\n"
    }
}
```

- [ ] **Step 6: Run the FULL `com.mobilegem.gemma.server` test package**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mobilegem.gemma.server.*"`
Expected: all server-package tests pass (existing `ChatCompletionHandlerTest`, `ChatCompletionHandlerMemoryTest`, `LocalLlmServerTest`, `GemmaPromptBuilderTest`, `MemoryContextAugmenterTest`, `OpenAiDtoTest` + the new `TokenEstimatorTest` and `MessageBudgetTest`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/server/MessageBudget.kt app/src/test/java/com/mobilegem/gemma/server/MessageBudgetTest.kt app/src/main/java/com/mobilegem/gemma/server/ChatCompletionHandler.kt
git commit -m "feat(server): truncate oldest turns to fit context window"
```

---

## Phase 4 — Embedding quantization

## Task 9: Quantize embeddings to int8 with per-vector scale

Halve the DB I/O for vector storage. Each `MemoryEntry.embedding` becomes an int8 `ByteArray` plus a `Float` scale; cosine similarity dequantizes at read time. Schema version bumps 1→2 with destructive migration (acceptable while no production data exists).

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/memory/Quantization.kt`
- Create: `app/src/test/java/com/mobilegem/gemma/memory/QuantizationTest.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/memory/db/Entities.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/memory/db/MemoryDatabase.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/memory/LongTermMemoryRepository.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/memory/MemoryRetriever.kt`
- Modify: `app/src/test/java/com/mobilegem/gemma/memory/db/SkillMemoryDaoTest.kt`
- Modify: `app/src/test/java/com/mobilegem/gemma/memory/LongTermMemoryRepositoryTest.kt`
- Modify: `app/src/test/java/com/mobilegem/gemma/memory/MemoryRetrieverTest.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/memory/SelfLearningExtractor.kt` (constructor of returned `MemoryEntry` instance)

- [ ] **Step 1: Write a failing roundtrip test**

`app/src/test/java/com/mobilegem/gemma/memory/QuantizationTest.kt`:
```kotlin
package com.mobilegem.gemma.memory

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class QuantizationTest {

    @Test
    fun zeroVectorQuantizesAndDequantizesToZero() {
        val v = FloatArray(8)
        val q = Quantization.quantize(v)
        val out = Quantization.dequantize(q.bytes, q.scale)
        assertThat(out.toList()).isEqualTo(v.toList())
    }

    @Test
    fun nonZeroVectorRoundtripsWithinTolerance() {
        val v = floatArrayOf(0.1f, -0.5f, 0.8f, -0.99f, 0.0f, 0.25f)
        val q = Quantization.quantize(v)
        val out = Quantization.dequantize(q.bytes, q.scale)
        for (i in v.indices) {
            // int8 quantization has ~1/127 ≈ 0.008 max error per component (scaled).
            val tolerance = 0.01f * (1f + abs(v.maxOf { abs(it) }))
            assertThat(abs(out[i] - v[i])).isLessThan(tolerance)
        }
    }

    @Test
    fun bytesLengthMatchesVectorLength() {
        val v = FloatArray(384) { it / 384f - 0.5f }
        val q = Quantization.quantize(v)
        assertThat(q.bytes.size).isEqualTo(v.size)
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "*QuantizationTest"`
Expected: FAIL — class unresolved.

- [ ] **Step 3: Implement `Quantization`**

`app/src/main/java/com/mobilegem/gemma/memory/Quantization.kt`:
```kotlin
package com.mobilegem.gemma.memory

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Symmetric int8 vector quantization. Each vector gets its own scale (the
 * max absolute value), and components are stored as signed bytes in
 * `[-127, 127]`. Memory cost: 1 byte per dim + 4 bytes for the scale,
 * versus 4 bytes per dim raw — a ~4x reduction.
 *
 * Max per-component absolute error is `scale / 127`, which for unit vectors
 * is well below cosine-similarity rounding tolerance.
 */
object Quantization {

    data class Quantized(val bytes: ByteArray, val scale: Float) {
        // Manual equals/hashCode because of ByteArray.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Quantized) return false
            return scale == other.scale && bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode() * 31 + scale.hashCode()
    }

    fun quantize(vector: FloatArray): Quantized {
        if (vector.isEmpty()) return Quantized(ByteArray(0), 0f)
        val maxAbs = vector.maxOf { abs(it) }
        if (maxAbs == 0f) return Quantized(ByteArray(vector.size), 0f)
        val scale = maxAbs / 127f
        val out = ByteArray(vector.size)
        for (i in vector.indices) {
            val q = (vector[i] / scale).roundToInt().coerceIn(-127, 127)
            out[i] = q.toByte()
        }
        return Quantized(out, scale)
    }

    fun dequantize(bytes: ByteArray, scale: Float): FloatArray {
        val out = FloatArray(bytes.size)
        if (scale == 0f) return out
        for (i in bytes.indices) {
            out[i] = bytes[i].toInt() * scale
        }
        return out
    }
}
```

- [ ] **Step 4: Run, verify PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*QuantizationTest"`
Expected: 3 tests pass.

- [ ] **Step 5: Change `MemoryEntry` to store quantized bytes + scale**

Replace the `MemoryEntry` data class in `app/src/main/java/com/mobilegem/gemma/memory/db/Entities.kt` with:

```kotlin
/** projectId == null means the memory is global. Embedding stored as int8-quantized
 *  bytes + a per-vector scale (see [com.mobilegem.gemma.memory.Quantization]). */
@Entity(tableName = "memory_entries", indices = [Index("projectId")])
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long?,
    val content: String,
    val embeddingBytes: ByteArray,
    val embeddingScale: Float,
    val sourceSessionId: Long?,
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryEntry) return false
        return id == other.id &&
            content == other.content &&
            embeddingBytes.contentEquals(other.embeddingBytes) &&
            embeddingScale == other.embeddingScale
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + embeddingBytes.contentHashCode()
        result = 31 * result + embeddingScale.hashCode()
        return result
    }
}
```

- [ ] **Step 6: Bump the DB schema version and add destructive migration fallback**

In `app/src/main/java/com/mobilegem/gemma/memory/db/MemoryDatabase.kt`, change the `@Database` annotation to `version = 2` and update the `create` companion to enable destructive migration:

```kotlin
@Database(
    entities = [Project::class, Session::class, StoredMessage::class, Skill::class, MemoryEntry::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun coreDao(): CoreDao
    abstract fun skillDao(): SkillDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        fun create(context: Context): MemoryDatabase =
            Room.databaseBuilder(context, MemoryDatabase::class.java, "memory.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
```
This deletes the on-device memory DB when upgrading from an older schema. Acceptable for v1 development; document in the commit message.

- [ ] **Step 7: Add `FloatArray`-friendly wrappers in `LongTermMemoryRepository`**

Replace the FULL contents of `app/src/main/java/com/mobilegem/gemma/memory/LongTermMemoryRepository.kt` with:

```kotlin
package com.mobilegem.gemma.memory

import com.mobilegem.gemma.memory.db.MemoryDao
import com.mobilegem.gemma.memory.db.MemoryEntry
import kotlinx.coroutines.flow.Flow

class LongTermMemoryRepository(
    private val memoryDao: MemoryDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun observeForProjectScope(projectId: Long): Flow<List<MemoryEntry>> =
        memoryDao.observeForProjectScope(projectId)

    suspend fun entriesForProjectScope(projectId: Long): List<MemoryEntry> =
        memoryDao.entriesForProjectScope(projectId)

    /**
     * Stores [embedding] as int8-quantized bytes. Callers pass and receive
     * `FloatArray` — the quantization is invisible above this layer.
     */
    suspend fun store(
        projectId: Long?,
        content: String,
        embedding: FloatArray,
        sourceSessionId: Long?,
    ): Long {
        val q = Quantization.quantize(embedding)
        return memoryDao.insert(
            MemoryEntry(
                projectId = projectId,
                content = content,
                embeddingBytes = q.bytes,
                embeddingScale = q.scale,
                sourceSessionId = sourceSessionId,
                createdAt = clock(),
            ),
        )
    }

    suspend fun delete(entryId: Long) = memoryDao.delete(entryId)
}

/** Dequantizes the entry's stored embedding back to a `FloatArray`. */
fun MemoryEntry.embeddingAsFloat(): FloatArray =
    Quantization.dequantize(embeddingBytes, embeddingScale)
```

- [ ] **Step 8: Update `MemoryRetriever` to dequantize**

In `app/src/main/java/com/mobilegem/gemma/memory/MemoryRetriever.kt`, replace the body of `retrieve` so similarity is computed against the dequantized vector:

```kotlin
    suspend fun retrieve(projectId: Long, query: String, topK: Int): List<MemoryEntry> {
        val candidates = ltm.entriesForProjectScope(projectId)
        if (candidates.isEmpty()) return emptyList()

        val queryVec = embedder.embed(query)
        return candidates
            .mapNotNull { entry ->
                val entryVec = entry.embeddingAsFloat()
                if (entryVec.size != queryVec.size) null
                else entry to VectorMath.cosineSimilarity(queryVec, entryVec)
            }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }
```
Add the import:
```kotlin
import com.mobilegem.gemma.memory.embeddingAsFloat
```

- [ ] **Step 9: Update `SelfLearningExtractor` to use new MemoryEntry constructor**

In `app/src/main/java/com/mobilegem/gemma/memory/SelfLearningExtractor.kt`, find the `MemoryEntry(id = id, …)` construction in the returned mapping and change the embedding arguments. Replace the `MemoryEntry(...)` call inside `facts.map { fact -> … }` with:

```kotlin
            val embedding = embedder.embed(fact)
            val id = ltm.store(
                projectId = projectId,
                content = fact,
                embedding = embedding,
                sourceSessionId = sessionId,
            )
            val q = Quantization.quantize(embedding)
            MemoryEntry(
                id = id, projectId = projectId, content = fact,
                embeddingBytes = q.bytes, embeddingScale = q.scale,
                sourceSessionId = sessionId, createdAt = 0,
            )
```
Add the import:
```kotlin
import com.mobilegem.gemma.memory.Quantization
```

- [ ] **Step 10: Update the existing DAO/repo/retriever tests to use the new MemoryEntry shape**

`app/src/test/java/com/mobilegem/gemma/memory/db/SkillMemoryDaoTest.kt` — replace every `MemoryEntry(projectId = …, embedding = floatArrayOf(...), …)` with the new field names. For example, change:
```kotlin
        db.memoryDao().insert(
            MemoryEntry(projectId = null, content = "global fact",
                embedding = floatArrayOf(1f), sourceSessionId = null, createdAt = 1),
        )
```
to:
```kotlin
        db.memoryDao().insert(
            MemoryEntry(projectId = null, content = "global fact",
                embeddingBytes = byteArrayOf(127), embeddingScale = 1f / 127f,
                sourceSessionId = null, createdAt = 1),
        )
```
Apply the same field-name update to the other two insertions in that file.

`app/src/test/java/com/mobilegem/gemma/memory/LongTermMemoryRepositoryTest.kt` — the test calls `repo.store(...)` with a `FloatArray` which is unchanged. The assertion `entries.single().embedding`...needs to become a dequantized comparison:
```kotlin
        val entries = repo.entriesForProjectScope(3)
        assertThat(entries).hasSize(1)
        assertThat(entries.single().content).isEqualTo("User prefers metric units")
        val roundtrip = com.mobilegem.gemma.memory.Quantization.dequantize(
            entries.single().embeddingBytes, entries.single().embeddingScale,
        )
        assertThat(roundtrip[0]).isWithin(0.01f).of(0.1f)
        assertThat(roundtrip[1]).isWithin(0.01f).of(0.2f)
```

`app/src/test/java/com/mobilegem/gemma/memory/MemoryRetrieverTest.kt` — the test calls `ltm.store(1, "about cats", floatArrayOf(1f, 0f), null)` which still works unchanged. The retriever returns entries; existing assertions on `.content` are unchanged. Leave this test alone unless it asserts on raw `embedding` (it doesn't).

- [ ] **Step 11: Run the full memory test package and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mobilegem.gemma.memory.*"`
Expected: all memory tests pass.

- [ ] **Step 12: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 0 failures, total count = previous total + new tests (Quantization 3, TokenEstimator 4, MessageBudget 3, fact parser 3, self-learning 3 = ~16 new across Phase 1-3 + 4).

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "feat(memory): int8-quantize embeddings; bump DB to v2 (destructive)"
```

---

## Phase 5 — Per-session `Conversation` reuse (KV-cache)

The biggest available perf win on multi-turn chat. A new optional interface `SessionedTextGenerator` extends `TextGenerator`; the LiteRT-LM implementation maintains a per-session `Conversation` cache and only sends the NEW user message when the incoming `messages` array is a prefix-with-one-more-user extension of what it already sent. On any divergence, the cache for that session is rebuilt.

`FakeTextGenerator` continues to implement only `TextGenerator`; the handler picks the session-aware path only when the generator opts in AND a session is active.

## Task 10: `SessionedTextGenerator` interface + scripted session test fake

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/inference/SessionedTextGenerator.kt`
- Create: `app/src/test/java/com/mobilegem/gemma/inference/FakeSessionedTextGenerator.kt`
- Create: `app/src/test/java/com/mobilegem/gemma/inference/FakeSessionedTextGeneratorTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/inference/FakeSessionedTextGeneratorTest.kt`:
```kotlin
package com.mobilegem.gemma.inference

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeSessionedTextGeneratorTest {

    @Test
    fun emitsScriptedTokensAndRecordsCallShape() = runTest {
        val gen = FakeSessionedTextGenerator(perCallOutputs = listOf("alpha", "beta"))
        val out1 = gen.generateSession(
            sessionId = "S1",
            systemContext = "sys",
            messages = listOf(ChatMessage("user", "hi")),
            temperature = 0.5f,
        ).toList().joinToString("")
        val out2 = gen.generateSession(
            sessionId = "S1",
            systemContext = null,
            messages = listOf(
                ChatMessage("user", "hi"),
                ChatMessage("assistant", "alpha"),
                ChatMessage("user", "again"),
            ),
            temperature = 0.5f,
        ).toList().joinToString("")

        assertThat(out1).isEqualTo("alpha")
        assertThat(out2).isEqualTo("beta")
        assertThat(gen.calls).hasSize(2)
        assertThat(gen.calls[0].sessionId).isEqualTo("S1")
        assertThat(gen.calls[1].messages.last().content).isEqualTo("again")
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "*FakeSessionedTextGeneratorTest"`
Expected: FAIL — classes unresolved.

- [ ] **Step 3: Implement the interface and the fake**

`app/src/main/java/com/mobilegem/gemma/inference/SessionedTextGenerator.kt`:
```kotlin
package com.mobilegem.gemma.inference

import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * Optional session-aware extension of [TextGenerator]. Implementations MAY
 * use [sessionId] to maintain a per-session KV-cache (e.g. a long-lived
 * LiteRT-LM [com.google.ai.edge.litertlm.Conversation]). Callers pass the
 * full message list as the source of truth; the implementation detects
 * prefix-extensions and only processes the new user message when possible.
 *
 * Implementations MUST behave correctly even if [sessionId] is null
 * (e.g. anonymous chat from outside the active session) — in that case,
 * they should fall back to the stateless [TextGenerator.generate] behaviour.
 */
interface SessionedTextGenerator : TextGenerator {
    fun generateSession(
        sessionId: String?,
        systemContext: String?,
        messages: List<ChatMessage>,
        temperature: Float,
    ): Flow<String>
}
```

`app/src/test/java/com/mobilegem/gemma/inference/FakeSessionedTextGenerator.kt`:
```kotlin
package com.mobilegem.gemma.inference

import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Test fake for [SessionedTextGenerator]. Returns one entry from
 * [perCallOutputs] per call (clamped to the last entry on overflow), and
 * records the shape of each call for assertions.
 */
class FakeSessionedTextGenerator(
    private val perCallOutputs: List<String>,
) : SessionedTextGenerator {

    data class Call(
        val sessionId: String?,
        val systemContext: String?,
        val messages: List<ChatMessage>,
        val temperature: Float,
    )

    val calls: MutableList<Call> = mutableListOf()
    private var idx = 0

    override fun generate(prompt: String, temperature: Float): Flow<String> {
        // Single-shot fallback path; records as session=null.
        calls += Call(null, null, listOf(ChatMessage("user", prompt)), temperature)
        return flowOf(nextOutput())
    }

    override fun generateSession(
        sessionId: String?,
        systemContext: String?,
        messages: List<ChatMessage>,
        temperature: Float,
    ): Flow<String> {
        calls += Call(sessionId, systemContext, messages, temperature)
        return flowOf(nextOutput())
    }

    private fun nextOutput(): String {
        val i = if (idx < perCallOutputs.size) idx else perCallOutputs.lastIndex
        idx++
        return perCallOutputs[i]
    }
}
```

- [ ] **Step 4: Run, verify PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*FakeSessionedTextGeneratorTest"`
Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/inference/SessionedTextGenerator.kt app/src/test/java/com/mobilegem/gemma/inference/FakeSessionedTextGenerator.kt app/src/test/java/com/mobilegem/gemma/inference/FakeSessionedTextGeneratorTest.kt
git commit -m "feat(inference): add SessionedTextGenerator interface and test fake"
```

---

## Task 11: Conversation cache that detects prefix-extensions

A pure utility that decides, for an incoming `messages` list and a previously-sent list, whether to REUSE the cached `Conversation` (and which suffix to send) or REBUILD it from scratch. The cache stores opaque tokens so it is fully unit-testable without LiteRT-LM.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/inference/ConversationCacheDecider.kt`
- Create: `app/src/test/java/com/mobilegem/gemma/inference/ConversationCacheDeciderTest.kt`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/mobilegem/gemma/inference/ConversationCacheDeciderTest.kt`:
```kotlin
package com.mobilegem.gemma.inference

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.server.ChatMessage
import org.junit.Test

class ConversationCacheDeciderTest {

    @Test
    fun firstCallRequestsRebuildWithAllMessages() {
        val decision = ConversationCacheDecider.decide(
            previouslySent = null,
            incoming = listOf(ChatMessage("user", "hi")),
        )
        assertThat(decision).isInstanceOf(ConversationCacheDecider.Decision.Rebuild::class.java)
        val rebuild = decision as ConversationCacheDecider.Decision.Rebuild
        assertThat(rebuild.fullMessages.map { it.content }).containsExactly("hi")
    }

    @Test
    fun strictPrefixExtensionRequestsIncremental() {
        val prior = listOf(
            ChatMessage("user", "hi"),
            ChatMessage("assistant", "hello"),
        )
        val incoming = prior + ChatMessage("user", "how are you?")
        val decision = ConversationCacheDecider.decide(prior, incoming)
        assertThat(decision).isInstanceOf(ConversationCacheDecider.Decision.Incremental::class.java)
        val inc = decision as ConversationCacheDecider.Decision.Incremental
        assertThat(inc.newUserMessage.content).isEqualTo("how are you?")
    }

    @Test
    fun divergenceTriggersRebuild() {
        val prior = listOf(
            ChatMessage("user", "old"),
            ChatMessage("assistant", "old-resp"),
        )
        val incoming = listOf(
            ChatMessage("user", "different"),
            ChatMessage("assistant", "different-resp"),
            ChatMessage("user", "follow-up"),
        )
        val decision = ConversationCacheDecider.decide(prior, incoming)
        assertThat(decision).isInstanceOf(ConversationCacheDecider.Decision.Rebuild::class.java)
    }

    @Test
    fun nonAppendExtensionTriggersRebuild() {
        // Same prefix but extra middle turn — not a strict prefix-with-one-more-user.
        val prior = listOf(ChatMessage("user", "a"))
        val incoming = listOf(
            ChatMessage("user", "a"),
            ChatMessage("user", "b"), // two user msgs without assistant in between
        )
        val decision = ConversationCacheDecider.decide(prior, incoming)
        assertThat(decision).isInstanceOf(ConversationCacheDecider.Decision.Rebuild::class.java)
    }

    @Test
    fun systemMessageChangeTriggersRebuild() {
        val prior = listOf(
            ChatMessage("system", "be terse"),
            ChatMessage("user", "hi"),
        )
        val incoming = listOf(
            ChatMessage("system", "be VERBOSE"),
            ChatMessage("user", "hi"),
            ChatMessage("assistant", "hello"),
            ChatMessage("user", "next"),
        )
        val decision = ConversationCacheDecider.decide(prior, incoming)
        assertThat(decision).isInstanceOf(ConversationCacheDecider.Decision.Rebuild::class.java)
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "*ConversationCacheDeciderTest"`
Expected: FAIL — class unresolved.

- [ ] **Step 3: Implement the decider**

`app/src/main/java/com/mobilegem/gemma/inference/ConversationCacheDecider.kt`:
```kotlin
package com.mobilegem.gemma.inference

import com.mobilegem.gemma.server.ChatMessage

/**
 * Pure decision logic for whether a cached LiteRT-LM `Conversation` can be
 * reused for a new request, or whether the cache must be rebuilt.
 *
 * Reuse is allowed ONLY when the incoming message list is the previously-sent
 * list with EXACTLY one trailing `user` message appended. This conservative
 * rule guarantees KV-cache correctness: if the WebView edits, regenerates,
 * or deletes any prior turn, the cache is invalidated.
 */
object ConversationCacheDecider {

    sealed interface Decision {
        /** Send only [newUserMessage] to the existing cached Conversation. */
        data class Incremental(val newUserMessage: ChatMessage) : Decision

        /** Close and recreate the Conversation; replay [fullMessages]. */
        data class Rebuild(val fullMessages: List<ChatMessage>) : Decision
    }

    fun decide(previouslySent: List<ChatMessage>?, incoming: List<ChatMessage>): Decision {
        if (previouslySent == null || previouslySent.isEmpty()) {
            return Decision.Rebuild(incoming)
        }
        if (incoming.size != previouslySent.size + 1) {
            return Decision.Rebuild(incoming)
        }
        // Every previously-sent message must match the corresponding incoming one
        // by role AND content.
        for (i in previouslySent.indices) {
            if (previouslySent[i].role != incoming[i].role ||
                previouslySent[i].content != incoming[i].content
            ) {
                return Decision.Rebuild(incoming)
            }
        }
        val appended = incoming.last()
        if (appended.role != "user") return Decision.Rebuild(incoming)
        return Decision.Incremental(appended)
    }
}
```

- [ ] **Step 4: Run, verify PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*ConversationCacheDeciderTest"`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/inference/ConversationCacheDecider.kt app/src/test/java/com/mobilegem/gemma/inference/ConversationCacheDeciderTest.kt
git commit -m "feat(inference): pure decider for Conversation cache reuse vs rebuild"
```

---

## Task 12: `LiteRtLmTextGenerator` implements `SessionedTextGenerator`

Compile-only — the underlying LiteRT-LM API is native and cannot be unit-tested. Adds the per-session `Conversation` cache, uses `ConversationCacheDecider`, and falls back to the existing stateless path when `sessionId` is null.

**Files:**
- Modify: `app/src/main/java/com/mobilegem/gemma/inference/LiteRtLmTextGenerator.kt`

- [ ] **Step 1: Replace the FULL contents** of `app/src/main/java/com/mobilegem/gemma/inference/LiteRtLmTextGenerator.kt` with:

```kotlin
package com.mobilegem.gemma.inference

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.mobilegem.gemma.logging.AppLog
import com.mobilegem.gemma.server.ChatMessage
import com.mobilegem.gemma.server.GemmaPromptBuilder
import com.mobilegem.gemma.settings.InferenceBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_TOP_K = 64
private const val DEFAULT_TOP_P = 0.95
private const val DEFAULT_SEED = 0

/**
 * LiteRT-LM-backed text generator.
 *
 * Maintains a per-session [Conversation] cache: when [generateSession] is
 * invoked for the same `sessionId` with a strict prefix-extension of the
 * previous request, the existing Conversation is reused and only the new
 * user message is sent — preserving the KV cache. On any divergence
 * (history edited, regenerated, system message changed), the cached
 * Conversation for that session is closed and a fresh one is built from
 * the templated prompt.
 *
 * Falls back to the single-shot stateless [generate] path when there is
 * no session id.
 */
class LiteRtLmTextGenerator private constructor(
    private val engine: Engine,
) : SessionedTextGenerator, Closeable {

    private data class Slot(val conversation: Conversation, val sentMessages: List<ChatMessage>)

    private val sessionSlots = ConcurrentHashMap<String, Slot>()

    override fun generate(prompt: String, temperature: Float): Flow<String> = flow {
        AppLog.event("engine", "generate.begin",
            "promptChars" to prompt.length, "temperature" to temperature)
        val conversationConfig = sampledConversationConfig(temperature)
        var emittedChars = 0
        try {
            engine.createConversation(conversationConfig).use { conv ->
                conv.sendMessageAsync(prompt).collect { message ->
                    for (content in message.contents.contents) {
                        if (content is Content.Text) {
                            emittedChars += content.text.length
                            emit(content.text)
                        }
                    }
                }
            }
            AppLog.event("engine", "generate.end", "emittedChars" to emittedChars)
        } catch (t: Throwable) {
            AppLog.error("engine", "generate.failed", t, "emittedChars" to emittedChars)
            throw t
        }
    }

    override fun generateSession(
        sessionId: String?,
        systemContext: String?,
        messages: List<ChatMessage>,
        temperature: Float,
    ): Flow<String> = flow {
        if (sessionId == null) {
            val effective = if (systemContext == null) messages
            else listOf(ChatMessage("system", systemContext)) + messages
            val prompt = GemmaPromptBuilder.build(effective)
            generate(prompt, temperature).collect { emit(it) }
            return@flow
        }

        val fullMessages = if (systemContext == null) messages
        else listOf(ChatMessage("system", systemContext)) + messages

        val cached = sessionSlots[sessionId]
        val decision = ConversationCacheDecider.decide(
            previouslySent = cached?.sentMessages,
            incoming = fullMessages,
        )

        val (conv, textToSend) = when (decision) {
            is ConversationCacheDecider.Decision.Incremental -> {
                AppLog.event("engine", "session.incremental",
                    "sessionId" to sessionId, "history" to cached!!.sentMessages.size)
                cached.conversation to decision.newUserMessage.content
            }
            is ConversationCacheDecider.Decision.Rebuild -> {
                AppLog.event("engine", "session.rebuild",
                    "sessionId" to sessionId, "messages" to decision.fullMessages.size)
                cached?.conversation?.runCatching { close() }
                val prompt = GemmaPromptBuilder.build(decision.fullMessages)
                val newConv = engine.createConversation(sampledConversationConfig(temperature))
                newConv to prompt
            }
        }

        sessionSlots[sessionId] = Slot(conv, fullMessages)

        var emittedChars = 0
        try {
            conv.sendMessageAsync(textToSend).collect { message ->
                for (content in message.contents.contents) {
                    if (content is Content.Text) {
                        emittedChars += content.text.length
                        emit(content.text)
                    }
                }
            }
            AppLog.event("engine", "session.end",
                "sessionId" to sessionId, "emittedChars" to emittedChars)
        } catch (t: Throwable) {
            AppLog.error("engine", "session.failed", t,
                "sessionId" to sessionId, "emittedChars" to emittedChars)
            // Invalidate the slot on error so the next request rebuilds.
            sessionSlots.remove(sessionId)?.conversation?.runCatching { close() }
            throw t
        }
    }

    override fun close() {
        AppLog.event("engine", "close", "sessions" to sessionSlots.size)
        sessionSlots.values.forEach { it.conversation.runCatching { close() } }
        sessionSlots.clear()
        runCatching { engine.close() }
            .onFailure { AppLog.error("engine", "close.failed", it) }
    }

    private fun sampledConversationConfig(temperature: Float): ConversationConfig =
        ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = DEFAULT_TOP_K,
                topP = DEFAULT_TOP_P,
                temperature = temperature.toDouble(),
                seed = DEFAULT_SEED,
            ),
        )

    companion object {
        fun create(
            modelPath: String,
            backend: InferenceBackend,
            cacheDir: File? = null,
        ): LiteRtLmTextGenerator {
            val file = File(modelPath)
            AppLog.event("engine", "create.begin",
                "modelPath" to modelPath,
                "modelExists" to file.exists(),
                "modelSizeBytes" to (if (file.exists()) file.length() else -1L),
                "backend" to backend.name,
                "cacheDir" to (cacheDir?.absolutePath ?: "<none>"))

            cacheDir?.mkdirs()
            val engineConfig = if (cacheDir != null) {
                EngineConfig(
                    modelPath = modelPath,
                    backend = when (backend) {
                        InferenceBackend.GPU -> Backend.GPU()
                        InferenceBackend.CPU -> Backend.CPU()
                    },
                    cacheDir = cacheDir.absolutePath,
                )
            } else {
                EngineConfig(
                    modelPath = modelPath,
                    backend = when (backend) {
                        InferenceBackend.GPU -> Backend.GPU()
                        InferenceBackend.CPU -> Backend.CPU()
                    },
                )
            }
            return try {
                val engine = Engine(engineConfig)
                engine.initialize()
                AppLog.event("engine", "create.end", "backend" to backend.name)
                LiteRtLmTextGenerator(engine)
            } catch (t: Throwable) {
                AppLog.error("engine", "create.failed", t,
                    "modelPath" to modelPath, "backend" to backend.name)
                throw t
            }
        }
    }
}
```

If the resolved LiteRT-LM 0.12.0 `Conversation` is not `AutoCloseable`/`Closeable` (verify with `javap`), replace `conv.use { … }` with explicit `try { … } finally { conv.close() }` and remove `.runCatching { close() }` if `close()` is non-existent. The shape is otherwise unchanged.

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the full suite (no new tests; ensure no regression)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/inference/LiteRtLmTextGenerator.kt
git commit -m "feat(inference): LiteRtLmTextGenerator implements SessionedTextGenerator with KV-cache reuse"
```

---

## Task 13: Route the chat handler through `generateSession` when a session is active

When the active session is set AND the generator implements `SessionedTextGenerator`, the handler builds the augmented system context separately (without prepending it to `messages`) and calls `generateSession(sessionId, systemContext, messages, temperature)`. Otherwise the existing stateless path is used unchanged.

**Files:**
- Modify: `app/src/main/java/com/mobilegem/gemma/server/ChatCompletionHandler.kt`
- Create: `app/src/test/java/com/mobilegem/gemma/server/ChatCompletionHandlerSessionTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/server/ChatCompletionHandlerSessionTest.kt`:
```kotlin
package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeSessionedTextGenerator
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.ConversationPersister
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChatCompletionHandlerSessionTest {

    private fun augmenterReturning(text: String?): ContextAugmenter =
        object : ContextAugmenter {
            override suspend fun systemContextFor(projectId: Long, latestUserMessage: String) = text
        }

    @Test
    fun routesThroughGenerateSessionWhenSessionActiveAndGeneratorSessioned() = runTest {
        val gen = FakeSessionedTextGenerator(listOf("answer"))
        val holder = ActiveSessionHolder().apply { set(projectId = 1, sessionId = 42) }
        val handler = ChatCompletionHandler(
            generator = gen,
            augmenter = augmenterReturning("Active skills: be terse"),
            persister = null,
            activeSession = holder,
        )
        handler.streamSse(
            ChatCompletionRequest(
                messages = listOf(ChatMessage("user", "hi")),
                stream = true,
            ),
        ).toList()

        assertThat(gen.calls).hasSize(1)
        val call = gen.calls.single()
        assertThat(call.sessionId).isEqualTo("42")
        assertThat(call.systemContext).contains("be terse")
        assertThat(call.messages.map { it.role to it.content })
            .containsExactly("user" to "hi")
    }

    @Test
    fun usesStatelessGenerateWhenNoSessionActive() = runTest {
        val gen = FakeSessionedTextGenerator(listOf("ok"))
        val handler = ChatCompletionHandler(
            generator = gen,
            augmenter = augmenterReturning("ignored"),
            persister = null,
            activeSession = ActiveSessionHolder(),
        )
        handler.streamSse(
            ChatCompletionRequest(
                messages = listOf(ChatMessage("user", "q")),
                stream = true,
            ),
        ).toList()

        assertThat(gen.calls).hasSize(1)
        val call = gen.calls.single()
        // Stateless fallback recorded as session=null.
        assertThat(call.sessionId).isNull()
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "*ChatCompletionHandlerSessionTest"`
Expected: FAIL — the handler currently calls `generator.generate(prompt, temp)`, not `generateSession`.

- [ ] **Step 3: Update `ChatCompletionHandler` to route via `generateSession` when applicable**

Replace the FULL contents of `app/src/main/java/com/mobilegem/gemma/server/ChatCompletionHandler.kt` with:

```kotlin
package com.mobilegem.gemma.server

import com.mobilegem.gemma.inference.SessionedTextGenerator
import com.mobilegem.gemma.inference.TextGenerator
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.ActiveSession
import com.mobilegem.gemma.memory.ConversationPersister
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class ChatCompletionHandler(
    private val generator: TextGenerator,
    private val augmenter: ContextAugmenter? = null,
    private val persister: ConversationPersister? = null,
    private val activeSession: ActiveSessionHolder? = null,
    private val contextWindow: Int = 8192,
    private val outputBudget: Int = 1024,
) {

    private val json = Json { encodeDefaults = true }
    private val maxInputTokens: Int get() = contextWindow - outputBudget

    fun streamSse(request: ChatCompletionRequest): Flow<String> = flow {
        val id = "chatcmpl-${System.nanoTime()}"
        val created = System.currentTimeMillis() / 1000
        val temp = request.temperature ?: 0.8f

        emit(sseChunk(id, created, request.model, Delta(role = "assistant"), null))
        val answer = StringBuilder()
        runGeneration(request.messages, temp).collect { token ->
            answer.append(token)
            emit(sseChunk(id, created, request.model, Delta(content = token), null))
        }
        emit(sseChunk(id, created, request.model, Delta(), "stop"))
        emit("data: [DONE]\n\n")
        persist(request.messages, answer.toString())
    }

    suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        val temp = request.temperature ?: 0.8f
        val answer = StringBuilder()
        runGeneration(request.messages, temp).collect { answer.append(it) }
        persist(request.messages, answer.toString())
        return ChatCompletionResponse(
            id = "chatcmpl-${System.nanoTime()}",
            created = System.currentTimeMillis() / 1000,
            model = request.model,
            choices = listOf(MessageChoice(message = ChatMessage("assistant", answer.toString()))),
        )
    }

    private fun runGeneration(
        original: List<ChatMessage>, temperature: Float,
    ): Flow<String> = flow {
        val session = activeSession?.current()
        val systemContext = buildSystemContext(session, original)

        if (session != null && generator is SessionedTextGenerator) {
            // Session-aware path: pass system context and message list separately
            // so the generator can reuse a per-session KV cache.
            val bounded = MessageBudget.fitWithinBudget(original, maxInputTokens)
            generator.generateSession(
                sessionId = session.sessionId.toString(),
                systemContext = systemContext,
                messages = bounded,
                temperature = temperature,
            ).collect { emit(it) }
        } else {
            // Stateless path: collapse system context into the messages list and
            // build the prompt locally.
            val augmented = if (systemContext == null) original
            else listOf(ChatMessage("system", systemContext)) + original
            val bounded = MessageBudget.fitWithinBudget(augmented, maxInputTokens)
            val prompt = GemmaPromptBuilder.build(bounded)
            generator.generate(prompt, temperature).collect { emit(it) }
        }
    }

    private suspend fun buildSystemContext(
        session: ActiveSession?, original: List<ChatMessage>,
    ): String? {
        if (session == null) return null
        val aug = augmenter ?: return null
        val latestUser = original.lastOrNull { it.role == "user" }?.content ?: ""
        return aug.systemContextFor(session.projectId, latestUser)
    }

    private suspend fun persist(original: List<ChatMessage>, answer: String) {
        val session = activeSession?.current() ?: return
        val p = persister ?: return
        p.persistConversation(session.sessionId, original + ChatMessage("assistant", answer))
    }

    private fun sseChunk(
        id: String, created: Long, model: String, delta: Delta, finish: String?,
    ): String {
        val chunk = ChatCompletionChunk(
            id = id, created = created, model = model,
            choices = listOf(ChunkChoice(delta = delta, finishReason = finish)),
        )
        return "data: ${json.encodeToString(ChatCompletionChunk.serializer(), chunk)}\n\n"
    }
}
```

- [ ] **Step 4: Run the FULL server-package test suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mobilegem.gemma.server.*"`
Expected: BUILD SUCCESSFUL. ALL server tests pass — `ChatCompletionHandlerTest`, `ChatCompletionHandlerMemoryTest`, `ChatCompletionHandlerSessionTest`, `LocalLlmServerTest`, `GemmaPromptBuilderTest`, `MemoryContextAugmenterTest`, `OpenAiDtoTest`, `TokenEstimatorTest`, `MessageBudgetTest`.

Specifically confirm:
- `ChatCompletionHandlerMemoryTest.injectsAugmentedContextAndPersistsConversationWhenSessionActive` still passes (it uses `FakeTextGenerator` which is NOT a `SessionedTextGenerator`, so the stateless path runs and the system message is still folded into `messages` by the handler — that test's assertions on `generator.lastPrompt` containing the system text and on the persister recording the conversation should both still hold).
- `ChatCompletionHandlerSessionTest` (the new tests) pass.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/server/ChatCompletionHandler.kt app/src/test/java/com/mobilegem/gemma/server/ChatCompletionHandlerSessionTest.kt
git commit -m "feat(server): route through SessionedTextGenerator when active session present"
```

---

## Done

After all 13 tasks land:
- `cd webui && npm run build && cd .. && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL with ~75-80 tests, 0 failures.
- The app now:
  - Rejects requests to the local server that don't carry the per-launch Bearer token.
  - Allows CORS only from the bundled WebView origin.
  - Retries self-learning extraction with a temperature ramp + bullet-line fallback parser, and logs raw output when parsing fails.
  - Truncates over-long conversation histories to fit the context window before generation.
  - Stores embeddings as int8-quantized bytes (4x smaller on disk).
  - Reuses LiteRT-LM `Conversation` objects per session for KV-cache locality — multi-turn latency drops materially.

## Known follow-ups (deferred — need separate brainstorming)
- **A.index** — vector index (HNSW / FTS5). The quantization in Task 9 stays compatible; an index can be layered on later.
- **C** — switch sessions inside the WebView without recreating it. Needs pi-web-ui API inspection.
- **G** — multi-model pool with LRU eviction.
- **H** — replace MediaPipe with LiteRT-LM-native embeddings if/when LiteRT-LM ships them.
- **I** — migrate to `@earendil-works/pi-web-ui` (verify API parity first).

## Known risks during execution
1. **Task 3 (auth)**: the exact Ktor 2.3.12 receiver type for route handlers (`RoutingContext` vs `PipelineContext<Unit, ApplicationCall>`) may differ. Adjust the `checkAuth` receiver to whatever resolves; behaviour is unchanged.
2. **Task 9 (quantization)**: the DB schema bump is destructive (`fallbackToDestructiveMigration`). Any user with existing memories WILL lose them on first launch after upgrade. Acceptable for v1 development; document in commit message.
3. **Task 12 (LiteRT-LM session impl)**: relies on `Conversation.sendMessageAsync(prompt)` building correct internal state when given a fully Gemma-templated multi-turn prompt as the initial message. The alternative — calling `sendMessageAsync` once per historical turn — wastes time generating discardable assistant responses. If the templated-prompt approach corrupts the Conversation's internal state machine (e.g. the API mandates one `<start_of_turn>user…<end_of_turn>` per `sendMessage` call), fall back to per-turn replay and document the trade-off.
4. **Task 13 (handler routing)**: existing `ChatCompletionHandlerMemoryTest` passes a `FakeTextGenerator` (NOT sessioned), so it falls through to the stateless path. The stateless path still prepends the augmenter's system message to `messages` and builds the prompt locally — preserving the test's existing `generator.lastPrompt` assertion. Verify this contract during execution.
