package com.myAllVideoBrowser.data.remote.service

import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.data.local.model.VideoInfoWrapper
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

class VideoServiceFfmpeg(private val proxyController: CustomProxyController) : VideoService {
    override fun getVideoInfo(
        url: Request,
        isM3u8OrMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfoWrapper? {
        try {
            return handleFfmpegUrl(url)
        } catch (ffmpegError: Throwable) {
            AppLogger.d("FFmpeg Error: $ffmpegError")
            return null
        }
    }

    private fun handleFfmpegUrl(url: Request): VideoInfoWrapper? {
        val urlString = url.url.toString()

        if (urlString.contains("https://strm.yandex.ru/vod/vh-canvas-converted")) {
            return null;
        }

        val proxyHost = proxyController.getCurrentRunningProxy().host
        val proxyPort = proxyController.getCurrentRunningProxy().port
        val pass = proxyController.getCurrentRunningProxy().password
        val user = proxyController.getCurrentRunningProxy().user

        val commandList = mutableListOf<String>()

        if (proxyHost.isNotEmpty() && proxyPort.isNotEmpty()) {
            val proxyUrl = if (user.isNotEmpty() && pass.isNotEmpty()) {
                "http://$user:$pass@$proxyHost:$proxyPort"
            } else {
                "http://$proxyHost:$proxyPort"
            }

            commandList.add("-http_proxy")
            commandList.add(proxyUrl)
        }

        // Use '-v error' to keep the output clean and focused on the JSON data
        commandList.add("-v")
        commandList.add("error")
        commandList.add("-print_format")
        commandList.add("json")
        commandList.add("-show_format")
        commandList.add("-show_streams")

        var userAgent: String? = null
        val otherHeaders = mutableListOf<String>()
        url.headers.forEach { (name, value) ->
            if (name.equals("User-Agent", ignoreCase = true)) {
                userAgent = value
            } else {
                if (value.isNotEmpty()) {
                    otherHeaders.add("$name: $value")
                }
            }
        }

        userAgent?.let {
            commandList.add("-user_agent")
            commandList.add(it) // No single quotes
        }

        val headersString = otherHeaders.joinToString(separator = "\r\n")

        if (headersString.isNotEmpty()) {
            commandList.add("-headers")
            commandList.add(headersString)
        }

        commandList.add("-i")
        commandList.add(urlString)

        AppLogger.d("Executing FFprobe with arguments: $commandList")

        val session = FFprobeKit.executeWithArguments(commandList.toTypedArray())

        if (ReturnCode.isSuccess(session.returnCode)) {
            val rawOutput = session.output
            AppLogger.d("FFprobe Success. Raw Output: $rawOutput")
            return parseFfprobeOutput(rawOutput, urlString, url.headers.toMap())
        } else {
            AppLogger.d("FFprobe execution failed with return code ${session.returnCode}. Output: ${session.allLogsAsString}")
        }
        return null
    }

    private fun parseFfprobeOutput(
        output: String,
        originalUrl: String,
        originalHeaders: Map<String, String>
    ): VideoInfoWrapper? {
        try {
            // Find the start of the JSON object. FFprobe sometimes includes logs before the JSON.
            val jsonStartIndex = output.indexOfFirst { it == '{' }
            if (jsonStartIndex == -1) {
                AppLogger.d("FFprobe JSON: No JSON object start found in the output.")
                return null
            }
            val jsonOutput = output.substring(jsonStartIndex)

            val jsonObj = JSONObject(jsonOutput)
            val formatNode = jsonObj.optJSONObject("format")
            val streamsNode = jsonObj.optJSONArray("streams")

            if (formatNode == null || streamsNode == null) {
                AppLogger.d("FFprobe JSON: 'format' or 'streams' array not found in JSON.")
                return null
            }

            val formats = mutableListOf<VideoFormatEntity>()
            // Group streams by their 'variant_bitrate' to combine video and audio.
            val streamsByBitrate = mutableMapOf<String, MutableList<JSONObject>>()

            for (i in 0 until streamsNode.length()) {
                val stream = streamsNode.getJSONObject(i)
                val bitrate = stream.optJSONObject("tags")?.optString("variant_bitrate", "0") ?: "0"
                streamsByBitrate.getOrPut(bitrate) { mutableListOf() }.add(stream)
            }

            // Handle cases with no bitrate grouping (single quality streams)
            if (streamsByBitrate.keys.all { it == "0" }) {
                val videoStream =
                    (0 until streamsNode.length()).map { streamsNode.getJSONObject(it) }
                        .firstOrNull { it.optString("codec_type") == "video" }
                val audioStream =
                    (0 until streamsNode.length()).map { streamsNode.getJSONObject(it) }
                        .firstOrNull { it.optString("codec_type") == "audio" }

                if (videoStream != null || audioStream != null) {
                    formats.add(
                        createFormatEntity(
                            videoStream,
                            audioStream,
                            originalUrl,
                            originalHeaders,
                            "0"
                        )
                    )
                }
            } else {
                // PARSING master playlists with multiple programs
                for ((bitrate, streamGroup) in streamsByBitrate) {
                    if (bitrate == "0") continue // Skip streams not belonging to a bitrate group if others exist
                    val videoStream =
                        streamGroup.firstOrNull { it.optString("codec_type") == "video" }
                    val audioStream =
                        streamGroup.firstOrNull { it.optString("codec_type") == "audio" }

                    formats.add(
                        createFormatEntity(
                            videoStream,
                            audioStream,
                            originalUrl,
                            originalHeaders,
                            bitrate
                        )
                    )
                }
            }

            if (formats.isEmpty()) {
                AppLogger.d("FFprobe JSON: Could not parse any valid formats from the streams.")
                return null
            }

            val videoTitle =
                formatNode.optJSONObject("tags")?.optString("title") ?: "HLS Video Stream"
            val duration = formatNode.optString("duration", "0").toDoubleOrNull()?.toLong() ?: 0

            return VideoInfoWrapper(
                VideoInfo(
                    title = videoTitle,
                    originalUrl = originalUrl,
                    formats = VideFormatEntityList(formats.sortedByDescending { it.height })
                ).apply {
                    ext = "mp4"
                    thumbnail = ""
                    this.duration = duration
                    isRegularDownload = false
                }
            )

        } catch (e: JSONException) {
            AppLogger.e("FFprobe JSON: Parsing failed with a JSONException. Error: ${e.message}")
            return null
        } catch (e: Exception) {
            AppLogger.e("FFprobe JSON: Parsing failed with an exception. Error: ${e.message}")
            return null
        }
    }

    /**
     * Helper function to create a VideoFormatEntity from JSON stream objects.
     */
    private fun createFormatEntity(
        videoStream: JSONObject?,
        audioStream: JSONObject?,
        originalUrl: String,
        originalHeaders: Map<String, String>,
        bitrate: String
    ): VideoFormatEntity {
        // Use the "index" from the JSON object for the ID.
        val videoIndex = videoStream?.optInt("index", -1) ?: -1
        val audioIndex = audioStream?.optInt("index", -1) ?: -1
        // Create an ID like "v0-a1" or "v2-aN" if audio is missing
        val formatId = "v${videoIndex}-a${audioIndex}".replace("-1", "N")

        val height = videoStream?.optInt("height") ?: 0
        val width = videoStream?.optInt("width") ?: 0

        val vcodec = videoStream?.optString("codec_name", "none") ?: "none"
        val acodec = audioStream?.optString("codec_name", "none") ?: "none"
        val bitrateLong = bitrate.toLongOrNull()

        return VideoFormatEntity(
            formatId = formatId,
            format = "hls-$height-${bitrateLong ?: 0}",
            formatNote = if (height > 0) "${height}p (HLS)" else "Audio Only (HLS)",
            ext = "mp4",
            vcodec = vcodec,
            acodec = acodec,
            url = originalUrl,
            manifestUrl = originalUrl,
            httpHeaders = originalHeaders,
            height = height,
            width = width,
//            bitrate = bitrateLong
        )
    }
}
