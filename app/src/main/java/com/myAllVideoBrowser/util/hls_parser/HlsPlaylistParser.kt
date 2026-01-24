package com.myAllVideoBrowser.util.hls_parser

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import com.myAllVideoBrowser.util.AppLogger
import java.io.IOException
import java.util.LinkedList

/**
 * A hybrid parser for HLS (m3u8) playlists.
 * - For standard video/audio playlists, it uses the robust ExoPlayer HLS library internally.
 * - For non-standard playlists (e.g., a list of image segments for a timelapse),
 *   it uses a simple legacy parser.
 */
object HlsPlaylistParser {

    // --- Public Data Models ---
    sealed class HlsPlaylist {
        abstract val baseUri: String
        abstract val tags: List<HlsTag>
    }

    data class MasterPlaylist(
        override val baseUri: String,
        val variants: List<HlsVariant>,
        val alternateRenditions: List<HlsRendition>, // Audio, Subtitles, etc.
        val sessionData: List<HlsTag.SessionData>,
        override val tags: List<HlsTag>
    ) : HlsPlaylist()

    data class MediaPlaylist(
        override val baseUri: String,
        val segments: List<MediaSegment>,
        val targetDuration: Int,
        val playlistType: PlaylistType?,
        val hasEndList: Boolean,
        val audioGroupId: String?,
        val alternateRenditions: List<HlsRendition>,
        override val tags: List<HlsTag>
    ) : HlsPlaylist() {
        val totalDuration: Double by lazy { segments.sumOf { it.duration } }

        val isFinished: Boolean
            get() = hasEndList
    }

    data class HlsVariant(
        val url: String,
        val formatInfo: Map<String, String>,
        val audioGroupId: String?,
        val videoGroupId: String?,
        val subtitlesGroupId: String?
    ) {
        val bandwidth: Long by lazy { formatInfo["BANDWIDTH"]?.toLongOrNull() ?: 0L }
        val averageBandwidth: Long by lazy { formatInfo["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L }
        val resolution: String? by lazy { formatInfo["RESOLUTION"] }
        val frameRate: Float? by lazy { formatInfo["FRAME-RATE"]?.toFloatOrNull() }
        val codecs: String? by lazy { formatInfo["CODECS"] }
        val height: Int by lazy {
            resolution?.split('x')?.getOrNull(1)?.toIntOrNull() ?: 0
        }
    }

    data class HlsRendition(
        val type: RenditionType,
        val url: String?,
        val groupId: String,
        val name: String,
        val language: String?,
        val isDefault: Boolean,
        val isAutoselect: Boolean,
        val formatInfo: Map<String, String>
    ) {
        val codecs: String? by lazy { formatInfo["CODECS"] }
        val bandwidth: Long by lazy { formatInfo["BANDWIDTH"]?.toLongOrNull() ?: 0L }
    }

    data class HlsEncryptionKey(
        val method: String,
        val uri: String,
        val iv: String? = null
    )

    sealed class MediaSegment {
        abstract val url: String
        abstract val duration: Double
        abstract val title: String?
        abstract val discontinuity: Boolean
        abstract val initializationSegment: InitializationSegment?
    }

    data class UrlMediaSegment(
        override val url: String,
        override val duration: Double,
        override val title: String?,
        override val discontinuity: Boolean,
        override val initializationSegment: InitializationSegment?,
        val byteRange: ByteRange?,
        val parts: List<PartialSegment>,
        val encryptionKey: HlsEncryptionKey? = null
    ) : MediaSegment()

    data class InitializationSegment(
        val url: String, val byteRange: ByteRange?
    )

    data class PartialSegment(
        val url: String, val duration: Double, val isIndependent: Boolean, val byteRange: ByteRange?
    )

    data class ByteRange(
        val length: Long, val offset: Long? = null
    )

    enum class PlaylistType { VOD, EVENT, LIVE }
    enum class RenditionType { AUDIO, VIDEO, SUBTITLES, CLOSED_CAPTIONS }

    data class HlsTag(
        val name: String, val value: String, val attributes: Map<String, String> = emptyMap()
    ) {
        data class SessionData(val dataId: String, val value: String?, val uri: String?)
    }


    // --- Main Parsing Logic (HYBRID APPROACH) ---

    @Throws(IOException::class)
    fun parse(playlistContent: String, baseUri: String): HlsPlaylist {
        try {
            AppLogger.d("HLS Parser: Attempting to parse with ExoPlayer parser. manifest body: $playlistContent")
            return parseWithExoPlayer(playlistContent, baseUri)
        } catch (exoPlayerError: Exception) {
            AppLogger.w("HLS Parser: ExoPlayer parser failed ('${exoPlayerError.message}'). Falling back to legacy parser.")
            try {
                return parseLegacy(playlistContent, baseUri)
            } catch (legacyError: Exception) {
                throw IOException(
                    "Failed to parse HLS playlist with both ExoPlayer and legacy parsers.",
                    legacyError
                )
            }
        }
    }

    private fun isStandardVideoPlaylist(content: String): Boolean {
        return content.contains("#EXT-X-STREAM-INF") ||
                content.contains(".ts") ||
                content.contains(".m4s")
    }

    // --- ExoPlayer-based Parser (for standard playlists) ---

    @OptIn(UnstableApi::class)
    @Throws(IOException::class)
    private fun parseWithExoPlayer(playlistContent: String, baseUri: String): HlsPlaylist {
        val parserFactory: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory()
        val parser = parserFactory.createPlaylistParser()
        val exoPlaylist = parser.parse(baseUri.toUri(), playlistContent.byteInputStream())

        return when (exoPlaylist) {
            is HlsMultivariantPlaylist -> translateMasterPlaylist(exoPlaylist)
            is HlsMediaPlaylist -> translateMediaPlaylist(exoPlaylist) // This now handles keys correctly
            else -> throw IOException("Unknown HLS playlist type parsed by ExoPlayer.")
        }
    }

    // --- Advanced Legacy Parser (for non-standard playlists) ---

    @Throws(IOException::class)
    private fun parseLegacy(playlistContent: String, baseUri: String): HlsPlaylist {
        val lines = LinkedList(playlistContent.lines().map { it.trim() }.filter { it.isNotEmpty() })
        if (lines.isEmpty() || !lines[0].startsWith("#EXTM3U")) {
            throw IOException("Legacy Parse: Invalid HLS playlist, must start with #EXTM3U.")
        }
        lines.removeFirst() // Consume #EXTM3U

        // Check if it's a master playlist from a legacy perspective
        val isMaster =
            lines.any { it.startsWith("#EXT-X-STREAM-INF") || it.startsWith("#EXT-X-I-FRAME-STREAM-INF") }

        return if (isMaster) {
            legacyParseMasterPlaylist(lines, baseUri)
        } else {
            legacyParseMediaPlaylist(lines, baseUri)
        }
    }

    private fun legacyParseMasterPlaylist(
        lines: LinkedList<String>,
        baseUri: String
    ): MasterPlaylist {
        val variants = mutableListOf<HlsVariant>()
        val renditions = mutableListOf<HlsRendition>()
        val sessionData = mutableListOf<HlsTag.SessionData>()
        val otherTags = mutableListOf<HlsTag>()

        while (lines.isNotEmpty()) {
            val line = lines.removeFirst()
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val attributes = parseAttributeList(line.substringAfter(':'))
                    val streamUrl = lines.removeFirstOrNull()?.takeIf { !it.startsWith("#") }
                    if (streamUrl != null) {
                        variants.add(
                            HlsVariant(
                                url = resolveUrl(baseUri, streamUrl),
                                formatInfo = attributes,
                                audioGroupId = attributes["AUDIO"],
                                videoGroupId = attributes["VIDEO"],
                                subtitlesGroupId = attributes["SUBTITLES"]
                            )
                        )
                    }
                }

                line.startsWith("#EXT-X-MEDIA") -> {
                    val attributes = parseAttributeList(line.substringAfter(':'))
                    try {
                        val type = RenditionType.valueOf(attributes["TYPE"] ?: "AUDIO")
                        renditions.add(
                            HlsRendition(
                                type = type,
                                url = attributes["URI"]?.let { resolveUrl(baseUri, it) },
                                groupId = attributes["GROUP-ID"] ?: continue,
                                name = attributes["NAME"] ?: "",
                                language = attributes["LANGUAGE"],
                                isDefault = attributes["DEFAULT"] == "YES",
                                isAutoselect = attributes["AUTOSELECT"] == "YES",
                                formatInfo = attributes
                            )
                        )
                    } catch (e: IllegalArgumentException) {
                        // Ignore unknown rendition types
                    }
                }

                line.startsWith("#EXT-X-SESSION-DATA") -> {
                    val attributes = parseAttributeList(line.substringAfter(':'))
                    sessionData.add(
                        HlsTag.SessionData(
                            dataId = attributes["DATA-ID"] ?: continue,
                            value = attributes["VALUE"],
                            uri = attributes["URI"]
                        )
                    )
                }

                else -> otherTags.add(HlsTag(line, ""))
            }
        }
        return MasterPlaylist(baseUri, variants, renditions, sessionData, otherTags)
    }

    private fun legacyParseMediaPlaylist(
        lines: LinkedList<String>,
        baseUri: String
    ): MediaPlaylist {
        val segments = mutableListOf<UrlMediaSegment>()
        val renditions = mutableListOf<HlsRendition>()
        val otherTags = mutableListOf<HlsTag>()
        var targetDuration = -1
        var playlistType: PlaylistType? = null
        var hasEndList = false
        var currentInitSegment: InitializationSegment? = null
        var currentByteRange: ByteRange? = null
        var discontinuity = false
        var currentParts = mutableListOf<PartialSegment>()
        var currentSegmentDuration: Double? = null
        var currentSegmentTitle: String? = null
        var defaultAudioGroupId: String? = null

        var currentKey: HlsEncryptionKey? = null

        while (lines.isNotEmpty()) {
            val line = lines.removeFirst()
            when {
                line.startsWith("#EXT-X-TARGETDURATION") -> targetDuration =
                    line.substringAfter(':').toIntOrNull() ?: -1

                line.startsWith("#EXT-X-PLAYLIST-TYPE") -> playlistType = try {
                    PlaylistType.valueOf(line.substringAfter(':'))
                } catch (e: Exception) {
                    null
                }

                line.startsWith("#EXT-X-ENDLIST") -> hasEndList = true
                line.startsWith("#EXT-X-MAP") -> {
                    val attributes = parseAttributeList(line.substringAfter(':'))
                    val mapUrl = attributes["URI"] ?: continue
                    val mapByteRange = attributes["BYTERANGE"]?.let { parseByteRange(it) }
                    currentInitSegment =
                        InitializationSegment(resolveUrl(baseUri, mapUrl), mapByteRange)
                }

                line.startsWith("#EXT-X-BYTERANGE") -> currentByteRange =
                    parseByteRange(line.substringAfter(':'))

                line.startsWith("#EXTINF") -> {
                    val info = line.substringAfter(':').split(",", limit = 2)
                    currentSegmentDuration = info.getOrNull(0)?.toDoubleOrNull()
                    currentSegmentTitle = info.getOrNull(1)
                }

                line.startsWith("#EXT-X-DISCONTINUITY") -> discontinuity = true
                line.startsWith("#EXT-X-PART") -> {
                    val attributes = parseAttributeList(line.substringAfter(':'))
                    val partUrl = attributes["URI"] ?: continue
                    currentParts.add(
                        PartialSegment(
                            url = resolveUrl(baseUri, partUrl),
                            duration = attributes["DURATION"]?.toDoubleOrNull() ?: 0.0,
                            isIndependent = attributes["INDEPENDENT"] == "YES",
                            byteRange = attributes["BYTERANGE"]?.let { parseByteRange(it) }
                        )
                    )
                }

                line.startsWith("#EXT-X-MEDIA") -> {
                    val attributes = parseAttributeList(line.substringAfter(':'))
                    try {
                        val type = RenditionType.valueOf(attributes["TYPE"] ?: "AUDIO")
                        if (type == RenditionType.AUDIO && attributes["DEFAULT"] == "YES") {
                            defaultAudioGroupId = attributes["GROUP-ID"]
                        }
                        renditions.add(
                            HlsRendition(
                                type = type,
                                url = attributes["URI"]?.let { resolveUrl(baseUri, it) },
                                groupId = attributes["GROUP-ID"] ?: continue,
                                name = attributes["NAME"] ?: "",
                                language = attributes["LANGUAGE"],
                                isDefault = attributes["DEFAULT"] == "YES",
                                isAutoselect = attributes["AUTOSELECT"] == "YES",
                                formatInfo = attributes
                            )
                        )
                    } catch (e: IllegalArgumentException) { /* Ignore */
                    }
                }

                line.startsWith("#EXT-X-KEY") -> {
                    val attributes = parseAttributeList(line.substringAfter(':'))
                    val method = attributes["METHOD"]
                    val uri = attributes["URI"]
                    if (method != null && uri != null) {
                        currentKey = HlsEncryptionKey(
                            method = method,
                            uri = resolveUrl(baseUri, uri), // Resolve the key's URL
                            iv = attributes["IV"]
                        )
                    }
                }

                !line.startsWith("#") -> {
                    if (currentSegmentDuration != null) {
                        segments.add(
                            UrlMediaSegment(
                                url = resolveUrl(baseUri, line),
                                duration = currentSegmentDuration,
                                title = currentSegmentTitle,
                                discontinuity = discontinuity,
                                initializationSegment = currentInitSegment,
                                byteRange = currentByteRange,
                                parts = currentParts,
                                encryptionKey = currentKey
                            )
                        )
                        // Reset for next segment
                        currentSegmentDuration = null
                        currentSegmentTitle = null
                        currentByteRange =
                            if (currentByteRange?.offset != null) null else currentByteRange
                        discontinuity = false
                        currentParts = mutableListOf()
                    }
                }

                else -> otherTags.add(HlsTag(line, ""))
            }
        }
        return MediaPlaylist(
            baseUri,
            segments,
            targetDuration,
            playlistType,
            hasEndList,
            defaultAudioGroupId,
            renditions,
            otherTags
        )
    }

    // --- Parsers and Helpers for Legacy Implementation ---

    private fun parseAttributeList(attributeString: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        val regex = """([\w-]+)=("([^"]*)"|([^,]*))""".toRegex()
        regex.findAll(attributeString).forEach { matchResult ->
            val key = matchResult.groups[1]?.value?.uppercase()
            val value = matchResult.groups[3]?.value ?: matchResult.groups[4]?.value
            if (key != null && value != null) {
                attributes[key] = value
            }
        }
        return attributes
    }

    private fun parseByteRange(byteRangeString: String): ByteRange {
        val parts = byteRangeString.split('@')
        return if (parts.size == 2) {
            ByteRange(length = parts[0].toLong(), offset = parts[1].toLong())
        } else {
            ByteRange(length = parts[0].toLong())
        }
    }

    // --- Translator Functions and Helpers ---

    @OptIn(UnstableApi::class)
    private fun translateMasterPlaylist(exoMaster: HlsMultivariantPlaylist): MasterPlaylist {
        val variants = exoMaster.variants.map { exoVariant ->
            HlsVariant(
                url = resolveUrl(exoMaster.baseUri, exoVariant.url.toString()),
                formatInfo = mapOf(
                    "BANDWIDTH" to exoVariant.format.bitrate.toString(),
                    "AVERAGE-BANDWIDTH" to (exoVariant.format.averageBitrate.takeIf { it > 0 }
                        ?.toString()),
                    "RESOLUTION" to exoVariant.format.width.takeIf { it > 0 }
                        ?.let { "${it}x${exoVariant.format.height}" },
                    "FRAME-RATE" to exoVariant.format.frameRate.takeIf { it > 0 }?.toString(),
                    "CODECS" to exoVariant.format.codecs
                ).filterValues { it != null }.mapValues { it.value!! },
                audioGroupId = exoVariant.audioGroupId,
                videoGroupId = exoVariant.videoGroupId,
                subtitlesGroupId = exoVariant.subtitleGroupId
            )
        }

        val renditions = (exoMaster.audios + exoMaster.subtitles + exoMaster.closedCaptions)
            .mapNotNull { exoRendition ->
                val type = when (exoRendition.format.sampleMimeType?.substringBefore('/')) {
                    "audio" -> RenditionType.AUDIO
                    "video" -> RenditionType.VIDEO
                    "text", "application" -> RenditionType.SUBTITLES
                    else -> null
                } ?: return@mapNotNull null

                HlsRendition(
                    type = type,
                    url = exoRendition.url?.toString()?.let { resolveUrl(exoMaster.baseUri, it) },
                    groupId = exoRendition.groupId,
                    name = exoRendition.format.label ?: "",
                    language = exoRendition.format.language,
                    isDefault = exoRendition.format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    isAutoselect = exoRendition.format.selectionFlags and C.SELECTION_FLAG_AUTOSELECT != 0,
                    formatInfo = mapOf(
                        "CODECS" to (exoRendition.format.codecs ?: ""),
                        "BANDWIDTH" to (exoRendition.format.bitrate.toString())
                    )
                )
            }
        return MasterPlaylist(
            baseUri = exoMaster.baseUri,
            variants = variants,
            alternateRenditions = renditions,
            sessionData = emptyList(),
            tags = emptyList()
        )
    }

    @OptIn(UnstableApi::class)
    private fun translateMediaPlaylist(exoMedia: HlsMediaPlaylist): MediaPlaylist {
        var hlsEncryptionKey: HlsEncryptionKey? = null
        if (exoMedia.protectionSchemes != null) {
            val firstEncryptedSegment =
                exoMedia.segments.firstOrNull { it.fullSegmentEncryptionKeyUri != null }
            if (firstEncryptedSegment != null) {
                hlsEncryptionKey = HlsEncryptionKey(
                    // TODO: detect method
                    method = "AES-128", // This is the assumed standard
                    uri = firstEncryptedSegment.fullSegmentEncryptionKeyUri!!,
                    iv = firstEncryptedSegment.encryptionIV
                )
                AppLogger.d("HLS Parser (Exo): Successfully translated encryption key. URI: ${hlsEncryptionKey.uri}")
            }
        }

        val segments = exoMedia.segments.map { exoSegment ->
            UrlMediaSegment(
                url = resolveUrl(exoMedia.baseUri, exoSegment.url),
                duration = exoSegment.durationUs / 1_000_000.0,
                title = exoSegment.title,
                discontinuity = exoMedia.segments.getOrNull(exoMedia.segments.indexOf(exoSegment) - 1)?.relativeDiscontinuitySequence != exoSegment.relativeDiscontinuitySequence,
                initializationSegment = exoSegment.initializationSegment?.let {
                    InitializationSegment(
                        url = resolveUrl(exoMedia.baseUri, it.url),
                        byteRange = it.byteRangeLength.takeIf { len -> len > 0 }
                            ?.let { len -> ByteRange(len, it.byteRangeOffset) })
                },
                byteRange = exoSegment.byteRangeLength.takeIf { it > 0 }?.let {
                    ByteRange(it, exoSegment.byteRangeOffset)
                },
                parts = exoSegment.parts.map { exoPart ->
                    PartialSegment(
                        url = resolveUrl(exoMedia.baseUri, exoPart.url),
                        duration = exoPart.durationUs / 1_000_000.0,
                        isIndependent = exoPart.isIndependent,
                        byteRange = exoPart.byteRangeLength.takeIf { it > 0 }?.let {
                            ByteRange(it, exoPart.byteRangeOffset)
                        })
                },
                encryptionKey = hlsEncryptionKey
            )
        }

        val playlistType = when (exoMedia.playlistType) {
            HlsMediaPlaylist.PLAYLIST_TYPE_VOD -> PlaylistType.VOD
            HlsMediaPlaylist.PLAYLIST_TYPE_EVENT -> PlaylistType.EVENT
            else -> PlaylistType.LIVE
        }
        return MediaPlaylist(
            baseUri = exoMedia.baseUri,
            segments = segments,
            targetDuration = (exoMedia.targetDurationUs / 1_000_000).toInt(),
            playlistType = playlistType,
            hasEndList = exoMedia.hasEndTag,
            audioGroupId = null,
            alternateRenditions = emptyList(),
            tags = emptyList()
        )
    }

    private fun resolveUrl(baseUri: String, url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> {
                val base = if (baseUri.endsWith("/") || !baseUri.contains(".")) {
                    baseUri
                } else {
                    baseUri.substringBeforeLast('/') + '/'
                }
                base.removeSuffix("/") + "/" + url.removePrefix("/")
            }
        }
    }
}