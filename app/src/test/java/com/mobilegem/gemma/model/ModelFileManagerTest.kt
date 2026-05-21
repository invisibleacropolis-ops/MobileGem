package com.mobilegem.gemma.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.ByteArrayInputStream
import java.io.InputStream

class ModelFileManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun source(name: String, bytes: ByteArray) = object : ContentSource {
        override val displayName = name
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
    }

    @Test
    fun importCopiesFileIntoModelsDirAndListsIt() = runTest {
        val manager = ModelFileManager(tmp.newFolder("models"))
        val imported = manager.import(source("gemma-4-E2B-it.litertlm", byteArrayOf(1, 2, 3)))

        assertThat(imported.name).isEqualTo("gemma-4-E2B-it.litertlm")
        assertThat(imported.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(manager.listModels().map { it.name })
            .containsExactly("gemma-4-E2B-it.litertlm")
    }

    @Test
    fun importRejectsNonLitertlmExtension() = runTest {
        val manager = ModelFileManager(tmp.newFolder("models"))
        val result = runCatching { manager.import(source("notamodel.txt", byteArrayOf(0))) }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun deleteRemovesModel() = runTest {
        val manager = ModelFileManager(tmp.newFolder("models"))
        manager.import(source("a.litertlm", byteArrayOf(9)))
        manager.delete("a.litertlm")
        assertThat(manager.listModels()).isEmpty()
    }
}
