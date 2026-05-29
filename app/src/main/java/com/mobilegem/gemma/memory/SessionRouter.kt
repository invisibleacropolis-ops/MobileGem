package com.mobilegem.gemma.memory

import com.mobilegem.gemma.logging.AppLog
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Auto-routes every chat conversation into the Memory layer as a [Session],
 * mirroring the agent-harness convention where each conversation becomes a
 * persisted session file titled by its first line.
 *
 * The chat WebView never sets an active session itself — without this router a
 * normal conversation would generate tokens but never be persisted (persistence
 * in [com.mobilegem.gemma.server.ChatCompletionHandler] is gated on an active
 * session). [route] is therefore invoked at the very start of each completion,
 * before generation, so an active session always exists by the time the handler
 * persists the exchange.
 *
 * Conversation identity is the **first user message**: as long as a request's
 * first user message matches the one that opened the current session, it is
 * treated as a continuation and reuses the same session (so follow-up turns
 * append to — rather than fork — the transcript). A different first user message
 * means a new conversation, so a fresh session is created and made active.
 */
class SessionRouter(
    private val repository: MemoryRepository,
    private val activeSession: ActiveSessionHolder,
    /** Name of the project that auto-created conversations are filed under. */
    private val projectName: String = DEFAULT_PROJECT_NAME,
) {
    private val mutex = Mutex()

    /** First user message that opened the currently-active router session. */
    private var currentSignature: String? = null

    /** Cached id of the default conversations project. */
    private var cachedProjectId: Long? = null

    /**
     * Ensures an active session exists for [messages], creating a new one when a
     * new conversation begins. Safe to call on every request; a no-op for
     * continuation turns of the conversation already in progress.
     */
    suspend fun route(messages: List<ChatMessage>) {
        val firstUser = messages.firstOrNull { it.role == "user" }?.content?.trim()
        if (firstUser.isNullOrEmpty()) return

        mutex.withLock {
            val continuation = firstUser == currentSignature && activeSession.current() != null
            if (continuation) return

            val projectId = ensureProject()
            val title = titleFrom(firstUser)
            val sessionId = repository.createSession(projectId, title)
            activeSession.set(projectId, sessionId)
            currentSignature = firstUser
            AppLog.event(
                "memory", "session.route.new",
                "projectId" to projectId, "sessionId" to sessionId, "title" to title,
            )
        }
    }

    private suspend fun ensureProject(): Long {
        cachedProjectId?.let { return it }
        val existing = repository.projectIdByName(projectName)
        val id = existing ?: repository.createProject(
            name = projectName,
            description = "Conversations saved automatically from chat.",
        )
        cachedProjectId = id
        return id
    }

    private fun titleFrom(firstUser: String): String {
        val firstLine = firstUser.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
            .ifEmpty { UNTITLED }
        return if (firstLine.length > MAX_TITLE_LEN) {
            firstLine.take(MAX_TITLE_LEN - 1).trimEnd() + "…"
        } else {
            firstLine
        }
    }

    companion object {
        const val DEFAULT_PROJECT_NAME = "Conversations"
        private const val MAX_TITLE_LEN = 80
        private const val UNTITLED = "Untitled conversation"
    }
}
