package com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy

import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SegmentDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import com.myAllVideoBrowser.util.hls_parser.MpdPlaylistParser
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
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

            try {
                // 1. Initial Manifest Parse and Init Segment Download
                val (initialVideoRep, initialAudioRep) = getMpdRepresentations(task.url, headers)
                downloadInitSegments(
                    initialVideoRep,
                    initialAudioRep,
                    downloadDir,
                    controller,
                    headers
                )

                // 2. Start Recording Loop
                val segmentDownloader = SegmentDownloader(httpClient, headers, controller)
                var updateInterval = 2000L

                AppLogger.d("MPD (Live): Starting recording loop for task ${task.mId}")
                task.setIsLive(true)

                while (currentCoroutineContext().isActive && !controller.isInterrupted()) {
                    val (videoRep, audioRep) = getMpdRepresentations(task.url, headers)

                    // Update the refresh interval from the latest manifest
                    updateInterval = videoRep?.manifest?.minimumUpdatePeriod?.let {
                        (it * 1000).toLong().coerceAtLeast(1000L)
                    }
                        ?: audioRep?.manifest?.minimumUpdatePeriod?.let {
                            (it * 1000).toLong().coerceAtLeast(1000L)
                        }
                                ?: updateInterval

                    val newVideoSegment = videoRep?.segments?.lastOrNull()
                    val newAudioSegment = audioRep?.segments?.lastOrNull()
                    var wasSegmentAdded = false

                    if (newVideoSegment != null) {
                        val videoFile =
                            downloadDir.resolve("segment_${newVideoSegment.url.hashCode()}.m4s")
                        if (!videoFile.exists()) {
                            AppLogger.d("MPD (Live): Capturing video segment: ${newVideoSegment.url}")
                            val bytes = segmentDownloader.download(
                                newVideoSegment.url,
                                videoFile,
                                "MPD-Live-V",
                                0
                            )
                            totalBytesDownloaded += bytes
                            wasSegmentAdded = true
                        }
                    }

                    if (newAudioSegment != null) {
                        val audioFile =
                            downloadDir.resolve("audio_segment_${newAudioSegment.url.hashCode()}.m4s")
                        if (!audioFile.exists()) {
                            AppLogger.d("MPD (Live): Capturing audio segment: ${newAudioSegment.url}")
                            val bytes = segmentDownloader.download(
                                newAudioSegment.url,
                                audioFile,
                                "MPD-Live-A",
                                0
                            )
                            totalBytesDownloaded += bytes
                            wasSegmentAdded = true
                        }
                    }

                    if (wasSegmentAdded) {
                        onProgress(Progress(totalBytesDownloaded, 0))
                    } else {
                        AppLogger.d("MPD (Live): No new segments found.")
                    }

                    // Check if stream has ended (becomes static)
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
                    downloadException?.let { throw it } // Re-throw original error if nothing was saved
                    throw IOException("No segments were recorded, nothing to merge.")
                }

                // --- MERGE ---
                onMergeProgress(Progress(totalBytesDownloaded, totalBytesDownloaded), task)
                finalOutputFile = downloadDir.resolve("merged_output.mp4")
                val mergeSession = mergeCapturedSegments(
                    downloadDir,
                    capturedVideo,
                    capturedAudio,
                    finalOutputFile.absolutePath
                )

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
                        url,
                        downloadDir.resolve("video_init.m4s"),
                        "MPD-Live-V-Init",
                        0
                    )
                }
            }
            audioRep?.initializationUrl?.let { url ->
                launch {
                    AppLogger.d("MPD Live: Downloading audio init segment.")
                    segmentDownloader.download(
                        url,
                        downloadDir.resolve("audio_init.m4s"),
                        "MPD-Live-A-Init",
                        0
                    )
                }
            }
        }
        if (controller.isInterrupted()) throw CancellationException("Live recording cancelled during init.")
    }

    private suspend fun interruptibleDelay(
        durationMillis: Long,
        controller: FileBasedDownloadController
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
        finalOutputPath: String
    ): FFmpegSession {
        // This function remains largely the same but is now called from the finally block
        val tempVideoFile = mpdTmpDir.resolve("temp_video.mp4")
        val tempAudioFile = mpdTmpDir.resolve("temp_audio.mp4")

        if (hasVideo) {
            val videoFiles = mpdTmpDir.listFiles { _, name -> name.startsWith("segment_") }
                ?.toList()?.sortedBy { it.lastModified() } ?: emptyList()
            if (videoFiles.isNotEmpty()) {
                val videoFilesToConcat = mutableListOf(mpdTmpDir.resolve("video_init.m4s"))
                videoFilesToConcat.addAll(videoFiles)
                manualConcat(videoFilesToConcat, tempVideoFile)
            }
        }

        if (hasAudio) {
            val audioFiles = mpdTmpDir.listFiles { _, name -> name.startsWith("audio_segment_") }
                ?.toList()?.sortedBy { it.lastModified() } ?: emptyList()
            if (audioFiles.isNotEmpty()) {
                val audioFilesToConcat = mutableListOf(mpdTmpDir.resolve("audio_init.m4s"))
                audioFilesToConcat.addAll(audioFiles)
                manualConcat(audioFilesToConcat, tempAudioFile)
            }
        }

        val videoFileToMerge = tempVideoFile.takeIf { it.exists() && it.length() > 0 }
            ?: throw IOException("Video was expected but concatenated file is missing or empty.")

        val mergeSession = mergeBaseUrlStreams(
            videoFileToMerge,
            tempAudioFile.takeIf { it.exists() && it.length() > 0 },
            finalOutputPath
        )

        mpdTmpDir.listFiles()?.forEach { file ->
            if (file.name != "merged_output.mp4") {
                file.delete()
            }
        }
        return mergeSession
    }

    private fun mergeBaseUrlStreams(
        videoFile: File,
        audioFile: File?,
        finalOutputPath: String
    ): FFmpegSession {
        val arguments = mutableListOf("-y", "-i", videoFile.absolutePath)
        audioFile?.let {
            arguments.add("-i")
            arguments.add(it.absolutePath)
        }
        addCommonMergeArguments(arguments, true, audioFile != null, finalOutputPath, false)
        AppLogger.d("FFmpeg: Executing MPD BaseURL merge with arguments: $arguments")

        return FFmpegKit.executeWithArguments(arguments.toTypedArray())
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

    private fun manualConcat(filesToConcat: List<File>, outputFile: File) {
        if (filesToConcat.isEmpty()) return
        AppLogger.d("ManualConcat: Concatenating ${filesToConcat.size} files for ${outputFile.name}.")
        outputFile.outputStream().use { output ->
            filesToConcat.forEach { file ->
                if (file.exists()) {
                    file.inputStream().use { input -> input.copyTo(output) }
                } else {
                    AppLogger.w("ManualConcat: File not found, skipping: ${file.name}")
                }
            }
        }
    }
}
