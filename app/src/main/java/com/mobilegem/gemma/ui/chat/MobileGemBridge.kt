package com.mobilegem.gemma.ui.chat

import android.webkit.JavascriptInterface

/**
 * Bridge object exposed to the in-app WebView's JavaScript context as
 * `window.MobileGem`. Provides the per-launch auth token required to talk
 * to the local LLM server. Methods MUST be safe to call from any thread —
 * the WebView invokes JavascriptInterface methods on a dedicated thread.
 */
class MobileGemBridge(private val authTokenProvider: () -> String) {
    @JavascriptInterface
    fun getAuthToken(): String = authTokenProvider()
}
