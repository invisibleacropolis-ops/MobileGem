# Gemma Android App — Memory Subsystem Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a full-screen Memory section that organizes Projects → Sessions, reusable Skills, and an automatically-populated Long-Term Memory, with retrieved memory and skills injected into every chat and durable facts extracted from finished sessions by the on-device model itself.

**Architecture:** All memory state lives in a local **Room (SQLite)** database. Long-Term Memory entries store a vector **embedding produced by the same on-device Gemma model** used for chat (exposed through an `Embedder` abstraction). At chat time, a `ContextAugmenter` retrieves the most relevant memory entries (cosine similarity) plus the project's enabled Skills and injects them as system context — this happens inside Plan 1's `ChatCompletionHandler`. When a session is finished, a `SelfLearningExtractor` sends the transcript to the local model, parses the durable facts it returns, embeds them, and stores them as new Long-Term Memory entries. The Memory UI replaces the Plan 1 placeholder screen.

**Tech Stack:** Kotlin, Room 2.6.1 + KSP, Jetpack Compose, kotlinx.coroutines/serialization. Builds directly on Plan 1's `TextGenerator`, `ChatCompletionHandler`, `InferenceController`, and `AppContainer`.

**Prerequisite:** Plan 1 (`2026-05-20-gemma-android-foundation.md`) must be fully implemented and committed first. This plan **modifies** these Plan 1 files: `GemmaPromptBuilder.kt`, `ChatCompletionHandler.kt`, `InferenceController.kt`, `AppContainer.kt`, `AppScaffold.kt`, `ChatScreen.kt`, and replaces `MemoryScreen.kt`.

**Conventions for every task:** Run JVM unit tests with `./gradlew :app:testDebugUnitTest` from the repo root. Android package root is `com.mobilegem.gemma`. Commit after every task with the message shown.

---

## Task 1: Add Room, KSP, and the database entities

Adds the Room dependency and the five Room entities. Entities are plain data classes; no test (verified transitively by the DAO tests in Task 2–3).

**Files:**
- Modify: `build.gradle.kts` (root) — add KSP plugin
- Modify: `app/build.gradle.kts` — apply KSP, add Room deps
- Create: `app/src/main/java/com/mobilegem/gemma/memory/db/Entities.kt`
- Create: `app/src/main/java/com/mobilegem/gemma/memory/db/Converters.kt`

- [ ] **Step 1: Add the KSP plugin to the root build**

In `build.gradle.kts` (root), add to the `plugins { }` block:

```kotlin
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
```

- [ ] **Step 2: Apply KSP and add Room dependencies in the app module**

In `app/build.gradle.kts`, add to the `plugins { }` block:

```kotlin
    id("com.google.devtools.ksp")
```

Add to the `dependencies { }` block:

```kotlin
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    testImplementation("androidx.room:room-testing:2.6.1")
```

- [ ] **Step 3: Create the entities**

`app/src/main/java/com/mobilegem/gemma/memory/db/Entities.kt`:

```kotlin
package com.mobilegem.gemma.memory.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "sessions",
    foreignKeys = [ForeignKey(
        entity = Project::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("projectId")],
)
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = Session::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class StoredMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
)

/** projectId == null means the skill is global (applies to every project). */
@Entity(tableName = "skills", indices = [Index("projectId")])
data class Skill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long?,
    val name: String,
    val description: String = "",
    val instructions: String,
    val enabled: Boolean = true,
)

/** projectId == null means the memory is global. embedding length is model-defined. */
@Entity(tableName = "memory_entries", indices = [Index("projectId")])
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long?,
    val content: String,
    val embedding: FloatArray,
    val sourceSessionId: Long?,
    val createdAt: Long,
) {
    // FloatArray needs explicit equals/hashCode; Room does not require them but
    // the Kotlin compiler warns without them for arrays in data classes.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryEntry) return false
        return id == other.id && content == other.content &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int =
        (id.hashCode() * 31 + content.hashCode()) * 31 + embedding.contentHashCode()
}
```

- [ ] **Step 4: Create the type converter**

`app/src/main/java/com/mobilegem/gemma/memory/db/Converters.kt`:

```kotlin
package com.mobilegem.gemma.memory.db

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Converters {

    @TypeConverter
    fun fromFloatArray(value: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(value.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        value.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    @TypeConverter
    fun toFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }
}
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts app/build.gradle.kts app/src/main/java/com/mobilegem/gemma/memory/db/Entities.kt app/src/main/java/com/mobilegem/gemma/memory/db/Converters.kt
git commit -m "feat: add Room dependency and memory entities"
```

---

## Task 2: Database + project/session/message DAOs

Creates the `MemoryDatabase` and DAOs for projects, sessions, and messages. Verified with an in-memory Room database under Robolectric.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/memory/db/CoreDao.kt`
- Create: `app/src/main/java/com/mobilegem/gemma/memory/db/MemoryDatabase.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/memory/db/CoreDaoTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/memory/db/CoreDaoTest.kt`:

```kotlin
package com.mobilegem.gemma.memory.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoreDaoTest {

    private lateinit var db: MemoryDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertsAndListsProjects() = runTest {
        val id = db.coreDao().insertProject(
            Project(name = "Research", createdAt = 1, updatedAt = 1),
        )
        val projects = db.coreDao().observeProjects().first()
        assertThat(projects.map { it.name }).containsExactly("Research")
        assertThat(projects.single().id).isEqualTo(id)
    }

    @Test
    fun sessionsAreScopedToProjectAndCascadeOnDelete() = runTest {
        val projectId = db.coreDao().insertProject(
            Project(name = "P", createdAt = 1, updatedAt = 1),
        )
        db.coreDao().insertSession(
            Session(projectId = projectId, title = "S1", createdAt = 1, updatedAt = 1),
        )
        assertThat(db.coreDao().observeSessions(projectId).first()).hasSize(1)

        db.coreDao().deleteProject(projectId)
        assertThat(db.coreDao().observeSessions(projectId).first()).isEmpty()
    }

    @Test
    fun messagesCanBeReplacedForASession() = runTest {
        val projectId = db.coreDao().insertProject(
            Project(name = "P", createdAt = 1, updatedAt = 1),
        )
        val sessionId = db.coreDao().insertSession(
            Session(projectId = projectId, title = "S", createdAt = 1, updatedAt = 1),
        )
        db.coreDao().insertMessage(
            StoredMessage(sessionId = sessionId, role = "user", content = "old", createdAt = 1),
        )
        db.coreDao().deleteMessagesForSession(sessionId)
        db.coreDao().insertMessage(
            StoredMessage(sessionId = sessionId, role = "user", content = "new", createdAt = 2),
        )
        val messages = db.coreDao().messagesForSession(sessionId)
        assertThat(messages.map { it.content }).containsExactly("new")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*CoreDaoTest"`
Expected: FAIL — `MemoryDatabase` / `CoreDao` unresolved.

- [ ] **Step 3: Write the DAO**

`app/src/main/java/com/mobilegem/gemma/memory/db/CoreDao.kt`:

```kotlin
package com.mobilegem.gemma.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CoreDao {

    @Insert
    suspend fun insertProject(project: Project): Long

    @Update
    suspend fun updateProject(project: Project)

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: Long)

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :projectId")
    suspend fun projectById(projectId: Long): Project?

    @Insert
    suspend fun insertSession(session: Session): Long

    @Update
    suspend fun updateSession(session: Session)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeSessions(projectId: Long): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun sessionById(sessionId: Long): Session?

    @Insert
    suspend fun insertMessage(message: StoredMessage): Long

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: Long)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun messagesForSession(sessionId: Long): List<StoredMessage>
}
```

- [ ] **Step 4: Write the database**

`app/src/main/java/com/mobilegem/gemma/memory/db/MemoryDatabase.kt`:

```kotlin
package com.mobilegem.gemma.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Project::class, Session::class, StoredMessage::class, Skill::class, MemoryEntry::class],
    version = 1,
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
                .build()
    }
}
```

> Note: `MemoryDatabase` references `SkillDao` and `MemoryDao`, created in Task 3. To make Task 2 compile and test in isolation, temporarily comment out the `skillDao()` and `memoryDao()` abstract methods and remove `Skill::class, MemoryEntry::class` from the `entities` list; restore them in Task 3, Step 4. If you are executing Tasks 2 and 3 back-to-back, you may instead create the Task 3 DAO stubs first.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*CoreDaoTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/memory/db/CoreDao.kt app/src/main/java/com/mobilegem/gemma/memory/db/MemoryDatabase.kt app/src/test/java/com/mobilegem/gemma/memory/db/CoreDaoTest.kt
git commit -m "feat: add memory database and core DAO"
```

---

## Task 3: Skill and memory-entry DAOs

Adds the DAOs for Skills and Long-Term Memory entries, including the "project-scoped OR global" queries.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/memory/db/SkillDao.kt`
- Create: `app/src/main/java/com/mobilegem/gemma/memory/db/MemoryDao.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/memory/db/MemoryDatabase.kt` (restore the two DAO methods + entities)
- Test: `app/src/test/java/com/mobilegem/gemma/memory/db/SkillMemoryDaoTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/memory/db/SkillMemoryDaoTest.kt`:

```kotlin
package com.mobilegem.gemma.memory.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkillMemoryDaoTest {

    private lateinit var db: MemoryDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun enabledSkillsForProjectIncludeGlobalAndProjectScoped() = runTest {
        db.skillDao().insert(
            Skill(projectId = null, name = "Global", instructions = "g", enabled = true),
        )
        db.skillDao().insert(
            Skill(projectId = 1, name = "Proj", instructions = "p", enabled = true),
        )
        db.skillDao().insert(
            Skill(projectId = 1, name = "Off", instructions = "x", enabled = false),
        )
        db.skillDao().insert(
            Skill(projectId = 2, name = "Other", instructions = "o", enabled = true),
        )

        val enabled = db.skillDao().enabledForProject(1)
        assertThat(enabled.map { it.name }).containsExactly("Global", "Proj")
    }

    @Test
    fun memoryEntriesForProjectScopeIncludeGlobal() = runTest {
        db.memoryDao().insert(
            MemoryEntry(projectId = null, content = "global fact",
                embedding = floatArrayOf(1f), sourceSessionId = null, createdAt = 1),
        )
        db.memoryDao().insert(
            MemoryEntry(projectId = 1, content = "project fact",
                embedding = floatArrayOf(2f), sourceSessionId = null, createdAt = 2),
        )
        db.memoryDao().insert(
            MemoryEntry(projectId = 9, content = "unrelated",
                embedding = floatArrayOf(3f), sourceSessionId = null, createdAt = 3),
        )

        val entries = db.memoryDao().entriesForProjectScope(1)
        assertThat(entries.map { it.content })
            .containsExactly("global fact", "project fact")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SkillMemoryDaoTest"`
Expected: FAIL — `SkillDao` / `MemoryDao` unresolved.

- [ ] **Step 3: Write the DAOs**

`app/src/main/java/com/mobilegem/gemma/memory/db/SkillDao.kt`:

```kotlin
package com.mobilegem.gemma.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillDao {

    @Insert
    suspend fun insert(skill: Skill): Long

    @Update
    suspend fun update(skill: Skill)

    @Query("DELETE FROM skills WHERE id = :skillId")
    suspend fun delete(skillId: Long)

    @Query("SELECT * FROM skills WHERE projectId = :projectId OR projectId IS NULL ORDER BY name")
    fun observeForProjectScope(projectId: Long): Flow<List<Skill>>

    @Query(
        "SELECT * FROM skills WHERE (projectId = :projectId OR projectId IS NULL) " +
            "AND enabled = 1 ORDER BY name",
    )
    suspend fun enabledForProject(projectId: Long): List<Skill>
}
```

`app/src/main/java/com/mobilegem/gemma/memory/db/MemoryDao.kt`:

```kotlin
package com.mobilegem.gemma.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert
    suspend fun insert(entry: MemoryEntry): Long

    @Query("DELETE FROM memory_entries WHERE id = :entryId")
    suspend fun delete(entryId: Long)

    @Query("SELECT * FROM memory_entries WHERE projectId = :projectId OR projectId IS NULL")
    suspend fun entriesForProjectScope(projectId: Long): List<MemoryEntry>

    @Query("SELECT * FROM memory_entries WHERE projectId = :projectId OR projectId IS NULL ORDER BY createdAt DESC")
    fun observeForProjectScope(projectId: Long): Flow<List<MemoryEntry>>
}
```

- [ ] **Step 4: Restore the database DAO methods**

Ensure `app/src/main/java/com/mobilegem/gemma/memory/db/MemoryDatabase.kt` matches exactly the version shown in Task 2, Step 4 (all five entities present, `skillDao()` and `memoryDao()` methods present). If they were commented out for Task 2, uncomment them now.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SkillMemoryDaoTest" --tests "*CoreDaoTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/memory/db/SkillDao.kt app/src/main/java/com/mobilegem/gemma/memory/db/MemoryDao.kt app/src/main/java/com/mobilegem/gemma/memory/db/MemoryDatabase.kt app/src/test/java/com/mobilegem/gemma/memory/db/SkillMemoryDaoTest.kt
git commit -m "feat: add skill and memory-entry DAOs"
```

---

## Task 4: Vector math (cosine similarity)

Pure Kotlin utility used by memory retrieval. Fully unit-testable.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/memory/VectorMath.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/memory/VectorMathTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/memory/VectorMathTest.kt`:

```kotlin
package com.mobilegem.gemma.memory

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VectorMathTest {

    @Test
    fun identicalVectorsHaveSimilarityOne() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertThat(VectorMath.cosineSimilarity(v, v)).isWithin(1e-6f).of(1f)
    }

    @Test
    fun orthogonalVectorsHaveSimilarityZero() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertThat(VectorMath.cosineSimilarity(a, b)).isWithin(1e-6f).of(0f)
    }

    @Test
    fun zeroVectorYieldsZeroSimilarity() {
        val a = floatArrayOf(0f, 0f)
        val b = floatArrayOf(1f, 1f)
        assertThat(VectorMath.cosineSimilarity(a, b)).isEqualTo(0f)
    }

    @Test
    fun mismatchedLengthsThrow() {
        val result = runCatching {
            VectorMath.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f))
        }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*VectorMathTest"`
Expected: FAIL — `VectorMath` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/memory/VectorMath.kt`:

```kotlin
package com.mobilegem.gemma.memory

import kotlin.math.sqrt

object VectorMath {

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vectors must have equal length" }
        var dot = 0f
        var normA = 0f
        var normB = 0f
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*VectorMathTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/memory/VectorMath.kt app/src/test/java/com/mobilegem/gemma/memory/VectorMathTest.kt
git commit -m "feat: add cosine similarity helper"
```

---

## Task 5: Memory repository (projects, sessions, messages)

Wraps `CoreDao` with timestamp handling and conversation persistence. Implements the `ConversationPersister` interface that Plan 1's `ChatCompletionHandler` will depend on (defined here so the handler modification in Task 13 can compile).

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/memory/ConversationPersister.kt`
- Create: `app/src/main/java/com/mobilegem/gemma/memory/MemoryRepository.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/memory/MemoryRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/memory/MemoryRepositoryTest.kt`:

```kotlin
package com.mobilegem.gemma.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.memory.db.MemoryDatabase
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryRepositoryTest {

    private lateinit var db: MemoryDatabase
    private lateinit var repo: MemoryRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = MemoryRepository(db.coreDao()) { 1000L }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun createsProjectAndSession() = runTest {
        val projectId = repo.createProject("Research", "notes")
        val sessionId = repo.createSession(projectId, "First chat")

        assertThat(repo.observeProjects().first().single().name).isEqualTo("Research")
        assertThat(repo.observeSessions(projectId).first().single().title)
            .isEqualTo("First chat")
        assertThat(repo.sessionById(sessionId)?.projectId).isEqualTo(projectId)
    }

    @Test
    fun persistConversationReplacesSessionMessages() = runTest {
        val projectId = repo.createProject("P", "")
        val sessionId = repo.createSession(projectId, "S")

        repo.persistConversation(sessionId, listOf(ChatMessage("user", "hi")))
        repo.persistConversation(
            sessionId,
            listOf(
                ChatMessage("user", "hi"),
                ChatMessage("assistant", "hello"),
            ),
        )

        val messages = repo.messagesForSession(sessionId)
        assertThat(messages.map { it.role to it.content })
            .containsExactly("user" to "hi", "assistant" to "hello").inOrder()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*MemoryRepositoryTest"`
Expected: FAIL — `MemoryRepository` / `ConversationPersister` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/memory/ConversationPersister.kt`:

```kotlin
package com.mobilegem.gemma.memory

import com.mobilegem.gemma.server.ChatMessage

/** Persists the full message list for a chat session, replacing any prior content. */
interface ConversationPersister {
    suspend fun persistConversation(sessionId: Long, messages: List<ChatMessage>)
}
```

`app/src/main/java/com/mobilegem/gemma/memory/MemoryRepository.kt`:

```kotlin
package com.mobilegem.gemma.memory

import com.mobilegem.gemma.memory.db.CoreDao
import com.mobilegem.gemma.memory.db.Project
import com.mobilegem.gemma.memory.db.Session
import com.mobilegem.gemma.memory.db.StoredMessage
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.Flow

class MemoryRepository(
    private val coreDao: CoreDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ConversationPersister {

    fun observeProjects(): Flow<List<Project>> = coreDao.observeProjects()
    fun observeSessions(projectId: Long): Flow<List<Session>> =
        coreDao.observeSessions(projectId)

    suspend fun projectById(id: Long): Project? = coreDao.projectById(id)
    suspend fun sessionById(id: Long): Session? = coreDao.sessionById(id)

    suspend fun createProject(name: String, description: String): Long {
        val now = clock()
        return coreDao.insertProject(
            Project(name = name, description = description, createdAt = now, updatedAt = now),
        )
    }

    suspend fun deleteProject(projectId: Long) = coreDao.deleteProject(projectId)

    suspend fun createSession(projectId: Long, title: String): Long {
        val now = clock()
        return coreDao.insertSession(
            Session(projectId = projectId, title = title, createdAt = now, updatedAt = now),
        )
    }

    suspend fun deleteSession(sessionId: Long) = coreDao.deleteSession(sessionId)

    suspend fun messagesForSession(sessionId: Long): List<StoredMessage> =
        coreDao.messagesForSession(sessionId)

    override suspend fun persistConversation(sessionId: Long, messages: List<ChatMessage>) {
        coreDao.deleteMessagesForSession(sessionId)
        val now = clock()
        messages.forEachIndexed { index, msg ->
            coreDao.insertMessage(
                StoredMessage(
                    sessionId = sessionId,
                    role = msg.role,
                    content = msg.content,
                    createdAt = now + index,
                ),
            )
        }
        coreDao.sessionById(sessionId)?.let {
            coreDao.updateSession(it.copy(updatedAt = now))
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*MemoryRepositoryTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/memory/ConversationPersister.kt app/src/main/java/com/mobilegem/gemma/memory/MemoryRepository.kt app/src/test/java/com/mobilegem/gemma/memory/MemoryRepositoryTest.kt
git commit -m "feat: add memory repository with conversation persistence"
```

---

## Task 6: Skill repository

Wraps `SkillDao` with create/toggle/delete operations.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/memory/SkillRepository.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/memory/SkillRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/memory/SkillRepositoryTest.kt`:

```kotlin
package com.mobilegem.gemma.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.memory.db.MemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkillRepositoryTest {

    private lateinit var db: MemoryDatabase
    private lateinit var repo: SkillRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = SkillRepository(db.skillDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun createdSkillIsEnabledByDefaultAndAppearsForProject() = runTest {
        val id = repo.createSkill(
            projectId = 5, name = "Be terse", description = "", instructions = "Answer briefly.",
        )
        val enabled = repo.enabledForProject(5)
        assertThat(enabled.map { it.id }).containsExactly(id)
    }

    @Test
    fun disablingASkillExcludesItFromEnabledList() = runTest {
        repo.createSkill(5, "S", "", "do x")
        val skill = repo.enabledForProject(5).single()
        repo.setEnabled(skill, false)
        assertThat(repo.enabledForProject(5)).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SkillRepositoryTest"`
Expected: FAIL — `SkillRepository` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/memory/SkillRepository.kt`:

```kotlin
package com.mobilegem.gemma.memory

import com.mobilegem.gemma.memory.db.Skill
import com.mobilegem.gemma.memory.db.SkillDao
import kotlinx.coroutines.flow.Flow

class SkillRepository(private val skillDao: SkillDao) {

    fun observeForProjectScope(projectId: Long): Flow<List<Skill>> =
        skillDao.observeForProjectScope(projectId)

    suspend fun enabledForProject(projectId: Long): List<Skill> =
        skillDao.enabledForProject(projectId)

    suspend fun createSkill(
        projectId: Long?, name: String, description: String, instructions: String,
    ): Long = skillDao.insert(
        Skill(
            projectId = projectId, name = name, description = description,
            instructions = instructions, enabled = true,
        ),
    )

    suspend fun updateSkill(skill: Skill) = skillDao.update(skill)

    suspend fun setEnabled(skill: Skill, enabled: Boolean) =
        skillDao.update(skill.copy(enabled = enabled))

    suspend fun deleteSkill(skillId: Long) = skillDao.delete(skillId)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SkillRepositoryTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/memory/SkillRepository.kt app/src/test/java/com/mobilegem/gemma/memory/SkillRepositoryTest.kt
git commit -m "feat: add skill repository"
```

---

## Task 7: Embedder abstraction

Defines the `Embedder` interface (lives next to Plan 1's `TextGenerator`) and a `FakeEmbedder` for tests. The real model-backed implementation is Task 8.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/inference/Embedder.kt`
- Create: `app/src/test/java/com/mobilegem/gemma/inference/FakeEmbedder.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/inference/FakeEmbedderTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/inference/FakeEmbedderTest.kt`:

```kotlin
package com.mobilegem.gemma.inference

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeEmbedderTest {

    @Test
    fun returnsConfiguredVectorForKnownTextAndRecordsCalls() = runTest {
        val embedder = FakeEmbedder(
            mapOf("hello" to floatArrayOf(1f, 0f), "world" to floatArrayOf(0f, 1f)),
        )
        assertThat(embedder.embed("hello").toList()).containsExactly(1f, 0f).inOrder()
        assertThat(embedder.embeddedTexts).containsExactly("hello")
    }

    @Test
    fun returnsZeroVectorForUnknownText() = runTest {
        val embedder = FakeEmbedder(mapOf("a" to floatArrayOf(1f, 1f)))
        assertThat(embedder.embed("unknown").toList()).containsExactly(0f, 0f).inOrder()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*FakeEmbedderTest"`
Expected: FAIL — `Embedder` / `FakeEmbedder` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/inference/Embedder.kt`:

```kotlin
package com.mobilegem.gemma.inference

/** Produces a fixed-length embedding vector for a piece of text. */
interface Embedder {
    suspend fun embed(text: String): FloatArray

    /** Length of vectors returned by [embed]; used to size zero vectors on failure. */
    val dimension: Int
}
```

`app/src/test/java/com/mobilegem/gemma/inference/FakeEmbedder.kt`:

```kotlin
package com.mobilegem.gemma.inference

class FakeEmbedder(
    private val vectors: Map<String, FloatArray>,
) : Embedder {

    override val dimension: Int = vectors.values.firstOrNull()?.size ?: 2

    val embeddedTexts = mutableListOf<String>()

    override suspend fun embed(text: String): FloatArray {
        embeddedTexts += text
        return vectors[text] ?: FloatArray(dimension)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*FakeEmbedderTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/inference/Embedder.kt app/src/test/java/com/mobilegem/gemma/inference/FakeEmbedder.kt app/src/test/java/com/mobilegem/gemma/inference/FakeEmbedderTest.kt
git commit -m "feat: add Embedder abstraction with test fake"
```

---

## Task 8: LiteRT-LM embedder

The real `Embedder`, producing vectors from the same on-device Gemma model used for chat. Like `LiteRtLmTextGenerator`, this touches the native API and has no JVM unit test; it is verified on-device in Task 18.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/inference/LiteRtLmEmbedder.kt`

- [ ] **Step 1: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/inference/LiteRtLmEmbedder.kt`:

```kotlin
package com.mobilegem.gemma.inference

import com.google.ai.edge.litertlm.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Produces embeddings from a LiteRT-LM [Engine].
 *
 * IMPORTANT — verify against the resolved `litertlm-android` artifact:
 * the embedding entry point is expected to be `Engine.embed(text): FloatArray`
 * (or an equivalent on a session). If the API differs, fix THIS FILE ONLY —
 * every downstream component (`MemoryRetriever`, `ContextAugmenter`,
 * `SelfLearningExtractor`) depends only on the [Embedder] interface.
 *
 * If the chat `.litertlm` model does not expose embeddings at all, the
 * fallback is to load a dedicated EmbeddingGemma `.litertlm` model into a
 * second Engine here, or to swap in an FTS5-based retriever. The interface
 * boundary keeps that change contained.
 */
class LiteRtLmEmbedder(
    private val engine: Engine,
    override val dimension: Int,
) : Embedder {

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        engine.embed(text)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. If `Engine.embed` does not resolve, consult the resolved `litertlm-android` artifact's API and correct this file only (see the class doc for the fallback path).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/inference/LiteRtLmEmbedder.kt
git commit -m "feat: add LiteRT-LM-backed embedder"
```

---

## Task 9: Long-term memory repository

Stores embedded memory entries and loads them for a project scope.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/memory/LongTermMemoryRepository.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/memory/LongTermMemoryRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/memory/LongTermMemoryRepositoryTest.kt`:

```kotlin
package com.mobilegem.gemma.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.memory.db.MemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LongTermMemoryRepositoryTest {

    private lateinit var db: MemoryDatabase
    private lateinit var repo: LongTermMemoryRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = LongTermMemoryRepository(db.memoryDao()) { 500L }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun storedEntryIsReturnedForItsProjectScope() = runTest {
        repo.store(
            projectId = 3, content = "User prefers metric units",
            embedding = floatArrayOf(0.1f, 0.2f), sourceSessionId = 7,
        )
        val entries = repo.entriesForProjectScope(3)
        assertThat(entries).hasSize(1)
        assertThat(entries.single().content).isEqualTo("User prefers metric units")
        assertThat(entries.single().embedding).isEqualTo(floatArrayOf(0.1f, 0.2f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*LongTermMemoryRepositoryTest"`
Expected: FAIL — `LongTermMemoryRepository` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/memory/LongTermMemoryRepository.kt`:

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

    suspend fun store(
        projectId: Long?, content: String, embedding: FloatArray, sourceSessionId: Long?,
    ): Long = memoryDao.insert(
        MemoryEntry(
            projectId = projectId,
            content = content,
            embedding = embedding,
            sourceSessionId = sourceSessionId,
            createdAt = clock(),
        ),
    )

    suspend fun delete(entryId: Long) = memoryDao.delete(entryId)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*LongTermMemoryRepositoryTest"`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/memory/LongTermMemoryRepository.kt app/src/test/java/com/mobilegem/gemma/memory/LongTermMemoryRepositoryTest.kt
git commit -m "feat: add long-term memory repository"
```

---

## Task 10: Memory retriever

Embeds a query and ranks a project's memory entries by cosine similarity.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/memory/MemoryRetriever.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/memory/MemoryRetrieverTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/memory/MemoryRetrieverTest.kt`:

```kotlin
package com.mobilegem.gemma.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeEmbedder
import com.mobilegem.gemma.memory.db.MemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryRetrieverTest {

    private lateinit var db: MemoryDatabase
    private lateinit var ltm: LongTermMemoryRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        ltm = LongTermMemoryRepository(db.memoryDao()) { 1L }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun returnsEntriesRankedByCosineSimilarityToTheQuery() = runTest {
        ltm.store(1, "about cats", floatArrayOf(1f, 0f), null)
        ltm.store(1, "about dogs", floatArrayOf(0f, 1f), null)

        val embedder = FakeEmbedder(mapOf("feline question" to floatArrayOf(0.9f, 0.1f)))
        val retriever = MemoryRetriever(embedder, ltm)

        val results = retriever.retrieve(projectId = 1, query = "feline question", topK = 2)
        assertThat(results.map { it.content })
            .containsExactly("about cats", "about dogs").inOrder()
    }

    @Test
    fun topKLimitsTheNumberOfResults() = runTest {
        ltm.store(1, "a", floatArrayOf(1f, 0f), null)
        ltm.store(1, "b", floatArrayOf(0.5f, 0.5f), null)
        ltm.store(1, "c", floatArrayOf(0f, 1f), null)

        val embedder = FakeEmbedder(mapOf("q" to floatArrayOf(1f, 0f)))
        val retriever = MemoryRetriever(embedder, ltm)

        assertThat(retriever.retrieve(1, "q", topK = 1)).hasSize(1)
    }

    @Test
    fun returnsEmptyWhenNoEntriesExist() = runTest {
        val embedder = FakeEmbedder(mapOf("q" to floatArrayOf(1f)))
        val retriever = MemoryRetriever(embedder, ltm)
        assertThat(retriever.retrieve(99, "q", topK = 5)).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*MemoryRetrieverTest"`
Expected: FAIL — `MemoryRetriever` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/memory/MemoryRetriever.kt`:

```kotlin
package com.mobilegem.gemma.memory

import com.mobilegem.gemma.inference.Embedder
import com.mobilegem.gemma.memory.db.MemoryEntry

class MemoryRetriever(
    private val embedder: Embedder,
    private val ltm: LongTermMemoryRepository,
) {

    suspend fun retrieve(projectId: Long, query: String, topK: Int): List<MemoryEntry> {
        val candidates = ltm.entriesForProjectScope(projectId)
        if (candidates.isEmpty()) return emptyList()

        val queryVec = embedder.embed(query)
        return candidates
            .mapNotNull { entry ->
                if (entry.embedding.size != queryVec.size) null
                else entry to VectorMath.cosineSimilarity(queryVec, entry.embedding)
            }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*MemoryRetrieverTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/memory/MemoryRetriever.kt app/src/test/java/com/mobilegem/gemma/memory/MemoryRetrieverTest.kt
git commit -m "feat: add memory retriever with similarity ranking"
```

---

## Task 11: Fold all system messages in the prompt builder (Plan 1 modification)

The context augmenter (Task 12) adds a second system message. Plan 1's `GemmaPromptBuilder` only used the *first* system message. This task makes it concatenate **all** system messages, so injected memory/skill context is never dropped.

**Files:**
- Modify: `app/src/main/java/com/mobilegem/gemma/server/GemmaPromptBuilder.kt`
- Modify: `app/src/test/java/com/mobilegem/gemma/server/GemmaPromptBuilderTest.kt`

- [ ] **Step 1: Add a failing test for multiple system messages**

Add this test to `GemmaPromptBuilderTest.kt`:

```kotlin
    @Test
    fun concatenatesAllSystemMessagesIntoFirstUserTurn() {
        val prompt = GemmaPromptBuilder.build(
            listOf(
                ChatMessage("system", "Skill: be terse."),
                ChatMessage("system", "Memory: user likes Kotlin."),
                ChatMessage("user", "Hi"),
            )
        )
        assertThat(prompt).isEqualTo(
            "<start_of_turn>user\n" +
                "Skill: be terse.\n\nMemory: user likes Kotlin.\n\nHi<end_of_turn>\n" +
                "<start_of_turn>model\n"
        )
    }
```

- [ ] **Step 2: Run the prompt builder tests to verify the new one fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*GemmaPromptBuilderTest"`
Expected: FAIL on `concatenatesAllSystemMessagesIntoFirstUserTurn` — only the first system message is currently used.

- [ ] **Step 3: Update the implementation**

Replace the body of `GemmaPromptBuilder.build` in `GemmaPromptBuilder.kt` with:

```kotlin
    fun build(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        val systemText = messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }
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
```

- [ ] **Step 4: Run the prompt builder tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*GemmaPromptBuilderTest"`
Expected: PASS (3 tests — the two original tests still pass; the single-system-message test is unaffected because joining one element produces that element).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/server/GemmaPromptBuilder.kt app/src/test/java/com/mobilegem/gemma/server/GemmaPromptBuilderTest.kt
git commit -m "feat: fold all system messages into the Gemma prompt"
```

---

## Task 12: Context augmenter

Builds an injected system message for a chat request: the project's enabled Skills plus the most relevant Long-Term Memory entries.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/server/ContextAugmenter.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/server/MemoryContextAugmenterTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/server/MemoryContextAugmenterTest.kt`:

```kotlin
package com.mobilegem.gemma.server

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeEmbedder
import com.mobilegem.gemma.memory.LongTermMemoryRepository
import com.mobilegem.gemma.memory.MemoryRetriever
import com.mobilegem.gemma.memory.SkillRepository
import com.mobilegem.gemma.memory.db.MemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryContextAugmenterTest {

    private lateinit var db: MemoryDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun augmenter(embedder: FakeEmbedder): MemoryContextAugmenter {
        val ltm = LongTermMemoryRepository(db.memoryDao()) { 1L }
        return MemoryContextAugmenter(
            skillRepository = SkillRepository(db.skillDao()),
            retriever = MemoryRetriever(embedder, ltm),
            topK = 3,
        )
    }

    @Test
    fun includesEnabledSkillsAndRetrievedMemory() = runTest {
        db.skillDao().insert(
            com.mobilegem.gemma.memory.db.Skill(
                projectId = 1, name = "Terse", instructions = "Answer briefly.", enabled = true,
            ),
        )
        db.memoryDao().insert(
            com.mobilegem.gemma.memory.db.MemoryEntry(
                projectId = 1, content = "User is a Kotlin developer.",
                embedding = floatArrayOf(1f, 0f), sourceSessionId = null, createdAt = 1,
            ),
        )
        val embedder = FakeEmbedder(mapOf("tell me about kotlin" to floatArrayOf(1f, 0f)))

        val context = augmenter(embedder).systemContextFor(1, "tell me about kotlin")

        assertThat(context).isNotNull()
        assertThat(context!!).contains("Answer briefly.")
        assertThat(context).contains("User is a Kotlin developer.")
    }

    @Test
    fun returnsNullWhenNoSkillsOrMemoryExist() = runTest {
        val embedder = FakeEmbedder(mapOf("q" to floatArrayOf(1f, 0f)))
        assertThat(augmenter(embedder).systemContextFor(1, "q")).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*MemoryContextAugmenterTest"`
Expected: FAIL — `ContextAugmenter` / `MemoryContextAugmenter` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/server/ContextAugmenter.kt`:

```kotlin
package com.mobilegem.gemma.server

import com.mobilegem.gemma.memory.MemoryRetriever
import com.mobilegem.gemma.memory.SkillRepository

/** Produces an optional system-prompt block for a chat request. */
interface ContextAugmenter {
    /** @return injected system text, or null when there is nothing to add. */
    suspend fun systemContextFor(projectId: Long, latestUserMessage: String): String?
}

class MemoryContextAugmenter(
    private val skillRepository: SkillRepository,
    private val retriever: MemoryRetriever,
    private val topK: Int = 4,
) : ContextAugmenter {

    override suspend fun systemContextFor(
        projectId: Long, latestUserMessage: String,
    ): String? {
        val skills = skillRepository.enabledForProject(projectId)
        val memories = if (latestUserMessage.isBlank()) {
            emptyList()
        } else {
            retriever.retrieve(projectId, latestUserMessage, topK)
        }
        if (skills.isEmpty() && memories.isEmpty()) return null

        val sb = StringBuilder()
        if (skills.isNotEmpty()) {
            sb.append("Active skills:\n")
            skills.forEach { sb.append("- ").append(it.name).append(": ")
                .append(it.instructions).append('\n') }
        }
        if (memories.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("Relevant long-term memory:\n")
            memories.forEach { sb.append("- ").append(it.content).append('\n') }
        }
        return sb.toString().trimEnd()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*MemoryContextAugmenterTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/server/ContextAugmenter.kt app/src/test/java/com/mobilegem/gemma/server/MemoryContextAugmenterTest.kt
git commit -m "feat: add context augmenter for skills and memory injection"
```

---

## Task 13: Self-learning extractor

Sends a finished session's transcript to the on-device model, parses the durable facts it returns, embeds each, and stores them as Long-Term Memory. Two units: `FactListParser` (pure) and `SelfLearningExtractor`.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/memory/FactListParser.kt`
- Create: `app/src/main/java/com/mobilegem/gemma/memory/SelfLearningExtractor.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/memory/FactListParserTest.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/memory/SelfLearningExtractorTest.kt`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/mobilegem/gemma/memory/FactListParserTest.kt`:

```kotlin
package com.mobilegem.gemma.memory

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FactListParserTest {

    @Test
    fun parsesAJsonArrayEmbeddedInProse() {
        val raw = """
            Here are the facts I extracted:
            ["User prefers metric units", "User is building an Android app"]
            Hope that helps!
        """.trimIndent()
        assertThat(FactListParser.parse(raw))
            .containsExactly("User prefers metric units", "User is building an Android app")
    }

    @Test
    fun returnsEmptyListWhenNoArrayPresent() {
        assertThat(FactListParser.parse("No durable facts found.")).isEmpty()
    }

    @Test
    fun ignoresBlankFacts() {
        assertThat(FactListParser.parse("""["real fact", "  ", ""]"""))
            .containsExactly("real fact")
    }
}
```

`app/src/test/java/com/mobilegem/gemma/memory/SelfLearningExtractorTest.kt`:

```kotlin
package com.mobilegem.gemma.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeEmbedder
import com.mobilegem.gemma.inference.FakeTextGenerator
import com.mobilegem.gemma.memory.db.MemoryDatabase
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelfLearningExtractorTest {

    private lateinit var db: MemoryDatabase
    private lateinit var ltm: LongTermMemoryRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        ltm = LongTermMemoryRepository(db.memoryDao()) { 1L }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun extractsFactsFromTranscriptAndStoresThemEmbedded() = runTest {
        val modelOutput = """["User prefers Kotlin", "User lives in Berlin"]"""
        val generator = FakeTextGenerator(tokens = listOf(modelOutput))
        val embedder = FakeEmbedder(
            mapOf(
                "User prefers Kotlin" to floatArrayOf(1f, 0f),
                "User lives in Berlin" to floatArrayOf(0f, 1f),
            ),
        )
        val extractor = SelfLearningExtractor(generator, embedder, ltm)

        val stored = extractor.extractAndStore(
            projectId = 2,
            sessionId = 5,
            transcript = listOf(
                ChatMessage("user", "I love Kotlin and I live in Berlin"),
                ChatMessage("assistant", "Noted!"),
            ),
        )

        assertThat(stored.map { it.content })
            .containsExactly("User prefers Kotlin", "User lives in Berlin")
        val persisted = ltm.entriesForProjectScope(2)
        assertThat(persisted.map { it.content })
            .containsExactly("User prefers Kotlin", "User lives in Berlin")
        assertThat(persisted.first { it.content == "User prefers Kotlin" }.embedding)
            .isEqualTo(floatArrayOf(1f, 0f))
        assertThat(persisted.all { it.sourceSessionId == 5L }).isTrue()
    }

    @Test
    fun storesNothingWhenModelReturnsNoFacts() = runTest {
        val extractor = SelfLearningExtractor(
            FakeTextGenerator(tokens = listOf("No durable facts.")),
            FakeEmbedder(emptyMap()),
            ltm,
        )
        val stored = extractor.extractAndStore(2, 5, listOf(ChatMessage("user", "hi")))
        assertThat(stored).isEmpty()
        assertThat(ltm.entriesForProjectScope(2)).isEmpty()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*FactListParserTest" --tests "*SelfLearningExtractorTest"`
Expected: FAIL — `FactListParser` / `SelfLearningExtractor` unresolved.

- [ ] **Step 3: Write the implementations**

`app/src/main/java/com/mobilegem/gemma/memory/FactListParser.kt`:

```kotlin
package com.mobilegem.gemma.memory

import kotlinx.serialization.json.Json

object FactListParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Extracts the first top-level JSON string array from [raw]; tolerant of surrounding prose. */
    fun parse(raw: String): List<String> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val slice = raw.substring(start, end + 1)
        return runCatching { json.decodeFromString<List<String>>(slice) }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
```

`app/src/main/java/com/mobilegem/gemma/memory/SelfLearningExtractor.kt`:

```kotlin
package com.mobilegem.gemma.memory

import com.mobilegem.gemma.inference.Embedder
import com.mobilegem.gemma.inference.TextGenerator
import com.mobilegem.gemma.memory.db.MemoryEntry
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.toList

class SelfLearningExtractor(
    private val generator: TextGenerator,
    private val embedder: Embedder,
    private val ltm: LongTermMemoryRepository,
) {

    /**
     * Extracts durable facts from [transcript] using the on-device model,
     * embeds and stores each as a memory entry scoped to [projectId].
     * @return the memory entries that were stored.
     */
    suspend fun extractAndStore(
        projectId: Long, sessionId: Long, transcript: List<ChatMessage>,
    ): List<MemoryEntry> {
        val prompt = buildExtractionPrompt(transcript)
        val output = generator.generate(prompt, temperature = 0.2f).toList().joinToString("")
        val facts = FactListParser.parse(output)

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

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*FactListParserTest" --tests "*SelfLearningExtractorTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/memory/FactListParser.kt app/src/main/java/com/mobilegem/gemma/memory/SelfLearningExtractor.kt app/src/test/java/com/mobilegem/gemma/memory/FactListParserTest.kt app/src/test/java/com/mobilegem/gemma/memory/SelfLearningExtractorTest.kt
git commit -m "feat: add self-learning extractor for durable facts"
```

---

## Task 14: Active session holder

A small shared holder for the currently-open project/session, so the stateless `ChatCompletionHandler` knows which session a WebView chat request belongs to.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/memory/ActiveSessionHolder.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/memory/ActiveSessionHolderTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/memory/ActiveSessionHolderTest.kt`:

```kotlin
package com.mobilegem.gemma.memory

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ActiveSessionHolderTest {

    @Test
    fun startsWithNoActiveSession() = runTest {
        assertThat(ActiveSessionHolder().active.first()).isNull()
    }

    @Test
    fun setUpdatesCurrentAndFlow() = runTest {
        val holder = ActiveSessionHolder()
        holder.set(projectId = 3, sessionId = 9)
        assertThat(holder.current()).isEqualTo(ActiveSession(3, 9))
        assertThat(holder.active.first()).isEqualTo(ActiveSession(3, 9))
    }

    @Test
    fun clearResetsToNull() = runTest {
        val holder = ActiveSessionHolder()
        holder.set(1, 1)
        holder.clear()
        assertThat(holder.current()).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ActiveSessionHolderTest"`
Expected: FAIL — `ActiveSessionHolder` / `ActiveSession` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/memory/ActiveSessionHolder.kt`:

```kotlin
package com.mobilegem.gemma.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveSession(val projectId: Long, val sessionId: Long)

class ActiveSessionHolder {
    private val _active = MutableStateFlow<ActiveSession?>(null)
    val active: StateFlow<ActiveSession?> = _active.asStateFlow()

    fun current(): ActiveSession? = _active.value

    fun set(projectId: Long, sessionId: Long) {
        _active.value = ActiveSession(projectId, sessionId)
    }

    fun clear() {
        _active.value = null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*ActiveSessionHolderTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/memory/ActiveSessionHolder.kt app/src/test/java/com/mobilegem/gemma/memory/ActiveSessionHolderTest.kt
git commit -m "feat: add active session holder"
```

---

## Task 15: Integrate augmentation + persistence into the chat handler (Plan 1 modification)

Modifies Plan 1's `ChatCompletionHandler` so that — when an active session exists — it injects skill/memory context before generation and persists the conversation after. All new dependencies are optional (nullable), so Plan 1's existing `ChatCompletionHandler(generator)` constructor and tests still pass.

**Files:**
- Modify: `app/src/main/java/com/mobilegem/gemma/server/ChatCompletionHandler.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/server/ChatCompletionHandlerMemoryTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/server/ChatCompletionHandlerMemoryTest.kt`:

```kotlin
package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeTextGenerator
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.ConversationPersister
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChatCompletionHandlerMemoryTest {

    private class RecordingPersister : ConversationPersister {
        var lastSessionId: Long? = null
        var lastMessages: List<ChatMessage>? = null
        override suspend fun persistConversation(sessionId: Long, messages: List<ChatMessage>) {
            lastSessionId = sessionId
            lastMessages = messages
        }
    }

    private fun augmenterReturning(text: String?): ContextAugmenter =
        object : ContextAugmenter {
            override suspend fun systemContextFor(projectId: Long, latestUserMessage: String) = text
        }

    @Test
    fun injectsAugmentedContextAndPersistsConversationWhenSessionActive() = runTest {
        val generator = FakeTextGenerator(tokens = listOf("answer"))
        val persister = RecordingPersister()
        val holder = ActiveSessionHolder().apply { set(projectId = 1, sessionId = 42) }
        val handler = ChatCompletionHandler(
            generator = generator,
            augmenter = augmenterReturning("Relevant long-term memory:\n- User likes tea"),
            persister = persister,
            activeSession = holder,
        )
        val request = ChatCompletionRequest(
            messages = listOf(ChatMessage("user", "what do I like?")),
            stream = true,
        )

        handler.streamSse(request).toList()

        // augmented context reached the generator's prompt
        assertThat(generator.lastPrompt).contains("User likes tea")
        // conversation persisted: original user message + assistant reply
        assertThat(persister.lastSessionId).isEqualTo(42)
        assertThat(persister.lastMessages!!.map { it.role })
            .containsExactly("user", "assistant").inOrder()
        assertThat(persister.lastMessages!!.last().content).isEqualTo("answer")
    }

    @Test
    fun behavesLikePlainHandlerWhenNoSessionActive() = runTest {
        val generator = FakeTextGenerator(tokens = listOf("hi"))
        val persister = RecordingPersister()
        val handler = ChatCompletionHandler(
            generator = generator,
            augmenter = augmenterReturning("should not be used"),
            persister = persister,
            activeSession = ActiveSessionHolder(), // no active session
        )
        handler.streamSse(
            ChatCompletionRequest(messages = listOf(ChatMessage("user", "q")), stream = true),
        ).toList()

        assertThat(generator.lastPrompt).doesNotContain("should not be used")
        assertThat(persister.lastSessionId).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ChatCompletionHandlerMemoryTest"`
Expected: FAIL — `ChatCompletionHandler` has no `augmenter` / `persister` / `activeSession` parameters.

- [ ] **Step 3: Rewrite the handler**

Replace the full contents of `app/src/main/java/com/mobilegem/gemma/server/ChatCompletionHandler.kt` with:

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
) {

    private val json = Json { encodeDefaults = true }

    /** Emits SSE payload strings, each already terminated with a blank line. */
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

    suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        val temp = request.temperature ?: 0.8f
        val messages = augmentedMessages(request.messages)
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

    /** Prepends a skills/memory system message when a session is active. */
    private suspend fun augmentedMessages(original: List<ChatMessage>): List<ChatMessage> {
        val session = activeSession?.current() ?: return original
        val aug = augmenter ?: return original
        val latestUser = original.lastOrNull { it.role == "user" }?.content ?: ""
        val context = aug.systemContextFor(session.projectId, latestUser) ?: return original
        return listOf(ChatMessage("system", context)) + original
    }

    /** Replaces the active session's stored transcript with the latest exchange. */
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

- [ ] **Step 4: Run the full server test suite to verify nothing regressed**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mobilegem.gemma.server.*"`
Expected: PASS — `ChatCompletionHandlerMemoryTest` (2), the original `ChatCompletionHandlerTest` (2), `GemmaPromptBuilderTest` (3), `LocalLlmServerTest` (2), `OpenAiDtoTest` (2), `MemoryContextAugmenterTest` (2).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/server/ChatCompletionHandler.kt app/src/test/java/com/mobilegem/gemma/server/ChatCompletionHandlerMemoryTest.kt
git commit -m "feat: inject memory context and persist conversations in chat handler"
```

---

## Task 16: Wire memory dependencies into the inference controller and app container (Plan 1 modification)

Updates `InferenceController` to build the handler with the augmenter/persister/active-session, and expands `AppContainer` to construct the database, repositories, retriever, augmenter, extractor, and holder.

**Files:**
- Modify: `app/src/main/java/com/mobilegem/gemma/inference/InferenceController.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/AppContainer.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/inference/InferenceControllerMemoryTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/inference/InferenceControllerMemoryTest.kt`:

```kotlin
package com.mobilegem.gemma.inference

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.settings.InferenceBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InferenceControllerMemoryTest {

    @Test
    fun loadModelStillWorksWithMemoryHooksWired() = runTest {
        val controller = InferenceController(
            generatorFactory = { _, _ -> FakeTextGenerator(listOf("hi")) },
            activeSession = ActiveSessionHolder(),
            augmenter = null,
            persister = null,
        )
        controller.loadModel("/data/models/m.litertlm", InferenceBackend.CPU)
        assertThat(controller.state.first().serverRunning).isTrue()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*InferenceControllerMemoryTest"`
Expected: FAIL — `InferenceController` has no `activeSession` / `augmenter` / `persister` parameters.

- [ ] **Step 3: Update `InferenceController`**

Replace the full contents of `app/src/main/java/com/mobilegem/gemma/inference/InferenceController.kt` with:

```kotlin
package com.mobilegem.gemma.inference

import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.ConversationPersister
import com.mobilegem.gemma.server.ChatCompletionHandler
import com.mobilegem.gemma.server.ContextAugmenter
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
    private val activeSession: ActiveSessionHolder? = null,
    private val augmenter: ContextAugmenter? = null,
    private val persister: ConversationPersister? = null,
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
        val handler = ChatCompletionHandler(
            generator = generator,
            augmenter = augmenter,
            persister = persister,
            activeSession = activeSession,
        )
        server.start(handler, modelId = name)
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

- [ ] **Step 4: Update `AppContainer`**

Replace the full contents of `app/src/main/java/com/mobilegem/gemma/AppContainer.kt` with:

```kotlin
package com.mobilegem.gemma

import android.content.Context
import com.mobilegem.gemma.inference.InferenceController
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.LongTermMemoryRepository
import com.mobilegem.gemma.memory.MemoryRepository
import com.mobilegem.gemma.memory.MemoryRetriever
import com.mobilegem.gemma.memory.SelfLearningExtractor
import com.mobilegem.gemma.memory.SkillRepository
import com.mobilegem.gemma.memory.db.MemoryDatabase
import com.mobilegem.gemma.model.ModelFileManager
import com.mobilegem.gemma.server.MemoryContextAugmenter
import com.mobilegem.gemma.settings.SettingsRepository
import java.io.File

class AppContainer(context: Context) {

    val settingsRepository = SettingsRepository(context)
    val modelFileManager = ModelFileManager(File(context.filesDir, "models"))

    private val database = MemoryDatabase.create(context)
    val memoryRepository = MemoryRepository(database.coreDao())
    val skillRepository = SkillRepository(database.skillDao())
    val longTermMemoryRepository = LongTermMemoryRepository(database.memoryDao())
    val activeSessionHolder = ActiveSessionHolder()

    /**
     * Embedder / extractor are created lazily after a model is loaded, because
     * they need the live LiteRT-LM engine. For this plan the augmenter is wired
     * with a retriever whose embedder is supplied once a model is active; until
     * then memory retrieval simply returns nothing. See InferenceController.
     *
     * The augmenter below uses the retriever; the retriever's embedder is the
     * one created alongside the generator. To keep wiring simple, the embedder
     * is provided through [inferenceController]'s generator factory in the
     * Memory UI task. For now the augmenter is built with a no-op embedder that
     * yields empty retrieval until Task 18 wires the real embedder.
     */
    val inferenceController = InferenceController(
        activeSession = activeSessionHolder,
        augmenter = MemoryContextAugmenter(
            skillRepository = skillRepository,
            retriever = MemoryRetriever(
                embedder = com.mobilegem.gemma.inference.NoOpEmbedder,
                ltm = longTermMemoryRepository,
            ),
        ),
        persister = memoryRepository,
    )
}
```

> The `NoOpEmbedder` referenced above is created in Task 17, Step 0 below. It is a safe default (empty retrieval) so the app runs before the real model-backed embedder is wired. Task 18 replaces it with `LiteRtLmEmbedder`.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*InferenceControllerMemoryTest" --tests "*InferenceControllerTest"`
Expected: `InferenceControllerMemoryTest` PASS (1); `InferenceControllerTest` (from Plan 1) still PASS (2) — its constructor call uses only `generatorFactory`, which is unchanged.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/inference/InferenceController.kt app/src/main/java/com/mobilegem/gemma/AppContainer.kt app/src/test/java/com/mobilegem/gemma/inference/InferenceControllerMemoryTest.kt
git commit -m "feat: wire memory subsystem into inference controller and container"
```

---

## Task 17: Memory ViewModel

State and actions for the Memory screen: list/create projects and sessions, manage skills, browse and delete memory entries, open a session in chat, and run self-learning extraction on a session.

**Files:**
- Create: `app/src/main/java/com/mobilegem/gemma/inference/NoOpEmbedder.kt`
- Create: `app/src/main/java/com/mobilegem/gemma/ui/memory/MemoryViewModel.kt`
- Test: `app/src/test/java/com/mobilegem/gemma/ui/memory/MemoryViewModelTest.kt`

- [ ] **Step 0: Create the NoOpEmbedder referenced by AppContainer**

`app/src/main/java/com/mobilegem/gemma/inference/NoOpEmbedder.kt`:

```kotlin
package com.mobilegem.gemma.inference

/** Placeholder embedder used before a model-backed embedder is available. */
object NoOpEmbedder : Embedder {
    override val dimension: Int = 1
    override suspend fun embed(text: String): FloatArray = FloatArray(dimension)
}
```

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mobilegem/gemma/ui/memory/MemoryViewModelTest.kt`:

```kotlin
package com.mobilegem.gemma.ui.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.LongTermMemoryRepository
import com.mobilegem.gemma.memory.MemoryRepository
import com.mobilegem.gemma.memory.SkillRepository
import com.mobilegem.gemma.memory.db.MemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryViewModelTest {

    private lateinit var db: MemoryDatabase
    private lateinit var holder: ActiveSessionHolder
    private lateinit var vm: MemoryViewModel

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        holder = ActiveSessionHolder()
        vm = MemoryViewModel(
            memoryRepository = MemoryRepository(db.coreDao()),
            skillRepository = SkillRepository(db.skillDao()),
            longTermMemoryRepository = LongTermMemoryRepository(db.memoryDao()),
            activeSessionHolder = holder,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun createProjectThenSelectItShowsItsSessions() = runTest {
        vm.createProject("Research")
        val project = vm.uiState.first().projects.single()
        vm.selectProject(project.id)
        vm.createSession("Kickoff")

        val state = vm.uiState.first()
        assertThat(state.selectedProjectId).isEqualTo(project.id)
        assertThat(state.sessions.map { it.title }).containsExactly("Kickoff")
    }

    @Test
    fun openSessionSetsTheActiveSessionHolder() = runTest {
        vm.createProject("P")
        val project = vm.uiState.first().projects.single()
        vm.selectProject(project.id)
        vm.createSession("S")
        val session = vm.uiState.first().sessions.single()

        vm.openSession(session.id)

        assertThat(holder.current()?.projectId).isEqualTo(project.id)
        assertThat(holder.current()?.sessionId).isEqualTo(session.id)
    }

    @Test
    fun addSkillMakesItVisibleForTheProject() = runTest {
        vm.createProject("P")
        val project = vm.uiState.first().projects.single()
        vm.selectProject(project.id)
        vm.addSkill("Be terse", "Answer briefly.")

        assertThat(vm.uiState.first().skills.map { it.name }).containsExactly("Be terse")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*MemoryViewModelTest"`
Expected: FAIL — `MemoryViewModel` unresolved.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/mobilegem/gemma/ui/memory/MemoryViewModel.kt`:

```kotlin
package com.mobilegem.gemma.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.LongTermMemoryRepository
import com.mobilegem.gemma.memory.MemoryRepository
import com.mobilegem.gemma.memory.SelfLearningExtractor
import com.mobilegem.gemma.memory.SkillRepository
import com.mobilegem.gemma.memory.db.MemoryEntry
import com.mobilegem.gemma.memory.db.Project
import com.mobilegem.gemma.memory.db.Session
import com.mobilegem.gemma.memory.db.Skill
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class MemoryUiState(
    val projects: List<Project> = emptyList(),
    val selectedProjectId: Long? = null,
    val sessions: List<Session> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val memories: List<MemoryEntry> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

class MemoryViewModel(
    private val memoryRepository: MemoryRepository,
    private val skillRepository: SkillRepository,
    private val longTermMemoryRepository: LongTermMemoryRepository,
    private val activeSessionHolder: ActiveSessionHolder,
    /** Supplied once a model is loaded; null disables self-learning. */
    private val extractorProvider: () -> SelfLearningExtractor? = { null },
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refreshProjects() }
    }

    private suspend fun refreshProjects() {
        _uiState.value = _uiState.value.copy(
            projects = memoryRepository.observeProjects().first(),
        )
    }

    private suspend fun refreshSelectedProject() {
        val projectId = _uiState.value.selectedProjectId ?: return
        _uiState.value = _uiState.value.copy(
            sessions = memoryRepository.observeSessions(projectId).first(),
            skills = skillRepository.observeForProjectScope(projectId).first(),
            memories = longTermMemoryRepository.observeForProjectScope(projectId).first(),
        )
    }

    fun createProject(name: String) = viewModelScope.launch {
        memoryRepository.createProject(name, "")
        refreshProjects()
    }

    fun selectProject(projectId: Long) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(selectedProjectId = projectId)
        refreshSelectedProject()
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedProjectId = null, sessions = emptyList(),
            skills = emptyList(), memories = emptyList(),
        )
    }

    fun createSession(title: String) = viewModelScope.launch {
        val projectId = _uiState.value.selectedProjectId ?: return@launch
        memoryRepository.createSession(projectId, title)
        refreshSelectedProject()
    }

    /** Marks a session active so the next chat is bound to it. */
    fun openSession(sessionId: Long) = viewModelScope.launch {
        val projectId = _uiState.value.selectedProjectId ?: return@launch
        activeSessionHolder.set(projectId, sessionId)
    }

    fun addSkill(name: String, instructions: String) = viewModelScope.launch {
        val projectId = _uiState.value.selectedProjectId ?: return@launch
        skillRepository.createSkill(projectId, name, "", instructions)
        refreshSelectedProject()
    }

    fun toggleSkill(skill: Skill) = viewModelScope.launch {
        skillRepository.setEnabled(skill, !skill.enabled)
        refreshSelectedProject()
    }

    fun deleteSkill(skillId: Long) = viewModelScope.launch {
        skillRepository.deleteSkill(skillId)
        refreshSelectedProject()
    }

    fun deleteMemory(entryId: Long) = viewModelScope.launch {
        longTermMemoryRepository.delete(entryId)
        refreshSelectedProject()
    }

    /** Runs self-learning extraction over a session's stored transcript. */
    fun runSelfLearning(sessionId: Long) = viewModelScope.launch {
        val projectId = _uiState.value.selectedProjectId ?: return@launch
        val extractor = extractorProvider()
        if (extractor == null) {
            _uiState.value = _uiState.value.copy(message = "Load a model first")
            return@launch
        }
        _uiState.value = _uiState.value.copy(busy = true, message = null)
        val transcript = memoryRepository.messagesForSession(sessionId)
            .map { ChatMessage(it.role, it.content) }
        val stored = extractor.extractAndStore(projectId, sessionId, transcript)
        _uiState.value = _uiState.value.copy(
            busy = false, message = "Learned ${stored.size} new memories",
        )
        refreshSelectedProject()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*MemoryViewModelTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/inference/NoOpEmbedder.kt app/src/main/java/com/mobilegem/gemma/ui/memory/MemoryViewModel.kt app/src/test/java/com/mobilegem/gemma/ui/memory/MemoryViewModelTest.kt
git commit -m "feat: add memory view model"
```

---

## Task 18: Memory screen UI, model-backed embedder wiring, and on-device verification

Replaces Plan 1's `MemoryScreen` placeholder with the real UI, wires `MemoryViewModel` and a model-backed `SelfLearningExtractor` into the app, reloads the chat WebView when the active session changes, and verifies the whole subsystem on-device.

**Files:**
- Replace: `app/src/main/java/com/mobilegem/gemma/ui/memory/MemoryScreen.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/AppContainer.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/MainActivity.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/ui/navigation/AppScaffold.kt`
- Modify: `app/src/main/java/com/mobilegem/gemma/ui/chat/ChatScreen.kt`

- [ ] **Step 1: Expose an embedder + extractor from `AppContainer`**

Add to `AppContainer` (replace the lazy-embedder comment block / `inferenceController` section with this). The embedder is created from the live engine when a model loads; the simplest cohesive approach is a mutable holder updated by the generator factory:

```kotlin
    // A mutable embedder slot: NoOpEmbedder until a model is loaded, then the
    // model-backed embedder. The retriever reads through this slot each call.
    private val embedderSlot = com.mobilegem.gemma.inference.MutableEmbedder()

    private val retriever = MemoryRetriever(embedderSlot, longTermMemoryRepository)

    val inferenceController = InferenceController(
        activeSession = activeSessionHolder,
        augmenter = MemoryContextAugmenter(skillRepository, retriever),
        persister = memoryRepository,
        generatorFactory = { path, backend ->
            val generator = com.mobilegem.gemma.inference.LiteRtLmTextGenerator
                .create(path, backend)
            embedderSlot.delegate =
                com.mobilegem.gemma.inference.modelEmbedderOrNull(path, backend)
            generator
        },
    )

    fun selfLearningExtractor(): SelfLearningExtractor? {
        val embedder = embedderSlot.delegate ?: return null
        val generator = embedderSlot.generatorForExtraction ?: return null
        return SelfLearningExtractor(generator, embedder, longTermMemoryRepository)
    }
```

To support this, create `app/src/main/java/com/mobilegem/gemma/inference/MutableEmbedder.kt`:

```kotlin
package com.mobilegem.gemma.inference

/** An [Embedder] whose backing implementation can be swapped at runtime. */
class MutableEmbedder : Embedder {
    @Volatile var delegate: Embedder? = null
    @Volatile var generatorForExtraction: TextGenerator? = null

    override val dimension: Int get() = delegate?.dimension ?: 1
    override suspend fun embed(text: String): FloatArray =
        delegate?.embed(text) ?: FloatArray(dimension)
}
```

And create `app/src/main/java/com/mobilegem/gemma/inference/ModelEmbedder.kt`:

```kotlin
package com.mobilegem.gemma.inference

import com.mobilegem.gemma.settings.InferenceBackend

/**
 * Builds an [Embedder] from the on-device model. Returns null if the model /
 * runtime does not support embeddings — callers degrade gracefully (empty
 * retrieval, self-learning disabled).
 *
 * VERIFY against the resolved litertlm-android API (see LiteRtLmEmbedder).
 * The dimension below must match the model's embedding size.
 */
fun modelEmbedderOrNull(modelPath: String, backend: InferenceBackend): Embedder? =
    runCatching {
        val engine = com.google.ai.edge.litertlm.Engine(
            com.google.ai.edge.litertlm.EngineConfig(
                modelPath = modelPath,
                backend = when (backend) {
                    InferenceBackend.GPU -> com.google.ai.edge.litertlm.Backend.GPU()
                    InferenceBackend.CPU -> com.google.ai.edge.litertlm.Backend.CPU()
                },
            ),
        ).also { it.initialize() }
        LiteRtLmEmbedder(engine, dimension = 768)
    }.getOrNull()
```

> Note on `generatorForExtraction`: self-learning needs a `TextGenerator`. Set `embedderSlot.generatorForExtraction = generator` inside the `generatorFactory` lambda (add that line right after `embedderSlot.delegate = ...`). The same generator instance powers both chat and extraction.

- [ ] **Step 2: Write the Memory screen UI**

Replace the full contents of `app/src/main/java/com/mobilegem/gemma/ui/memory/MemoryScreen.kt` with:

```kotlin
package com.mobilegem.gemma.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MemoryScreen(viewModel: MemoryViewModel, onOpenChat: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    if (state.selectedProjectId == null) {
        ProjectListView(
            projects = state.projects.map { it.id to it.name },
            onCreate = viewModel::createProject,
            onSelect = viewModel::selectProject,
        )
    } else {
        ProjectDetailView(
            state = state,
            onBack = viewModel::clearSelection,
            onCreateSession = viewModel::createSession,
            onOpenSession = { id -> viewModel.openSession(id); onOpenChat() },
            onLearn = viewModel::runSelfLearning,
            onAddSkill = viewModel::addSkill,
            onToggleSkill = viewModel::toggleSkill,
            onDeleteSkill = viewModel::deleteSkill,
            onDeleteMemory = viewModel::deleteMemory,
        )
    }
}

@Composable
private fun ProjectListView(
    projects: List<Pair<Long, String>>,
    onCreate: (String) -> Unit,
    onSelect: (Long) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), Arrangement.spacedBy(12.dp)) {
        Text("Projects")
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("New project") }, modifier = Modifier.weight(1f),
            )
            Button(onClick = { if (name.isNotBlank()) { onCreate(name); name = "" } }) {
                Text("Add")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(projects) { (id, label) ->
                Card(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onSelect(id) }) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun ProjectDetailView(
    state: MemoryUiState,
    onBack: () -> Unit,
    onCreateSession: (String) -> Unit,
    onOpenSession: (Long) -> Unit,
    onLearn: (Long) -> Unit,
    onAddSkill: (String, String) -> Unit,
    onToggleSkill: (com.mobilegem.gemma.memory.db.Skill) -> Unit,
    onDeleteSkill: (Long) -> Unit,
    onDeleteMemory: (Long) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().padding(16.dp), Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("< Projects") }
        state.message?.let { Text(it) }
        TabRow(selectedTabIndex = tab) {
            listOf("Sessions", "Skills", "Memory").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> SessionsTab(state, onCreateSession, onOpenSession, onLearn)
            1 -> SkillsTab(state, onAddSkill, onToggleSkill, onDeleteSkill)
            else -> MemoryTab(state, onDeleteMemory)
        }
    }
}

@Composable
private fun SessionsTab(
    state: MemoryUiState,
    onCreateSession: (String) -> Unit,
    onOpenSession: (Long) -> Unit,
    onLearn: (Long) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("New session") }, modifier = Modifier.weight(1f),
            )
            Button(onClick = { if (title.isNotBlank()) { onCreateSession(title); title = "" } }) {
                Text("Add")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.sessions) { session ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), Arrangement.spacedBy(4.dp)) {
                        Text(session.title)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpenSession(session.id) }) {
                                Text("Open in Chat")
                            }
                            TextButton(
                                enabled = !state.busy,
                                onClick = { onLearn(session.id) },
                            ) { Text("End & learn") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillsTab(
    state: MemoryUiState,
    onAddSkill: (String, String) -> Unit,
    onToggleSkill: (com.mobilegem.gemma.memory.db.Skill) -> Unit,
    onDeleteSkill: (Long) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Skill name") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = instructions, onValueChange = { instructions = it },
            label = { Text("Instructions") }, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            if (name.isNotBlank() && instructions.isNotBlank()) {
                onAddSkill(name, instructions); name = ""; instructions = ""
            }
        }) { Text("Add skill") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.skills) { skill ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(skill.name)
                            Text(skill.instructions)
                        }
                        Switch(
                            checked = skill.enabled,
                            onCheckedChange = { onToggleSkill(skill) },
                        )
                        TextButton(onClick = { onDeleteSkill(skill.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryTab(state: MemoryUiState, onDeleteMemory: (Long) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.memories) { entry ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    Arrangement.SpaceBetween,
                ) {
                    Text(entry.content, Modifier.weight(1f))
                    TextButton(onClick = { onDeleteMemory(entry.id) }) { Text("Delete") }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Provide `MemoryViewModel` in `MainActivity` and pass it to navigation**

In `app/src/main/java/com/mobilegem/gemma/MainActivity.kt`, after the existing `settingsViewModel` is created, add a second ViewModel and pass both to `AppScaffold`. The `MemoryViewModel`'s `extractorProvider` is `container::selfLearningExtractor`:

```kotlin
        val memoryViewModelFactory = object : Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                com.mobilegem.gemma.ui.memory.MemoryViewModel(
                    memoryRepository = container.memoryRepository,
                    skillRepository = container.skillRepository,
                    longTermMemoryRepository = container.longTermMemoryRepository,
                    activeSessionHolder = container.activeSessionHolder,
                    extractorProvider = container::selfLearningExtractor,
                ) as T
        }
        val memoryViewModel = ViewModelProvider(this, memoryViewModelFactory)[
            com.mobilegem.gemma.ui.memory.MemoryViewModel::class.java]
```

Change the `setContent { MaterialTheme { AppScaffold(settingsViewModel) } }` call to:

```kotlin
        setContent {
            MaterialTheme {
                AppScaffold(
                    settingsViewModel = settingsViewModel,
                    memoryViewModel = memoryViewModel,
                    activeSessionHolder = container.activeSessionHolder,
                )
            }
        }
```

- [ ] **Step 4: Update `AppScaffold` to host the real Memory screen and cross-navigate**

In `app/src/main/java/com/mobilegem/gemma/ui/navigation/AppScaffold.kt`, change the signature and the `memory`/`chat` composables:

```kotlin
@Composable
fun AppScaffold(
    settingsViewModel: SettingsViewModel,
    memoryViewModel: com.mobilegem.gemma.ui.memory.MemoryViewModel,
    activeSessionHolder: com.mobilegem.gemma.memory.ActiveSessionHolder,
) {
```

Inside `NavHost`, replace the `chat` and `memory` composables with:

```kotlin
            composable("chat") { ChatScreen(activeSessionHolder = activeSessionHolder) }
            composable("memory") {
                com.mobilegem.gemma.ui.memory.MemoryScreen(
                    viewModel = memoryViewModel,
                    onOpenChat = {
                        navController.navigate("chat") {
                            launchSingleTop = true
                        }
                    },
                )
            }
```

Remove the now-unused `import com.mobilegem.gemma.ui.memory.MemoryScreen` line if it causes an unused-import warning (the fully-qualified call above is used instead), or keep the import and use the short name — either is fine as long as it compiles.

- [ ] **Step 5: Reload the chat WebView when the active session changes**

In `app/src/main/java/com/mobilegem/gemma/ui/chat/ChatScreen.kt`, change `ChatScreen` to observe the holder and key the WebView on the session id so switching sessions starts a fresh chat:

```kotlin
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatScreen(
    activeSessionHolder: com.mobilegem.gemma.memory.ActiveSessionHolder,
    modifier: Modifier = Modifier,
) {
    val active = activeSessionHolder.active.collectAsState().value
    Box(modifier.fillMaxSize()) {
        // Keying on session id recreates the WebView for a fresh chat per session.
        androidx.compose.runtime.key(active?.sessionId) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> /* ...existing WebView factory body unchanged... */ },
            )
        }
    }
}
```

Keep the existing `WebViewAssetLoader` factory body exactly as written in Plan 1; only the wrapping `key(...)` and the new parameter/`collectAsState` are added. Add the import `import androidx.compose.runtime.collectAsState`.

- [ ] **Step 6: Verify the full build and JVM test suite**

Run: `cd webui && npm run build && cd .. && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: all unit tests PASS; `BUILD SUCCESSFUL`; `app-debug.apk` produced.

- [ ] **Step 7: On-device verification**

Run: `./gradlew :app:installDebug`, then manually:
1. Settings → import and select a Gemma 4 `.litertlm` model (from Plan 1).
2. Memory → create a project "Demo" → open it → Sessions tab → create session "S1".
3. Tap **Open in Chat** on S1 → Chat tab opens with a fresh WebView.
4. Send a message stating a durable fact, e.g. "Remember that I prefer concise answers and I work in Kotlin."
5. Get a streamed reply.
6. Memory → Demo → Sessions → tap **End & learn** on S1 → wait → a "Learned N new memories" message appears.
7. Memory tab → confirm extracted facts are listed.
8. Skills tab → add a skill "Terse" / "Always answer in one sentence." → ensure it is enabled.
9. Open S1 in Chat again → send a question → confirm the answer reflects the skill (concise) and recalled memory (Kotlin context). This exercises augmentation end-to-end.

Expected: all steps pass. If self-learning reports "Load a model first", confirm a model is selected in Settings. If memory recall returns nothing, the embedder may be unavailable — check `modelEmbedderOrNull` against the resolved LiteRT-LM API (see Known risks).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mobilegem/gemma/ui/memory/MemoryScreen.kt app/src/main/java/com/mobilegem/gemma/AppContainer.kt app/src/main/java/com/mobilegem/gemma/MainActivity.kt app/src/main/java/com/mobilegem/gemma/ui/navigation/AppScaffold.kt app/src/main/java/com/mobilegem/gemma/ui/chat/ChatScreen.kt app/src/main/java/com/mobilegem/gemma/inference/MutableEmbedder.kt app/src/main/java/com/mobilegem/gemma/inference/ModelEmbedder.kt
git commit -m "feat: add memory screen UI and wire self-learning end-to-end"
```

---

## Done

The Memory section is complete: Projects organize Sessions; Skills and retrieved Long-Term Memory are injected into every chat in the active session; finished sessions are mined for durable facts by the on-device model and those facts become future memory.

## Known risks / things to verify during execution

1. **LiteRT-LM embedding API** (Tasks 8, 18): `Engine.embed(text): FloatArray` is assumed. The actual LiteRT-LM Kotlin API for embeddings must be confirmed against the resolved `litertlm-android` artifact. The `Embedder` interface isolates this — only `LiteRtLmEmbedder.kt` and `modelEmbedderOrNull` in `ModelEmbedder.kt` touch the native embedding call. If the chat model cannot embed, load a dedicated EmbeddingGemma `.litertlm` model in `modelEmbedderOrNull`, or substitute an FTS5 retriever (`MemoryRetriever` is the only consumer).
2. **Embedding dimension**: `ModelEmbedder.kt` hardcodes `dimension = 768`. Set this to the actual embedding size of the chosen model. `MemoryRetriever` already skips entries whose stored vector length differs from the query vector, so a wrong dimension degrades gracefully rather than crashing — but recall will be empty until corrected.
3. **Self-learning cost**: extraction runs a full generation pass over the transcript on-device; for long sessions this is slow. It is user-triggered ("End & learn") so it never blocks chat. A future enhancement could cap transcript length or run it in a background `WorkManager` job.
4. **Session resume**: opening a session loads a *fresh* WebView chat (pi-web-ui manages its own display history). The native `messages` table is the durable record consumed by self-learning and is kept in sync each turn by `ChatCompletionHandler`. Re-hydrating an old transcript into the interactive pi-web-ui chat is out of scope for this plan.
5. **Two engines for one model**: `InferenceController` creates a generation `Engine` and `modelEmbedderOrNull` creates a second `Engine` over the same file for embeddings. This doubles model memory. If on-device memory is tight, investigate whether one `Engine` can serve both generation and embeddings and unify them — again, contained behind the `Embedder`/`TextGenerator` interfaces.
