package com.myAllVideoBrowser.data.remote.service

import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.data.local.model.VideoInfoWrapper
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.CookieUtils
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.AppLogger
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import okhttp3.Request
import org.json.JSONObject
import java.util.*
import kotlin.text.lowercase

interface VideoService {
    fun getVideoInfo(
        url: Request,
        isM3u8: Boolean = false,
        isMpd: Boolean = false,
        isAudioCheck: Boolean
    ): VideoInfoWrapper?
}

open class VideoServiceLocal(
    private val proxyController: CustomProxyController
) : VideoService {
    companion object {
        const val MP4_EXT = "mp4"
        private const val FACEBOOK_HOST = ".facebook."
        private const val COOKIE_HEADER = "Cookie"
    }

    override fun getVideoInfo(
        url: Request,
        isM3u8: Boolean,
        isMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfoWrapper? {
        AppLogger.d("Getting info url...:  $url  ${url.headers["Cookie"]}")

        var result: VideoInfoWrapper? = null

        try {
            result = handleYoutubeDlUrl(url, isM3u8 || isMpd, isAudioCheck)
        } catch (e: Throwable) {
            AppLogger.d("YoutubeDL Error: $e")
        }

        return result
    }

    private fun handleYoutubeDlUrl(
        url: Request,
        isM3u8OrMpd: Boolean = false,
        isAudioCheck: Boolean
    ): VideoInfoWrapper {
        val originalUrl =
            url.url.toString().replace(Regex("&feature=youtu.be|#bottom-sheet"), "").trim()

        if (originalUrl.contains("youtube.com")) {
            throw Exception("Youtube not supported")
        }

        val request = YoutubeDLRequest(originalUrl)

        request.addOption("--dump-json")

        url.headers.forEach { (name, value) ->
            if (name != COOKIE_HEADER) {
                request.addOption("--add-header", "$name:${value}")
            }
        }

        val currentProxy = proxyController.getCurrentRunningProxy()
        if (currentProxy != Proxy.noProxy()) {
            attachProxyToRequest(request, currentProxy)
        }

        val tmpCookieFile = CookieUtils.addCookiesToRequest(originalUrl, request)

        try {
            val response = YoutubeDL.getInstance().execute(request)
            val jsonStr = response.out

            val json = JSONObject(jsonStr)

            val videoTitle = json.optString("title", "no title")
            val videoDuration = json.optLong("duration", 0L)
            val videoThumbnail = json.optString("thumbnail", "")
            val videoExt = json.optString("ext", MP4_EXT)

            val formats = mutableListOf<VideoFormatEntity>()
            val formatsArray = json.optJSONArray("formats")

            if (formatsArray != null) {
                for (i in 0 until formatsArray.length()) {
                    val f = formatsArray.getJSONObject(i)

                    // Extract localization (language)
                    val lang = f.optString("language", "").replace("null", "")
                    val formatNote = f.optString("format_note", "")

                    // Create localization label: "original [en]" or just "[en]"
                    val localizedNote = when {
                        lang.isNotBlank() && !formatNote.isNullOrBlank() -> "$formatNote [$lang]"
                        lang.isNotBlank() -> "[$lang]"
                        else -> formatNote
                    }

                    val entity = VideoFormatEntity(
                        formatId = f.optString("format_id"),
                        format = f.optString("format"),
                        formatNote = localizedNote,
                        url = f.optString("url"),
                        ext = f.optString("ext"),
                        vcodec = f.optString("vcodec", "none"),
                        acodec = f.optString("acodec", "none"),
                        width = f.optInt("width", 0),
                        height = f.optInt("height", 0),
                        fps = f.optInt("fps", 0),
                        asr = f.optDouble("asr", 0.0).toInt(),
                        tbr = f.optDouble("tbr", 0.0).toInt(),
                        abr = f.optDouble("abr", 0.0).toInt(),
                        fileSize = f.optLong("filesize", 0L),
                        duration = videoDuration * 1000,
                        manifestUrl = f.optString("manifest_url"),
                        httpHeaders = url.headers.toMap()
                    )
                    formats.add(entity)
                }
            }

            val filtered = if (originalUrl.contains(FACEBOOK_HOST)) {
                formats.filter {
                    it.formatId?.lowercase(Locale.ROOT)?.contains(Regex("hd|sd")) == true
                }
            } else {
                emptyList()
            }

            val listFormats = VideFormatEntityList(filtered.ifEmpty {
                if (isAudioCheck) {
                    formats
                } else {
                    formats.filter { it.vcodec != "none" || it.acodec == "none" }
                }
            })

            return VideoInfoWrapper(
                VideoInfo(
                    title = videoTitle,
                    formats = listFormats
                ).apply {
                    ext = videoExt
                    thumbnail = videoThumbnail
                    duration = videoDuration * 1000 // Convert to millis
                    this.originalUrl = originalUrl
                    downloadUrls = if (isM3u8OrMpd) emptyList() else listOf(url)
                    isRegularDownload = false
                }
            )
        } catch (e: Throwable) {
            throw e
        } finally {
            tmpCookieFile.delete()
        }
    }

    private fun attachProxyToRequest(request: YoutubeDLRequest, currentProxy: Proxy) {
        val user = proxyController.getProxyCredentials().first
        val password = proxyController.getProxyCredentials().second
        if (user.isNotEmpty() && password.isNotEmpty()) {
            request.addOption(
                "--proxy", "http://${user}:${password}@${currentProxy.host}:${currentProxy.port}"
            )
        } else {
            request.addOption(
                "--proxy", "${currentProxy.host}:${currentProxy.port}"
            )
        }
    }

    private fun videoEntityFromFormat(videoFormat: VideoFormat): VideoFormatEntity {
        return VideoFormatEntity(
            asr = videoFormat.asr,
            tbr = videoFormat.tbr,
            abr = videoFormat.abr,
            format = videoFormat.format,
            formatId = videoFormat.formatId,
            formatNote = videoFormat.formatNote,
            ext = videoFormat.ext,
            preference = videoFormat.preference,
            vcodec = videoFormat.vcodec,
            acodec = videoFormat.acodec,
            width = videoFormat.width,
            height = videoFormat.height,
            fileSize = videoFormat.fileSize,
            fileSizeApproximate = videoFormat.fileSizeApproximate,
            fps = videoFormat.fps,
            url = videoFormat.url,
            manifestUrl = videoFormat.manifestUrl,
            httpHeaders = videoFormat.httpHeaders
        )
    }
}
