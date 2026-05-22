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
