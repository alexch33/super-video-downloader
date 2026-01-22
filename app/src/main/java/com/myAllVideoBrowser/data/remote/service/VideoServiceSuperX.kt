package com.myAllVideoBrowser.data.remote.service

import com.myAllVideoBrowser.data.local.model.VideoInfoWrapper
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.hls_parser.HlsPlaylistParser
import com.myAllVideoBrowser.util.hls_parser.MpdPlaylistParser
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration

/**
 * A dedicated service that parses HLS (.m3u8) and MPD (.mpd) manifests
 * to discover available video and audio streams. It uses efficient, in-app
 * parsers instead of FFprobe for speed and reliability.
 */
class VideoServiceSuperX(
    private val client: OkHttpProxyClient
) : VideoService {

    override fun getVideoInfo(
        url: Request, isM3u8: Boolean, isMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfoWrapper? {
        if (!(isM3u8 || isMpd)) {
            return null
        }

        return try {
            handlePlaylistUrl(url, isM3u8, isMpd)
        } catch (e: Throwable) {
            AppLogger.d("PlaylistService Error: Failed to parse manifest. ${e.message}")
            null
        }
    }

    /**
     * Fetches the manifest content and delegates parsing to the appropriate
     * HLS or MPD parsing function based on the URL.
     */
    private fun handlePlaylistUrl(
        url: Request,
        isM3u8: Boolean,
        isMpd: Boolean
    ): VideoInfoWrapper? {
        val urlString = url.url.toString()
        AppLogger.d("PlaylistService: Fetching manifest from $urlString")

        // 1. Fetch the manifest content
        val response = client.getProxyOkHttpClient().newCall(url).execute()
        val content = response.body.string()
        AppLogger.d("Manifest body: $content")

        if (!response.isSuccessful || content.isEmpty()) {
            throw IOException("Failed to download playlist at $urlString. HTTP ${response.code}")
        }

        // 2. Determine playlist type and parse
        return if (isM3u8) {
            AppLogger.d("PlaylistService: Detected HLS manifest.")
            val manifest = HlsPlaylistParser.parse(content, urlString)
            parseHlsManifest(manifest, url.headers.toMap())
        } else if (isMpd) {
            AppLogger.d("PlaylistService: Detected MPD manifest.")
            val manifest = MpdPlaylistParser.parse(content, urlString)
            parseMpdManifest(manifest, url.headers.toMap())
        } else {
            AppLogger.w("PlaylistService: URL was flagged as a playlist but extension is not .m3u8 or .mpd.")
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
                // Get duration and live status from the first child playlist
                val firstMediaPlaylist = fetchFirstMediaPlaylist(manifest, headers)
                isLive = firstMediaPlaylist?.hasEndList == false
                duration =
                    if (isLive) 0L else (firstMediaPlaylist?.totalDuration?.times(1000))?.toLong()
                        ?: 0L

                if (manifest.variants.isEmpty()) {
                    AppLogger.d("HLS Parse: Master playlist has no variants.")
                    return null
                }

                // --- Handle Separate Audio and Video ---

                // 1. Find all available audio renditions, grouping them by their GROUP-ID.
                val audioRenditionsByGroup: Map<String, List<HlsPlaylistParser.HlsRendition>> =
                    manifest.alternateRenditions
                        .filter { it.type == HlsPlaylistParser.RenditionType.AUDIO && it.url != null }
                        .groupBy { it.groupId }

                // 2. Process each video variant
                manifest.variants.mapNotNullTo(formats) { variant ->
                    val height = variant.resolution?.split("x")?.getOrNull(1)?.toIntOrNull() ?: 0
                    val width = variant.resolution?.split("x")?.getOrNull(0)?.toIntOrNull() ?: 0

                    // This is a video-only or muxed video variant.
                    if (width > 0 && height > 0) {
                        val videoUrl = variant.url // The URL to the video media playlist

                        // Find the best associated audio rendition using the variant's audio group ID.
                        val audioGroupId = variant.audioGroupId
                        val associatedAudioRendition =
                            audioRenditionsByGroup[audioGroupId]?.firstOrNull()
                        val audioUrl = associatedAudioRendition?.url

                        // The final manifest URL for downloading is the MASTER playlist URL.
                        // The URLs for video/audio tracks will be selected by the downloader later.
                        VideoFormatEntity(
                            formatId = "hls-${height}p-${variant.bandwidth}",
                            format = "hls-${height}p-${variant.bandwidth}",
                            formatNote = "${height}p",
                            ext = "mp4",
                            vcodec = variant.codecs?.substringBefore(",") ?: "unknown",
                            // If we have separate audio, its codec is in the rendition tag.
                            acodec = associatedAudioRendition?.codecs
                                ?: variant.codecs?.substringAfter(",", "unknown") ?: "unknown",
                            // The downloader only needs the MASTER manifest URL.
                            url = manifest.baseUri,
                            manifestUrl = manifest.baseUri,
                            // Store the specific media playlist URLs if needed for later selection.
                            // We will use the formatId to find these again.
                            videoOnlyUrl = videoUrl,
                            audioOnlyUrl = audioUrl,
                            httpHeaders = headers,
                            height = height,
                            width = width,
                            bitrate = variant.bandwidth + (associatedAudioRendition?.bandwidth
                                ?: 0), // Combined bitrate
                            duration = duration
                        )
                    } else {
                        // This is an audio-only variant, which we can ignore if we are properly
                        // pairing video variants with audio renditions.
                        null
                    }
                }
            }

            is HlsPlaylistParser.MediaPlaylist -> {
                // This logic for single media playlists remains the same and is correct.
                title = "HLS Stream"
                duration = (manifest.totalDuration * 1000).toLong()
                isLive = !manifest.hasEndList

                // Heuristic to guess height from URL if not available
                val inferredHeight =
                    manifest.baseUri.substringAfterLast('-').substringBefore('.').toIntOrNull()
                        ?: 480

                formats.add(
                    VideoFormatEntity(
                        formatId = "hls-media",
                        format = "hls-${inferredHeight}p",
                        formatNote = "${inferredHeight}p",
                        ext = "mp4",
                        vcodec = "unknown",
                        acodec = "unknown",
                        url = manifest.baseUri,
                        manifestUrl = manifest.baseUri,
                        httpHeaders = headers,
                        height = inferredHeight,
                        width = 0,
                        duration = duration
                    )
                )
            }
        }

        if (formats.isEmpty()) {
            AppLogger.d("HLS Parse: No suitable video formats could be created.")
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

    /**
     * Translates a parsed MPD Manifest into a VideoInfoWrapper object
     * containing a list of selectable video formats.
     */
    private fun parseMpdManifest(
        manifest: MpdPlaylistParser.MpdManifest, headers: Map<String, String>
    ): VideoInfoWrapper? {
        // 1. Detect if the stream is live. This is the primary indicator.
        val isLive = manifest.type == "dynamic"

        // 2. Determine duration. For live streams, duration is 0. For VoD, parse it.
        val durationInMillis = if (isLive) {
            0L
        } else {
            parseIso8601Duration(manifest.mediaPresentationDuration)
        }

        // 3. Find all video representations across all periods and adaptation sets.
        val allVideoRepresentations = manifest.periods.flatMap { it.adaptationSets }
            .filter { it.mimeType?.startsWith("video/") == true }
            .flatMap { it.representations }

        if (allVideoRepresentations.isEmpty()) {
            AppLogger.d("MPD Parse: No video representations found.")
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
                // A better fallback for acodec in case there is no comma
                acodec = if (rep.codecs?.contains(',') == true) rep.codecs.substringAfter(
                    ","
                ) else "unknown",
                url = manifest.baseUri, // The download URL is always the manifest URL
                manifestUrl = manifest.baseUri,
                httpHeaders = headers,
                height = rep.height,
                width = rep.width,
                bitrate = rep.bandwidth,
                duration = durationInMillis,
            )
        }

        if (formats.isEmpty()) {
            AppLogger.d("MPD Parse: No suitable video representations found.")
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

    /**
     * Parses an ISO 8601 duration string (like "PT0H1M35.378S" or "P1DT2H")
     * into milliseconds using java.time.Duration.
     */
    private fun parseIso8601Duration(duration: String?): Long {
        if (duration.isNullOrBlank()) {
            return 0L
        }
        return try {
            Duration.parse(duration).toMillis()
        } catch (e: Exception) {
            AppLogger.e("Failed to parse ISO 8601 duration string: $duration \n${e.printStackTrace()}")
            0L
        }
    }


    /**
     * Helper function to fetch the first available media playlist from a master playlist.
     * This is used to accurately determine if the stream is live.
     */
    private fun fetchFirstMediaPlaylist(
        manifest: HlsPlaylistParser.MasterPlaylist,
        headers: Map<String, String>
    ): HlsPlaylistParser.MediaPlaylist? {
        // Find the first variant that has a valid URL.
        val firstVariantUrl = manifest.variants.firstOrNull()?.url ?: return null

        return try {
            val request = Request.Builder().url(firstVariantUrl).apply {
                headers.forEach { (key, value) -> addHeader(key, value) }
            }.build()
            val response = client.getProxyOkHttpClient().newCall(request).execute()
            val content = response.body.string()

            if (!response.isSuccessful || content.isEmpty()) {
                return null
            }
            // Parse the content of the child playlist.
            val mediaPlaylist = HlsPlaylistParser.parse(content, firstVariantUrl)
            mediaPlaylist as? HlsPlaylistParser.MediaPlaylist
        } catch (e: Exception) {
            AppLogger.e("Failed to fetch child media playlist: $firstVariantUrl ${e.printStackTrace()}")
            null
        }
    }
}
