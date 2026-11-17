package com.myAllVideoBrowser.data.local.service

import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.VideoInfoWrapper
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.ContextUtils
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A local service that constructs video or audio [VideoInfoWrapper] objects directly
 * from URL, headers, content type, and content length provided locally (e.g. via JS/WebView callbacks)
 * without making secondary network requests.
 */
@Singleton
class RegularLocalVideoService @Inject constructor() {

    fun getVideoAudioInfoLocal(
        url: String,
        headers: Map<String, String>,
        contentType: String,
        contentLength: Long,
        title: String = "no_title",
        originalUrl: String = ""
    ): VideoInfoWrapper? {
        try {
            if (url.isBlank() || !url.startsWith("http")) {
                return null
            }

            val isAudio = contentType.contains("audio", true) ||
                    url.endsWith(".mp3", true) ||
                    url.endsWith(".m4a", true) ||
                    url.endsWith(".aac", true) ||
                    url.endsWith(".wav", true)

            val isVideo = contentType.contains("video", true) ||
                    url.endsWith(".mp4", true) ||
                    url.endsWith(".m3u8", true) ||
                    url.endsWith(".webm", true) ||
                    url.endsWith(".mkv", true) ||
                    url.endsWith(".mov", true)

            val ext = when {
                isAudio -> "mp3"
                isVideo -> "mp4"
                else -> "mp4"
            }

            val request = Request.Builder()
                .url(url.trim())
                .headers(headers.toHeaders())
                .build()

            val downloadUrls = listOf(request)

            val formatName = if (isAudio) {
                "audio"
            } else {
                runCatching {
                    ContextUtils.getApplicationContext().getString(R.string.player_resolution)
                }.getOrNull() ?: "HD"
            }

            val videoInfo = VideoInfo(
                downloadUrls = downloadUrls,
                title = title,
                ext = ext,
                originalUrl = originalUrl,
                formats = VideFormatEntityList(
                    mutableListOf(
                        VideoFormatEntity(
                            formatId = "0",
                            format = formatName,
                            ext = ext,
                            url = url,
                            httpHeaders = headers,
                            fileSize = contentLength
                        )
                    )
                ),
                isRegularDownload = true
            )

            return VideoInfoWrapper(videoInfo)
        } catch (e: Throwable) {
            e.printStackTrace()
            return null
        }
    }
}
