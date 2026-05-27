package com.mobilegem.gemma.ui.chat

import android.annotation.SuppressLint
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
import com.mobilegem.gemma.memory.ActiveSessionHolder

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
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? =
                                assetLoader.shouldInterceptRequest(request.url)
                        }
                        loadUrl(ChatConfig.WEB_UI_URL)
                    }
                },
            )
        }
    }
}
