package com.myAllVideoBrowser.ui.main.home.browser

import android.webkit.JavascriptInterface

class JsWebBridge(
    private val onIntercept: (url: String, body: String) -> Boolean,
    private val onManifestReceived: ((url: String, headersJson: String, content: String) -> Unit)? = null,
    private val onVideoReceived: ((url: String, headersJson: String) -> Unit)? = null,
    private val onAudioReceived: ((url: String, headersJson: String) -> Unit)? = null
) {
    constructor(onIntercept: (url: String, body: String) -> Boolean) : this(onIntercept, null, null, null)
    constructor(
        onIntercept: (url: String, body: String) -> Boolean,
        onManifestReceived: ((url: String, headersJson: String, content: String) -> Unit)?
    ) : this(onIntercept, onManifestReceived, null, null)

    companion object {
        const val BRIDGE_NAME = "AndroidBridge"
    }

    @JavascriptInterface
    fun shouldInterceptPost(url: String, body: String): Boolean {
        // This runs on a background thread (JavaBridge thread)
        // Return true to BLOCK the request
        // Return false to let the browser CONTINUE the request
        return onIntercept(url, body)
    }

    @JavascriptInterface
    fun onManifest(url: String, headersJson: String, content: String) {
        onManifestReceived?.invoke(url, headersJson, content)
    }

    @JavascriptInterface
    fun onVideoDetected(url: String, headersJson: String) {
        onVideoReceived?.invoke(url, headersJson)
    }

    @JavascriptInterface
    fun onAudioDetected(url: String, headersJson: String) {
        onAudioReceived?.invoke(url, headersJson)
    }
}
