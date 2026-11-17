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
            val regex = Regex("\\.(js|css|m4s|ts)$|^blob:")
            if (regex.containsMatchIn(url)) {
                return ContentType.OTHER
            }

            return runCatching {
                val request = Request.Builder()
                    .url(url)
                    .headers(headers ?: Headers.headersOf())
                    .get()
                    .build()

                okHttpProxyClient.getProxyOkHttpClient().newCall(request).execute()
                    .use { response ->
                        val contentTypeStr = response.header("Content-Type")

                        when {
                            contentTypeStr?.contains("mpegurl") == true -> ContentType.M3U8
                            contentTypeStr?.contains("dash") == true -> ContentType.MPD
                            contentTypeStr?.contains("video") == true -> ContentType.VIDEO
                            contentTypeStr?.contains(
                                "audio",
                                ignoreCase = true
                            ) == true -> ContentType.AUDIO

                            contentTypeStr?.contains("application/octet-stream") == true -> {
                                response.body.charStream().use { reader ->
                                    val content = reader.read(CharArray(7), 0, 7)
                                        .takeIf { it > 0 } // check if any chars are read
                                        ?.let { String(CharArray(7)) } ?: ""
                                    when {
                                        content.startsWith("#EXTM3U") -> ContentType.M3U8
                                        content.contains("<MPD") -> ContentType.MPD
                                        else -> ContentType.OTHER
                                    }
                                }
                            }

                            else -> ContentType.OTHER
                        }
                    }
            }.getOrDefault(ContentType.OTHER)
        }

        fun parseRequestHeadersToMap(headersJson: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            if (headersJson.isBlank()) return map
            try {
                val cleanedJson = headersJson.replace("\\/", "/")
                val jsonObject = org.json.JSONObject(cleanedJson)

                val requestObj = jsonObject.optJSONObject("request")
                if (requestObj != null) {
                    val keys = requestObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = requestObj.optString(key)
                        if (value.isNotEmpty() && value != "null") {
                            map[key] = value.replace("\\/", "/")
                        }
                    }
                } else {
                    val keys = jsonObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key != "type" && key != "response") {
                            val value = jsonObject.optString(key)
                            if (value.isNotEmpty() && value != "null") {
                                map[key] = value.replace("\\/", "/")
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                try {
                    val jsonObject = org.json.JSONObject(headersJson.replace("\\/", "/"))
                    val keys = jsonObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key != "type") {
                            val value = jsonObject.optString(key)
                            if (value.isNotEmpty() && value != "null") {
                                map[key] = value.replace("\\/", "/")
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return map
        }

        fun parseInterceptorType(headersJson: String): String {
            if (headersJson.isBlank()) return "unknown"
            try {
                val cleanedJson = headersJson.replace("\\/", "/")
                val jsonObject = org.json.JSONObject(cleanedJson)
                val type = jsonObject.optString("type", "unknown")
                if (type.isNotEmpty() && type != "null") {
                    return type
                }
            } catch (_: Exception) {
            }
            return "unknown"
        }

        fun parseResponseHeadersToMap(headersJson: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            if (headersJson.isBlank()) return map
            try {
                val cleanedJson = headersJson.replace("\\/", "/")
                val jsonObject = org.json.JSONObject(cleanedJson)

                val responseObj = jsonObject.optJSONObject("response")
                if (responseObj != null) {
                    val keys = responseObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = responseObj.optString(key)
                        if (value.isNotEmpty() && value != "null") {
                            map["$key"] = value.replace("\\/", "/")
                        }
                    }
                } else {
                    val keys = jsonObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key != "request" && key != "type") {
                            val value = jsonObject.optString(key)
                            if (value.isNotEmpty() && value != "null") {
                                map[key] = value.replace("\\/", "/")
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                try {
                    val jsonObject = org.json.JSONObject(headersJson.replace("\\/", "/"))
                    val keys = jsonObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = jsonObject.optString(key)
                        if (value.isNotEmpty() && value != "null") {
                            map[key] = value.replace("\\/", "/")
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return map
        }
    }
}