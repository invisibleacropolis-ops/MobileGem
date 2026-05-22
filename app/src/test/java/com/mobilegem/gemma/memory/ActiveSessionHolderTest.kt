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
