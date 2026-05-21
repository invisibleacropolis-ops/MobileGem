package com.mobilegem.gemma.model

import android.content.ContentResolver
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class UriContentSourceTest {

    @Test
    fun usesDisplayNameAndOpensStream() {
        val resolver = mockk<ContentResolver>()
        val uri = Uri.parse("content://test/model")
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf(7))

        val source = UriContentSource(resolver, uri, "gemma-4-E4B-it.litertlm")

        assertThat(source.displayName).isEqualTo("gemma-4-E4B-it.litertlm")
        assertThat(source.openStream().readBytes()).isEqualTo(byteArrayOf(7))
    }
}
