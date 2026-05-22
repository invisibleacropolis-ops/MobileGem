package com.mobilegem.gemma.server

object GemmaPromptBuilder {

    fun build(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        val systemText = messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }
        var systemConsumed = false

        for (msg in messages.filter { it.role != "system" }) {
            when (msg.role) {
                "assistant" -> sb.append("<start_of_turn>model\n")
                    .append(msg.content).append("<end_of_turn>\n")
                else -> {
                    val content = if (!systemConsumed && systemText != null) {
                        systemConsumed = true
                        "$systemText\n\n${msg.content}"
                    } else {
                        msg.content
                    }
                    sb.append("<start_of_turn>user\n")
                        .append(content).append("<end_of_turn>\n")
                }
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }
}
