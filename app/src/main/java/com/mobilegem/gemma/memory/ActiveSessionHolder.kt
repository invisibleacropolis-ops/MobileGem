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
