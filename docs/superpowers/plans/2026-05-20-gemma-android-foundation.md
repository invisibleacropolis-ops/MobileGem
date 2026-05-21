# Gemma Android App — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native Android app that runs Gemma 4 fully on-device via LiteRT-LM, with a Settings section (model file picker) and a Chat section powered by the pi-web-ui chat interface.

**Architecture:** A native Kotlin app (Jetpack Compose) provides the app chrome, navigation, and Settings. Inference runs natively through LiteRT-LM (`Engine`/`Conversation`). An embedded Ktor HTTP server on `127.0.0.1:8765` wraps LiteRT-LM and exposes an **OpenAI-compatible** `/v1/chat/completions` endpoint (SSE streaming). The Chat section is a `WebView` hosting a Vite-built pi-web-ui app (bundled into `assets/`), configured to use the local server as a custom OpenAI-compatible provider. This avoids a fragile JS↔native token bridge — the JS UI just talks HTTP to localhost. The Memory section is a navigable stub here; it is built fully in Plan 2.

**Tech Stack:** Kotlin, Jetpack Compose, Jetpack Navigation, DataStore (preferences), Ktor server (CIO engine), `com.google.ai.edge.litertlm:litertlm-android`, kotlinx.serialization, kotlinx.coroutines. JUnit4 + Robolectric + Truth + MockK for JVM unit tests. Web side: Vite + TypeScript + `lit` + `@mariozechner/pi-web-ui`.

**Scope note:** This is Plan 1 of 2. Plan 2 covers the Memory subsystem (Projects, Sessions, Skills, Self-learning, Long-Term Memory) on a custom SQLite + local vector index design. This plan delivers a working, shippable single-model on-device chat app on its own.

**Conventions for every task:** Run tests with `./gradlew test` (JVM unit tests) from the repo root unless a task says otherwise. Commit after every task with the message shown. The app module is `app/`; the web module is `webui/`. Android package is `com.mobilegem.gemma`.

---

## Task 1: Android project scaffold

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/mobilegem/gemma/GemmaApp.kt`

- [ ] **Step 1: Create the Gradle wrapper and root settings**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MobileGem"
include(":app")
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g
android.useAndroidX=true
kotlin.code.style=official
```

`build.gradle.kts` (root):

```kotlin
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
```

- [ ] **Step 2: Create the app module build file**

`app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mobilegem.gemma"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mobilegem.gemma"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // LiteRT-LM on-device inference
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    // Embedded HTTP server
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-cors:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
    testImplementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
```

- [ ] **Step 3: Create the manifest and Application class**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:name=".GemmaApp"
        android:label="MobileGem"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DynamicColors.DayNight">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/java/com/mobilegem/gemma/GemmaApp.kt`:

```kotlin
package com.mobilegem.gemma

import android.app.Application

class GemmaApp : Application()
```

- [ ] **Step 4: Verify the project builds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (`MainActivity` does not exist yet — temporarily remove the `<activity>` block from the manifest if the build fails on the missing class, and restore it in Task 9. If it builds with the manifest as-is because resource linking is lenient, leave it.)

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/com/mobilegem/gemma/GemmaApp.kt
git commit -m "chore: scaffold Android app module with Compose, Ktor, LiteRT-LM deps"
```

---

## Task 2: Settings repository (DataStore)

Stores user preferences: active model filename, inference backend (CPU/GPU), and a temperature value. Pure logic — fully unit-testable with Robolectric.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/settings/AppSettings.kt`
- Create: `app/src/main/java/com/mobilegem/gemma/settings/SettingsRepository.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/settings/SettingsRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/settings/SettingsRepositoryTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SettingsRepositoryTest"`
Expected: FAIL — compilation error, `SettingsRepository` / `InferenceBackend` / `AppSettings` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/settings/AppSettings.kt`:

```kotlin
package com.mobilegem.gemma.settings

enum class InferenceBackend { CPU, GPU }

data class AppSettings(
    val activeModelFileName: String?,
    val backend: InferenceBackend,
    val temperature: Float,
) {
    companion object {
        val DEFAULT = AppSettings(
            activeModelFileName = null,
            backend = InferenceBackend.CPU,
            temperature = 0.8f,
        )
    }
}
```

`app/src/main/java/com/mobilegem/gemma/settings/SettingsRepository.kt`:

```kotlin
package com.mobilegem.gemma.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "app_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val activeModel = stringPreferencesKey("active_model")
        val backend = stringPreferencesKey("backend")
        val temperature = floatPreferencesKey("temperature")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            activeModelFileName = prefs[Keys.activeModel],
            backend = prefs[Keys.backend]
                ?.let { runCatching { InferenceBackend.valueOf(it) }.getOrNull() }
                ?: AppSettings.DEFAULT.backend,
            temperature = prefs[Keys.temperature] ?: AppSettings.DEFAULT.temperature,
        )
    }

    suspend fun setActiveModel(fileName: String) =
        context.dataStore.edit { it[Keys.activeModel] = fileName }

    suspend fun setBackend(backend: InferenceBackend) =
        context.dataStore.edit { it[Keys.backend] = backend.name }

    suspend fun setTemperature(value: Float) =
        context.dataStore.edit { it[Keys.temperature] = value }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SettingsRepositoryTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/settings app/src/test/java/com/mobilegem/gemma/settings
git commit -m "feat: add DataStore-backed settings repository"
```

---

## Task 3: Model file manager (import + copy + list)

Imports a user-picked `.litertlm` file (via SAF `Uri`) into app-private storage, lists installed models, and deletes them. The `Uri`→stream copy is abstracted behind a `ContentSource` interface so it is unit-testable without Android's `ContentResolver`.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/model/ContentSource.kt`
- Create: `app/src/main/java/com/mobilegem/gemma/model/ModelFileManager.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/model/ModelFileManagerTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/model/ModelFileManagerTest.kt`:

```kotlin
package com.mobilegem.gemma.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.ByteArrayInputStream
import java.io.InputStream

class ModelFileManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun source(name: String, bytes: ByteArray) = object : ContentSource {
        override val displayName = name
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
    }

    @Test
    fun importCopiesFileIntoModelsDirAndListsIt() = runTest {
        val manager = ModelFileManager(tmp.newFolder("models"))
        val imported = manager.import(source("gemma-4-E2B-it.litertlm", byteArrayOf(1, 2, 3)))

        assertThat(imported.name).isEqualTo("gemma-4-E2B-it.litertlm")
        assertThat(imported.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(manager.listModels().map { it.name })
            .containsExactly("gemma-4-E2B-it.litertlm")
    }

    @Test
    fun importRejectsNonLitertlmExtension() = runTest {
        val manager = ModelFileManager(tmp.newFolder("models"))
        val result = runCatching { manager.import(source("notamodel.txt", byteArrayOf(0))) }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun deleteRemovesModel() = runTest {
        val manager = ModelFileManager(tmp.newFolder("models"))
        manager.import(source("a.litertlm", byteArrayOf(9)))
        manager.delete("a.litertlm")
        assertThat(manager.listModels()).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ModelFileManagerTest"`
Expected: FAIL — `ContentSource` / `ModelFileManager` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/model/ContentSource.kt`:

```kotlin
package com.mobilegem.gemma.model

import java.io.InputStream

interface ContentSource {
    val displayName: String
    fun openStream(): InputStream
}
```

`app/src/main/java/com/mobilegem/gemma/model/ModelFileManager.kt`:

```kotlin
package com.mobilegem.gemma.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ModelFileManager(private val modelsDir: File) {

    init {
        if (!modelsDir.exists()) modelsDir.mkdirs()
    }

    suspend fun import(source: ContentSource): File = withContext(Dispatchers.IO) {
        require(source.displayName.endsWith(".litertlm")) {
            "Model file must have a .litertlm extension"
        }
        val target = File(modelsDir, source.displayName)
        source.openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target
    }

    fun listModels(): List<File> =
        modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }
            ?.sortedBy { it.name } ?: emptyList()

    fun resolve(fileName: String): File = File(modelsDir, fileName)

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        File(modelsDir, fileName).delete()
        Unit
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*ModelFileManagerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/model
git commit -m "feat: add model file manager for importing .litertlm models"
```

---

## Task 4: Android ContentSource adapter

A thin adapter that turns a SAF `Uri` into a `ContentSource`. This is the only Android-specific piece of the import path; it is verified with Robolectric.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/model/UriContentSource.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/model/UriContentSourceTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/model/UriContentSourceTest.kt`:

```kotlin
package com.mobilegem.gemma.model

import android.content.ContentResolver
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class UriContentSourceTest {

    @Test
    fun usesDisplayNameAndOpensStream() {
        val resolver = mockk<ContentResolver>()
        val uri = Uri.parse("content://test/model")
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf(7))

        val source = UriContentSource(resolver, uri, "gemma-4-E4B-it.litertlm")

        assertThat(source.displayName).isEqualTo("gemma-4-E4B-it.litertlm")
        assertThat(source.openStream().readBytes()).isEqualTo(byteArrayOf(7))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*UriContentSourceTest"`
Expected: FAIL — `UriContentSource` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/model/UriContentSource.kt`:

```kotlin
package com.mobilegem.gemma.model

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

class UriContentSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val displayName: String,
) : ContentSource {

    override fun openStream(): InputStream =
        resolver.openInputStream(uri)
            ?: error("Unable to open input stream for $uri")

    companion object {
        /** Resolves the human-readable file name for a SAF Uri. */
        fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
            return uri.lastPathSegment ?: "model.litertlm"
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*UriContentSourceTest"`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/model/UriContentSource.kt app/src/test/java/com/mobilegem/gemma/model/UriContentSourceTest.kt
git commit -m "feat: add SAF Uri adapter for model import"
```

---

## Task 5: OpenAI-compatible request/response DTOs

Serializable data classes for the subset of the OpenAI Chat Completions API the local server speaks. Kept in their own file because the server, the handler, and tests all depend on them.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/server/OpenAiDto.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/server/OpenAiDtoTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/server/OpenAiDtoTest.kt`:

```kotlin
package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class OpenAiDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesAChatCompletionRequest() {
        val raw = """
            {"model":"gemma","stream":true,
             "messages":[{"role":"user","content":"hi"}]}
        """.trimIndent()
        val req = json.decodeFromString<ChatCompletionRequest>(raw)
        assertThat(req.stream).isTrue()
        assertThat(req.messages).hasSize(1)
        assertThat(req.messages[0].role).isEqualTo("user")
        assertThat(req.messages[0].content).isEqualTo("hi")
    }

    @Test
    fun serializesAStreamChunk() {
        val chunk = ChatCompletionChunk(
            id = "abc",
            created = 1L,
            model = "gemma",
            choices = listOf(ChunkChoice(delta = Delta(content = "tok"), finishReason = null)),
        )
        val out = json.encodeToString(ChatCompletionChunk.serializer(), chunk)
        assertThat(out).contains("\"object\":\"chat.completion.chunk\"")
        assertThat(out).contains("\"content\":\"tok\"")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*OpenAiDtoTest"`
Expected: FAIL — DTO classes unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/server/OpenAiDto.kt`:

```kotlin
package com.mobilegem.gemma.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatCompletionRequest(
    val model: String = "gemma",
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val temperature: Float? = null,
)

@Serializable
data class Delta(val role: String? = null, val content: String? = null)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: Delta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val obj: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
)

@Serializable
data class MessageChoice(
    val index: Int = 0,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String = "stop",
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val obj: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<MessageChoice>,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*OpenAiDtoTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/server/OpenAiDto.kt app/src/test/java/com/mobilegem/gemma/server/OpenAiDtoTest.kt
git commit -m "feat: add OpenAI-compatible chat completion DTOs"
```

---

## Task 6: Text generator abstraction

Defines the `TextGenerator` interface that the HTTP handler depends on, plus a `FakeTextGenerator` for tests. The real LiteRT-LM-backed implementation comes in Task 7. This split lets the handler (Task 8) be tested without loading a real model.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/inference/TextGenerator.kt`
- Create: `app/src/test/java/com/mobilegem/gemma/inference/FakeTextGenerator.kt` (lives in test sources so it is reusable by other tests)
- Test: `app/src/test/java/com/mobilegem/gemma/inference/FakeTextGeneratorTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/inference/FakeTextGeneratorTest.kt`:

```kotlin
package com.mobilegem.gemma.inference

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeTextGeneratorTest {

    @Test
    fun emitsConfiguredTokensInOrder() = runTest {
        val gen = FakeTextGenerator(tokens = listOf("Hel", "lo", "!"))
        val collected = gen.generate("ignored prompt", temperature = 0.5f).toList()
        assertThat(collected).containsExactly("Hel", "lo", "!").inOrder()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*FakeTextGeneratorTest"`
Expected: FAIL — `TextGenerator` / `FakeTextGenerator` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/inference/TextGenerator.kt`:

```kotlin
package com.mobilegem.gemma.inference

import kotlinx.coroutines.flow.Flow

/**
 * Generates assistant text for a fully-rendered prompt, streaming token chunks.
 * The prompt is already templated/concatenated by the caller.
 */
interface TextGenerator {
    fun generate(prompt: String, temperature: Float): Flow<String>
}
```

`app/src/test/java/com/mobilegem/gemma/inference/FakeTextGenerator.kt`:

```kotlin
package com.mobilegem.gemma.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

class FakeTextGenerator(private val tokens: List<String>) : TextGenerator {
    var lastPrompt: String? = null
        private set

    override fun generate(prompt: String, temperature: Float): Flow<String> {
        lastPrompt = prompt
        return tokens.asFlow()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*FakeTextGeneratorTest"`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/inference/TextGenerator.kt app/src/test/java/com/mobilegem/gemma/inference/FakeTextGenerator.kt app/src/test/java/com/mobilegem/gemma/inference/FakeTextGeneratorTest.kt
git commit -m "feat: add TextGenerator abstraction with test fake"
```

---

## Task 7: LiteRT-LM text generator

The real `TextGenerator` backed by LiteRT-LM's `Engine`/`Conversation`. This wraps a fast-moving native API, so it has no JVM unit test (it needs a real model + native libs); it is verified on-device in Task 16. The class is kept tiny and free of branching logic so the lack of a unit test is low-risk.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/inference/LiteRtLmTextGenerator.kt`

- [ ] **Step 1: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/inference/LiteRtLmTextGenerator.kt`:

```kotlin
package com.mobilegem.gemma.inference

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.mobilegem.gemma.settings.InferenceBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.Closeable

/**
 * Owns a single LiteRT-LM [Engine] for one model file. Construct via [create];
 * call [close] when switching models or shutting down.
 *
 * NOTE: LiteRT-LM's Kotlin API is verified against
 * `com.google.ai.edge.litertlm:litertlm-android`. If `Engine`/`EngineConfig`/
 * `Backend`/`Conversation` symbols differ in the resolved version, consult the
 * artifact's bundled API and adjust this single file — it is the only place
 * the native API is referenced.
 */
class LiteRtLmTextGenerator private constructor(
    private val engine: Engine,
) : TextGenerator, Closeable {

    override fun generate(prompt: String, temperature: Float): Flow<String> = flow {
        engine.createConversation().use { conversation ->
            conversation.sendMessageAsync(prompt).collect { message ->
                emit(message.toString())
            }
        }
    }

    override fun close() {
        engine.close()
    }

    companion object {
        fun create(modelPath: String, backend: InferenceBackend): LiteRtLmTextGenerator {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = when (backend) {
                    InferenceBackend.GPU -> Backend.GPU()
                    InferenceBackend.CPU -> Backend.CPU()
                },
            )
            val engine = Engine(engineConfig)
            engine.initialize()
            return LiteRtLmTextGenerator(engine)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. If a LiteRT-LM symbol fails to resolve, open the resolved `litertlm-android` artifact, correct the symbol names in this file only, and re-run until it compiles.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/inference/LiteRtLmTextGenerator.kt
git commit -m "feat: add LiteRT-LM-backed text generator"
```

---

## Task 8: Prompt builder + chat completion handler

Two units. `GemmaPromptBuilder` renders an OpenAI `messages` array into a single Gemma-templated prompt string. `ChatCompletionHandler` takes a parsed request, calls the `TextGenerator`, and produces either SSE chunk strings or a single JSON response.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/server/GemmaPromptBuilder.kt`
- Create: `app/src/main/java/com/mobilegem/gemma/server/ChatCompletionHandler.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/server/GemmaPromptBuilderTest.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/server/ChatCompletionHandlerTest.kt`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/mobilegem/gemma/server/GemmaPromptBuilderTest.kt`:

```kotlin
package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GemmaPromptBuilderTest {

    @Test
    fun rendersUserAndAssistantTurnsWithGemmaTemplate() {
        val prompt = GemmaPromptBuilder.build(
            listOf(
                ChatMessage("user", "Hello"),
                ChatMessage("assistant", "Hi there"),
                ChatMessage("user", "How are you?"),
            )
        )
        assertThat(prompt).isEqualTo(
            "<start_of_turn>user\nHello<end_of_turn>\n" +
                "<start_of_turn>model\nHi there<end_of_turn>\n" +
                "<start_of_turn>user\nHow are you?<end_of_turn>\n" +
                "<start_of_turn>model\n"
        )
    }

    @Test
    fun foldsSystemMessageIntoFirstUserTurn() {
        val prompt = GemmaPromptBuilder.build(
            listOf(
                ChatMessage("system", "Be terse."),
                ChatMessage("user", "Hi"),
            )
        )
        assertThat(prompt).isEqualTo(
            "<start_of_turn>user\nBe terse.\n\nHi<end_of_turn>\n" +
                "<start_of_turn>model\n"
        )
    }
}
```

`app/src/test/java/com/mobilegem/gemma/server/ChatCompletionHandlerTest.kt`:

```kotlin
package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeTextGenerator
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChatCompletionHandlerTest {

    @Test
    fun streamingProducesSseChunksEndingWithDone() = runTest {
        val gen = FakeTextGenerator(tokens = listOf("Hi", "!"))
        val handler = ChatCompletionHandler(gen)
        val request = ChatCompletionRequest(
            messages = listOf(ChatMessage("user", "hello")),
            stream = true,
        )

        val events = handler.streamSse(request).toList()

        // role chunk, two content chunks, finish chunk, [DONE]
        assertThat(events).hasSize(5)
        assertThat(events[0]).contains("\"role\":\"assistant\"")
        assertThat(events[1]).contains("\"content\":\"Hi\"")
        assertThat(events[2]).contains("\"content\":\"!\"")
        assertThat(events[3]).contains("\"finish_reason\":\"stop\"")
        assertThat(events[4]).isEqualTo("data: [DONE]\n\n")
        assertThat(gen.lastPrompt).contains("<start_of_turn>user\nhello")
    }

    @Test
    fun nonStreamingAggregatesIntoSingleResponse() = runTest {
        val gen = FakeTextGenerator(tokens = listOf("Full ", "answer"))
        val handler = ChatCompletionHandler(gen)
        val request = ChatCompletionRequest(
            messages = listOf(ChatMessage("user", "q")),
            stream = false,
        )

        val response = handler.complete(request)

        assertThat(response.choices).hasSize(1)
        assertThat(response.choices[0].message.content).isEqualTo("Full answer")
        assertThat(response.choices[0].message.role).isEqualTo("assistant")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*GemmaPromptBuilderTest" --tests "*ChatCompletionHandlerTest"`
Expected: FAIL — `GemmaPromptBuilder` / `ChatCompletionHandler` unresolved.

- [ ] **Step 3: Write the implementations**

`app/src/main/java/com/mobilegem/gemma/server/GemmaPromptBuilder.kt`:

```kotlin
package com.mobilegem.gemma.server

object GemmaPromptBuilder {

    fun build(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        val systemText = messages.firstOrNull { it.role == "system" }?.content
        var systemConsumed = false

        for (msg in messages.filter { it.role != "system" }) {
            when (msg.role) {
                "assistant" -> sb.append("<start_of_turn>model\n")
                    .append(msg.content).append("<end_of_turn>\n")
                else -> {
                    val content = if (!systemConsumed && systemText != null) {
                        systemConsumed = true
                        "$systemText\n\n${msg.content}"
                    } else {
                        msg.content
                    }
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

`app/src/main/java/com/mobilegem/gemma/server/ChatCompletionHandler.kt`:

```kotlin
package com.mobilegem.gemma.server

import com.mobilegem.gemma.inference.TextGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class ChatCompletionHandler(private val generator: TextGenerator) {

    private val json = Json { encodeDefaults = true }

    /** Emits SSE payload strings, each already terminated with a blank line. */
    fun streamSse(request: ChatCompletionRequest): Flow<String> = flow {
        val id = "chatcmpl-${System.nanoTime()}"
        val created = System.currentTimeMillis() / 1000
        val temp = request.temperature ?: 0.8f
        val prompt = GemmaPromptBuilder.build(request.messages)

        emit(sseChunk(id, created, request.model, Delta(role = "assistant"), null))
        generator.generate(prompt, temp).collect { token ->
            emit(sseChunk(id, created, request.model, Delta(content = token), null))
        }
        emit(sseChunk(id, created, request.model, Delta(), "stop"))
        emit("data: [DONE]\n\n")
    }

    suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        val temp = request.temperature ?: 0.8f
        val prompt = GemmaPromptBuilder.build(request.messages)
        val sb = StringBuilder()
        generator.generate(prompt, temp).collect { sb.append(it) }
        return ChatCompletionResponse(
            id = "chatcmpl-${System.nanoTime()}",
            created = System.currentTimeMillis() / 1000,
            model = request.model,
            choices = listOf(
                MessageChoice(message = ChatMessage("assistant", sb.toString())),
            ),
        )
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

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*GemmaPromptBuilderTest" --tests "*ChatCompletionHandlerTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/server/GemmaPromptBuilder.kt app/src/main/java/com/mobilegem/gemma/server/ChatCompletionHandler.kt app/src/test/java/com/mobilegem/gemma/server/GemmaPromptBuilderTest.kt app/src/test/java/com/mobilegem/gemma/server/ChatCompletionHandlerTest.kt
git commit -m "feat: add Gemma prompt builder and chat completion handler"
```

---

## Task 9: Embedded Ktor server

Wires the handler into a Ktor CIO server exposing `GET /v1/models`, `POST /v1/chat/completions`, and permissive CORS (the WebView origin differs). Verified with `ktor-server-test-host`.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/server/LocalLlmServer.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/server/LocalLlmServerTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/server/LocalLlmServerTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*LocalLlmServerTest"`
Expected: FAIL — `installLlmRoutes` / `LocalLlmServer` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/server/LocalLlmServer.kt`:

```kotlin
package com.mobilegem.gemma.server

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
import kotlinx.serialization.json.Json

private val jsonFormat = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun Application.installLlmRoutes(handler: ChatCompletionHandler, modelId: String) {
    install(ContentNegotiation) { json(jsonFormat) }
    install(CORS) {
        anyHost()
        allowHeader("Content-Type")
        allowHeader("Authorization")
    }
    routing {
        get("/v1/models") {
            call.respond(
                mapOf(
                    "object" to "list",
                    "data" to listOf(
                        mapOf("id" to modelId, "object" to "model", "owned_by" to "local"),
                    ),
                ),
            )
        }
        post("/v1/chat/completions") {
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

/** Owns the running HTTP server. The active model can be swapped by rebuilding. */
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

    fun isRunning(): Boolean = server != null

    val baseUrl: String get() = "http://127.0.0.1:$port/v1"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*LocalLlmServerTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/server/LocalLlmServer.kt app/src/test/java/com/mobilegem/gemma/server/LocalLlmServerTest.kt
git commit -m "feat: add embedded Ktor server with OpenAI-compatible routes"
```

---

## Task 10: Inference controller

A single object that holds the app-wide inference state: the loaded `LiteRtLmTextGenerator`, the `LocalLlmServer`, and the currently active model name. It exposes `loadModel(...)` and `unload()`. This is the seam the UI calls; it has a unit test using a fake generator factory.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/inference/InferenceController.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/inference/InferenceControllerTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/inference/InferenceControllerTest.kt`:

```kotlin
package com.mobilegem.gemma.inference

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.settings.InferenceBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InferenceControllerTest {

    @Test
    fun loadModelStartsServerAndExposesState() = runTest {
        val controller = InferenceController(
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
            generatorFactory = { _, _ -> FakeTextGenerator(listOf("hi")) },
        )
        controller.loadModel("/data/models/m.litertlm", InferenceBackend.CPU)
        controller.unload()
        val state = controller.state.first()
        assertThat(state.loadedModelName).isNull()
        assertThat(state.serverRunning).isFalse()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*InferenceControllerTest"`
Expected: FAIL — `InferenceController` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/inference/InferenceController.kt`:

```kotlin
package com.mobilegem.gemma.inference

import com.mobilegem.gemma.server.ChatCompletionHandler
import com.mobilegem.gemma.server.LocalLlmServer
import com.mobilegem.gemma.settings.InferenceBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.File

data class InferenceState(
    val loadedModelName: String? = null,
    val serverRunning: Boolean = false,
)

class InferenceController(
    val server: LocalLlmServer = LocalLlmServer(),
    private val generatorFactory: (modelPath: String, backend: InferenceBackend) -> TextGenerator =
        { path, backend -> LiteRtLmTextGenerator.create(path, backend) },
) {
    private val _state = MutableStateFlow(InferenceState())
    val state: StateFlow<InferenceState> = _state.asStateFlow()

    private var current: TextGenerator? = null

    @Synchronized
    fun loadModel(modelPath: String, backend: InferenceBackend) {
        unload()
        val name = File(modelPath).name
        val generator = generatorFactory(modelPath, backend)
        current = generator
        server.start(ChatCompletionHandler(generator), modelId = name)
        _state.value = InferenceState(loadedModelName = name, serverRunning = true)
    }

    @Synchronized
    fun unload() {
        server.stop()
        (current as? Closeable)?.close()
        current = null
        _state.value = InferenceState()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*InferenceControllerTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/inference/InferenceController.kt app/src/test/java/com/mobilegem/gemma/inference/InferenceControllerTest.kt
git commit -m "feat: add inference controller managing model load and local server"
```

---

## Task 11: Service locator wiring

A minimal `AppContainer` constructed in `GemmaApp`, giving the UI access to the repository, file manager, and controller without a DI framework.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/AppContainer.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/GemmaApp.kt`

- [ ] **Step 1: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/AppContainer.kt`:

```kotlin
package com.mobilegem.gemma

import android.content.Context
import com.mobilegem.gemma.inference.InferenceController
import com.mobilegem.gemma.model.ModelFileManager
import com.mobilegem.gemma.settings.SettingsRepository
import java.io.File

class AppContainer(context: Context) {
    val settingsRepository = SettingsRepository(context)
    val modelFileManager = ModelFileManager(File(context.filesDir, "models"))
    val inferenceController = InferenceController()
}
```

`app/src/main/java/com/mobilegem/gemma/GemmaApp.kt`:

```kotlin
package com.mobilegem.gemma

import android.app.Application

class GemmaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/AppContainer.kt app/src/main/java/com/mobilegem/gemma/GemmaApp.kt
git commit -m "feat: add app container for dependency wiring"
```

---

## Task 12: Settings ViewModel

Holds Settings screen state and actions: import a model, list models, select active model, change backend/temperature, and trigger `(re)loadModel`. Unit-tested with Robolectric using a temp models dir.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/ui/settings/SettingsViewModel.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/ui/settings/SettingsViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/ui/settings/SettingsViewModelTest.kt`:

```kotlin
package com.mobilegem.gemma.ui.settings

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeTextGenerator
import com.mobilegem.gemma.inference.InferenceController
import com.mobilegem.gemma.model.ContentSource
import com.mobilegem.gemma.model.ModelFileManager
import com.mobilegem.gemma.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

    private fun source(name: String) = object : ContentSource {
        override val displayName = name
        override fun openStream(): InputStream = ByteArrayInputStream(byteArrayOf(1))
    }

    private fun newViewModel(): SettingsViewModel = SettingsViewModel(
        settingsRepository = SettingsRepository(ApplicationProvider.getApplicationContext()),
        modelFileManager = ModelFileManager(tmp.newFolder("models")),
        inferenceController = InferenceController(
            generatorFactory = { _, _ -> FakeTextGenerator(listOf("x")) },
        ),
    )

    @Test
    fun importingAModelAddsItToTheInstalledList() = runTest {
        val vm = newViewModel()
        vm.importModel(source("gemma-4-E2B-it.litertlm"))
        assertThat(vm.uiState.first().installedModels).contains("gemma-4-E2B-it.litertlm")
    }

    @Test
    fun selectingAModelPersistsItAndLoadsInference() = runTest {
        val vm = newViewModel()
        vm.importModel(source("gemma-4-E2B-it.litertlm"))
        vm.selectModel("gemma-4-E2B-it.litertlm")

        val state = vm.uiState.first()
        assertThat(state.activeModel).isEqualTo("gemma-4-E2B-it.litertlm")
        assertThat(state.modelLoaded).isTrue()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SettingsViewModelTest"`
Expected: FAIL — `SettingsViewModel` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/ui/settings/SettingsViewModel.kt`:

```kotlin
package com.mobilegem.gemma.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilegem.gemma.inference.InferenceController
import com.mobilegem.gemma.model.ContentSource
import com.mobilegem.gemma.model.ModelFileManager
import com.mobilegem.gemma.settings.AppSettings
import com.mobilegem.gemma.settings.InferenceBackend
import com.mobilegem.gemma.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsUiState(
    val installedModels: List<String> = emptyList(),
    val activeModel: String? = null,
    val backend: InferenceBackend = InferenceBackend.CPU,
    val temperature: Float = 0.8f,
    val modelLoaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val modelFileManager: ModelFileManager,
    private val inferenceController: InferenceController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val settings = settingsRepository.settings.first()
        val infState = inferenceController.state.first()
        _uiState.value = _uiState.value.copy(
            installedModels = modelFileManager.listModels().map { it.name },
            activeModel = settings.activeModelFileName,
            backend = settings.backend,
            temperature = settings.temperature,
            modelLoaded = infState.loadedModelName != null,
        )
    }

    fun importModel(source: ContentSource) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(busy = true, error = null)
        runCatching { modelFileManager.import(source) }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        _uiState.value = _uiState.value.copy(busy = false)
        refresh()
    }

    fun selectModel(fileName: String) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(busy = true, error = null)
        settingsRepository.setActiveModel(fileName)
        loadActive()
        _uiState.value = _uiState.value.copy(busy = false)
        refresh()
    }

    fun setBackend(backend: InferenceBackend) = viewModelScope.launch {
        settingsRepository.setBackend(backend)
        refresh()
    }

    fun setTemperature(value: Float) = viewModelScope.launch {
        settingsRepository.setTemperature(value)
        refresh()
    }

    /** Loads the persisted active model into inference; safe to call at startup. */
    fun loadActive() = viewModelScope.launch {
        val settings = settingsRepository.settings.first()
        val name = settings.activeModelFileName ?: return@launch
        val file = modelFileManager.resolve(name)
        if (!file.exists()) return@launch
        runCatching { inferenceController.loadModel(file.absolutePath, settings.backend) }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        refresh()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SettingsViewModelTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/ui/settings/SettingsViewModel.kt app/src/test/java/com/mobilegem/gemma/ui/settings/SettingsViewModelTest.kt
git commit -m "feat: add settings view model for model management"
```

---

## Task 13: Settings screen UI

Compose UI for the full-screen Settings section: a model file picker (SAF `OpenDocument`), the installed-models list with selection, a backend toggle, and a temperature slider. UI scaffolding has no unit test; it is verified visually in Task 16.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/ui/settings/SettingsScreen.kt`:

```kotlin
package com.mobilegem.gemma.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobilegem.gemma.model.UriContentSource
import com.mobilegem.gemma.settings.InferenceBackend

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            val name = UriContentSource.queryDisplayName(resolver, uri)
            viewModel.importModel(UriContentSource(resolver, uri, name))
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings")
        Button(
            enabled = !state.busy,
            onClick = { picker.launch(arrayOf("*/*")) },
        ) { Text("Import model (.litertlm)") }

        state.error?.let { Text("Error: $it") }

        Text("Installed models")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.installedModels) { name ->
                Card {
                    Column(Modifier.padding(12.dp)) {
                        RadioButton(
                            selected = name == state.activeModel,
                            onClick = { viewModel.selectModel(name) },
                        )
                        Text(name)
                        if (name == state.activeModel && state.modelLoaded) {
                            Text("Loaded")
                        }
                    }
                }
            }
        }

        Text("Inference backend")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InferenceBackend.entries.forEach { backend ->
                FilterChip(
                    selected = backend == state.backend,
                    onClick = { viewModel.setBackend(backend) },
                    label = { Text(backend.name) },
                )
            }
        }

        Text("Temperature: ${"%.2f".format(state.temperature)}")
        Slider(
            value = state.temperature,
            onValueChange = { viewModel.setTemperature(it) },
            valueRange = 0f..1.5f,
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/ui/settings/SettingsScreen.kt
git commit -m "feat: add settings screen with model file picker"
```

---

## Task 14: pi-web-ui chat web app

A separate Vite + TypeScript module that builds the chat UI from `@mariozechner/pi-web-ui` and points it at the local server. The built output is copied into `app/src/main/assets/webui/` (done in Task 15).

**Files:**
- Create: `webui/package.json`
- Create: `webui/tsconfig.json`
- Create: `webui/vite.config.ts`
- Create: `webui/index.html`
- Create: `webui/src/main.ts`

- [ ] **Step 1: Create the web module config**

`webui/package.json`:

```json
{
  "name": "mobilegem-webui",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build"
  },
  "dependencies": {
    "@mariozechner/pi-web-ui": "^0.73.1",
    "@mariozechner/mini-lit": "^0.2.0",
    "lit": "^3.3.1"
  },
  "devDependencies": {
    "typescript": "^5.5.4",
    "vite": "^5.4.0"
  }
}
```

`webui/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "experimentalDecorators": true,
    "useDefineForClassFields": false,
    "skipLibCheck": true,
    "lib": ["ES2022", "DOM", "DOM.Iterable"]
  },
  "include": ["src"]
}
```

`webui/vite.config.ts`:

```ts
import { defineConfig } from "vite";

// Relative base so the bundle works when loaded from file:// or the
// WebView's asset loader.
export default defineConfig({
  base: "./",
  build: {
    outDir: "dist",
    target: "es2022",
  },
});
```

`webui/index.html`:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>MobileGem Chat</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

- [ ] **Step 2: Install dependencies**

Run: `cd webui && npm install`
Expected: dependencies installed, `node_modules/@mariozechner/pi-web-ui` present.

- [ ] **Step 3: Inspect the installed pi-web-ui API**

Run: `cd webui && cat node_modules/@mariozechner/pi-web-ui/dist/index.d.ts | head -120`
Expected: a list of exported symbols. Identify the chat entry component (the search and npm docs indicate a high-level `ChatPanel` and a lower-level `AgentInterface`, plus an `IndexedDBStorageBackend` and provider/store helpers). The next step wires whichever high-level component the installed `0.73.x` version exports. **Do not guess** — read the actual `.d.ts`.

- [ ] **Step 4: Write the app entry point**

`webui/src/main.ts` — instantiate the high-level chat component and configure a local OpenAI-compatible provider. The skeleton below uses `ChatPanel`; if the inspected `.d.ts` names it differently, use the actual export name and constructor/property shape. The two invariants that must hold regardless of API details: (a) the provider `baseUrl` is `http://127.0.0.1:8765/v1`, (b) the model id is read from the local `/v1/models` response.

```ts
import "@mariozechner/pi-web-ui/app.css";
import { ChatPanel } from "@mariozechner/pi-web-ui";

const LOCAL_BASE_URL = "http://127.0.0.1:8765/v1";

async function resolveModelId(): Promise<string> {
  const res = await fetch(`${LOCAL_BASE_URL}/models`);
  const body = await res.json();
  return body?.data?.[0]?.id ?? "gemma";
}

async function bootstrap(): Promise<void> {
  const modelId = await resolveModelId();

  const panel = new ChatPanel();
  // pi-web-ui exposes OpenAI-compatible custom providers (used for Ollama /
  // LM Studio / vLLM). Configure one pointing at the in-app server.
  // Property/method names below MUST match the inspected index.d.ts.
  panel.setProvider({
    id: "local-gemma",
    name: "Gemma (on-device)",
    type: "openai-compatible",
    baseUrl: LOCAL_BASE_URL,
    apiKey: "not-needed",
    models: [modelId],
  });
  panel.setModel(modelId);

  document.getElementById("app")!.appendChild(panel);
}

bootstrap();
```

- [ ] **Step 5: Build the web app**

Run: `cd webui && npm run build`
Expected: `webui/dist/` produced with `index.html` and hashed JS/CSS assets. If the build fails because a symbol used in `main.ts` does not exist, correct `main.ts` against the `.d.ts` from Step 3 and rebuild.

- [ ] **Step 6: Commit**

```bash
git add webui/package.json webui/tsconfig.json webui/vite.config.ts webui/index.html webui/src/main.ts webui/package-lock.json
git commit -m "feat: add pi-web-ui chat web app targeting local server"
```

---

## Task 15: Bundle the web app into Android assets

Copies `webui/dist/` into `app/src/main/assets/webui/` via a Gradle task so the WebView can load it offline.

**Files:**
- Create: `webui/dist/` → copied to `app/src/main/assets/webui/` (build artifact, git-ignored except as noted)
- Create: `.gitignore`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add a copy task to the app build**

Append to `app/build.gradle.kts` (after the `dependencies { }` block):

```kotlin
val syncWebUi by tasks.registering(Copy::class) {
    from(rootProject.file("webui/dist"))
    into(layout.projectDirectory.dir("src/main/assets/webui"))
}

tasks.named("preBuild") {
    dependsOn(syncWebUi)
}
```

- [ ] **Step 2: Add .gitignore**

`.gitignore` (repo root):

```gitignore
.gradle/
build/
app/build/
node_modules/
webui/dist/
app/src/main/assets/webui/
local.properties
.idea/
*.iml
```

- [ ] **Step 3: Verify the sync runs**

Run: `cd webui && npm run build && cd .. && ./gradlew :app:syncWebUi`
Expected: `BUILD SUCCESSFUL`, and `app/src/main/assets/webui/index.html` now exists.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts .gitignore
git commit -m "build: sync built web UI into Android assets"
```

---

## Task 16: Chat screen WebView + navigation + MainActivity

The full-screen Chat section is a `WebView` loading the bundled web app via `WebViewAssetLoader` (gives it an `https://appassets.androidplatform.net` origin so `fetch` to `127.0.0.1` is permitted). `MainActivity` hosts a Compose `NavHost` with bottom navigation for **Chat / Memory / Settings**. Memory is a stub for Plan 2. The active model is loaded at startup.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/ui/chat/ChatScreen.kt`
- Create: `app/src/main/java/com/mobilegem/gemma/ui/memory/MemoryScreen.kt`
- Create: `app/src/main/java/com/mobilegem/gemma/ui/navigation/AppScaffold.kt`
- Create: `app/src/main/java/com/mobilegem/gemma/MainActivity.kt`
- Modify: `app/build.gradle.kts` (add `androidx.webkit`)
- Modify: `app/src/main/AndroidManifest.xml` (ensure `<activity>` block present)

- [ ] **Step 1: Add the WebKit dependency**

Add to the `dependencies { }` block in `app/build.gradle.kts`:

```kotlin
    implementation("androidx.webkit:webkit:1.11.0")
```

- [ ] **Step 2: Write the Chat screen**

`app/src/main/java/com/mobilegem/gemma/ui/chat/ChatScreen.kt`:

```kotlin
package com.mobilegem.gemma.ui.chat

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler(
                        "/assets/",
                        WebViewAssetLoader.AssetsPathHandler(context),
                    )
                    .build()

                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? =
                            assetLoader.shouldInterceptRequest(request.url)
                    }
                    loadUrl(
                        "https://appassets.androidplatform.net/assets/webui/index.html",
                    )
                }
            },
        )
    }
}
```

- [ ] **Step 3: Write the Memory stub screen**

`app/src/main/java/com/mobilegem/gemma/ui/memory/MemoryScreen.kt`:

```kotlin
package com.mobilegem.gemma.ui.memory

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Placeholder. The full Memory subsystem is delivered in Plan 2. */
@Composable
fun MemoryScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Memory — coming soon")
    }
}
```

- [ ] **Step 4: Write the navigation scaffold**

`app/src/main/java/com/mobilegem/gemma/ui/navigation/AppScaffold.kt`:

```kotlin
package com.mobilegem.gemma.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mobilegem.gemma.ui.chat.ChatScreen
import com.mobilegem.gemma.ui.memory.MemoryScreen
import com.mobilegem.gemma.ui.settings.SettingsScreen
import com.mobilegem.gemma.ui.settings.SettingsViewModel

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Dest("chat", "Chat", Icons.Filled.Chat),
    Dest("memory", "Memory", Icons.Filled.Memory),
    Dest("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun AppScaffold(settingsViewModel: SettingsViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.padding(padding),
        ) {
            composable("chat") { ChatScreen() }
            composable("memory") { MemoryScreen() }
            composable("settings") { SettingsScreen(settingsViewModel) }
        }
    }
}
```

- [ ] **Step 5: Write MainActivity**

`app/src/main/java/com/mobilegem/gemma/MainActivity.kt`:

```kotlin
package com.mobilegem.gemma

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.Factory
import androidx.lifecycle.ViewModel
import androidx.compose.material3.MaterialTheme
import com.mobilegem.gemma.ui.navigation.AppScaffold
import com.mobilegem.gemma.ui.settings.SettingsViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as GemmaApp).container

        val factory = object : Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    modelFileManager = container.modelFileManager,
                    inferenceController = container.inferenceController,
                ) as T
        }
        val settingsViewModel =
            ViewModelProvider(this, factory)[SettingsViewModel::class.java]

        // Load the previously-selected model on startup.
        settingsViewModel.loadActive()

        setContent {
            MaterialTheme {
                AppScaffold(settingsViewModel)
            }
        }
    }
}
```

- [ ] **Step 6: Confirm the manifest activity block**

Ensure `app/src/main/AndroidManifest.xml` contains the `<activity android:name=".MainActivity" ...>` block from Task 1, Step 3 (restore it if it was removed in Task 1, Step 4).

- [ ] **Step 7: Build the full app**

Run: `cd webui && npm run build && cd .. && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk` produced.

- [ ] **Step 8: On-device smoke test**

Install on a physical device or emulator (LiteRT-LM needs a real or modern emulator with the right ABI):
Run: `./gradlew :app:installDebug`
Then manually:
1. Open the app → bottom nav shows Chat / Memory / Settings.
2. Go to **Settings** → tap **Import model** → pick a `.litertlm` Gemma 4 file → it appears in the installed list.
3. Select the model via its radio button → it shows **Loaded**.
4. Go to **Chat** → the pi-web-ui interface renders → send "Hello" → a streamed response appears.
5. Go to **Memory** → see the "coming soon" placeholder.

Expected: all five steps pass. If Chat shows a connection error, confirm the server is running (model selected in Settings first) and that `LOCAL_BASE_URL` in `webui/src/main.ts` matches `LocalLlmServer`'s port (`8765`).

- [ ] **Step 9: Run the full JVM test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — all unit tests green.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/ui/chat/ChatScreen.kt app/src/main/java/com/mobilegem/gemma/ui/memory/MemoryScreen.kt app/src/main/java/com/mobilegem/gemma/ui/navigation/AppScaffold.kt app/src/main/java/com/mobilegem/gemma/MainActivity.kt app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "feat: add chat WebView, navigation, and main activity"
```

---

## Done

At this point the app is shippable: import a Gemma 4 `.litertlm` model, select it, and chat with it fully offline through the pi-web-ui interface. Memory is a visible stub.

## Notes for Plan 2 (Memory subsystem)

- The Memory section (Projects → Sessions, Skills, Self-learning, Long-Term Memory) will be built on a custom **SQLite** schema (via Room or SQLDelight) plus a local vector index for semantic recall. `turboagents` is **not** a dependency — it is a KV-cache/vector compression library, not a memory/projects framework, and does not integrate with LiteRT-LM.
- A natural integration point: the embedded server's `ChatCompletionHandler` is where retrieved long-term-memory context can be injected into the prompt before generation, and where new turns can be persisted as session history.
- Multi-turn efficiency: this plan re-renders the full prompt each request and uses a fresh `Conversation` per request. Plan 2 (or a follow-up) may keep a `Conversation` alive per session to reuse the KV-cache.

## Known risks / things to verify during execution

1. **LiteRT-LM Kotlin symbols** (Task 7): `Engine`, `EngineConfig`, `Backend`, `Conversation.sendMessageAsync` are taken from Google's published Kotlin guide. If the resolved `litertlm-android` artifact differs, fix `LiteRtLmTextGenerator.kt` only — it is the sole file touching the native API.
2. **pi-web-ui API** (Task 14): the exact `ChatPanel`/provider API must be read from the installed `dist/index.d.ts` (Step 3) rather than assumed. The package bundles the `ollama` and `@lmstudio/sdk` SDKs, confirming first-class support for local OpenAI-compatible providers.
3. **WebView → localhost**: serving the web app from the `appassets.androidplatform.net` origin keeps it a secure context; `fetch` to `http://127.0.0.1:8765` is plain HTTP. If a mixed-content block occurs, set `mixedContentMode` on the `WebView` settings or expose the local server over the asset loader as a proxied path.
4. **pi fork divergence**: `earendil-works/pi` publishes `@earendil-works/*` packages and has no `web-ui` package; the published `@mariozechner/pi-web-ui` depends on `@mariozechner/pi-ai`/`pi-tui`. This plan uses the published `@mariozechner/pi-web-ui` as-is. Forking is only needed if you must modify the UI source — not required for this plan.
