package com.myAllVideoBrowser.util

import com.myAllVideoBrowser.ui.main.home.browser.ContentType
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import okhttp3.Headers
import okhttp3.Request

class VideoUtils {
    companion object {
        fun getContentTypeByUrl(
            url: String,
            headers: Headers?,
            okHttpProxyClient: OkHttpProxyClient
        ): ContentType {
            if (url.contains(".js") || url.contains(".css")) {
                return ContentType.OTHER
            }

            val client = okHttpProxyClient.getProxyOkHttpClient()
            val request = Request.Builder()
                .url(url)
                .headers(headers ?: Headers.headersOf())
                .get()
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: Throwable) {
                null
            }
            val contentType = response?.header("Content-Type")

            when {
                contentType?.contains("mpegurl") == true -> {
                    return ContentType.M3U8
                }

                contentType?.contains("dash") == true -> {
                    return ContentType.MPD
                }

                contentType?.contains("mp4") == true -> {
                    return ContentType.MP4
                }

                contentType?.contains("application/octet-stream") == true -> {
                    val content = response.body.string()
                    if (content.startsWith("#EXTM3U")) {
                        return ContentType.M3U8
                    } else if (content.contains("<MPD")) {
                        return ContentType.MPD
                    }

                    return ContentType.OTHER
                }

                else -> {
                    return ContentType.OTHER
                }
            }
        }
    }
}