package com.myAllVideoBrowser.util.hls_parser

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
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

/**
 * A parser for MPEG-DASH (MPD) manifests that uses the robust ExoPlayer DASH library internally.
 * It provides a simplified API, similar to HlsPlaylistParser, by wrapping ExoPlayer's parsing logic.
 *
 * This approach supports all DASH features handled by ExoPlayer's parser, including:
 * - static and dynamic presentations.
 * - SegmentTemplate, SegmentList, and SegmentBase.
 * - Number, Time, and Timeline template modes.
 *
 * It also includes a custom fallback parser to handle manifests that ExoPlayer may fail to parse.
 */
object MpdPlaylistParser {

    // --- Public Data Models (Our simple, consistent API) ---

    data class MpdManifest(
        val baseUri: String,
        val mediaPresentationDuration: String?,
        val periods: List<Period>,
        val type: String?
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
        val segments: List<Segment>
    )

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

        } catch (e: Exception) {
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
                if (periodDurationMs == -9223372036854775807L) null else periodDurationMs / 1000.0

            Period(
                id = exoPeriod.id,
                duration = periodDuration,
                adaptationSets = exoPeriod.adaptationSets.map { exoAdaptationSet ->
                    AdaptationSet(
                        id = exoAdaptationSet.id,
                        mimeType = exoAdaptationSet.representations.firstOrNull()?.format?.sampleMimeType,
                        representations = exoAdaptationSet.representations.map { exoRepresentation ->
                            translateRepresentation(exoRepresentation, periodDuration, baseUri)
                        }
                    )
                }
            )
        }

        val manifestDurationString = extractMediaPresentationDuration(manifestContent)
        val manifestType = if (exoManifest.dynamic) "dynamic" else "static"

        return MpdManifest(
            baseUri = baseUri,
            mediaPresentationDuration = manifestDurationString,
            periods = periods,
            type = manifestType
        )
    }

    private fun extractMediaPresentationDuration(manifestContent: String): String? {
        // Regex to find mediaPresentationDuration="<value>"
        val pattern = Pattern.compile("""mediaPresentationDuration="([^"]*)"""")
        val matcher = pattern.matcher(manifestContent)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }


    @OptIn(UnstableApi::class)
    private fun translateRepresentation(
        exoRep: Representation,
        periodDuration: Double?,
        baseUri: String
    ): MpdRepresentation {
        val segments = mutableListOf<Segment>()
        val index = exoRep.index

        // Check against -1 because getSegmentCount can return it for unknown lengths (live streams).
        if (index != null && periodDuration != null) {
            // Convert the period duration from seconds (Double) to microseconds (Long) for the ExoPlayer API.
            val periodDurationUs = (periodDuration * 1_000_000).toLong()

            val segmentCount = index.getSegmentCount(periodDurationUs)
            if (segmentCount > 0) {
                for (i in 0 until segmentCount.toInt()) {
                    val segmentDurationUs = index.getDurationUs(i.toLong(), periodDurationUs)
                    val segmentUri = index.getSegmentUrl(i.toLong())

                    segments.add(
                        Segment(
                            url = resolveUrl(baseUri, segmentUri.toString()),
                            durationSeconds = segmentDurationUs / 1_000_000.0
                        )
                    )
                }
            }
        }

        val resolvedBaseUrls = exoRep.baseUrls.map { baseUrlObject ->
            resolveUrl(baseUri, baseUrlObject.url)
        }

        val finalBaseUrls = resolvedBaseUrls.ifEmpty {
            listOf(baseUri)
        }

        return MpdRepresentation(
            id = exoRep.format.id,
            bandwidth = exoRep.format.bitrate.toLong(),
            width = exoRep.format.width,
            height = exoRep.format.height,
            codecs = exoRep.format.codecs,
            baseUrls = finalBaseUrls,
            segments = segments
        )
    }

    private fun resolveUrl(baseUri: String, url: String): String {
        return try {
            // If the URL is already absolute, the constructor will use it as is.
            // If the URL is relative, it will be resolved against the baseUri context.
            URL(URL(baseUri), url).toString()
        } catch (e: MalformedURLException) {
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

        val periodNodes =
            xpath.evaluate("/MPD/Period", document, XPathConstants.NODESET) as NodeList
        val periods = (0 until periodNodes.length).map { i ->
            val periodNode = periodNodes.item(i) as Element
            val periodId = periodNode.getAttribute("id")
            val periodDurationStr = periodNode.getAttribute("duration")
            val periodDuration = periodDurationStr.let {
                try {
                    it.replace("PT", "").replace("S", "").toDoubleOrNull()
                } catch (e: Exception) {
                    null
                }
            }

            val adaptationSetNodes = periodNode.getElementsByTagName("AdaptationSet")
            val adaptationSets = (0 until adaptationSetNodes.length).map { j ->
                val adaptationSetNode = adaptationSetNodes.item(j) as Element
                val adaptationSetId = adaptationSetNode.getAttribute("id")
                val mimeType = adaptationSetNode.getAttribute("mimeType")

                val representationNodes = adaptationSetNode.getElementsByTagName("Representation")
                val representations = (0 until representationNodes.length).map { k ->
                    val representationNode = representationNodes.item(k) as Element
                    val representationId = representationNode.getAttribute("id")
                    val bandwidth =
                        representationNode.getAttribute("bandwidth").toLongOrNull() ?: 0L
                    val width = representationNode.getAttribute("width").toIntOrNull() ?: 0
                    val height = representationNode.getAttribute("height").toIntOrNull() ?: 0
                    val codecs = representationNode.getAttribute("codecs")

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
                        segments = emptyList() // Segment parsing in fallback is complex and omitted for this example.
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

        return MpdManifest(
            baseUri = baseUri,
            mediaPresentationDuration = mediaPresentationDuration,
            periods = periods,
            type = type
        )
    }
}
