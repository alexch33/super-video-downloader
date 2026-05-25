package com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy

import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.downloaders.custom_downloader.CustomFileDownloader
import com.myAllVideoBrowser.util.downloaders.custom_downloader.DownloadListener
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
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Download strategy for MPD (MPEG-DASH) manifests.
 *
 * This class handles two primary MPD types:
 * 1. Segment-based (SegmentTemplate/SegmentList): Downloads all segments in parallel and merges them.
 * 2. BaseURL-based (SegmentBase): Downloads complete video/audio files and merges them.
 *
 * @param httpClient The OkHttpClient for network requests.
 * @param getMpdRepresentations A function to parse the MPD manifest and find the correct video/audio streams.
 * @param onMergeProgress A callback to update progress when merging begins.
 * @param threadCount The number of parallel downloads for segments or chunks.
 * @param videoCodec The video codec to check for compatibility.
 */
class MpdDownloader(
    private val httpClient: OkHttpClient,
    private val getMpdRepresentations: suspend (url: String, headers: Map<String, String>) -> Pair<MpdPlaylistParser.MpdRepresentation?, MpdPlaylistParser.MpdRepresentation?>,
    private val onMergeProgress: (progress: Progress, task: VideoTaskItem) -> Unit,
    private val threadCount: Int,
    private val videoCodec: String?,
    private val isAudioOnlyExtract: Boolean = false
) : ManifestDownloader {
    override suspend fun download(
        task: VideoTaskItem,
        headers: Map<String, String>,
        downloadDir: File,
        controller: FileBasedDownloadController,
        onProgress: (progress: Progress) -> Unit
    ): File {
        return withContext(Dispatchers.IO) {
            val (videoRep, audioRep) = getMpdRepresentations(task.url, headers)

            if (videoRep?.isDrmProtected == true || audioRep?.isDrmProtected == true) {
                AppLogger.e("MPD: Content is DRM-protected. Download is not permitted.")
                throw IOException("Cannot download: The content is protected by DRM and cannot be downloaded.")
            }

            val primaryRep = videoRep ?: audioRep
            ?: throw IOException("No downloadable video or audio representation found for the selected format.")

            val finalOutputFile = if (primaryRep.segments.isNotEmpty()) {
                AppLogger.d("MPD: Detected manifest with segments. Starting segment download.")
                downloadBySegments(
                    task,
                    headers,
                    downloadDir,
                    videoRep,
                    audioRep,
                    controller,
                    onProgress
                )
            } else if (primaryRep.baseUrls.isNotEmpty()) {
                AppLogger.d("MPD: Detected manifest with Base URLs. Starting file download.")
                downloadByBaseUrl(
                    task,
                    headers,
                    downloadDir,
                    videoRep,
                    audioRep,
                    controller,
                    onProgress
                )
            } else {
                throw IOException("MPD manifest contains neither segments nor base URLs. Cannot download.")
            }

            finalOutputFile
        }
    }

    /**
     * Downloads DASH content by fetching individual segments in parallel.
     */
    private suspend fun downloadBySegments(
        task: VideoTaskItem,
        headers: Map<String, String>,
        downloadDir: File,
        videoRepRaw: MpdPlaylistParser.MpdRepresentation?,
        audioRep: MpdPlaylistParser.MpdRepresentation?,
        controller: FileBasedDownloadController,
        onProgress: (progress: Progress) -> Unit
    ): File = coroutineScope {
        val totalBytesDownloaded = AtomicLong(0)
        val segmentsCompleted = AtomicLong(0)

        // Optimization: Skip video segments if audio-only is requested and separate audio exists.
        val videoRep = if (isAudioOnlyExtract && audioRep?.segments?.isNotEmpty() == true) {
            AppLogger.d("MPD: Audio-only requested and separate audio track found. Skipping video track download.")
            null
        } else {
            videoRepRaw
        }

        val videoSegments = videoRep?.segments
        val audioSegments = audioRep?.segments

        val totalSegmentsToDownload = (videoSegments?.size ?: 0) + (audioSegments?.size ?: 0)
        if (totalSegmentsToDownload == 0) {
            throw IOException("No segments found to download for either video or audio.")
        }

        // Resume logic
        val alreadyDownloadedVideo = videoSegments?.filter {
            downloadDir.resolve("segment_${"%05d".format(videoSegments.indexOf(it))}.m4s").exists()
        } ?: emptyList()
        val alreadyDownloadedAudio = audioSegments?.filter {
            downloadDir.resolve("audio_segment_${"%05d".format(audioSegments.indexOf(it))}.m4s")
                .exists()
        } ?: emptyList()

        // Sum initial size and report progress
        val initialSize = (alreadyDownloadedVideo.sumOf {
            downloadDir.resolve(
                "segment_${
                    "%05d".format(videoSegments!!.indexOf(it))
                }.m4s"
            ).length()
        } +
                alreadyDownloadedAudio.sumOf {
                    downloadDir.resolve(
                        "audio_segment_${
                            "%05d".format(
                                audioSegments!!.indexOf(it)
                            )
                        }.m4s"
                    ).length()
                })
        totalBytesDownloaded.set(initialSize)
        segmentsCompleted.set((alreadyDownloadedVideo.size + alreadyDownloadedAudio.size).toLong())

        // Download remaining segments
        val segmentDownloader = SegmentDownloader(httpClient, headers, controller)
        val dispatcher = Dispatchers.IO.limitedParallelism(threadCount)
        val downloadJobs = mutableListOf<Job>()

        videoRep?.initializationUrl?.let { url ->
            val job = launch(dispatcher) {
                AppLogger.d("MPD: Downloading video init segment from $url")
                segmentDownloader.download(
                    url,
                    downloadDir.resolve("video_init.m4s"),
                    "MPD-Video-Init",
                    0
                )
            }
            downloadJobs.add(job)
        }

        audioRep?.initializationUrl?.let { url ->
            val job = launch(dispatcher) {
                AppLogger.d("MPD: Downloading audio init segment from $url")
                segmentDownloader.download(
                    url,
                    downloadDir.resolve("audio_init.m4s"),
                    "MPD-Audio-Init",
                    0
                )
            }
            downloadJobs.add(job)
        }

        videoSegments?.filterNot { alreadyDownloadedVideo.contains(it) }?.forEach { segment ->
            val index = videoSegments.indexOf(segment)
            val job = launch(dispatcher) {
                val downloadedBytes = segmentDownloader.download(
                    segment.url,
                    downloadDir.resolve("segment_${"%05d".format(index)}.m4s"),
                    "MPD-Video",
                    index
                )
                val completed = segmentsCompleted.incrementAndGet()
                val totalDownloaded = totalBytesDownloaded.addAndGet(downloadedBytes)
                val estimatedTotal =
                    if (completed > 0) (totalDownloaded / completed) * totalSegmentsToDownload else 0
                onProgress(
                    Progress(
                        totalDownloaded,
                        estimatedTotal,
                        "Segments download: $completed/$totalSegmentsToDownload"
                    )
                )
            }
            downloadJobs.add(job)
        }

        audioSegments?.filterNot { alreadyDownloadedAudio.contains(it) }?.forEach { segment ->
            val index = audioSegments.indexOf(segment)
            val job = launch(dispatcher) {
                val downloadedBytes = segmentDownloader.download(
                    segment.url,
                    downloadDir.resolve("audio_segment_${"%05d".format(index)}.m4s"),
                    "MPD-Audio",
                    index
                )
                val completed = segmentsCompleted.incrementAndGet()
                val totalDownloaded = totalBytesDownloaded.addAndGet(downloadedBytes)
                val estimatedTotal =
                    if (completed > 0) (totalDownloaded / completed) * totalSegmentsToDownload else 0
                onProgress(
                    Progress(
                        totalDownloaded,
                        estimatedTotal,
                        "Segments download: $completed/$totalSegmentsToDownload"
                    )
                )
            }
            downloadJobs.add(job)
        }

        downloadJobs.joinAll()
        if (controller.isInterrupted()) throw CancellationException("Download interrupted.")

        val totalDurationSeconds = videoRep?.segments?.sumOf { it.durationSeconds }
            ?: audioRep?.segments?.sumOf { it.durationSeconds } ?: 0.0
        AppLogger.d("MPD (Segments): All segments downloaded. Merging...")
        var isPreparing = true
        onMergeProgress(
            Progress(0, totalBytesDownloaded.get()),
            task.apply {
                this.setLineInfo("Merging... 0%")
                this.setTaskState(VideoTaskState.PREPARE)
            })

        val finalOutputFile = downloadDir.resolve("merged_output.mp4")
        val mergeFlagFile = downloadDir.resolve("merge_in_progress.flag")

        if (finalOutputFile.exists() && !mergeFlagFile.exists()) {
            return@coroutineScope finalOutputFile
        }

        val mergeSession =
            mergeMpdSegments(
                downloadDir,
                videoRep,
                audioRep,
                finalOutputFile.absolutePath,
                totalDurationSeconds

            ) { percentage ->
                if (isPreparing && percentage == 100) {
                    isPreparing = false
                }

                val message =
                    if (isPreparing) "Preparing segments... $percentage%" else "Merging... $percentage%"

                onMergeProgress(
                    Progress(
                        totalBytesDownloaded.get() * percentage / 100,
                        totalBytesDownloaded.get(),
                        message
                    ),
                    task.apply {
                        this.setLineInfo(message)
                        this.setTaskState(VideoTaskState.PREPARE)
                    }
                )
            }

        if (ReturnCode.isSuccess(mergeSession.returnCode)) {
            mergeFlagFile.delete()
        } else {
            throw IOException("FFmpeg failed to merge MPD segments. Log: ${mergeSession.allLogsAsString}")
        }
        finalOutputFile
    }

    /**
     * Downloads DASH content using Base URLs, which represent complete files.
     */
    private suspend fun downloadByBaseUrl(
        task: VideoTaskItem,
        headers: Map<String, String>,
        downloadDir: File,
        videoRepRaw: MpdPlaylistParser.MpdRepresentation?,
        audioRep: MpdPlaylistParser.MpdRepresentation?,
        controller: FileBasedDownloadController,
        onProgress: (progress: Progress) -> Unit
    ): File = coroutineScope {
        // Combined files used as input for FFmpeg
        val videoCombined = downloadDir.resolve("video_combined")
        val audioCombined = downloadDir.resolve("audio_combined")
        val finalFile = downloadDir.resolve("merged_output.mp4")

        // Optimization: Skip video download if audio-only is requested and separate audio exists.
        val videoRep = if (isAudioOnlyExtract && audioRep?.baseUrls?.isNotEmpty() == true) {
            AppLogger.d("MPD: Audio-only requested and separate audio file found. Skipping video track download.")
            null
        } else {
            videoRepRaw
        }

        val videoDownloaded = AtomicLong(0)
        val audioDownloaded = AtomicLong(0)
        val videoTotal = AtomicLong(0)
        val audioTotal = AtomicLong(0)

        val jobs = mutableListOf<Deferred<*>>()

        // --- Video Logic ---
        videoRep?.let { rep ->
            jobs.add(async {
                val videoParts = mutableListOf<File>()
                rep.baseUrls.takeIf { it.isNotEmpty() }?.let { urls ->
                    val dataFile = downloadDir.resolve("video_data_base")
                    downloadFileWithCustomDownloader(
                        urls,
                        dataFile,
                        headers,
                    ) { dl, tot ->
                        videoDownloaded.set(dl)
                        videoTotal.set(tot)

                        val current = videoDownloaded.get() + audioDownloaded.get()
                        val total = videoTotal.get() + audioTotal.get()
                        onProgress(
                            createProgressForBaseUrlsMpd(current, total)
                        )
                    }
                    if (dataFile.exists() && dataFile.length() > 0) videoParts.add(dataFile)
                }

                if (videoParts.isNotEmpty()) {
                    manualConcat(videoParts, videoCombined) { }
                    videoParts.forEach { it.delete() }
                }
            })
        }

        // --- Audio Logic ---
        audioRep?.let { rep ->
            jobs.add(async {
                val audioParts = mutableListOf<File>()
                rep.baseUrls.takeIf { it.isNotEmpty() }?.let { urls ->
                    val dataFile = downloadDir.resolve("audio_data_base")
                    downloadFileWithCustomDownloader(
                        urls,
                        dataFile,
                        headers,
                    ) { dl, tot ->

                        audioDownloaded.set(dl)
                        audioTotal.set(tot)

                        val current = videoDownloaded.get() + audioDownloaded.get()
                        val total = videoTotal.get() + audioTotal.get()
                        onProgress(
                            createProgressForBaseUrlsMpd(current, total)
                        )
                    }
                    if (dataFile.exists() && dataFile.length() > 0) audioParts.add(dataFile)
                }

                if (audioParts.isNotEmpty()) {
                    manualConcat(audioParts, audioCombined) { }
                    audioParts.forEach { it.delete() }
                }
            })
        }
        jobs.awaitAll()
        if (controller.isInterrupted()) throw CancellationException("Download interrupted.")

        AppLogger.d("MPD (BaseURL): All stream downloads complete. Merging...")
        val totalDurationSeconds = videoRep?.segments?.sumOf { it.durationSeconds }
            ?: audioRep?.segments?.sumOf { it.durationSeconds } ?: 0.0

        onMergeProgress(
            Progress(
                0,
                videoDownloaded.get() + audioDownloaded.get(),
                "Merging... $1%"
            ),
            task.apply {
                this.setLineInfo("Merging... 0%")
                this.setTaskState(VideoTaskState.PREPARE)
            }
        )

        val mergeSession = mergeBaseUrlStreams(
            videoCombined,
            audioCombined.takeIf { it.exists() && it.length() > 0 },
            finalFile.absolutePath,
            totalDurationSeconds = totalDurationSeconds,
            onMergeProgress = { percentage ->
                val totalBytesDownloaded = videoTotal.get() + audioTotal.get()
                onMergeProgress(
                    Progress(
                        totalBytesDownloaded * percentage / 100,
                        totalBytesDownloaded,
                        "Merging... $percentage%"
                    ),
                    task.apply {
                        this.setLineInfo("Merging... $percentage%")
                        this.setTaskState(VideoTaskState.PREPARE)
                    }
                )
            },
            audioCodec = audioRep?.codecs
        )

        if (!ReturnCode.isSuccess(mergeSession.returnCode)) {
            AppLogger.e("FFmpeg Fail: ${mergeSession.allLogsAsString}")
            throw IOException("FFmpeg failed to merge BaseURL streams.")
        }

        videoCombined.delete()
        audioCombined.delete()

        finalFile
    }

    private fun createProgressForBaseUrlsMpd(current: Long, total: Long): Progress {
        if (total <= 0) return Progress(current, 0, "Starting download...")

        val percentage = (current.toDouble() / total.toDouble()) * 100
        val formattedPercent = String.format("%.2f", percentage)
        val currentReadable = FileUtil.getFileSizeReadable(current.toDouble())
        val totalReadable = FileUtil.getFileSizeReadable(total.toDouble())

        return Progress(
            current,
            total,
            "[Downloading] $formattedPercent% ($currentReadable / $totalReadable)"
        )
    }

    /**
     * Wraps the legacy CustomFileDownloader in a pausable/cancellable coroutine.
     */
    private suspend fun downloadFileWithCustomDownloader(
        urls: List<String>,
        outputFile: File,
        headers: Map<String, String>,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) = suspendCancellableCoroutine { continuation ->
        val listener = object : DownloadListener {
            override fun onSuccess() {
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }

            override fun onFailure(e: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onProgressUpdate(downloadedBytes: Long, totalBytes: Long) {
                onProgress(downloadedBytes, totalBytes)
            }

            override fun onChunkProgressUpdate(
                downloadedBytes: Long,
                allBytesChunk: Long,
                chunkIndex: Int
            ) {
            }

            override fun onChunkFailure(e: Throwable, index: CustomFileDownloader.Chunk) {}
        }

        val downloader = CustomFileDownloader(
            URL(urls.first()),
            outputFile,
            threadCount,
            headers,
            httpClient,
            listener,
            false
        )
        try {
            downloader.download()
        } catch (e: Throwable) {
            listener.onFailure(e)
        }
        if (continuation.isActive) {
            continuation.resume(Unit)
        }
    }

    private fun mergeMpdSegments(
        mpdTmpDir: File,
        videoRep: MpdPlaylistParser.MpdRepresentation?,
        audioRep: MpdPlaylistParser.MpdRepresentation?,
        finalOutputPath: String,
        totalDurationSeconds: Double,
        onMergeProgress: ((percentage: Int) -> Unit)?
    ): FFmpegSession {
        val videoSegments = videoRep?.segments
        val audioSegments = audioRep?.segments
        val hasVideo = !videoSegments.isNullOrEmpty()
        val hasAudio = !audioSegments.isNullOrEmpty()

        AppLogger.d("MPD: Starting manual concat merge. ${videoSegments?.size ?: 0} video, ${audioSegments?.size ?: 0} audio.")

        val tempVideoFile = mpdTmpDir.resolve("temp_video.mp4")
        val tempAudioFile = mpdTmpDir.resolve("temp_audio.mp4")

        val videoFilesToConcat = mutableListOf<File>()
        if (hasVideo) {
            if (mpdTmpDir.resolve("video_init.m4s").exists()) {
                videoFilesToConcat.add(mpdTmpDir.resolve("video_init.m4s"))
            }
            videoSegments.indices.forEach { index ->
                videoFilesToConcat.add(mpdTmpDir.resolve("segment_${"%05d".format(index)}.m4s"))
            }
        }

        val audioFilesToConcat = mutableListOf<File>()
        if (hasAudio) {
            if (mpdTmpDir.resolve("audio_init.m4s").exists()) {
                audioFilesToConcat.add(mpdTmpDir.resolve("audio_init.m4s"))
            }
            audioSegments.indices.forEach { index ->
                audioFilesToConcat.add(mpdTmpDir.resolve("audio_segment_${"%05d".format(index)}.m4s"))
            }
        }

        val totalConcatSize = (videoFilesToConcat.sumOf { if (it.exists()) it.length() else 0L } +
                audioFilesToConcat.sumOf { if (it.exists()) it.length() else 0L })
        var concatenatedBytes = 0L

        val concatProgressCallback: (bytes: Long) -> Unit = { bytes ->
            concatenatedBytes += bytes
            if (totalConcatSize > 0) {
                val percentage = ((concatenatedBytes * 100) / totalConcatSize).toInt()
                onMergeProgress?.invoke(percentage)
            }
        }

        if (hasVideo) {
            manualConcat(videoFilesToConcat, tempVideoFile, concatProgressCallback)
        }
        if (hasAudio) {
            manualConcat(audioFilesToConcat, tempAudioFile, concatProgressCallback)
        }

        AppLogger.d("MPD: Concatenation finished. Starting FFmpeg merge.")

        val mergeSession = mergeBaseUrlStreams(
            tempVideoFile,
            tempAudioFile.takeIf { it.exists() && it.length() > 0 },
            finalOutputPath,
            totalDurationSeconds,
            onMergeProgress
        )

        tempVideoFile.delete()
        tempAudioFile.delete()

        return mergeSession
    }

    private fun mergeBaseUrlStreams(
        videoFile: File,
        audioFile: File?,
        finalOutputPath: String,
        totalDurationSeconds: Double,
        onMergeProgress: ((percentage: Int) -> Unit)?,
        audioCodec: String? = null
    ): FFmpegSession {
        val arguments = mutableListOf<String>()

        if (videoFile.exists() && videoFile.length() > 0) {
            arguments.addAll(listOf("-i", videoFile.absolutePath))
        }

        audioFile?.takeIf { it.exists() && it.length() > 0 }?.let {
            arguments.addAll(listOf("-i", it.absolutePath))
        }

        if (arguments.isEmpty()) {
            throw IOException("No valid video or audio files to merge.")
        }
        arguments.add(0, "-y")

        val hasVideo = videoFile.exists() && videoFile.length() > 0
        val hasAudio = audioFile?.exists() == true && audioFile.length() > 0

        addCommonMergeArguments(arguments, hasVideo, hasAudio, finalOutputPath, audioCodec)

        val commandString = arguments.joinToString(" ")
        AppLogger.d("FFmpeg: Executing MPD BaseURL merge with command: $commandString")

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
            if (onMergeProgress != null && totalDurationSeconds > 0) {
                val totalDurationMillis = (totalDurationSeconds * 1000).toLong()
                val currentTimeMillis = statistics.time
                if (currentTimeMillis > 0) {
                    val percentage = ((currentTimeMillis * 100) / totalDurationMillis).toInt()
                    onMergeProgress(percentage.coerceIn(0, 100))
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
        audioCodec: String? = null
    ) {
        arguments.apply {
            if (isAudioOnlyExtract) {
                add("-vn")
                if (hasAudio && hasVideo) {
                    add("-map"); add("1:a:0?")
                } else {
                    add("-map"); add("0:a:0?")
                }
                add("-c:a"); add("libmp3lame")
                add("-q:a"); add("2")
            } else {
                when {
                    hasVideo && hasAudio -> {
                        // Map video from the first input (0) and audio from the second (1).
                        // This is now applied to both segment and base URL merges.
                        add("-map"); add("0:v:0?"); add("-map"); add("1:a:0?")
                    }
                    // This case might not be strictly necessary if you always have one input
                    // when there's only one stream, but it's good for robustness.
                    hasVideo -> {
                        add("-map"); add("0:v:0?")
                    }

                    hasAudio -> {
                        add("-map"); add("0:a:0?")
                    }
                }

                if (hasVideo && (videoCodec?.startsWith("hvc1") == true || videoCodec?.startsWith("dvh1") == true)) {
                    add("-c:v"); add("libx264"); add("-preset"); add("ultrafast"); add("-crf"); add(
                        "26"
                    ); add(
                        "-pix_fmt"
                    ); add("yuv420p")
                    if (hasAudio) add("-c:a"); add("copy")
                } else {
                    add("-c"); add("copy")
                }

                if (hasAudio && audioCodec?.contains("aac", ignoreCase = true) == true) {
                    add("-bsf:a"); add("aac_adtstoasc")
                }

                if (finalOutputPath.endsWith(".mp4", true) || finalOutputPath.endsWith(
                        ".mov",
                        true
                    )
                ) {
                    add("-movflags"); add("+faststart")
                }
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
                                // Reset counters
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
