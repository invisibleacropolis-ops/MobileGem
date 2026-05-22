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
