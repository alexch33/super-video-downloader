package com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy

import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.custom_downloader.CustomFileDownloader
import com.myAllVideoBrowser.util.downloaders.custom_downloader.DownloadListener
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SegmentDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import com.myAllVideoBrowser.util.hls_parser.MpdPlaylistParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.URL
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
    private val onMergeProgress: (progress: Progress) -> Unit,
    private val threadCount: Int,
    private val videoCodec: String?
) : ManifestDownloader {

    // Mutex to protect progress updates from concurrent BaseURL downloads
    private val progressMutex = Mutex()
    private var videoProgress = Progress(0, 0)
    private var audioProgress = Progress(0, 0)

    override suspend fun download(
        task: VideoTaskItem,
        headers: Map<String, String>,
        downloadDir: File,
        controller: FileBasedDownloadController,
        onProgress: (progress: Progress) -> Unit
    ): File {
        return withContext(Dispatchers.IO) {
            // Step 1: Parse the manifest to get user-selected representations
            val (videoRep, audioRep) = getMpdRepresentations(task.url, headers)
            val primaryRep = videoRep ?: audioRep
            ?: throw IOException("No downloadable video or audio representation found for the selected format.")

            // Step 2: Decide which download strategy to use
            val finalOutputFile = if (primaryRep.segments.isNotEmpty()) {
                // --- STRATEGY A: Segment-based Download ---
                AppLogger.d("MPD: Detected manifest with segments. Starting segment download.")
                downloadBySegments(task, headers, downloadDir, videoRep?.segments, audioRep?.segments, controller, onProgress)
            } else if (primaryRep.baseUrls.isNotEmpty()) {
                // --- STRATEGY B: BaseURL-based Download ---
                AppLogger.d("MPD: Detected manifest with Base URLs. Starting file download.")
                downloadByBaseUrl(task, headers, downloadDir, videoRep, audioRep, controller, onProgress)
            } else {
                throw IOException("MPD manifest contains neither segments nor base URLs. Cannot download.")
            }

            finalOutputFile // Return the successfully merged file
        }
    }

    /**
     * Downloads DASH content by fetching individual segments in parallel.
     */
    private suspend fun downloadBySegments(
        task: VideoTaskItem,
        headers: Map<String, String>,
        downloadDir: File,
        videoSegments: List<MpdPlaylistParser.Segment>?,
        audioSegments: List<MpdPlaylistParser.Segment>?,
        controller: FileBasedDownloadController,
        onProgress: (progress: Progress) -> Unit
    ): File = coroutineScope {
        val totalBytesDownloaded = AtomicLong(0)
        val segmentsCompleted = AtomicLong(0)

        val totalSegmentsToDownload = (videoSegments?.size ?: 0) + (audioSegments?.size ?: 0)
        if (totalSegmentsToDownload == 0) {
            throw IOException("No segments found to download for either video or audio.")
        }

        // Resume logic
        val alreadyDownloadedVideo = videoSegments?.filter {
            downloadDir.resolve("segment_${"%05d".format(videoSegments.indexOf(it))}.m4s").exists()
        } ?: emptyList()
        val alreadyDownloadedAudio = audioSegments?.filter {
            downloadDir.resolve("audio_segment_${"%05d".format(audioSegments.indexOf(it))}.m4s").exists()
        } ?: emptyList()

        // Sum initial size and report progress
        val initialSize = (alreadyDownloadedVideo.sumOf { downloadDir.resolve("segment_${"%05d".format(videoSegments!!.indexOf(it))}.m4s").length() } +
                alreadyDownloadedAudio.sumOf { downloadDir.resolve("audio_segment_${"%05d".format(audioSegments!!.indexOf(it))}.m4s").length() })
        totalBytesDownloaded.set(initialSize)
        segmentsCompleted.set((alreadyDownloadedVideo.size + alreadyDownloadedAudio.size).toLong())
        // (Initial progress reporting logic can be enhanced here)

        // Download remaining segments
        val segmentDownloader = SegmentDownloader(httpClient, headers, controller)
        val downloadJobs = mutableListOf<Job>()
        val dispatcher = Dispatchers.IO.limitedParallelism(threadCount)

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
                val estimatedTotal = if (completed > 0) (totalDownloaded / completed) * totalSegmentsToDownload else 0
                onProgress(Progress(totalDownloaded, estimatedTotal))
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
                val estimatedTotal = if (completed > 0) (totalDownloaded / completed) * totalSegmentsToDownload else 0
                onProgress(Progress(totalDownloaded, estimatedTotal))
            }
            downloadJobs.add(job)
        }

        downloadJobs.joinAll()
        if (controller.isInterrupted()) throw CancellationException("Download interrupted.")

        AppLogger.d("MPD (Segments): All segments downloaded. Merging...")
        onMergeProgress(Progress(totalBytesDownloaded.get(), totalBytesDownloaded.get()))
        val finalOutputFile = downloadDir.resolve("merged_output.mp4")
        val mergeSession = mergeMpdSegments(downloadDir, videoSegments, audioSegments, finalOutputFile.absolutePath)

        if (!ReturnCode.isSuccess(mergeSession.returnCode)) {
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
        videoRep: MpdPlaylistParser.MpdRepresentation?,
        audioRep: MpdPlaylistParser.MpdRepresentation?,
        controller: FileBasedDownloadController,
        onProgress: (progress: Progress) -> Unit
    ): File = coroutineScope {
        val videoTempFile = downloadDir.resolve("video_stream.mp4")
        val audioTempFile = downloadDir.resolve("audio_stream.mp4")
        val finalFile = downloadDir.resolve("merged_output.mp4")

        val jobs = mutableListOf<Deferred<*>>()

        // Launch video download if it exists
        videoRep?.baseUrls?.takeIf { it.isNotEmpty() }?.let { urls ->
            val job = async {
                downloadFileWithCustomDownloader(urls, videoTempFile, headers, controller) { downloaded, total ->
                    launch {
                        progressMutex.withLock {
                            videoProgress = Progress(downloaded, total)
                            onProgress(Progress(videoProgress.currentBytes + audioProgress.currentBytes, videoProgress.totalBytes + audioProgress.totalBytes))
                        }
                    }
                }
            }
            jobs.add(job)
        }

        // Launch audio download if it exists
        audioRep?.baseUrls?.takeIf { it.isNotEmpty() }?.let { urls ->
            val job = async {
                downloadFileWithCustomDownloader(urls, audioTempFile, headers, controller) { downloaded, total ->
                    launch {
                        progressMutex.withLock {
                            audioProgress = Progress(downloaded, total)
                            onProgress(Progress(videoProgress.currentBytes + audioProgress.currentBytes, videoProgress.totalBytes + audioProgress.totalBytes))
                        }
                    }
                }
            }
            jobs.add(job)
        }

        jobs.awaitAll()
        if (controller.isInterrupted()) throw CancellationException("Download interrupted.")

        AppLogger.d("MPD (BaseURL): All stream downloads complete. Merging...")
        onMergeProgress(Progress(videoTempFile.length() + audioTempFile.length(), videoTempFile.length() + audioTempFile.length()))

        val mergeSession = mergeBaseUrlStreams(videoTempFile, audioTempFile.takeIf { it.exists() }, finalFile.absolutePath)
        if (!ReturnCode.isSuccess(mergeSession.returnCode)) {
            throw IOException("FFmpeg failed to merge BaseURL streams. Log: ${mergeSession.allLogsAsString}")
        }
        videoTempFile.delete()
        audioTempFile.delete()
        finalFile
    }

    /**
     * Wraps the legacy CustomFileDownloader in a pausable/cancellable coroutine.
     */
    private suspend fun downloadFileWithCustomDownloader(
        urls: List<String>,
        outputFile: File,
        headers: Map<String, String>,
        controller: FileBasedDownloadController,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) = suspendCancellableCoroutine { continuation ->
        val listener = object : DownloadListener {
            override fun onSuccess() {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onFailure(e: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onProgressUpdate(downloadedBytes: Long, totalBytes: Long) {
                onProgress(downloadedBytes, totalBytes)
            }
            override fun onChunkProgressUpdate(downloadedBytes: Long, allBytesChunk: Long, chunkIndex: Int) {}
            override fun onChunkFailure(e: Throwable, index: CustomFileDownloader.Chunk) {}
        }

        val downloader = CustomFileDownloader(URL(urls.first()), outputFile, threadCount, headers, httpClient, listener, false)
        downloader.download()

        continuation.invokeOnCancellation {
            CustomFileDownloader.pause(outputFile)
        }

        CoroutineScope(continuation.context).launch {
            while (isActive) { // Check `isActive` of this new scope
                if (controller.isInterrupted()) {
                    CustomFileDownloader.pause(outputFile)
                    break
                }
                delay(500)
            }
        }
    }

    private fun mergeMpdSegments(
        mpdTmpDir: File,
        videoSegments: List<MpdPlaylistParser.Segment>?,
        audioSegments: List<MpdPlaylistParser.Segment>?,
        finalOutputPath: String,
    ): FFmpegSession {
        AppLogger.d("MPD: Starting merge. ${videoSegments?.size ?: 0} video, ${audioSegments?.size ?: 0} audio.")

        val arguments = mutableListOf<String>()

        if (!videoSegments.isNullOrEmpty()) {
            val videoConcatFile = mpdTmpDir.resolve("ffmpeg_video_concat.txt")
            videoConcatFile.writeText(
                videoSegments.indices.joinToString("\n") { index ->
                    "file 'segment_${"%05d".format(index)}.m4s'"
                })
            arguments.apply {
                add("-f"); add("concat"); add("-safe"); add("0")
                add("-i"); add(videoConcatFile.absolutePath)
            }
        }

        if (!audioSegments.isNullOrEmpty()) {
            val audioConcatFile = mpdTmpDir.resolve("ffmpeg_audio_concat.txt")
            audioConcatFile.writeText(
                audioSegments.indices.joinToString("\n") { index ->
                    "file 'audio_segment_${"%05d".format(index)}.m4s'"
                })
            arguments.apply {
                add("-f"); add("concat"); add("-safe"); add("0")
                add("-i"); add(audioConcatFile.absolutePath)
            }
        }

        if (arguments.isEmpty()) {
            throw IOException("Cannot merge segments: No video or audio segments were provided.")
        }

        addCommonMergeArguments(arguments, !videoSegments.isNullOrEmpty(), !audioSegments.isNullOrEmpty(), finalOutputPath)
        AppLogger.d("FFmpeg: Executing MPD segment merge with arguments: $arguments")
        return FFmpegKit.executeWithArguments(arguments.toTypedArray())
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
        addCommonMergeArguments(arguments, true, audioFile != null, finalOutputPath)
        AppLogger.d("FFmpeg: Executing MPD BaseURL merge with arguments: $arguments")
        return FFmpegKit.executeWithArguments(arguments.toTypedArray())
    }

    private fun addCommonMergeArguments(arguments: MutableList<String>, hasVideo: Boolean, hasAudio: Boolean, finalOutputPath: String) {
        arguments.apply {
            when {
                hasVideo && hasAudio -> { add("-map"); add("0:v:0?"); add("-map"); add("1:a:0?") }
                hasVideo -> { add("-map"); add("0:v:0?") }
                hasAudio -> { add("-map"); add("0:a:0?") }
            }

            if (hasVideo && (videoCodec?.startsWith("hvc1") == true || videoCodec?.startsWith("dvh1") == true)) {
                add("-c:v"); add("libx264"); add("-preset"); add("veryfast"); add("-crf"); add("23"); add("-pix_fmt"); add("yuv420p")
                if (hasAudio) add("-c:a"); add("copy")
            } else {
                add("-c"); add("copy")
            }

            add("-bsf:a"); add("aac_adtstoasc")
            add("-movflags"); add("+faststart")
            add("-y"); add(finalOutputPath)
        }
    }
}
