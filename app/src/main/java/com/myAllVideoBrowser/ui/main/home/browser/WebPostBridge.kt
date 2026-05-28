package com.myAllVideoBrowser.ui.main.home.browser

class WebPostBridge(
    private val onIntercept: (url: String, body: String) -> Boolean
) {
    companion object {
        const val BRIDGE_NAME = "AndroidBridge"
    }
    @android.webkit.JavascriptInterface
    fun shouldInterceptPost(url: String, body: String): Boolean {
        // This runs on a background thread (JavaBridge thread)
        // Return true to BLOCK the request
        // Return false to let the browser CONTINUE the request
        return onIntercept(url, body)
    }
}
