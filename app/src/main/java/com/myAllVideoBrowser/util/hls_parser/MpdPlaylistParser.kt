package com.myAllVideoBrowser.util.hls_parser

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
import com.myAllVideoBrowser.util.AppLogger
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URL
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

object MpdPlaylistParser {

    // --- Public Data Models ---
    data class MpdManifest(
        val baseUri: String,
        val mediaPresentationDuration: String?,
        val periods: List<Period>,
        val type: String?,
        val minimumUpdatePeriod: Double?,
        val timeShiftBufferDepth: Double?
    )

    data class Period(
        val id: String?,
        val duration: Double?,
        val adaptationSets: List<AdaptationSet>
    )

    data class AdaptationSet(
        val id: Long,
        val mimeType: String?,
        val representations: List<MpdRepresentation>
    )

    data class MpdRepresentation(
        val id: String?,
        val bandwidth: Long,
        val width: Int,
        val height: Int,
        val codecs: String?,
        val baseUrls: List<String>,
        val segments: List<Segment>,
        val initializationUrl: String?
    ) {
        var manifest: MpdManifest? = null
    }

    // A simplified segment model
    data class Segment(
        val url: String,
        val durationSeconds: Double
    )


    // --- Main Parsing Logic ---

    @OptIn(UnstableApi::class)
    @Throws(IOException::class)
    fun parse(manifestContent: String, baseUri: String): MpdManifest {
        return try {
            // 1. Instantiate ExoPlayer's public parser.
            val parser = DashManifestParser()
            val inputStream: InputStream = manifestContent.byteInputStream()
            val manifestUri = baseUri.toUri()

            // 2. Use the parser to get ExoPlayer's native DashManifest object.
            val exoManifest = parser.parse(manifestUri, inputStream)

            // 3. "Translate" the complex ExoPlayer manifest into our simple, clean data models.
            translateManifest(exoManifest, baseUri, manifestContent)

        } catch (_: Exception) {
            // If ExoPlayer fails, attempt to parse with the custom fallback parser.
            try {
                parseWithFallback(manifestContent, baseUri)
            } catch (fallbackException: Exception) {
                throw IOException(
                    "Failed to parse MPD manifest using both ExoPlayer and fallback parser",
                    fallbackException
                )
            }
        }
    }

    // --- Private Translator Functions ---
    @OptIn(UnstableApi::class)
    private fun translateManifest(
        exoManifest: DashManifest,
        baseUri: String,
        manifestContent: String
    ): MpdManifest {
        val periods = (0 until exoManifest.periodCount).map { i ->
            val exoPeriod = exoManifest.getPeriod(i)
            val periodDurationMs = exoManifest.getPeriodDurationMs(i)
            val periodDuration =
                if (periodDurationMs == C.TIME_UNSET) null else periodDurationMs / 1000.0

            Period(
                id = exoPeriod.id,
                duration = periodDuration,
                adaptationSets = exoPeriod.adaptationSets.map { exoAdaptationSet ->
                    AdaptationSet(
                        id = exoAdaptationSet.id,
                        mimeType = exoAdaptationSet.representations.firstOrNull()?.format?.sampleMimeType,
                        representations = exoAdaptationSet.representations.map { exoRepresentation ->
                            translateRepresentation(
                                exoRepresentation,
                                periodDuration,
                                baseUri,
                                exoManifest.dynamic
                            )
                        }
                    )
                }
            )
        }

        val manifestDurationString = extractMediaPresentationDuration(manifestContent)
        val manifestType = if (exoManifest.dynamic) "dynamic" else "static"
        val minimumUpdatePeriodSeconds =
            if (exoManifest.minUpdatePeriodMs == C.TIME_UNSET) null else exoManifest.minUpdatePeriodMs / 1000.0
        val timeShiftBufferDepthSeconds =
            if (exoManifest.timeShiftBufferDepthMs == C.TIME_UNSET) null else exoManifest.timeShiftBufferDepthMs / 1000.0

        val manifest = MpdManifest(
            baseUri,
            manifestDurationString,
            periods,
            manifestType,
            minimumUpdatePeriodSeconds,
            timeShiftBufferDepthSeconds
        )

        manifest.periods.forEach { period ->
            period.adaptationSets.forEach { adaptationSet ->
                adaptationSet.representations.forEach { representation ->
                    representation.manifest = manifest
                }
            }
        }

        return manifest
    }

    private fun extractMediaPresentationDuration(manifestContent: String): String? {
        val pattern = Pattern.compile("""mediaPresentationDuration="([^"]*)"""")
        val matcher = pattern.matcher(manifestContent)
        return if (matcher.find()) matcher.group(1) else null
    }

    @OptIn(UnstableApi::class)
    private fun translateRepresentation(
        exoRep: Representation,
        periodDuration: Double?,
        baseUri: String,
        isLive: Boolean
    ): MpdRepresentation {
        val segments = mutableListOf<Segment>()
        val index = exoRep.index
        val initUrlTemplate = exoRep.initializationUri?.resolveUri(baseUri)?.toString()

        if (index == null) {
            AppLogger.d("MPD Parser: No segment index found for representation ${exoRep.format.id}.")
        } else {
            if (isLive) {
                // Case 1: LIVE stream logic (as determined by the top-level manifest)
                AppLogger.d("MPD Parser: Detected LIVE index for ${exoRep.format.id}.")
                try {
                    val liveEdgeUs =
                        System.currentTimeMillis() * 1000 - exoRep.presentationTimeOffsetUs
                    val segmentNum = index.getSegmentNum(liveEdgeUs, C.TIME_UNSET)

                    if (segmentNum > 0) {
                        val segmentUrl =
                            index.getSegmentUrl(segmentNum).resolveUri(baseUri).toString()
                        val segmentDuration =
                            index.getDurationUs(segmentNum, C.TIME_UNSET) / 1_000_000.0
                        segments.add(Segment(segmentUrl, segmentDuration))
                        AppLogger.d("MPD Parser (Live): Calculated live edge segment #$segmentNum for ${exoRep.format.id}")
                    } else {
                        AppLogger.w("MPD Parser (Live): Calculated a non-positive segment number ($segmentNum), skipping.")
                    }
                } catch (e: Exception) {
                    AppLogger.e("MPD Parser (Live): Failed to calculate live edge segment ${e.message}")
                    e.printStackTrace()
                }
            } else {
                // Case 2: VOD (Static) stream logic
                AppLogger.d("MPD Parser: Detected VOD index for ${exoRep.format.id}.")
                if (periodDuration != null) {
                    val periodDurationUs = (periodDuration * 1_000_000).toLong()
                    val segmentCount = index.getSegmentCount(periodDurationUs)

                    if (segmentCount > 0) {
                        val firstSegmentNum = index.firstSegmentNum
                        (0 until segmentCount).forEach { i ->
                            val segmentNum = firstSegmentNum + i
                            val segmentRangedUri = index.getSegmentUrl(segmentNum)
                            segments.add(
                                Segment(
                                    url = segmentRangedUri.resolveUri(baseUri).toString(),
                                    durationSeconds = index.getDurationUs(
                                        segmentNum,
                                        periodDurationUs
                                    ) / 1_000_000.0
                                )
                            )
                        }
                        AppLogger.d("MPD Parser (VOD): Found $segmentCount segments for ${exoRep.format.id}.")
                    } else {
                        AppLogger.d("MPD Parser (VOD): No segments found for ${exoRep.format.id} (count: $segmentCount).")
                    }
                }
            }
        }

        val resolvedBaseUrls = exoRep.baseUrls.map { baseUrlObject ->
            resolveUrl(baseUri, baseUrlObject.url)
        }
        val finalBaseUrls = resolvedBaseUrls.ifEmpty { listOf(baseUri) }

        return MpdRepresentation(
            id = exoRep.format.id,
            bandwidth = exoRep.format.bitrate.toLong(),
            width = exoRep.format.width,
            height = exoRep.format.height,
            codecs = exoRep.format.codecs,
            baseUrls = finalBaseUrls,
            segments = segments,
            initializationUrl = initUrlTemplate?.replace(
                "\$RepresentationID\$",
                exoRep.format.id ?: ""
            )
        )
    }

    private fun resolveUrl(baseUri: String, url: String): String {
        return try {
            // If the URL is already absolute, the constructor will use it as is.
            // If the URL is relative, it will be resolved against the baseUri context.
            URL(URL(baseUri), url).toString()
        } catch (_: MalformedURLException) {
            // Fallback for cases where the baseUri might not be a perfect URL (e.g., missing a slash)
            // This logic is similar to your original but slightly safer.
            when {
                url.startsWith("http://") || url.startsWith("https://") -> url
                else -> {
                    val base = baseUri.substringBeforeLast('/') + '/'
                    base + url.removePrefix("/")
                }
            }
        }
    }

    // --- Fallback Parser ---
    private fun parseWithFallback(manifestContent: String, baseUri: String): MpdManifest {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(ByteArrayInputStream(manifestContent.toByteArray()))
        val xpathFactory = XPathFactory.newInstance()
        val xpath = xpathFactory.newXPath()

        val mediaPresentationDuration = xpath.evaluate("/MPD/@mediaPresentationDuration", document)
        val type = xpath.evaluate("/MPD/@type", document)

        val minimumUpdatePeriodStr = xpath.evaluate("/MPD/@minimumUpdatePeriod", document)
        val minimumUpdatePeriod = minimumUpdatePeriodStr?.let {
            try {
                it.replace("PT", "").replace("S", "").toDoubleOrNull()
            } catch (_: Exception) {
                null
            }
        }
        val timeShiftBufferDepthStr = xpath.evaluate("/MPD/@timeShiftBufferDepth", document)
        val timeShiftBufferDepth = timeShiftBufferDepthStr?.let {
            try {
                it.replace("PT", "").replace("S", "").toDoubleOrNull()
            } catch (_: Exception) {
                null
            }
        }

        val periodNodes =
            xpath.evaluate("/MPD/Period", document, XPathConstants.NODESET) as NodeList
        val periods = (0 until periodNodes.length).map { i ->
            val periodNode = periodNodes.item(i) as Element
            val periodId = periodNode.getAttribute("id")
            val periodDurationStr = periodNode.getAttribute("duration")
            val periodDuration = periodDurationStr.let {
                try {
                    it.replace("PT", "").replace("S", "").toDoubleOrNull()
                } catch (_: Exception) {
                    null
                }
            }

            val adaptationSetNodes = periodNode.getElementsByTagName("AdaptationSet")
            val adaptationSets = (0 until adaptationSetNodes.length).map { j ->
                val adaptationSetNode = adaptationSetNodes.item(j) as Element
                val adaptationSetId = adaptationSetNode.getAttribute("id")
                val mimeType = adaptationSetNode.getAttribute("mimeType")

                // 1. Look for a SegmentTemplate at the AdaptationSet level.
                val adaptationSetSegmentTemplate =
                    adaptationSetNode.getElementsByTagName("SegmentTemplate").item(0) as? Element

                val representationNodes = adaptationSetNode.getElementsByTagName("Representation")
                val representations = (0 until representationNodes.length).map { k ->
                    val representationNode = representationNodes.item(k) as Element
                    val representationId = representationNode.getAttribute("id")
                    val bandwidth =
                        representationNode.getAttribute("bandwidth").toLongOrNull() ?: 0L
                    val width = representationNode.getAttribute("width").toIntOrNull() ?: 0
                    val height = representationNode.getAttribute("height").toIntOrNull() ?: 0
                    val codecs = representationNode.getAttribute("codecs")

                    // 2. Look for a SegmentTemplate at the Representation level.
                    val representationSegmentTemplate =
                        representationNode.getElementsByTagName("SegmentTemplate")
                            .item(0) as? Element

                    // 3. The Representation's template takes precedence over the AdaptationSet's.
                    val segmentTemplate =
                        representationSegmentTemplate ?: adaptationSetSegmentTemplate

                    val initUrlTemplate = segmentTemplate?.getAttribute("initialization")

                    val initializationUrl = if (!initUrlTemplate.isNullOrBlank()) {
                        resolveUrl(baseUri, initUrlTemplate).replace(
                            "\$RepresentationID\$",
                            representationId
                        )
                    } else {
                        null
                    }

                    val baseUrls = (representationNode.getElementsByTagName("BaseURL").let {
                        (0 until it.length).map { l -> it.item(l).textContent }
                    }).ifEmpty { listOf(baseUri) }

                    MpdRepresentation(
                        id = representationId,
                        bandwidth = bandwidth,
                        width = width,
                        height = height,
                        codecs = codecs,
                        baseUrls = baseUrls.map { resolveUrl(baseUri, it) },
                        segments = emptyList(),
                        initializationUrl = initializationUrl
                    )
                }

                AdaptationSet(
                    id = adaptationSetId.toLongOrNull() ?: j.toLong(),
                    mimeType = mimeType,
                    representations = representations
                )
            }
            Period(
                id = periodId,
                duration = periodDuration,
                adaptationSets = adaptationSets
            )
        }

        val manifest = MpdManifest(
            baseUri = baseUri,
            mediaPresentationDuration = mediaPresentationDuration,
            periods = periods,
            type = type,
            minimumUpdatePeriod = minimumUpdatePeriod,
            timeShiftBufferDepth = timeShiftBufferDepth
        )

        manifest.periods.forEach { period ->
            period.adaptationSets.forEach { adaptationSet ->
                adaptationSet.representations.forEach { representation ->
                    representation.manifest = manifest
                }
            }
        }

        return manifest
    }
}
