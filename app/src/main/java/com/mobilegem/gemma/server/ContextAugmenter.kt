package com.mobilegem.gemma.server

import com.mobilegem.gemma.logging.AppLog
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
        if (skills.isEmpty() && memories.isEmpty()) {
            AppLog.event(
                "augmenter", "augmenter.build",
                "projectId" to projectId, "skills" to 0,
                "memories" to 0, "chars" to 0,
            )
            return null
        }

        val sb = StringBuilder()
        if (skills.isNotEmpty()) {
            sb.append("Active skills:\n")
            skills.forEach {
                sb.append("- ").append(it.name).append(": ")
                    .append(it.instructions).append('\n')
            }
        }
        if (memories.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("Relevant long-term memory:\n")
            memories.forEach { sb.append("- ").append(it.content).append('\n') }
        }
        val result = sb.toString().trimEnd()
        AppLog.event(
            "augmenter", "augmenter.build",
            "projectId" to projectId,
            "skills" to skills.size,
            "memories" to memories.size,
            "chars" to result.length,
        )
        return result
    }
}
