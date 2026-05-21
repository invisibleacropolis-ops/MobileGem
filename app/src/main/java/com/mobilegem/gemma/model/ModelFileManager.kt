package com.mobilegem.gemma.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ModelFileManager(private val modelsDir: File) {

    init {
        if (!modelsDir.exists()) modelsDir.mkdirs()
    }

    suspend fun import(source: ContentSource): File = withContext(Dispatchers.IO) {
        require(source.displayName.endsWith(".litertlm")) {
            "Model file must have a .litertlm extension"
        }
        val target = File(modelsDir, source.displayName)
        source.openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target
    }

    fun listModels(): List<File> =
        modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }
            ?.sortedBy { it.name } ?: emptyList()

    fun resolve(fileName: String): File = File(modelsDir, fileName)

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        File(modelsDir, fileName).delete()
        Unit
    }
}
