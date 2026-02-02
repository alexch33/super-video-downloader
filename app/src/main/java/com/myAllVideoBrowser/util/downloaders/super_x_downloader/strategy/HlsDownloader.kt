package com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy

import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
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
 *
 * @param httpClient The OkHttpClient to be used for network requests.
 * @param getMediaSegments A function reference to parse the HLS manifest and get segments.
 * @param onMergeProgress A callback to update progress when merging begins.
 * @param threadCount The maximum number of segments to download in parallel.
 * @param videoCodec The video codec of the stream, used to determine if re-encoding is needed.
 */
class HlsDownloader(
    private val httpClient: OkHttpClient,
    private val getMediaSegments: suspend (url: String, headers: Map<String, String>) -> Pair<List<HlsPlaylistParser.MediaSegment>?, List<HlsPlaylistParser.MediaSegment>?>,
    private val onMergeProgress: (progress: Progress, task: VideoTaskItem) -> Unit,
    private val threadCount: Int,
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
            val hlsTotalBytesDownloaded = AtomicLong(0L)
            val hlsSegmentsCompleted = AtomicInteger(0)

            // 1. Get the list of media segments by calling the provided function
            val (videoSegments, audioSegments) = getMediaSegments(task.url, headers)

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

            val initialVideoSize = alreadyDownloadedVideo.sumOf {
                downloadDir.resolve(
                    "segment_${
                        "%05d".format(videoSegments!!.indexOf(it))
                    }.$videoExt"
                ).length()
            }
            val initialAudioSize = alreadyDownloadedAudio.sumOf {
                downloadDir.resolve(
                    "audio_segment_${
                        "%05d".format(audioSegments!!.indexOf(it))
                    }.$audioExt"
                ).length()
            }
            val initialTotalDownloaded = initialVideoSize + initialAudioSize
            val initialSegmentsCompleted = alreadyDownloadedVideo.size + alreadyDownloadedAudio.size

            hlsTotalBytesDownloaded.set(initialTotalDownloaded)
            hlsSegmentsCompleted.set(initialSegmentsCompleted)

            if (initialSegmentsCompleted > 0) {
                AppLogger.d("HLS Resumed: ${alreadyDownloadedVideo.size}/${videoSegments?.size ?: 0} video and ${alreadyDownloadedAudio.size}/${audioSegments?.size ?: 0} audio segments already present.")
                if (initialSegmentsCompleted < totalSegmentsToDownload) {
                    val avgSegmentSize = initialTotalDownloaded / initialSegmentsCompleted
                    val estimatedOverallTotal = avgSegmentSize * totalSegmentsToDownload
                    onProgress(Progress(initialTotalDownloaded, estimatedOverallTotal))
                }
            }

            // 3. Download remaining segments in parallel using coroutines
            val segmentDownloader = SegmentDownloader(httpClient, headers, controller)
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
                    val estimatedTotal = avgSegmentSize * totalSegmentsToDownload
                    onProgress(Progress(totalDownloaded, estimatedTotal))
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
                    val completed = hlsSegmentsCompleted.incrementAndGet()
                    val totalDownloaded = hlsTotalBytesDownloaded.addAndGet(downloadedBytes)

                    val avgSegmentSize = if (completed > 0) totalDownloaded / completed else 0
                    val estimatedTotal = avgSegmentSize * totalSegmentsToDownload
                    onProgress(Progress(totalDownloaded, estimatedTotal))
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
            val finalOutputFile = downloadDir.resolve("merged_output.mp4")

            // 4. Merge all segments using FFmpeg
            val mergeSession = DownloaderUtils.mergeHlsSegments(
                downloadDir,
                videoSegments,
                audioSegments,
                finalOutputFile.absolutePath,
                videoCodec,
                httpClient,
                headers.toHeaders(),
                onMergeProgress = { percentage ->
                    onMergeProgress(
                        Progress(finalDownloadedBytes * percentage / 100, finalDownloadedBytes),
                        task.apply {
                            this.lineInfo = "Merging segments... $percentage"
                            this.taskState = VideoTaskState.PREPARE
                        });
                }
            )
            if (!ReturnCode.isSuccess(mergeSession.returnCode)) {
                throw IOException("FFmpeg failed to merge segments. Return code: ${mergeSession.returnCode}. Logs: ${mergeSession.allLogsAsString}")
            }

            AppLogger.d("HLS: Merge completed successfully.")
            finalOutputFile
        }
    }
}
