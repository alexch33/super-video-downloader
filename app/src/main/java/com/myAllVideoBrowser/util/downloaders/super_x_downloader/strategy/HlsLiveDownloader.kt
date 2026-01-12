package com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy

import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.DownloaderUtils
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SegmentDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import com.myAllVideoBrowser.util.hls_parser.HlsPlaylistParser
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * Download strategy for HLS Live streams.
 *
 * This class continuously fetches the live playlist, downloads new segments as they appear,
 * and merges all downloaded segments into a single file when the download is stopped
 * (either by user action or by the stream ending).
 *
 * @param httpClient The OkHttpClient for network requests.
 * @param getMediaPlaylists A function to fetch and parse the latest version of the media playlists.
 * @param onMergeProgress A callback to update progress when merging begins.
 */
class HlsLiveDownloader(
    private val httpClient: OkHttpClient,
    private val getMediaPlaylists: suspend (url: String, headers: Map<String, String>) -> Pair<HlsPlaylistParser.MediaPlaylist?, HlsPlaylistParser.MediaPlaylist?>,
    private val onMergeProgress: (progress: Progress, task: VideoTaskItem) -> Unit,
    private val videoCodec: String?,
    private val mergeOnly: Boolean = false
) : ManifestDownloader {

    override suspend fun download(
        task: VideoTaskItem,
        headers: Map<String, String>,
        downloadDir: File,
        controller: FileBasedDownloadController,
        onProgress: (progress: Progress) -> Unit
    ): File {
        return withContext(Dispatchers.IO) {
            val downloadedSegmentUrls = mutableSetOf<String>()
            val allVideoSegments = mutableListOf<HlsPlaylistParser.MediaSegment>()
            val allAudioSegments = mutableListOf<HlsPlaylistParser.MediaSegment>()
            var isLiveStreamFinished = false
            var totalBytesDownloaded = 0L

            if (!mergeOnly) {
                var targetDuration = 10.0 // Default HLS target duration

                val segmentDownloader = SegmentDownloader(httpClient, headers, controller)

                AppLogger.d("HLS (Live): Starting download loop for task ${task.mId}")
                task.setIsLive(true) // Ensure task is marked as live

                // The main loop for live recording
                while (!isLiveStreamFinished && !controller.isInterrupted()) {

                    AppLogger.d("HLS (Live): Fetching latest playlist for task ${task.mId}...")

                    // 1. Fetch the latest version of the media playlists
                    val (currentVideoPlaylist, currentAudioPlaylist) = getMediaPlaylists(
                        task.url,
                        headers
                    )

                    targetDuration = (currentVideoPlaylist?.targetDuration?.toDouble()
                        ?: currentAudioPlaylist?.targetDuration?.toDouble() ?: targetDuration)

                    // 2. Identify new segments that haven't been downloaded yet
                    val newVideoSegments =
                        currentVideoPlaylist?.segments?.filter { downloadedSegmentUrls.add(it.url) }
                            ?: emptyList()
                    val newAudioSegments =
                        currentAudioPlaylist?.segments?.filter { downloadedSegmentUrls.add(it.url) }
                            ?: emptyList()

                    if (newVideoSegments.isNotEmpty() || newAudioSegments.isNotEmpty()) {
                        AppLogger.d("HLS (Live): Found ${newVideoSegments.size} new video and ${newAudioSegments.size} new audio segments.")

                        // Update total recorded duration for progress reporting
                        val newDuration =
                            (newVideoSegments.sumOf { it.duration } + newAudioSegments.sumOf { it.duration }).toLong()
                        task.accumulatedDuration += newDuration

                        allVideoSegments.addAll(newVideoSegments)
                        allAudioSegments.addAll(newAudioSegments)

                        // 3. Download the new segments sequentially
                        val downloadedBytes = downloadLiveSegments(
                            newVideoSegments,
                            newAudioSegments,
                            segmentDownloader,
                            allVideoSegments,
                            allAudioSegments,
                            downloadDir
                        )
                        totalBytesDownloaded += downloadedBytes
                        onProgress(
                            Progress(
                                totalBytesDownloaded,
                                0
                            )
                        ) // For live, total is unknown (0)

                    } else {
                        AppLogger.d("HLS (Live): No new segments found.")
                    }

                    // 4. Check if the livestream has ended
                    val isVideoFinished = currentVideoPlaylist?.isFinished ?: true
                    val isAudioFinished = currentAudioPlaylist?.isFinished ?: true
                    isLiveStreamFinished = isVideoFinished && isAudioFinished

                    // 5. Wait interruptibly before the next check
                    if (!isLiveStreamFinished) {
                        val waitTime = (targetDuration / 2 * 1000).toLong()
                        AppLogger.d("HLS (Live): Waiting for up to ${waitTime / 1000.0} seconds...")
                        // Use a cancellable delay that checks for interruptions
                        interruptibleDelay(waitTime, controller)
                    }
                }
            } else {
                AppLogger.d("HLS (Live): Starting in MERGE-ONLY mode.")
            }

            // Loop has exited. Now determine the reason.
            when {
                controller.isCancelRequested() -> throw CancellationException("Download was canceled.")
                controller.isPauseRequested() -> throw CancellationException("Download was paused.")
                controller.isStopAndSaveRequested() -> AppLogger.d("HLS (Live): Download stopped by user. Proceeding to merge.")
                isLiveStreamFinished -> AppLogger.d("HLS (Live): Stream finished naturally. Proceeding to merge.")
            }

            if (allVideoSegments.isEmpty() && allAudioSegments.isEmpty()) {
                throw IOException("No segments were downloaded, nothing to merge.")
            }

            // 6. Merge all downloaded segments
            onMergeProgress(Progress(totalBytesDownloaded, totalBytesDownloaded), task)
            val finalOutputFile = downloadDir.resolve("merged_output.mp4")
            val mergeSession = DownloaderUtils.mergeHlsSegments(
                hlsTmpDir = downloadDir,
                videoSegments = allVideoSegments,
                audioSegments = allAudioSegments,
                finalOutputPath = finalOutputFile.absolutePath,
                videoCodec = videoCodec,
                httpClient = httpClient
            )
            if (!ReturnCode.isSuccess(mergeSession.returnCode)) {
                throw IOException("FFmpeg failed to merge live stream segments. Return code: ${mergeSession.returnCode}")
            }

            finalOutputFile // Return the final merged file
        }
    }

    /**
     * Downloads new live segments sequentially. Live streams are sensitive to parallel downloads
     * from different time points, so sequential is safer.
     */
    private suspend fun downloadLiveSegments(
        videoSegments: List<HlsPlaylistParser.MediaSegment>,
        audioSegments: List<HlsPlaylistParser.MediaSegment>,
        segmentDownloader: SegmentDownloader,
        allVideoSegments: List<HlsPlaylistParser.MediaSegment>,
        allAudioSegments: List<HlsPlaylistParser.MediaSegment>,
        downloadDir: File
    ): Long {
        var bytesDownloaded: Long = 0
        val isVideoFmp4 = allVideoSegments.firstOrNull()
            ?.let { (it as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null } == true
        val isAudioFmp4 = allAudioSegments.firstOrNull()
            ?.let { (it as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null } == true
        val videoExt = if (isVideoFmp4) "m4s" else "ts"
        val audioExt = if (isAudioFmp4) "m4s" else "ts"

        for (segment in videoSegments) {
            val index = allVideoSegments.indexOf(segment)
            val outputFile = downloadDir.resolve("segment_${"%05d".format(index)}.$videoExt")
            bytesDownloaded += segmentDownloader.download(
                segment.url,
                outputFile,
                "HLS-Live-Video",
                index
            )
        }

        for (segment in audioSegments) {
            val index = allAudioSegments.indexOf(segment)
            val outputFile = downloadDir.resolve("audio_segment_${"%05d".format(index)}.$audioExt")
            bytesDownloaded += segmentDownloader.download(
                segment.url,
                outputFile,
                "HLS-Live-Audio",
                index
            )
        }
        return bytesDownloaded
    }

    /**
     * A version of `delay` that can be interrupted by controller flags.
     * It checks for interruptions every 250ms.
     */
    private suspend fun interruptibleDelay(
        durationMillis: Long,
        controller: FileBasedDownloadController
    ) {
        val endTime = System.currentTimeMillis() + durationMillis
        while (System.currentTimeMillis() < endTime) {
            if (controller.isInterrupted()) {
                AppLogger.d("HLS (Live): Action detected during wait. Breaking delay.")
                break
            }
            delay(250L) // Short, non-blocking delay
        }
    }
}
