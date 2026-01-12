// com/myAllVideoBrowser/util/downloaders/super_x_downloader/DownloaderUtils.kt
package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.hls_parser.HlsPlaylistParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

object DownloaderUtils {

    /**
     * This is the MERGE logic, now living in a central utility location.
     */
    fun mergeHlsSegments(
        hlsTmpDir: File,
        videoSegments: List<HlsPlaylistParser.MediaSegment>?,
        audioSegments: List<HlsPlaylistParser.MediaSegment>?,
        finalOutputPath: String,
        videoCodec: String?,
        httpClient: OkHttpClient
    ): FFmpegSession {
        val arguments = mutableListOf<String>()

        val isVideoFmp4 = !videoSegments.isNullOrEmpty() &&
                (videoSegments.first() as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null
        val isAudioFmp4 = !audioSegments.isNullOrEmpty() &&
                (audioSegments.first() as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null

        // --- Video Input ---
        if (!videoSegments.isNullOrEmpty()) {
            if (isVideoFmp4) {
                val concatenatedVideoFile = createConcatenatedFmp4File(hlsTmpDir, videoSegments, "video", httpClient)
                arguments.add("-i")
                arguments.add(concatenatedVideoFile.absolutePath)
            } else {
                val videoPlaylistFile = createTsPlaylistFile(hlsTmpDir, videoSegments, "segment_", "video.m3u8", httpClient)
                addPlaylistArguments(arguments, videoPlaylistFile)
            }
        }

        // --- Audio Input ---
        if (!audioSegments.isNullOrEmpty()) {
            if (isAudioFmp4) {
                val concatenatedAudioFile = createConcatenatedFmp4File(hlsTmpDir, audioSegments, "audio", httpClient)
                arguments.add("-i")
                arguments.add(concatenatedAudioFile.absolutePath)
            } else {
                val audioPlaylistFile = createTsPlaylistFile(hlsTmpDir, audioSegments, "audio_segment_", "audio.m3u8", httpClient)
                addPlaylistArguments(arguments, audioPlaylistFile)
            }
        }

        if (videoSegments.isNullOrEmpty() && audioSegments.isNullOrEmpty()) {
            throw IOException("Cannot merge segments: No video or audio segments were provided.")
        }

        // --- Final FFmpeg Arguments ---
        arguments.apply {
            val hasVideo = !videoSegments.isNullOrEmpty()
            val hasAudio = !audioSegments.isNullOrEmpty()

            when {
                hasVideo && hasAudio -> { add("-map"); add("0:v?"); add("-map"); add("1:a?") }
                hasVideo -> { add("-map"); add("0") }
                hasAudio -> { add("-map"); add("0:a?") }
            }

            if (videoCodec?.startsWith("hvc1") == true || videoCodec?.startsWith("dvh1") == true) {
                add("-c:v"); add("libx264")
                add("-preset"); add("veryfast")
                add("-crf"); add("23")
                add("-pix_fmt"); add("yuv420p")
                if (hasAudio) add("-c:a"); add("copy")
            } else {
                add("-c"); add("copy")
            }

            add("-bsf:a"); add("aac_adtstoasc")
            add("-movflags"); add("+faststart")
            add("-y"); add(finalOutputPath)
        }

        AppLogger.d("DownloaderUtils: Executing HLS merge with arguments: $arguments")
        return FFmpegKit.executeWithArguments(arguments.toTypedArray())
    }

    /**
     * Creates a single MP4 file by concatenating an fMP4 init segment and all media segments.
     */
    private fun createConcatenatedFmp4File(
        hlsTmpDir: File,
        segments: List<HlsPlaylistParser.MediaSegment>,
        prefix: String, // "video" or "audio"
        httpClient: OkHttpClient
    ): File {
        val initSegment =
            (segments.first() as HlsPlaylistParser.UrlMediaSegment).initializationSegment!!
        val concatenatedFile = hlsTmpDir.resolve("concatenated_$prefix.mp4")

        try {
            concatenatedFile.outputStream().use { output ->
                // 1. Download and write the initialization segment
                AppLogger.d("HLS (fMP4): Downloading $prefix init segment from ${initSegment.url}")
                val initRequest = Request.Builder().url(initSegment.url).build()
                httpClient.newCall(initRequest).execute()
                    .use { response ->
                        if (!response.isSuccessful) throw IOException("Failed to download fMP4 $prefix init segment. HTTP ${response.code}")
                        response.body?.use { output.write(it.bytes()) }
                    }

                // 2. Append all corresponding media segments
                segments.forEachIndexed { index, _ ->
                    val filePrefix = if (prefix == "video") "segment_" else "audio_segment_"
                    val segmentFile = hlsTmpDir.resolve("${filePrefix}${"%05d".format(index)}.m4s")

                    if (segmentFile.exists()) {
                        output.write(segmentFile.readBytes())
                    } else {
                        // Keep a fallback for .ts just in case, but .m4s is expected for fMP4
                        val tsFile = File(segmentFile.path.replace(".m4s", ".ts"))
                        if (tsFile.exists()) {
                            output.write(tsFile.readBytes())
                        } else {
                            AppLogger.w("HLS (fMP4): Could not find $prefix segment file: ${segmentFile.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw IOException(
                "Failed to create concatenated fMP4 file for $prefix: ${e.message}",
                e
            )
        }
        AppLogger.d("HLS (fMP4): All $prefix segments concatenated into ${concatenatedFile.absolutePath}")
        return concatenatedFile
    }

    /**
     * Creates a .m3u8 (encrypted) or .txt (unencrypted) playlist file for TS segments.
     */
    private fun createTsPlaylistFile(
        hlsTmpDir: File,
        segments: List<HlsPlaylistParser.MediaSegment>,
        filePrefix: String,
        playlistName: String,
        httpClient: OkHttpClient
    ): File {
        val firstSegment = segments.firstOrNull() as? HlsPlaylistParser.UrlMediaSegment
        val key = firstSegment?.encryptionKey
        val isEncrypted = key != null

        val finalPlaylistName = if (isEncrypted) playlistName.replace(".txt", ".m3u8")
        else playlistName.replace(".m3u8", ".txt")

        val playlistFile = hlsTmpDir.resolve(finalPlaylistName)
        val keyFileName = "${filePrefix}encryption.key"

        if (isEncrypted) {
            AppLogger.d("HLS: Encryption detected for $filePrefix. Method: ${key!!.method}, URI: ${key.uri}")
            val keyFile = hlsTmpDir.resolve(keyFileName)
            try {
                val request = Request.Builder().url(key.uri).build()
                httpClient.newCall(request).execute()
                    .use { response ->
                        if (!response.isSuccessful) throw IOException("Failed to download key file. HTTP ${response.code}")
                        response.body?.use { keyFile.writeBytes(it.bytes()) }
                        AppLogger.d("HLS: Encryption key for $filePrefix downloaded to ${keyFile.absolutePath}")
                    }
            } catch (e: Exception) {
                throw IOException(
                    "Failed to download HLS encryption key for $filePrefix: ${e.message}",
                    e
                )
            }
        }

        val playlistContent = buildString {
            if (isEncrypted) {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:3")
                appendLine("#EXT-X-TARGETDURATION:10") // A default value, FFmpeg is robust to this.
                appendLine("#EXT-X-KEY:METHOD=${key!!.method},URI=\"$keyFileName\"")
            }

            segments.forEachIndexed { index, segment ->
                // Assuming TS segments are saved with a predictable name
                val segmentFile = hlsTmpDir.resolve("${filePrefix}${"%05d".format(index)}.ts")
                if (isEncrypted) {
                    appendLine("#EXTINF:${segment.duration},")
                    appendLine(segmentFile.name)
                } else {
                    appendLine("file '${segmentFile.absolutePath}'")
                }
            }

            if (isEncrypted) {
                appendLine("#EXT-X-ENDLIST")
            }
        }
        playlistFile.writeText(playlistContent)
        AppLogger.d("Created playlist file: ${playlistFile.name}")
        return playlistFile
    }

    private fun addPlaylistArguments(arguments: MutableList<String>, playlistFile: File) {
        val isEncrypted = playlistFile.extension == "m3u8"
        arguments.apply {
            if (isEncrypted) {
                add("-protocol_whitelist"); add("file,pipe,crypto")
                add("-allowed_extensions"); add("ALL")
            } else {
                add("-f"); add("concat")
                add("-safe"); add("0")
            }
            add("-i"); add(playlistFile.absolutePath)
        }
    }
}
