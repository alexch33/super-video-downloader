package com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy

import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.DownloaderUtils
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SegmentDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import com.myAllVideoBrowser.util.hls_parser.HlsPlaylistParser
import kotlinx.coroutines.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Download strategy for VOD (Video on Demand) HLS playlists.
 * This class is responsible for parsing the playlist, downloading all segments in parallel,
 * and merging them into a final file using FFmpeg.
 */
class HlsDownloader(
    private val httpClient: OkHttpClient,
    private val getMediaSegments: suspend (url: String, headers: Map<String, String>) -> Pair<List<HlsPlaylistParser.MediaSegment>?, List<HlsPlaylistParser.MediaSegment>?>,
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
            val hlsTotalBytesDownloaded = AtomicLong(0L)
            val hlsSegmentsCompleted = AtomicInteger(0)

            // 1. Get the list of media segments by calling the provided function
            val (videoSegmentsRaw, audioSegments) = getMediaSegments(task.url, headers)

            // Optimization: If audio-only is requested and separate audio tracks exist,
            // we don't need to download the video track at all.
            val videoSegments = if (isAudioOnlyExtract && !audioSegments.isNullOrEmpty()) {
                AppLogger.d("HLS: Audio-only extract requested and separate audio track found. Skipping video segments.")
                null
            } else {
                videoSegmentsRaw
            }

            if (videoSegments.isNullOrEmpty() && audioSegments.isNullOrEmpty()) {
                throw IOException("No media segments found in HLS playlist for the selected format.")
            }
            AppLogger.d("HLS: Found ${videoSegments?.size ?: 0} video and ${audioSegments?.size ?: 0} audio segments.")

            val isVideoFmp4 =
                !videoSegments.isNullOrEmpty() && (videoSegments.first() as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null
            val isAudioFmp4 =
                !audioSegments.isNullOrEmpty() && (audioSegments.first() as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null
            val videoExt = if (isVideoFmp4) "m4s" else "ts"
            val audioExt = if (isAudioFmp4) "m4s" else "ts"
            val totalSegmentsToDownload = (videoSegments?.size ?: 0) + (audioSegments?.size ?: 0)

            val segmentDownloader = SegmentDownloader(httpClient, headers, controller)

            // --- Download Initialization Segments (for fMP4) ---
            if (isVideoFmp4 && !videoSegments.isNullOrEmpty()) {
                val initSegment =
                    (videoSegments.first() as HlsPlaylistParser.UrlMediaSegment).initializationSegment!!
                val initFile = downloadDir.resolve("init_video.mp4")
                if (!initFile.exists() || initFile.length() == 0L) {
                    AppLogger.d("HLS (fMP4): Downloading video init segment from ${initSegment.url}")
                    val downloadedBytes =
                        segmentDownloader.download(initSegment.url, initFile, "HLS-Init", 0)
                    hlsTotalBytesDownloaded.addAndGet(downloadedBytes)
                }
            }
            if (isAudioFmp4 && !audioSegments.isNullOrEmpty()) {
                val initSegment =
                    (audioSegments.first() as HlsPlaylistParser.UrlMediaSegment).initializationSegment!!
                val initFile = downloadDir.resolve("init_audio.mp4")
                if (!initFile.exists() || initFile.length() == 0L) {
                    AppLogger.d("HLS (fMP4): Downloading audio init segment from ${initSegment.url}")
                    val downloadedBytes =
                        segmentDownloader.download(initSegment.url, initFile, "HLS-Init", 1)
                    hlsTotalBytesDownloaded.addAndGet(downloadedBytes)
                }
            }

            // --- Download Encryption Keys (for TS) ---
            (videoSegments?.firstOrNull() as? HlsPlaylistParser.UrlMediaSegment)?.encryptionKey?.let { key ->
                val keyFile = downloadDir.resolve("video_encryption.key")
                if (!keyFile.exists() || keyFile.length() == 0L) {
                    AppLogger.d("HLS: Downloading video encryption key from ${key.uri}")
                    DownloaderUtils.downloadKey(httpClient, key.uri, keyFile, headers.toHeaders())
                }
            }
            (audioSegments?.firstOrNull() as? HlsPlaylistParser.UrlMediaSegment)?.encryptionKey?.let { key ->
                val keyFile = downloadDir.resolve("audio_encryption.key")
                if (!keyFile.exists() || keyFile.length() == 0L) {
                    AppLogger.d("HLS: Downloading audio encryption key from ${key.uri}")
                    DownloaderUtils.downloadKey(httpClient, key.uri, keyFile, headers.toHeaders())
                }
            }

            // 2. Handle Resuming: Calculate initial progress from already downloaded files
            val alreadyDownloadedVideo = videoSegments?.filter { segment ->
                val segmentFile =
                    downloadDir.resolve("segment_${"%05d".format(videoSegments.indexOf(segment))}.$videoExt")
                segmentFile.exists() && segmentFile.length() > 0
            } ?: emptyList()

            val alreadyDownloadedAudio = audioSegments?.filter { segment ->
                val segmentFile =
                    downloadDir.resolve("audio_segment_${"%05d".format(audioSegments.indexOf(segment))}.$audioExt")
                segmentFile.exists() && segmentFile.length() > 0
            } ?: emptyList()

            val initialVideoSize = if (videoSegments != null) {
                alreadyDownloadedVideo.sumOf {
                    downloadDir.resolve("segment_${"%05d".format(videoSegments.indexOf(it))}.$videoExt")
                        .length()
                }
            } else 0L

            val initialAudioSize = if (audioSegments != null) {
                alreadyDownloadedAudio.sumOf {
                    downloadDir.resolve("audio_segment_${"%05d".format(audioSegments.indexOf(it))}.$audioExt")
                        .length()
                }
            } else 0L

            val initialTotalDownloaded = initialVideoSize + initialAudioSize
            val initialSegmentsCompleted = alreadyDownloadedVideo.size + alreadyDownloadedAudio.size

            hlsTotalBytesDownloaded.addAndGet(initialTotalDownloaded)
            hlsSegmentsCompleted.set(initialSegmentsCompleted)

            if (initialSegmentsCompleted > 0) {
                val info =
                    "HLS Resumed: ${alreadyDownloadedVideo.size}/${videoSegments?.size ?: 0} video and ${alreadyDownloadedAudio.size}/${audioSegments?.size ?: 0} audio segments already present."
                AppLogger.d(info)
                if (initialSegmentsCompleted < totalSegmentsToDownload) {
                    val avgSegmentSize = initialTotalDownloaded / initialSegmentsCompleted
                    val estimatedOverallTotal = avgSegmentSize * totalSegmentsToDownload
                    onProgress(Progress(initialTotalDownloaded, estimatedOverallTotal, info))
                }
            }

            // 3. Download remaining segments in parallel using coroutines
            val downloadJobs = mutableListOf<Job>()
            val dispatcher = Dispatchers.IO.limitedParallelism(threadCount)

            // Create jobs for video segments
            videoSegments?.filterNot { alreadyDownloadedVideo.contains(it) }?.forEach { segment ->
                val index = videoSegments.indexOf(segment)
                val job = launch(dispatcher) {
                    val outputFile =
                        downloadDir.resolve("segment_${"%05d".format(index)}.$videoExt")
                    val downloadedBytes =
                        segmentDownloader.download(segment.url, outputFile, "HLS", index)
                    val completed = hlsSegmentsCompleted.incrementAndGet()
                    val totalDownloaded = hlsTotalBytesDownloaded.addAndGet(downloadedBytes)

                    val avgSegmentSize = if (completed > 0) totalDownloaded / completed else 0
                    val totalToDownload = avgSegmentSize * totalSegmentsToDownload

                    onProgress(
                        createDownloadProgress(
                            totalDownloaded,
                            totalToDownload,
                            completed,
                            totalSegmentsToDownload
                        )
                    )
                }
                downloadJobs.add(job)
            }

            // Create jobs for audio segments
            audioSegments?.filterNot { alreadyDownloadedAudio.contains(it) }?.forEach { segment ->
                val index = audioSegments.indexOf(segment)
                val job = launch(dispatcher) {
                    val outputFile =
                        downloadDir.resolve("audio_segment_${"%05d".format(index)}.$audioExt")
                    val downloadedBytes =
                        segmentDownloader.download(segment.url, outputFile, "HLS-Audio", index)
                    val completedSegments = hlsSegmentsCompleted.incrementAndGet()
                    val totalDownloaded = hlsTotalBytesDownloaded.addAndGet(downloadedBytes)

                    val avgSegmentSize =
                        if (completedSegments > 0) totalDownloaded / completedSegments else 0
                    val totalToDownloaded = avgSegmentSize * totalSegmentsToDownload

                    onProgress(
                        createDownloadProgress(
                            totalDownloaded,
                            totalToDownloaded,
                            completedSegments,
                            totalSegmentsToDownload
                        )
                    )
                }
                downloadJobs.add(job)
            }

            // Wait for all segment downloads to complete
            downloadJobs.joinAll()

            // After all jobs are done, a final check for interruption is crucial.
            if (controller.isInterrupted()) {
                throw CancellationException("Download was interrupted after segment downloads.")
            }

            val finalDownloadedBytes = hlsTotalBytesDownloaded.get()
            onMergeProgress(Progress(finalDownloadedBytes, finalDownloadedBytes), task.apply {
                this.taskState = VideoTaskState.PREPARE
                this.lineInfo = "Merging segments..."
            });

            AppLogger.d("HLS: All segments downloaded successfully. Starting merge.")
            val extension = if (isAudioOnlyExtract) "mp3" else "mp4"
            val finalOutputFile = downloadDir.resolve("merged_output.$extension")
            val mergeFlagFile = downloadDir.resolve("merge_in_progress.flag")

            if (finalOutputFile.exists() && !mergeFlagFile.exists()) {
                return@withContext finalOutputFile
            }

            mergeFlagFile.createNewFile()

            // 4. Merge all segments using FFmpeg
            val mergeSession = DownloaderUtils.mergeHlsSegments(
                downloadDir,
                videoSegments,
                audioSegments,
                finalOutputFile.absolutePath,
                videoCodec,
                isAudioOnlyExtract,
                onMergeProgress = { percentage ->
                    onMergeProgress(
                        Progress(finalDownloadedBytes * percentage / 100, finalDownloadedBytes),
                        task.apply {
                            this.lineInfo = "Merging segments... $percentage%"
                            this.taskState = VideoTaskState.PREPARE
                        });
                }
            )
            if (ReturnCode.isSuccess(mergeSession.returnCode)) {
                mergeFlagFile.delete()
            } else {
                throw IOException("FFmpeg failed to merge segments. Return code: ${mergeSession.returnCode}. Logs: ${mergeSession.allLogsAsString}")
            }

            AppLogger.d("HLS: Merge completed successfully.")
            finalOutputFile
        }
    }

    private fun createDownloadProgress(
        currentTotalDownloaded: Long,
        totalToDownloaded: Long,
        completedSegments: Int,
        totalSegments: Int
    ): Progress {
        val currentReadable = FileUtil.getFileSizeReadable(currentTotalDownloaded.toDouble())
        val totalReadable = FileUtil.getFileSizeReadable(totalToDownloaded.toDouble())

        val percents = completedSegments / totalSegments.toDouble() * 100
        return Progress(
            currentTotalDownloaded,
            totalToDownloaded,
            "${String.format("%.2f", percents)}% ${currentReadable}/${totalReadable} segments: $completedSegments / $totalSegments"
        )
    }
}
