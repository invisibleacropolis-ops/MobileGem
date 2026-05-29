package com.mobilegem.gemma.ui.chat

import android.webkit.JavascriptInterface
import com.mobilegem.gemma.logging.AppLog

/**
 * Bridge object exposed to the in-app WebView's JavaScript context as
 * `window.MobileGem`. Methods MUST be safe to call from any thread — the
 * WebView invokes JavascriptInterface methods on a dedicated thread.
 *
 * The local LLM server binds to 127.0.0.1 and is reachable only from this
 * app's own WebView, so no auth token is exchanged.
 */
class MobileGemBridge {
    /**
     * Called by the WebView's global error trap to report uncaught JS errors and
     * unhandled promise rejections. [payload] is a JSON string with `type`,
     * `message`, `source`, `line`, `col`, `stack` fields (subset depending on
     * the trigger). Forwarded verbatim to [AppLog].
     */
    @JavascriptInterface
    fun logJsError(payload: String) {
        AppLog.error(
            category = "webview",
            message = "js.uncaught",
            throwable = null,
            "payload" to payload,
        )
    }
}
