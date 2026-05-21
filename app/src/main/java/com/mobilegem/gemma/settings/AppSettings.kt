package com.mobilegem.gemma.settings

enum class InferenceBackend { CPU, GPU }

data class AppSettings(
    val activeModelFileName: String?,
    val backend: InferenceBackend,
    val temperature: Float,
) {
    companion object {
        val DEFAULT = AppSettings(
            activeModelFileName = null,
            backend = InferenceBackend.CPU,
            temperature = 0.8f,
        )
    }
}
