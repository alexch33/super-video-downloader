package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.hls_parser.HlsPlaylistParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch

object DownloaderUtils {

    /**
     * This is the MERGE logic, now living in a central utility location.
     * With progress reporting.
     *
     * This version uses executeAsync and blocks until completion to fit the synchronous
     * nature of the calling HlsLiveDownloader.
     */
    fun mergeHlsSegments(
        hlsTmpDir: File,
        videoSegments: List<HlsPlaylistParser.MediaSegment>?,
        audioSegments: List<HlsPlaylistParser.MediaSegment>?,
        finalOutputPath: String,
        videoCodec: String?,
        onMergeProgress: ((percentage: Int) -> Unit)? = null
    ): FFmpegSession {
        val arguments = mutableListOf<String>()

        val isVideoFmp4 = !videoSegments.isNullOrEmpty() &&
                (videoSegments.first() as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null
        val isAudioFmp4 = !audioSegments.isNullOrEmpty() &&
                (audioSegments.first() as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null

        // --- Video Input ---
        if (!videoSegments.isNullOrEmpty()) {
            if (isVideoFmp4) {
                val concatenatedVideoFile = createConcatenatedFmp4File(
                    hlsTmpDir,
                    videoSegments,
                    "video"
                )
                arguments.add("-i")
                arguments.add(concatenatedVideoFile.absolutePath)
            } else {
                val videoPlaylistFile = createTsPlaylistFile(
                    hlsTmpDir,
                    videoSegments,
                    "segment_",
                    "video.m3u8",
                    "video_encryption.key"
                )
                addPlaylistArguments(arguments, videoPlaylistFile)
            }
        }

        // --- Audio Input ---
        if (!audioSegments.isNullOrEmpty()) {
            if (isAudioFmp4) {
                val concatenatedAudioFile = createConcatenatedFmp4File(
                    hlsTmpDir,
                    audioSegments,
                    "audio"
                )
                arguments.add("-i")
                arguments.add(concatenatedAudioFile.absolutePath)
            } else {
                val audioPlaylistFile = createTsPlaylistFile(
                    hlsTmpDir,
                    audioSegments,
                    "audio_segment_",
                    "audio.m3u8",
                    "audio_encryption.key"
                )
                addPlaylistArguments(arguments, audioPlaylistFile)
            }
        }

        if (videoSegments.isNullOrEmpty() && audioSegments.isNullOrEmpty()) {
            throw IOException("Cannot merge segments: No video or audio segments were provided.")
        }

        // --- Calculate Total Duration for Progress ---
        val totalDurationSeconds = (videoSegments?.sumOf { it.duration } ?: 0.0) +
                (audioSegments?.takeIf { videoSegments.isNullOrEmpty() }?.sumOf { it.duration }
                    ?: 0.0)

        // --- Final FFmpeg Arguments ---
        arguments.apply {
            val hasVideo = !videoSegments.isNullOrEmpty()
            val hasAudio = !audioSegments.isNullOrEmpty()

            when {
                hasVideo && hasAudio -> {
                    add("-map"); add("0:v?"); add("-map"); add("1:a?")
                }

                hasVideo -> {
                    add("-map"); add("0")
                }

                hasAudio -> {
                    add("-map"); add("0:a?")
                }
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

        val latch = CountDownLatch(1)
        lateinit var finalSession: FFmpegSession

        // 1. Execute FFmpeg asynchronously
        val session = FFmpegKit.executeAsync(
            arguments.joinToString(" "),
            { completedSession -> // This callback runs when the command finishes
                finalSession = completedSession
                // If the command failed, log the reason.
                if (!ReturnCode.isSuccess(completedSession.returnCode)) {
                    AppLogger.e("FFmpeg merge failed with return code ${completedSession.returnCode}. Log: ${completedSession.allLogsAsString}")
                }
                latch.countDown() // Release the latch
            },
            { log ->
                // Log callback (optional, but good for debugging)
                AppLogger.d("FFmpeg: ${log.message}")
            },
            { statistics ->
                // 2. This is the statistics callback for progress
                if (onMergeProgress != null && totalDurationSeconds > 0) {
                    val totalDurationMillis = (totalDurationSeconds * 1000).toLong()
                    val currentTimeMillis = statistics.time
                    if (currentTimeMillis > 0) {
                        val percentage = ((currentTimeMillis * 100) / totalDurationMillis).toInt()
                        onMergeProgress(percentage.coerceIn(0, 100))
                    }
                }
            })

        // 3. Block the current thread until the FFmpeg command completes
        try {
            latch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            // If the wait is interrupted, try to cancel the FFmpeg job
            FFmpegKit.cancel(session.sessionId)
            throw IOException("FFmpeg merge was interrupted.", e)
        }

        // 4. Return the completed session
        return finalSession
    }

    /**
     * Downloads an HLS encryption key. This method is thread-safe and can be called from any downloader.
     *
     * @param httpClient The OkHttpClient to use for the request.
     * @param uri The URL of the key file.
     * @param keyFile The destination file to save the key.
     * @param headers The headers to include in the request.
     * @throws IOException if the download fails.
     */
    @Throws(IOException::class)
    fun downloadKey(httpClient: OkHttpClient, uri: String, keyFile: File, headers: okhttp3.Headers) {
        try {
            val request = Request.Builder().url(uri).headers(headers).build()
            httpClient.newCall(request).execute()
                .use { response ->
                    if (!response.isSuccessful) throw IOException("Failed to download key file. HTTP ${response.code}")
                    response.body.use { keyFile.writeBytes(it.bytes()) }
                    AppLogger.d("HLS: Encryption key downloaded to ${keyFile.absolutePath}")
                }
        } catch (e: Exception) {
            throw IOException(
                "Failed to download HLS encryption key: ${e.message}",
                e
            )
        }
    }

    /**
     * Creates a single MP4 file by concatenating an fMP4 init segment and all media segments.
     */
    private fun createConcatenatedFmp4File(
        hlsTmpDir: File,
        segments: List<HlsPlaylistParser.MediaSegment>,
        prefix: String // "video" or "audio"
    ): File {
        val initFile = hlsTmpDir.resolve("init_$prefix.mp4")
        val concatenatedFile = hlsTmpDir.resolve("concatenated_$prefix.mp4")

        try {
            concatenatedFile.outputStream().use { output ->
                // 1. Write the pre-downloaded initialization segment
                if (!initFile.exists()) {
                    throw IOException("fMP4 init segment file not found for $prefix: ${initFile.absolutePath}")
                }
                AppLogger.d("HLS (fMP4): Reading $prefix init segment from ${initFile.absolutePath}")
                output.write(initFile.readBytes())

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
        keyFileName: String
    ): File {
        val firstSegment = segments.firstOrNull() as? HlsPlaylistParser.UrlMediaSegment
        val key = firstSegment?.encryptionKey
        val isEncrypted = key != null

        val finalPlaylistName = if (isEncrypted) playlistName.replace(".txt", ".m3u8")
        else playlistName.replace(".m3u8", ".txt")

        val playlistFile = hlsTmpDir.resolve(finalPlaylistName)

        if (isEncrypted) {
            AppLogger.d("HLS: Encryption detected for $filePrefix. Method: ${key.method}")
            val keyFile = hlsTmpDir.resolve(keyFileName)
            if (!keyFile.exists() || keyFile.length() == 0L) {
                throw IOException("Encryption key file not found: ${keyFile.absolutePath}")
            }
        }

        val playlistContent = buildString {
            if (isEncrypted) {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:3")
                appendLine("#EXT-X-TARGETDURATION:10") // A default value, FFmpeg is robust to this.
                appendLine("#EXT-X-KEY:METHOD=${key.method},URI=\"$keyFileName\"")
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
