package com.mobilegem.gemma.ui.chat

/**
 * Centralized constants for the embedded chat WebView. Extracted from
 * ChatScreen so the URL and origin can be referenced consistently (e.g. for
 * CORS allow-listing on the server side) without string duplication.
 */
object ChatConfig {
    /** Origin under which WebViewAssetLoader serves the bundled web app. */
    const val WEB_UI_ORIGIN = "https://appassets.androidplatform.net"

    /** Full URL of the chat web app's entry point. */
    const val WEB_UI_URL = "$WEB_UI_ORIGIN/assets/webui/index.html"
}
