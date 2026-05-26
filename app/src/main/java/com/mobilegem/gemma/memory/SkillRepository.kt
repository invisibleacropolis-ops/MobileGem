package com.mobilegem.gemma.memory

import com.mobilegem.gemma.logging.AppLog
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
    ): Long {
        AppLog.event(
            "memory", "memory.skill.create",
            "projectId" to projectId, "name" to name,
        )
        return skillDao.insert(
            Skill(
                projectId = projectId, name = name, description = description,
                instructions = instructions, enabled = true,
            ),
        )
    }

    suspend fun updateSkill(skill: Skill) = skillDao.update(skill)

    suspend fun setEnabled(skill: Skill, enabled: Boolean) {
        AppLog.event(
            "memory", "memory.skill.toggle",
            "skillId" to skill.id, "enabled" to enabled,
        )
        skillDao.update(skill.copy(enabled = enabled))
    }

    suspend fun deleteSkill(skillId: Long) {
        AppLog.event("memory", "memory.skill.delete", "skillId" to skillId)
        skillDao.delete(skillId)
    }
}
