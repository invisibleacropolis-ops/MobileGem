package com.mobilegem.gemma.model

import java.io.InputStream

interface ContentSource {
    val displayName: String
    fun openStream(): InputStream
}
