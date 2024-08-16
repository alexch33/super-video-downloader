package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.webkit.WebView
import java.util.UUID

class WebTab(
    private val url: String,
    private val title: String?,
    private val iconBytes: Bitmap? = null,
    private val headers: Map<String, String> = emptyMap(),
    private var webview: WebView? = null,
    private var resultMsg: Message? = null,
    var id: String = UUID.randomUUID().toString()
) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        val HOME_TAB = WebTab(
            "",
            "Home Tab",
            id = "home"
        )
    }

    fun getMessage(): Message? {
        return resultMsg
    }

    fun flushMessage() {
        resultMsg = null
    }

    fun getWebView(): WebView? {
        return this.webview
    }

    fun setWebView(webview: WebView?) {
        this.webview = webview
    }

    fun getHeaders(): Map<String, String>? {
        return this.headers
    }

    fun getUrl(): String {
        return this.url
    }

    fun getTitle(): String {
        return this.title ?: ""
    }

    fun getFavicon(): Bitmap? {
        return iconBytes
    }

    fun isHome(): Boolean {
        return this.id.contains("home")
    }


    override fun toString(): String {
        return "WebTab(url='$url', title=$title, iconBytes=$iconBytes, headers=$headers, webview=$webview, resultMsg=$resultMsg, id='$id')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WebTab

        if (url != other.url) return false
        if (title != other.title) return false
        if (iconBytes != other.iconBytes) return false
        if (headers != other.headers) return false
        if (webview != other.webview) return false
        if (resultMsg != other.resultMsg) return false
        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (iconBytes?.hashCode() ?: 0)
        result = 31 * result + headers.hashCode()
        result = 31 * result + (webview?.hashCode() ?: 0)
        result = 31 * result + (resultMsg?.hashCode() ?: 0)
        result = 31 * result + (id?.hashCode() ?: 0)
        return result
    }
}