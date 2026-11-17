package com.myAllVideoBrowser.data.local.service

import com.myAllVideoBrowser.data.local.model.VideoInfoWrapper
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.DetectionLogger
import com.myAllVideoBrowser.util.hls_parser.HlsPlaylistParser
import com.myAllVideoBrowser.util.hls_parser.MpdPlaylistParser
import java.time.Duration
import javax.inject.Inject

class VideoServiceSuperXLocal @Inject constructor() {
    fun getVideoInfo(
        baseUrl: String,
        manifestBody: String,
        isM3u8: Boolean = false,
        isMpd: Boolean = false,
        headers: Map<String, String> = emptyMap()
    ): VideoInfoWrapper? {
        val urlType = if (isM3u8) {
            "M3U8"
        } else {
            "MPD"
        }

        DetectionLogger.log("SUPERX OFFLINE $urlType", "START", baseUrl)

        if (!(isM3u8 || isMpd)) {
            return null
        }

        if (manifestBody.isBlank()) {
            AppLogger.d("PlaylistService Offline: Manifest body is empty.")
            return null
        }

        return try {
            val result = parseManifestContent(baseUrl, manifestBody, isM3u8, isMpd, headers)
            if (result != null) {
                DetectionLogger.log("SUPERX OFFLINE $urlType", "SUCCESS", baseUrl)
            }
            result
        } catch (e: Throwable) {
            DetectionLogger.log("SUPERX OFFLINE $urlType", "FAILED", baseUrl, e.message ?: "unknown")
            AppLogger.d("PlaylistService Offline Error: Failed to parse manifest. ${e.message}")
            null
        }
    }

    private fun parseManifestContent(
        baseUrl: String,
        content: String,
        isM3u8: Boolean,
        isMpd: Boolean,
        headers: Map<String, String>
    ): VideoInfoWrapper? {
        return if (isM3u8) {
            AppLogger.d("PlaylistService Offline: Parsing HLS manifest.")
            val manifest = HlsPlaylistParser.parse(content, baseUrl)
            parseHlsManifest(manifest, headers)
        } else if (isMpd) {
            AppLogger.d("PlaylistService Offline: Parsing MPD manifest.")
            val manifest = MpdPlaylistParser.parse(content, baseUrl)
            parseMpdManifest(manifest, headers)
        } else {
            AppLogger.w("PlaylistService Offline: URL was flagged as a playlist but neither M3U8 nor MPD.")
            null
        }
    }

    private fun parseHlsManifest(
        manifest: HlsPlaylistParser.HlsPlaylist, headers: Map<String, String>
    ): VideoInfoWrapper? {
        val formats = mutableListOf<VideoFormatEntity>()
        val title: String
        var duration: Long
        val isLive: Boolean

        when (manifest) {
            is HlsPlaylistParser.MasterPlaylist -> {
                title = "HLS Stream"
                isLive = false
                duration = 0L

                if (manifest.variants.isEmpty()) {
                    AppLogger.d("HLS Parse Offline: Master playlist has no variants.")
                    return null
                }

                val audioRenditionsByGroup: Map<String, List<HlsPlaylistParser.HlsRendition>> =
                    manifest.alternateRenditions
                        .filter { it.type == HlsPlaylistParser.RenditionType.AUDIO && it.url != null }
                        .groupBy { it.groupId }

                manifest.variants.mapNotNullTo(formats) { variant ->
                    val height = variant.resolution?.split("x")?.getOrNull(1)?.toIntOrNull() ?: 0
                    val width = variant.resolution?.split("x")?.getOrNull(0)?.toIntOrNull() ?: 0

                    if (width > 0 && height > 0) {
                        val videoUrl = variant.url
                        val audioGroupId = variant.audioGroupId
                        val associatedAudioRendition =
                            audioRenditionsByGroup[audioGroupId]?.firstOrNull()
                        val audioUrl = associatedAudioRendition?.url

                        VideoFormatEntity(
                            formatId = "hls-${height}p-${variant.bandwidth}",
                            format = "hls-${height}p-${variant.bandwidth}",
                            formatNote = "${height}p",
                            ext = "mp4",
                            vcodec = variant.codecs?.substringBefore(",") ?: "unknown",
                            acodec = associatedAudioRendition?.codecs
                                ?: variant.codecs?.substringAfter(",", "unknown") ?: "unknown",
                            url = manifest.baseUri,
                            manifestUrl = manifest.baseUri,
                            videoOnlyUrl = videoUrl,
                            audioOnlyUrl = audioUrl,
                            httpHeaders = headers,
                            height = height,
                            width = width,
                            bitrate = variant.bandwidth + (associatedAudioRendition?.bandwidth
                                ?: 0),
                            duration = duration
                        )
                    } else {
                        null
                    }
                }
            }

            is HlsPlaylistParser.MediaPlaylist -> {
                title = "HLS Stream"
                duration = (manifest.totalDuration * 1000).toLong()
                isLive = !manifest.hasEndList

                val inferredHeight =
                    manifest.baseUri.substringAfterLast('-').substringBefore('.').toIntOrNull()
                val formatString =
                    if (inferredHeight == null) "hls-unknown" else "hls-${inferredHeight}p"

                formats.add(
                    VideoFormatEntity(
                        formatId = "hls-media",
                        format = formatString,
                        formatNote = formatString,
                        ext = "mp4",
                        vcodec = "unknown",
                        acodec = "unknown",
                        url = manifest.baseUri,
                        manifestUrl = manifest.baseUri,
                        httpHeaders = headers,
                        height = inferredHeight ?: 480,
                        width = 0,
                        duration = duration
                    )
                )
            }
        }

        if (formats.isEmpty()) {
            AppLogger.d("HLS Parse Offline: No suitable video formats could be created.")
            return null
        }

        return VideoInfoWrapper(
            VideoInfo(
                title = title,
                originalUrl = manifest.baseUri,
                formats = VideFormatEntityList(formats.sortedByDescending { it.bitrate })
            ).apply {
                this.ext = "mp4"
                this.isRegularDownload = false
                this.duration = duration
                this.isLive = isLive
                this.isDetectedBySuperX = true
            }
        )
    }

    private fun parseMpdManifest(
        manifest: MpdPlaylistParser.MpdManifest, headers: Map<String, String>
    ): VideoInfoWrapper? {
        val isLive = manifest.type == "dynamic"

        val durationInMillis = if (isLive) {
            0L
        } else {
            parseIso8601Duration(manifest.mediaPresentationDuration)
        }

        val allVideoRepresentations = manifest.periods.flatMap { it.adaptationSets }
            .filter { it.mimeType?.startsWith("video/") == true }
            .flatMap { it.representations }

        if (allVideoRepresentations.isEmpty()) {
            AppLogger.d("MPD Parse Offline: No video representations found.")
            return null
        }

        val formats = allVideoRepresentations.mapNotNull { rep ->
            if (rep.height == 0 || rep.width == 0) return@mapNotNull null

            VideoFormatEntity(
                formatId = "mpd-${rep.height}p-${rep.bandwidth}",
                format = "mpd-${rep.height}p-${rep.bandwidth}",
                formatNote = "${rep.height}p",
                ext = "mp4",
                vcodec = rep.codecs?.substringBefore(",") ?: "unknown",
                acodec = if (rep.codecs?.contains(',') == true) rep.codecs.substringAfter(
                    ","
                ) else "unknown",
                url = manifest.baseUri,
                manifestUrl = manifest.baseUri,
                httpHeaders = headers,
                height = rep.height,
                width = rep.width,
                bitrate = rep.bandwidth,
                duration = durationInMillis,
            )
        }

        if (formats.isEmpty()) {
            AppLogger.d("MPD Parse Offline: No suitable video representations found.")
            return null
        }

        return VideoInfoWrapper(
            VideoInfo(
                title = "MPEG-DASH Stream",
                originalUrl = manifest.baseUri,
                formats = VideFormatEntityList(formats.sortedByDescending { it.height })
            ).apply {
                this.ext = "mp4"
                this.isRegularDownload = false
                this.duration = durationInMillis
                this.isLive = isLive
                isDetectedBySuperX = true
            })
    }

    private fun parseIso8601Duration(duration: String?): Long {
        if (duration.isNullOrBlank()) return 0L

        try {
            return Duration.parse(duration).toMillis()
        } catch (_: Throwable) {
            // If strict parsing fails, use regex for a more flexible extraction
        }

        val pattern =
            Regex("PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?")

        return try {
            val matchResult = pattern.matchEntire(duration)
            if (matchResult != null) {
                val hours = matchResult.groupValues[1].ifEmpty { "0" }.toDouble()
                val minutes = matchResult.groupValues[2].ifEmpty { "0" }.toDouble()
                val seconds = matchResult.groupValues[3].ifEmpty { "0" }.toDouble()

                val totalSeconds = (hours * 3600) + (minutes * 60) + seconds
                (totalSeconds * 1000).toLong()
            } else {
                AppLogger.e("Unrecognized duration format: $duration")
                0L
            }
        } catch (e: Throwable) {
            AppLogger.e("Error calculating duration for: $duration - ${e.message}")
            0L
        }
    }
}
