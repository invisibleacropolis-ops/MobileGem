package com.mobilegem.gemma.ui.chat

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.mobilegem.gemma.logging.AppLog
import com.mobilegem.gemma.memory.ActiveSessionHolder

private fun shouldReport(request: WebResourceRequest): Boolean =
    request.isForMainFrame || request.url.toString().contains("127.0.0.1:8765")

private val JS_ERROR_TRAP = """
    (function() {
        if (window.__mobileGemErrorTrapInstalled) return;
        window.__mobileGemErrorTrapInstalled = true;
        window.addEventListener('error', function(e) {
            try {
                var payload = JSON.stringify({
                    type: 'error',
                    message: e.message,
                    source: e.filename,
                    line: e.lineno,
                    col: e.colno,
                    stack: (e.error && e.error.stack) ? e.error.stack : ''
                });
                if (window.MobileGem && window.MobileGem.logJsError) window.MobileGem.logJsError(payload);
            } catch (ignored) {}
        });
        window.addEventListener('unhandledrejection', function(e) {
            try {
                var reason = e.reason;
                var payload = JSON.stringify({
                    type: 'unhandledrejection',
                    message: (reason && reason.message) ? reason.message : String(reason),
                    stack: (reason && reason.stack) ? reason.stack : ''
                });
                if (window.MobileGem && window.MobileGem.logJsError) window.MobileGem.logJsError(payload);
            } catch (ignored) {}
        });
    })();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatScreen(
    activeSessionHolder: ActiveSessionHolder,
    modifier: Modifier = Modifier,
) {
    val active by activeSessionHolder.active.collectAsState()
    Box(modifier.fillMaxSize()) {
        // Keying on the active session id forces a fresh WebView per session.
        key(active?.sessionId) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val assetLoader = WebViewAssetLoader.Builder()
                        .addPathHandler(
                            "/assets/",
                            WebViewAssetLoader.AssetsPathHandler(context),
                        )
                        .build()

                    WebView(context).apply {
                        addJavascriptInterface(MobileGemBridge(), "MobileGem")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                val data = arrayOf(
                                    "level" to message.messageLevel().name,
                                    "sourceId" to (message.sourceId() ?: ""),
                                    "lineNumber" to message.lineNumber(),
                                    "text" to (message.message() ?: ""),
                                )
                                when (message.messageLevel()) {
                                    ConsoleMessage.MessageLevel.ERROR ->
                                        AppLog.error(
                                            category = "webview",
                                            message = "console",
                                            throwable = null,
                                            *data,
                                        )
                                    ConsoleMessage.MessageLevel.WARNING ->
                                        AppLog.warn("webview", "console", *data)
                                    else ->
                                        AppLog.event("webview", "console", *data)
                                }
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? =
                                assetLoader.shouldInterceptRequest(request.url)

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                view.evaluateJavascript(JS_ERROR_TRAP, null)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                if (shouldReport(request)) {
                                    AppLog.error(
                                        category = "webview",
                                        message = "resource.error",
                                        throwable = null,
                                        "url" to request.url.toString(),
                                        "errorCode" to error.errorCode,
                                        "description" to (error.description?.toString() ?: ""),
                                        "mainFrame" to request.isForMainFrame,
                                    )
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: WebResourceResponse,
                            ) {
                                if (shouldReport(request)) {
                                    AppLog.warn(
                                        category = "webview",
                                        message = "http.error",
                                        "url" to request.url.toString(),
                                        "statusCode" to errorResponse.statusCode,
                                        "reason" to (errorResponse.reasonPhrase ?: ""),
                                        "mainFrame" to request.isForMainFrame,
                                    )
                                }
                            }
                        }
                        loadUrl(ChatConfig.WEB_UI_URL)
                    }
                },
            )
        }
    }
}
