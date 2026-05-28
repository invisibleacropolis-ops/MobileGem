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

    @Test
    fun fallsBackToBulletedLines() {
        val raw = """
            Here are the facts:
            - User prefers metric units
            - User is building an Android app
            * Bullets with asterisks also work
        """.trimIndent()
        assertThat(FactListParser.parse(raw))
            .containsExactly(
                "User prefers metric units",
                "User is building an Android app",
                "Bullets with asterisks also work",
            )
    }

    @Test
    fun fallsBackToNumberedLines() {
        val raw = """
            1. User likes Kotlin
            2. User is in Berlin
        """.trimIndent()
        assertThat(FactListParser.parse(raw))
            .containsExactly("User likes Kotlin", "User is in Berlin")
    }

    @Test
    fun ignoresNonFactLinesInFallback() {
        val raw = """
            Sure! Here are some facts:
            - User has a cat
            Hope this helps.
        """.trimIndent()
        assertThat(FactListParser.parse(raw)).containsExactly("User has a cat")
    }

    @Test
    fun explicitEmptyJsonArrayShortCircuitsBulletScan() {
        // The model explicitly emitted an empty JSON array — that IS the answer.
        // Must NOT fall through to the bullet scanner; result is empty by design.
        val raw = """
            Sure, here are the facts: []
            - this bullet should NOT be picked up because the JSON parsed successfully
        """.trimIndent()
        assertThat(FactListParser.parse(raw)).isEmpty()
    }
}
