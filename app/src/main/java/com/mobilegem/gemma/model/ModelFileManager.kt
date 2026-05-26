package com.mobilegem.gemma.model

import com.mobilegem.gemma.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ModelFileManager(private val modelsDir: File) {

    init {
        if (!modelsDir.exists()) modelsDir.mkdirs()
    }

    suspend fun import(source: ContentSource): File = withContext(Dispatchers.IO) {
        AppLog.event("model", "import.begin", "name" to source.displayName)
        require(source.displayName.endsWith(".litertlm")) {
            "Model file must have a .litertlm extension"
        }
        val target = File(modelsDir, source.displayName)
        source.openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        AppLog.event(
            "model", "import.end",
            "name" to source.displayName,
            "path" to target.absolutePath,
            "bytes" to target.length(),
        )
        target
    }

    fun listModels(): List<File> {
        val models = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }
            ?.sortedBy { it.name } ?: emptyList()
        AppLog.event("model", "list", "count" to models.size)
        return models
    }

    fun resolve(fileName: String): File = File(modelsDir, fileName)

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        AppLog.event("model", "delete", "name" to fileName)
        File(modelsDir, fileName).delete()
        Unit
    }
}
