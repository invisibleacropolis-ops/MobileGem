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
