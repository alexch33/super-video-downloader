package com.myAllVideoBrowser.util

import android.webkit.WebResourceResponse
import java.io.*


object AdBlockerHelper {
    fun createEmptyResource(): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
    }

    fun parseAdsLine(line: String?): String {
        val a = line.toString().replace("^$\\third-party", "")
            .replace("0.0.0.0", "")
            .replace(":::::", "")
            .replace(":", "")
            .replace("127.0.0.1", "")
            .replace("255.255.255.255", "")
            .replace("localhost", "")
            .trim()
            .lowercase().replace(Regex(" \\.{1,2} "), "")
        a.replace("www.", "").replace(".m", "").trim()
            .lowercase()
            .trim()
            .let {
                if (it.startsWith(".") || it.startsWith("ip6-")) {
                    return ""
                }
                return it
            }
    }
}