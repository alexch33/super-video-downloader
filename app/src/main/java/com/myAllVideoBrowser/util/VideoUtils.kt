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
            val contentTypeStr = response?.header("Content-Type")
            var contentType: ContentType = ContentType.OTHER

            when {
                contentTypeStr?.contains("mpegurl") == true -> {
                    contentType = ContentType.M3U8
                }

                contentTypeStr?.contains("dash") == true -> {
                    contentType = ContentType.MPD
                }

                contentTypeStr?.contains("mp4") == true -> {
                    contentType =  ContentType.MP4
                }

                contentTypeStr?.contains("application/octet-stream") == true -> {
                    val chars = CharArray(7)
                    response.body.charStream().read(chars, 0, 7)
                    response.body.charStream().close()
                    response.body.close()
                    val content = chars.toString()
                    if (content.startsWith("#EXTM3U")) {
                        contentType = ContentType.M3U8
                    } else if (content.contains("<MPD")) {
                        contentType = ContentType.MPD
                    }
                }

                else -> {
                    contentType = ContentType.OTHER
                }
            }

            return contentType
        }
    }
}