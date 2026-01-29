package com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy

import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SegmentDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import com.myAllVideoBrowser.util.hls_parser.MpdPlaylistParser
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Download strategy for LIVE MPD (MPEG-DASH) manifests.
 *
 * This class "records" a live stream by sequentially downloading segments as they become available
 * until the download is cancelled by the user. It then merges the captured segments.
 *
 * @param httpClient The OkHttpClient for network requests.
 * @param getMpdRepresentations A function to parse the MPD manifest and find the correct video/audio streams.
 * @param onMergeProgress A callback to update progress when merging begins.
 * @param videoCodec The video codec to check for compatibility.
 */
class MpdLiveDownloader(
    private val httpClient: OkHttpClient,
    private val getMpdRepresentations: suspend (url: String, headers: Map<String, String>) -> Pair<MpdPlaylistParser.MpdRepresentation?, MpdPlaylistParser.MpdRepresentation?>,
    private val onMergeProgress: (progress: Progress, task: VideoTaskItem) -> Unit,
    private val videoCodec: String?
) : ManifestDownloader {
    override suspend fun download(
        task: VideoTaskItem,
        headers: Map<String, String>,
        downloadDir: File,
        controller: FileBasedDownloadController,
        onProgress: (progress: Progress) -> Unit
    ): File {
        return withContext(Dispatchers.IO) {
            var downloadException: Exception? = null
            var totalBytesDownloaded = 0L
            lateinit var finalOutputFile: File

            val progressCallback: (bytes: Long) -> Unit = { bytes ->
                totalBytesDownloaded += bytes
                // Total size is unknown for live streams, so it's 0.
                onProgress(Progress(totalBytesDownloaded, 0))
            }

            try {
                // 1. Initial Manifest Parse and Init Segment Download
                val (initialVideoRep, initialAudioRep) = getMpdRepresentations(task.url, headers)
                downloadInitSegments(
                    initialVideoRep, initialAudioRep, downloadDir, controller, headers
                )

                // 2. Start Recording Loop
                val segmentDownloader =
                    SegmentDownloader(httpClient, headers, controller, progressCallback)
                var updateInterval = 2000L
                val downloadedSegmentUrls = mutableSetOf<String>()

                AppLogger.d("MPD (Live): Starting recording loop for task ${task.mId}")
                task.setIsLive(true)

                while (currentCoroutineContext().isActive && !controller.isInterrupted()) {
                    val (videoRep, audioRep) = getMpdRepresentations(task.url, headers)

                    updateInterval = videoRep?.manifest?.minimumUpdatePeriod?.let {
                        (it * 1000).toLong().coerceAtLeast(1000L)
                    } ?: audioRep?.manifest?.minimumUpdatePeriod?.let {
                        (it * 1000).toLong().coerceAtLeast(1000L)
                    } ?: updateInterval

                    val newVideoSegments =
                        videoRep?.segments?.filter { downloadedSegmentUrls.add(it.url) }
                            ?: emptyList()
                    val newAudioSegments =
                        audioRep?.segments?.filter { downloadedSegmentUrls.add(it.url) }
                            ?: emptyList()

                    if (newVideoSegments.isNotEmpty() || newAudioSegments.isNotEmpty()) {
                        AppLogger.d("MPD (Live): Found ${newVideoSegments.size} new video and ${newAudioSegments.size} new audio segments.")

                        for (segment in newVideoSegments) {
                            val videoFile =
                                downloadDir.resolve("segment_${segment.url.hashCode()}.m4s")
                            segmentDownloader.download(segment.url, videoFile, "MPD-Live-V", 0)
                            task.accumulatedDuration += segment.durationSeconds.toLong()
                        }
                        for (segment in newAudioSegments) {
                            val audioFile =
                                downloadDir.resolve("audio_segment_${segment.url.hashCode()}.m4s")
                            segmentDownloader.download(segment.url, audioFile, "MPD-Live-A", 0)
                            if (newVideoSegments.isEmpty()) {
                                task.accumulatedDuration += segment.durationSeconds.toLong()
                            }
                        }
                    } else {
                        AppLogger.d("MPD (Live): No new segments found.")
                    }

                    if (videoRep?.manifest?.type == "static" || audioRep?.manifest?.type == "static") {
                        AppLogger.d("MPD (Live): Stream type changed to 'static'. Ending recording.")
                        break
                    }

                    interruptibleDelay(updateInterval, controller)
                }

                if (controller.isInterrupted()) {
                    throw CancellationException("Recording stopped by user.")
                }

            } catch (e: Exception) {
                downloadException = e
                AppLogger.w("MPD (Live): Exception in recording loop: ${e.message}. Attempting to save captured segments.")
            } finally {
                AppLogger.d("MPD (Live): Entering 'finally' block to attempt merge.")
                val capturedVideo =
                    downloadDir.listFiles { _, name -> name.startsWith("segment_") }?.any() == true
                val capturedAudio =
                    downloadDir.listFiles { _, name -> name.startsWith("audio_segment_") }
                        ?.any() == true

                if (!capturedVideo && !capturedAudio) {
                    downloadException?.let { throw it }
                    throw IOException("No segments were recorded, nothing to merge.")
                }

                var isPreparing = true

                onMergeProgress(
                    Progress(0, totalBytesDownloaded), task.apply {
                        this.taskState = VideoTaskState.PREPARE
                        this.lineInfo = "Preparing segments... 0%"
                    })

                finalOutputFile = downloadDir.resolve("merged_output.mp4")
                val mergeSession = mergeCapturedSegments(
                    downloadDir,
                    capturedVideo,
                    capturedAudio,
                    finalOutputFile.absolutePath,
                    task.accumulatedDuration.toDouble() / 1000.0
                ) { percentage ->
                    if (isPreparing && percentage == 100) {
                        isPreparing = false
                    }
                    val message =
                        if (isPreparing) "Preparing segments... $percentage%" else "Merging... $percentage%"
                    onMergeProgress(
                        Progress(totalBytesDownloaded * percentage / 100, totalBytesDownloaded),
                        task.apply { this.lineInfo = message })
                }

                if (!ReturnCode.isSuccess(mergeSession.returnCode)) {
                    val mergeError =
                        IOException("FFmpeg failed to merge live stream segments. Log: ${mergeSession.allLogsAsString}")
                    downloadException?.let { mergeError.initCause(it) } // Chain the original exception
                    throw mergeError
                }

                if (downloadException != null) {
                    AppLogger.w("MPD (Live): Recording was interrupted, but merge was successful. Returning partial file.")
                }
            }

            finalOutputFile // Return the successfully merged file
        }
    }

    private suspend fun downloadInitSegments(
        videoRep: MpdPlaylistParser.MpdRepresentation?,
        audioRep: MpdPlaylistParser.MpdRepresentation?,
        downloadDir: File,
        controller: FileBasedDownloadController,
        headers: Map<String, String>
    ) {
        val segmentDownloader = SegmentDownloader(httpClient, headers, controller)
        coroutineScope {
            videoRep?.initializationUrl?.let { url ->
                launch {
                    AppLogger.d("MPD Live: Downloading video init segment.")
                    segmentDownloader.download(
                        url, downloadDir.resolve("video_init.m4s"), "MPD-Live-V-Init", 0
                    )
                }
            }
            audioRep?.initializationUrl?.let { url ->
                launch {
                    AppLogger.d("MPD Live: Downloading audio init segment.")
                    segmentDownloader.download(
                        url, downloadDir.resolve("audio_init.m4s"), "MPD-Live-A-Init", 0
                    )
                }
            }
        }
        if (controller.isInterrupted()) throw CancellationException("Live recording cancelled during init.")
    }

    private suspend fun interruptibleDelay(
        durationMillis: Long, controller: FileBasedDownloadController
    ) {
        val endTime = System.currentTimeMillis() + durationMillis
        while (System.currentTimeMillis() < endTime) {
            if (controller.isInterrupted()) break
            delay(250L)
        }
    }

    private fun mergeCapturedSegments(
        mpdTmpDir: File,
        hasVideo: Boolean,
        hasAudio: Boolean,
        finalOutputPath: String,
        totalDurationSeconds: Double,
        onProgress: (percentage: Int) -> Unit
    ): FFmpegSession {
        val finalOutputFile = File(finalOutputPath)
        val tempVideoFile = mpdTmpDir.resolve("temp_video.mp4")
        val tempAudioFile = mpdTmpDir.resolve("temp_audio.mp4")

        val videoFilesToConcat = if (hasVideo) {
            val init = mpdTmpDir.resolve("video_init.m4s")
            val segments = mpdTmpDir.listFiles { _, name -> name.startsWith("segment_") }
                ?.sortedBy { it.lastModified() } ?: emptyList()
            listOf(init) + segments
        } else emptyList()

        val audioFilesToConcat = if (hasAudio) {
            val init = mpdTmpDir.resolve("audio_init.m4s")
            val segments = mpdTmpDir.listFiles { _, name -> name.startsWith("audio_segment_") }
                ?.sortedBy { it.lastModified() } ?: emptyList()
            listOf(init) + segments
        } else emptyList()

        val totalConcatSize =
            (videoFilesToConcat.sumOf { if (it.exists()) it.length() else 0L } + audioFilesToConcat.sumOf { if (it.exists()) it.length() else 0L })
        var concatenatedBytes = 0L

        val concatProgressCallback: (bytes: Long) -> Unit = { bytes ->
            concatenatedBytes += bytes
            if (totalConcatSize > 0) {
                val percentage = ((concatenatedBytes * 100) / totalConcatSize).toInt()
                onProgress(percentage.coerceIn(0, 100))
            }
        }

        if (hasVideo) {
            manualConcat(videoFilesToConcat, tempVideoFile, concatProgressCallback)
        }
        if (hasAudio) {
            manualConcat(audioFilesToConcat, tempAudioFile, concatProgressCallback)
        }

        val videoFileToMerge = tempVideoFile.takeIf { it.exists() && it.length() > 0 }
            ?: throw IOException("Video was expected but concatenated file is missing or empty.")

        val mergeSession = mergeBaseUrlStreams(
            videoFileToMerge,
            tempAudioFile.takeIf { it.exists() && it.length() > 0 },
            finalOutputPath,
            totalDurationSeconds,
            onProgress
        )

        mpdTmpDir.listFiles { file -> file.name != finalOutputFile.name }?.forEach { it.delete() }

        return mergeSession
    }

    private fun mergeBaseUrlStreams(
        videoFile: File,
        audioFile: File?,
        finalOutputPath: String,
        totalDurationSeconds: Double,
        onProgress: ((percentage: Int) -> Unit)?
    ): FFmpegSession {
        val arguments = mutableListOf<String>()

        if (videoFile.exists()) {
            arguments.addAll(listOf("-i", videoFile.absolutePath))
        }
        audioFile?.takeIf { it.exists() }?.let {
            arguments.addAll(listOf("-i", it.absolutePath))
        }
        if (arguments.isEmpty()) {
            throw IOException("No valid video or audio files to merge.")
        }
        arguments.add(0, "-y")

        addCommonMergeArguments(
            arguments, videoFile.exists(), audioFile?.exists() == true, finalOutputPath, false
        )

        val commandString = arguments.joinToString(" ")
        AppLogger.d("FFmpeg: Executing MPD Live merge with command: $commandString")

        val latch = CountDownLatch(1)
        lateinit var finalSession: FFmpegSession

        val session = FFmpegKit.executeAsync(commandString, { completedSession ->
            finalSession = completedSession
            if (!ReturnCode.isSuccess(completedSession.returnCode)) {
                AppLogger.e("FFmpeg merge failed. Log: ${completedSession.allLogsAsString}")
            }
            latch.countDown()
        }, { log ->
            AppLogger.d("FFmpeg: ${log.message}")
        }, { statistics ->
            if (onProgress != null && totalDurationSeconds > 0) {
                val totalDurationMillis = (totalDurationSeconds * 1000).toLong()
                val currentTimeMillis = statistics.time
                if (currentTimeMillis > 0) {
                    val percentage = ((currentTimeMillis * 100) / totalDurationMillis).toInt()
                    onProgress(percentage.coerceIn(0, 100))
                }
            }
        })

        try {
            latch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            FFmpegKit.cancel(session.sessionId)
            throw IOException("FFmpeg merge was interrupted.", e)
        }

        return finalSession
    }

    private fun addCommonMergeArguments(
        arguments: MutableList<String>,
        hasVideo: Boolean,
        hasAudio: Boolean,
        finalOutputPath: String,
        isSegmentMerge: Boolean
    ) {
        arguments.apply {
            when {
                hasVideo && hasAudio -> {
                    add("-map"); add("0:v:0?"); add("-map"); add("1:a:0?")
                }

                hasVideo -> {
                    add("-map"); add("0:v:0?")
                }

                hasAudio -> {
                    add("-map"); add("0:a:0?")
                }
            }

            if (hasVideo && (videoCodec?.startsWith("hvc1") == true || videoCodec?.startsWith("dvh1") == true)) {
                add("-c:v"); add("libx264"); add("-preset"); add("veryfast"); add("-crf"); add("23"); add(
                    "-pix_fmt"
                ); add("yuv420p")
                if (hasAudio) add("-c:a"); add("copy")
            } else {
                add("-c"); add("copy")
            }
            if (!isSegmentMerge) {
                add("-bsf:a"); add("aac_adtstoasc"); add("-movflags"); add("+faststart")
            }
            add("-y"); add(finalOutputPath)
        }
    }

    private fun manualConcat(
        filesToConcat: List<File>,
        outputFile: File,
        onProgress: ((bytesCopied: Long) -> Unit)? = null
    ) {
        if (filesToConcat.isEmpty()) {
            AppLogger.w("ManualConcat: No files to concatenate for ${outputFile.name}")
            return
        }
        AppLogger.d("ManualConcat: Starting for ${outputFile.name}. Concatenating ${filesToConcat.size} files.")

        outputFile.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int

            var lastUpdateTime = System.currentTimeMillis()
            val updateInterval = 250L
            var bytesSinceLastUpdate = 0L

            filesToConcat.forEach { file ->
                if (file.exists()) {
                    file.inputStream().use { input ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesSinceLastUpdate += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime > updateInterval) {
                                onProgress?.invoke(bytesSinceLastUpdate)
                                bytesSinceLastUpdate = 0L
                                lastUpdateTime = now
                            }
                        }
                    }
                } else {
                    AppLogger.w("ManualConcat: File not found, skipping: ${file.name}")
                }
            }
            if (bytesSinceLastUpdate > 0) {
                onProgress?.invoke(bytesSinceLastUpdate)
            }
        }
        AppLogger.d("ManualConcat: Finished. Output size: ${outputFile.length()} bytes.")
    }
}
