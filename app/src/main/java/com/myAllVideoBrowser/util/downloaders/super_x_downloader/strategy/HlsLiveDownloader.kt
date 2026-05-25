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

/**
 * Download strategy for HLS Live streams.
 *
 * This class continuously fetches the live playlist, downloads new segments as they appear,
 * and merges all downloaded segments into a single file when the download is stopped.
 *
 * @param httpClient The OkHttpClient for network requests.
 * @param getMediaPlaylists A function to fetch and parse the latest version of the media playlists.
 * @param onMergeProgress A callback to update progress when merging begins.
 * @param videoCodec The video codec.
 * @param mergeOnly If true, only merge existing segments.
 * @param isAudioOnlyExtract If true, extract audio only during merge and skip video download if possible.
 */
class HlsLiveDownloader(
    private val httpClient: OkHttpClient,
    private val getMediaPlaylists: suspend (url: String, headers: Map<String, String>) -> Pair<HlsPlaylistParser.MediaPlaylist?, HlsPlaylistParser.MediaPlaylist?>,
    private val onMergeProgress: (progress: Progress, task: VideoTaskItem) -> Unit,
    private val videoCodec: String?,
    private val mergeOnly: Boolean = false,
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
            val allVideoSegments = mutableListOf<HlsPlaylistParser.MediaSegment>()
            val allAudioSegments = mutableListOf<HlsPlaylistParser.MediaSegment>()
            var totalBytesDownloaded = 0L
            var downloadException: Exception? = null
            
            val extension = if (isAudioOnlyExtract) "mp3" else "mp4"
            lateinit var finalOutputFile: File

            val progressCallback: (bytes: Long) -> Unit = { bytes ->
                totalBytesDownloaded += bytes
                onProgress(Progress(totalBytesDownloaded, 0))
            }

            try {
                if (!mergeOnly) {
                    var targetDuration = 10.0 // Default HLS target duration
                    val downloadedSegmentUrls = mutableSetOf<String>()
                    val segmentDownloader =
                        SegmentDownloader(httpClient, headers, controller, progressCallback)

                    AppLogger.d("HLS (Live): Starting download loop for task ${task.mId}")
                    task.setIsLive(true)

                    while (!controller.isInterrupted()) {
                        AppLogger.d("HLS (Live): Fetching latest playlist for task ${task.mId}...")
                        val (currentVideoPlaylist, currentAudioPlaylist) = getMediaPlaylists(
                            task.url,
                            headers
                        )

                        targetDuration = (currentVideoPlaylist?.targetDuration?.toDouble()
                            ?: currentAudioPlaylist?.targetDuration?.toDouble() ?: targetDuration)

                        // Optimization: If audio-only is requested and separate audio track exists, skip video download entirely.
                        val newVideoSegments = if (isAudioOnlyExtract && currentAudioPlaylist != null) {
                            emptyList()
                        } else {
                            currentVideoPlaylist?.segments?.filter { downloadedSegmentUrls.add(it.url) }
                                ?: emptyList()
                        }
                        
                        val newAudioSegments =
                            currentAudioPlaylist?.segments?.filter { downloadedSegmentUrls.add(it.url) }
                                ?: emptyList()

                        if (newVideoSegments.isNotEmpty() || newAudioSegments.isNotEmpty()) {
                            AppLogger.d("HLS (Live): Found ${newVideoSegments.size} new video and ${newAudioSegments.size} new audio segments.")
                            val newDuration = (newVideoSegments.sumOf { it.duration } + newAudioSegments.sumOf { it.duration }).toLong()
                            task.accumulatedDuration += newDuration
                            allVideoSegments.addAll(newVideoSegments)
                            allAudioSegments.addAll(newAudioSegments)

                            downloadLiveSegments(
                                newVideoSegments,
                                newAudioSegments,
                                segmentDownloader,
                                allVideoSegments,
                                allAudioSegments,
                                downloadDir
                            )
                        }

                        val isVideoFinished = currentVideoPlaylist?.isFinished ?: true
                        val isAudioFinished = currentAudioPlaylist?.isFinished ?: true
                        if (isVideoFinished && isAudioFinished) {
                            AppLogger.d("HLS (Live): Stream finished naturally. Proceeding to merge.")
                            break
                        }

                        val waitTime = (targetDuration / 2 * 1000).toLong()
                        interruptibleDelay(waitTime, controller)
                    }
                } else {
                    AppLogger.d("HLS (Live): Starting in MERGE-ONLY mode.")
                    val (videoPlaylist, audioPlaylist) = getMediaPlaylists(task.url, headers)
                    
                    if (isAudioOnlyExtract && audioPlaylist != null) {
                         allAudioSegments.addAll(audioPlaylist.segments)
                    } else {
                        allVideoSegments.addAll(videoPlaylist?.segments ?: emptyList())
                        allAudioSegments.addAll(audioPlaylist?.segments ?: emptyList())
                    }
                }

                when {
                    controller.isCancelRequested() -> throw CancellationException("Download was canceled.")
                    controller.isPauseRequested() -> throw CancellationException("Download was paused.")
                }

            } catch (e: Exception) {
                downloadException = e
                AppLogger.w("HLS (Live): Exception caught during download loop: ${e.message}. Attempting to save partial file.")
                e.printStackTrace()
            } finally {
                AppLogger.d("HLS (Live): Entering 'finally' block to attempt merge.")

                if (allVideoSegments.isEmpty() && allAudioSegments.isEmpty()) {
                    if (downloadException != null) throw downloadException
                    throw IOException("No segments were downloaded, nothing to merge.")
                }

                // Download keys before merging
                (allVideoSegments.firstOrNull() as? HlsPlaylistParser.UrlMediaSegment)?.encryptionKey?.let { key ->
                    val keyFile = downloadDir.resolve("video_encryption.key")
                    if (!keyFile.exists() || keyFile.length() == 0L) {
                        DownloaderUtils.downloadKey(httpClient, key.uri, keyFile, headers.toHeaders())
                    }
                }
                (allAudioSegments.firstOrNull() as? HlsPlaylistParser.UrlMediaSegment)?.encryptionKey?.let { key ->
                    val keyFile = downloadDir.resolve("audio_encryption.key")
                    if (!keyFile.exists() || keyFile.length() == 0L) {
                        DownloaderUtils.downloadKey(httpClient, key.uri, keyFile, headers.toHeaders())
                    }
                }

                AppLogger.d("HLS (Live): Proceeding to merge into $extension.")
                onMergeProgress(
                    Progress(totalBytesDownloaded, totalBytesDownloaded),
                    task.apply {
                        this.taskState = VideoTaskState.PREPARE
                        this.lineInfo = "Merging segments..."
                        this.setIsLive(true)
                    })
                
                finalOutputFile = downloadDir.resolve("merged_output.$extension")
                val mergeSession = DownloaderUtils.mergeHlsSegments(
                    hlsTmpDir = downloadDir,
                    videoSegments = allVideoSegments,
                    audioSegments = allAudioSegments,
                    finalOutputPath = finalOutputFile.absolutePath,
                    videoCodec = videoCodec,
                    isAudioOnlyExtract = isAudioOnlyExtract,
                    onMergeProgress = { percentage ->
                        onMergeProgress(
                            Progress(totalBytesDownloaded * percentage / 100, totalBytesDownloaded),
                            task.apply {
                                this.lineInfo = "Merging segments... $percentage%"
                                this.taskState = VideoTaskState.PREPARE
                                this.setIsLive(true)
                            });
                    }
                )

                if (!ReturnCode.isSuccess(mergeSession.returnCode)) {
                    val mergeError = IOException("FFmpeg failed to merge live stream segments.")
                    if (downloadException != null) mergeError.initCause(downloadException)
                    throw mergeError
                }
            }

            finalOutputFile
        }
    }

    private suspend fun downloadLiveSegments(
        videoSegments: List<HlsPlaylistParser.MediaSegment>,
        audioSegments: List<HlsPlaylistParser.MediaSegment>,
        segmentDownloader: SegmentDownloader,
        allVideoSegments: List<HlsPlaylistParser.MediaSegment>,
        allAudioSegments: List<HlsPlaylistParser.MediaSegment>,
        downloadDir: File
    ) {
        val isVideoFmp4 = allVideoSegments.firstOrNull()?.let { (it as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null } == true
        val isAudioFmp4 = allAudioSegments.firstOrNull()?.let { (it as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null } == true
        val videoExt = if (isVideoFmp4) "m4s" else "ts"
        val audioExt = if (isAudioFmp4) "m4s" else "ts"

        if (isVideoFmp4 && videoSegments.isNotEmpty()) {
            val initSegment = (videoSegments.first() as HlsPlaylistParser.UrlMediaSegment).initializationSegment!!
            val initFile = downloadDir.resolve("init_video.mp4")
            if (!initFile.exists() || initFile.length() == 0L) {
                segmentDownloader.download(initSegment.url, initFile, "HLS-Init", 0)
            }
        }
        if (isAudioFmp4 && audioSegments.isNotEmpty()) {
            val initSegment = (audioSegments.first() as HlsPlaylistParser.UrlMediaSegment).initializationSegment!!
            val initFile = downloadDir.resolve("init_audio.mp4")
            if (!initFile.exists() || initFile.length() == 0L) {
                segmentDownloader.download(initSegment.url, initFile, "HLS-Init", 1)
            }
        }

        for (segment in videoSegments) {
            val index = allVideoSegments.indexOf(segment)
            val outputFile = downloadDir.resolve("segment_${"%05d".format(index)}.$videoExt")
            segmentDownloader.download(segment.url, outputFile, "HLS-Live-Video", index)
        }

        for (segment in audioSegments) {
            val index = allAudioSegments.indexOf(segment)
            val outputFile = downloadDir.resolve("audio_segment_${"%05d".format(index)}.$audioExt")
            segmentDownloader.download(segment.url, outputFile, "HLS-Live-Audio", index)
        }
    }

    private suspend fun interruptibleDelay(durationMillis: Long, controller: FileBasedDownloadController) {
        val endTime = System.currentTimeMillis() + durationMillis
        while (System.currentTimeMillis() < endTime) {
            if (controller.isInterrupted()) break
            delay(250L)
        }
    }
}
