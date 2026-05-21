package com.mobilegem.gemma.model

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

class UriContentSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val displayName: String,
) : ContentSource {

    override fun openStream(): InputStream =
        resolver.openInputStream(uri)
            ?: error("Unable to open input stream for $uri")

    companion object {
        /** Resolves the human-readable file name for a SAF Uri. */
        fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
            return uri.lastPathSegment ?: "model.litertlm"
        }
    }
}
