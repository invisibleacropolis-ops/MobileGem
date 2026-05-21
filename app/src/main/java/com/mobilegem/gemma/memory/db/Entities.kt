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
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryEntry) return false
        return id == other.id && content == other.content &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int =
        (id.hashCode() * 31 + content.hashCode()) * 31 + embedding.contentHashCode()
}
