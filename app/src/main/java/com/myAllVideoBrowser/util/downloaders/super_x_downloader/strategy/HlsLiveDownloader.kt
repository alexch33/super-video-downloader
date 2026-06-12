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
            val videoDurations = mutableListOf<Double>()
            val audioDurations = mutableListOf<Double>()
            var firstVideoSegment: HlsPlaylistParser.UrlMediaSegment? = null
            var firstAudioSegment: HlsPlaylistParser.UrlMediaSegment? = null

            var totalBytesDownloaded = 0L
            var downloadException: Throwable? = null

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
                        val newVideoSegments =
                            if (isAudioOnlyExtract && currentAudioPlaylist != null) {
                                emptyList()
                            } else {
                                currentVideoPlaylist?.segments?.filter {
                                    downloadedSegmentUrls.add(
                                        it.url
                                    )
                                }
                                    ?: emptyList()
                            }

                        val newAudioSegments =
                            currentAudioPlaylist?.segments?.filter { downloadedSegmentUrls.add(it.url) }
                                ?: emptyList()

                        if (newVideoSegments.isNotEmpty() || newAudioSegments.isNotEmpty()) {
                            AppLogger.d("HLS (Live): Found ${newVideoSegments.size} new video and ${newAudioSegments.size} new audio segments.")
                            val newDuration =
                                (newVideoSegments.sumOf { it.duration } + newAudioSegments.sumOf { it.duration }).toLong()
                            task.accumulatedDuration += newDuration

                            if (firstVideoSegment == null) {
                                firstVideoSegment =
                                    newVideoSegments.firstOrNull() as? HlsPlaylistParser.UrlMediaSegment
                            }
                            if (firstAudioSegment == null) {
                                firstAudioSegment =
                                    newAudioSegments.firstOrNull() as? HlsPlaylistParser.UrlMediaSegment
                            }

                            val videoStartIndex = videoDurations.size
                            val audioStartIndex = audioDurations.size

                            videoDurations.addAll(newVideoSegments.map { it.duration })
                            audioDurations.addAll(newAudioSegments.map { it.duration })

                            downloadLiveSegments(
                                newVideoSegments,
                                newAudioSegments,
                                segmentDownloader,
                                videoStartIndex,
                                audioStartIndex,
                                downloadDir,
                                isVideoFmp4 = firstVideoSegment?.initializationSegment != null,
                                isAudioFmp4 = firstAudioSegment?.initializationSegment != null
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
                        firstAudioSegment =
                            audioPlaylist.segments.firstOrNull() as? HlsPlaylistParser.UrlMediaSegment
                        audioDurations.addAll(audioPlaylist.segments.map { it.duration })
                    } else {
                        firstVideoSegment =
                            videoPlaylist?.segments?.firstOrNull() as? HlsPlaylistParser.UrlMediaSegment
                        firstAudioSegment =
                            audioPlaylist?.segments?.firstOrNull() as? HlsPlaylistParser.UrlMediaSegment
                        videoDurations.addAll(videoPlaylist?.segments?.map { it.duration }
                            ?: emptyList())
                        audioDurations.addAll(audioPlaylist?.segments?.map { it.duration }
                            ?: emptyList())
                    }
                }

                when {
                    controller.isCancelRequested() -> throw CancellationException("Download was canceled.")
                    controller.isPauseRequested() -> throw CancellationException("Download was paused.")
                }

            } catch (e: Throwable) {
                downloadException = e
                AppLogger.w("HLS (Live): Exception caught during download loop: ${e.message}. Attempting to save partial file.")
                e.printStackTrace()
            } finally {
                AppLogger.d("HLS (Live): Entering 'finally' block to attempt merge.")

                if (videoDurations.isEmpty() && audioDurations.isEmpty()) {
                    if (downloadException != null) throw downloadException
                    throw IOException("No segments were downloaded, nothing to merge.")
                }

                // Download keys before merging
                firstVideoSegment?.encryptionKey?.let { key ->
                    val keyFile = downloadDir.resolve("video_encryption.key")
                    if (!keyFile.exists() || keyFile.length() == 0L) {
                        DownloaderUtils.downloadKey(
                            httpClient,
                            key.uri,
                            keyFile,
                            headers.toHeaders()
                        )
                    }
                }
                firstAudioSegment?.encryptionKey?.let { key ->
                    val keyFile = downloadDir.resolve("audio_encryption.key")
                    if (!keyFile.exists() || keyFile.length() == 0L) {
                        DownloaderUtils.downloadKey(
                            httpClient,
                            key.uri,
                            keyFile,
                            headers.toHeaders()
                        )
                    }
                }

                AppLogger.d("HLS (Live): Proceeding to merge into $extension.")
                val msg = "Merging segments..."
                onMergeProgress(
                    Progress(totalBytesDownloaded, totalBytesDownloaded, msg),
                    task.apply {
                        this.taskState = VideoTaskState.PREPARE
                        this.lineInfo = msg
                        this.setIsLive(true)
                    })

                finalOutputFile = downloadDir.resolve("merged_output.$extension")

                val videoSegmentsToMerge = reconstructSegments(videoDurations, firstVideoSegment)
                val audioSegmentsToMerge = reconstructSegments(audioDurations, firstAudioSegment)

                val mergeSession = DownloaderUtils.mergeHlsSegments(
                    hlsTmpDir = downloadDir,
                    videoSegments = videoSegmentsToMerge,
                    audioSegments = audioSegmentsToMerge,
                    finalOutputPath = finalOutputFile.absolutePath,
                    videoCodec = videoCodec,
                    isAudioOnlyExtract = isAudioOnlyExtract,
                    onMergeProgress = { percentage ->
                        val msg = "Merging segments... $percentage%"
                        onMergeProgress(
                            Progress(
                                totalBytesDownloaded * percentage / 100,
                                totalBytesDownloaded,
                                msg
                            ),
                            task.apply {
                                this.lineInfo = msg
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

    private fun reconstructSegments(
        durations: List<Double>,
        firstSegment: HlsPlaylistParser.UrlMediaSegment?
    ): List<HlsPlaylistParser.MediaSegment> {
        if (durations.isEmpty()) return emptyList()
        val list = ArrayList<HlsPlaylistParser.MediaSegment>(durations.size)
        durations.forEachIndexed { index, duration ->
            if (index == 0 && firstSegment != null) {
                list.add(firstSegment.copy(duration = duration))
            } else {
                list.add(
                    HlsPlaylistParser.UrlMediaSegment(
                        url = "",
                        duration = duration,
                        title = null,
                        discontinuity = false,
                        initializationSegment = null,
                        byteRange = null,
                        parts = emptyList(),
                        encryptionKey = null
                    )
                )
            }
        }
        return list
    }

    private suspend fun downloadLiveSegments(
        videoSegments: List<HlsPlaylistParser.MediaSegment>,
        audioSegments: List<HlsPlaylistParser.MediaSegment>,
        segmentDownloader: SegmentDownloader,
        videoStartIndex: Int,
        audioStartIndex: Int,
        downloadDir: File,
        isVideoFmp4: Boolean,
        isAudioFmp4: Boolean
    ) {
        val videoExt = if (isVideoFmp4) "m4s" else "ts"
        val audioExt = if (isAudioFmp4) "m4s" else "ts"

        if (isVideoFmp4 && videoSegments.isNotEmpty()) {
            val initSegment =
                (videoSegments.first() as HlsPlaylistParser.UrlMediaSegment).initializationSegment!!
            val initFile = downloadDir.resolve("init_video.mp4")
            if (!initFile.exists() || initFile.length() == 0L) {
                segmentDownloader.download(initSegment.url, initFile, "HLS-Init", 0)
            }
        }
        if (isAudioFmp4 && audioSegments.isNotEmpty()) {
            val initSegment =
                (audioSegments.first() as HlsPlaylistParser.UrlMediaSegment).initializationSegment!!
            val initFile = downloadDir.resolve("init_audio.mp4")
            if (!initFile.exists() || initFile.length() == 0L) {
                segmentDownloader.download(initSegment.url, initFile, "HLS-Init", 1)
            }
        }

        videoSegments.forEachIndexed { i, segment ->
            val index = videoStartIndex + i
            val outputFile = downloadDir.resolve("segment_${"%05d".format(index)}.$videoExt")
            segmentDownloader.download(segment.url, outputFile, "HLS-Live-Video", index)
        }

        audioSegments.forEachIndexed { i, segment ->
            val index = audioStartIndex + i
            val outputFile = downloadDir.resolve("audio_segment_${"%05d".format(index)}.$audioExt")
            segmentDownloader.download(segment.url, outputFile, "HLS-Live-Audio", index)
        }
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
}
