package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import android.content.Context
import android.text.format.Formatter
import android.util.Base64
import androidx.core.net.toUri
import androidx.work.WorkerParameters
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.custom_downloader.CustomFileDownloader
import com.myAllVideoBrowser.util.downloaders.custom_downloader.DownloadListener
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.GenericDownloadWorkerWrapper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import com.myAllVideoBrowser.util.hls_parser.HlsPlaylistParser
import com.myAllVideoBrowser.util.hls_parser.MpdPlaylistParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.filter
import kotlin.collections.first
import kotlin.collections.flatMap
import kotlin.collections.isNotEmpty
import kotlin.collections.sumOf
import kotlin.coroutines.resumeWithException

// TODO: REFACTORING
class SuperXDownloaderWorker(appContext: Context, workerParams: WorkerParameters) :
    GenericDownloadWorkerWrapper(appContext, workerParams) {

    private val ffmpegSession = AtomicReference<FFmpegSession?>()
    private var outputFileName: String? = null

    @Volatile
    private var lastProgressUpdateTime = 0L

    @Volatile
    private lateinit var taskId: String
    private val videoProgress = AtomicReference(Progress(0, 0))
    private val audioProgress = AtomicReference(Progress(0, 0))
    private val hlsSegmentsCompleted = AtomicInteger(0)
    private val hlsTotalBytesDownloaded = AtomicLong(0L)
    private val isPaused = AtomicBoolean(false)

    companion object {
        const val PAUSED_MESSAGE = "HLS_DOWNLOAD_PAUSED"
        private const val RETRY_COUNT = 3
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1000
        private const val PAUSE_FLAG_FILENAME = "stop"
        private const val CANCEL_FLAG_FILENAME = "cancel"
        private const val STOP_AND_SAVE_FLAG_FILENAME = "stop_and_save"
    }

    override fun handleAction(
        action: String, task: VideoTaskItem, headers: Map<String, String>, isFileRemove: Boolean
    ) {
        taskId = task.mId ?: inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)!!
        val downloadDir = fileUtil.tmpDir.resolve(taskId)

        when (action) {
            GenericDownloader.DownloaderActions.DOWNLOAD, GenericDownloader.DownloaderActions.RESUME -> {
                File(downloadDir, PAUSE_FLAG_FILENAME).delete()
                File(downloadDir, STOP_AND_SAVE_FLAG_FILENAME).delete()
                File(downloadDir, CANCEL_FLAG_FILENAME).delete()
                startDownload(task, headers)
            }

            GenericDownloader.DownloaderActions.CANCEL -> {
                isPaused.set(false)
                AppLogger.d("HLS: Cancel action received for task $taskId. Creating flag file.")
                cancelTask(task, isPause = false)
            }

            GenericDownloader.DownloaderActions.PAUSE -> {
                isPaused.set(true)
                cancelTask(task, isPause = true)
            }

            GenericDownloader.DownloaderActions.STOP_SAVE_ACTION -> {
                isPaused.set(false)
                AppLogger.d("HLS (Live): Stop and Save action received for task $taskId.")
                val downloadDir = fileUtil.tmpDir.resolve(taskId)
                try {
                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs()
                    }
                    // Create the flag file that the running worker will detect
                    File(downloadDir, STOP_AND_SAVE_FLAG_FILENAME).createNewFile()
                    getContinuation().resume(Result.success())
                } catch (e: IOException) {
                    finishWorkWithFailureTaskId(task)
                    AppLogger.e("FFmpeg: Failed to create stop_and_save flag file. $e")
                }
            }
        }
    }

    private fun startDownload(task: VideoTaskItem, headersRaw: Map<String, String>) {
        val taskDuration = inputData.getLong(GenericDownloader.Constants.DURATION, 0)
        AppLogger.d("Custom M3U8/MPD: Starting download for initial taskId: $taskId with duration: $taskDuration\ntask: $task")

        val headers = decodeCookieHeader(headersRaw)

        val isLive = inputData.getBoolean(GenericDownloader.Constants.IS_LIVE, false)

        // Check if the URL is a playlist
        if (isHlsPlaylist(task.url)) {
            AppLogger.d("HLS Playlist detected. Starting advanced parsing flow.")
            if (isLive) {
                startLiveHlsDownloadLoop(task, headers)
            } else {
                startHlsDownload(task, headers)
            }
        } else if (isMpdPlaylist(task.url)) {
            AppLogger.d("MPD Manifest detected. Starting advanced parsing flow.")
            startMpdDownload(task, headers)
        } else {
            val tsk = task.apply {
                this.taskState = VideoTaskState.ERROR
                this.errorMessage = "Unsupported manifest type."
            }
            finishWork(tsk)
        }
    }

    private fun startLiveHlsDownloadLoop(task: VideoTaskItem, headers: Map<String, String>) {
        AppLogger.d("HLS (Live): Starting download loop for task $taskId")
        val hlsTmpDir = fileUtil.tmpDir.resolve(taskId).apply { mkdirs() }
        // Ensure all state flags are clean before starting
        unPause(hlsTmpDir)
        File(hlsTmpDir, STOP_AND_SAVE_FLAG_FILENAME).delete()

        var totalDurationSeconds = task.accumulatedDuration

        CoroutineScope(Dispatchers.IO).launch {
            val downloadedSegmentUrls = mutableSetOf<String>()
            val allVideoSegments = mutableListOf<HlsPlaylistParser.MediaSegment>()
            val allAudioSegments = mutableListOf<HlsPlaylistParser.MediaSegment>()

            var isLiveStreamFinished = false
            var targetDuration = 10.0 // Default HLS target duration

            try {
                while (!isLiveStreamFinished && !File(
                        hlsTmpDir,
                        CANCEL_FLAG_FILENAME
                    ).exists() && !isPaused.get() && !File(
                        hlsTmpDir,
                        STOP_AND_SAVE_FLAG_FILENAME
                    ).exists()
                ) {
                    if (checkPauseState(hlsTmpDir)) {
                        throw IOException(PAUSED_MESSAGE)
                    }

                    AppLogger.d("HLS (Live): Fetching latest playlist for task $taskId...")

                    // 1. Fetch the latest version of the media playlists
                    val (currentVideoPlaylist, currentAudioPlaylist) = getMediaPlaylists(
                        task.url,
                        headers
                    )

                    targetDuration = (currentVideoPlaylist?.targetDuration?.toDouble()
                        ?: currentAudioPlaylist?.targetDuration?.toDouble() ?: 10.0)

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
                            (newVideoSegments.sumOf { it.duration.toDouble() } + newAudioSegments.sumOf { it.duration.toDouble() }).toLong()
                        totalDurationSeconds += newDuration
                        task.accumulatedDuration = totalDurationSeconds

                        allVideoSegments.addAll(newVideoSegments)
                        allAudioSegments.addAll(newAudioSegments)

                        // 3. Download the new segments
                        downloadLiveSegments(
                            newVideoSegments,
                            newAudioSegments,
                            headers,
                            hlsTmpDir,
                            task,
                            allVideoSegments,
                            allAudioSegments
                        )
                    } else {
                        AppLogger.d("HLS (Live): No new segments found.")
                    }

                    // 4. Check if the livestream has ended via the #EXT-X-ENDLIST tag
                    val isVideoFinished = currentVideoPlaylist?.isFinished ?: true
                    val isAudioFinished = currentAudioPlaylist?.isFinished ?: true
                    isLiveStreamFinished = isVideoFinished && isAudioFinished

                    // 5. Wait interruptibly before the next check
                    if (!isLiveStreamFinished) {
                        AppLogger.d("HLS (Live): Waiting for up to ${targetDuration / 2} seconds before next check...")
                        val waitUntil =
                            System.currentTimeMillis() + (targetDuration / 2 * 1000).toLong()
                        while (System.currentTimeMillis() < waitUntil) {
                            // Check for stop/pause/cancel flags every 250ms to allow for quick interruption
                            if (File(
                                    hlsTmpDir,
                                    STOP_AND_SAVE_FLAG_FILENAME
                                ).exists() || checkPauseState(hlsTmpDir) || File(
                                    hlsTmpDir,
                                    CANCEL_FLAG_FILENAME
                                ).exists()
                            ) {
                                AppLogger.d("HLS (Live): Action detected during wait. Breaking inner wait loop.")
                                break // Exit the waiting loop immediately
                            }
                            delay(250L) // Short, non-blocking delay
                        }
                    }
                }

                // Loop has exited. Now determine the reason and act accordingly.

                if (File(hlsTmpDir, CANCEL_FLAG_FILENAME).exists()) {
                    AppLogger.d("HLS (Live): Task $taskId was canceled.")
                    // Finish with a CANCELED state
                    finishWork(task.apply { taskState = VideoTaskState.CANCELED })
                    return@launch
                }

                if (isPaused.get()) {
                    // This will be caught by the outer catch block to set the PAUSE state
                    throw IOException(PAUSED_MESSAGE)
                }

                // Check which condition ended the loop to provide accurate logging.
                if (File(hlsTmpDir, STOP_AND_SAVE_FLAG_FILENAME).exists()) {
                    onProgress(
                        Progress(hlsTotalBytesDownloaded.get(), hlsTotalBytesDownloaded.get()),
                        task.also {
                            it.taskState = VideoTaskState.PREPARE
                        },
                        isSizeEstimated = true,
                        isLIve = true
                    )
                    AppLogger.d("HLS (Live): Download stopped by user. Proceeding to merge ${allVideoSegments.size} video and ${allAudioSegments.size} audio segments.")
                } else {
                    AppLogger.d("HLS (Live): Stream finished naturally. Proceeding to merge ${allVideoSegments.size} video and ${allAudioSegments.size} audio segments.")
                }

                // 6. Merge all downloaded segments into a final file
                val finalOutputFile = hlsTmpDir.resolve("merged_output.mp4").absolutePath
                val mergeSession =
                    mergeSegments(hlsTmpDir, allVideoSegments, allAudioSegments, finalOutputFile)

                if (ReturnCode.isSuccess(mergeSession.returnCode)) {
                    AppLogger.d("HLS (Live): Merge completed successfully.")
                    val completedTask = task.also {
                        it.mId = taskId
                        it.taskState = VideoTaskState.SUCCESS
                        it.filePath = finalOutputFile
                        it.totalSize = File(finalOutputFile).length()
                        it.downloadSize = it.totalSize
                        it.errorMessage = null
                    }
                    finishWork(completedTask)
                } else {
                    AppLogger.e("HLS (Live): Merge failed with return code: ${mergeSession.returnCode}. Logs: ${mergeSession.allLogsAsString}")
                    throw IOException("FFmpeg failed to merge live stream segments. Return code: ${mergeSession.returnCode}")
                }

            } catch (e: Exception) {
                // Gracefully handle pause, cancellation, and errors
                if (isPaused.get() || e is InterruptedException || e.message == PAUSED_MESSAGE || e.cause?.message == PAUSED_MESSAGE) {
                    AppLogger.d("HLS (Live): Task $taskId is pausing gracefully.")
                    finishWork(task.also {
                        it.mId = taskId
                        it.taskState = VideoTaskState.PAUSE
                        it.errorMessage = "Paused"
                    })
                } else if (e.message == "STOP_AND_SAVE_REQUESTED") {
                    AppLogger.d("HLS (Live): Exited segment download for Stop & Save. Will proceed to merge.")
                    // This exception is a control-flow signal, not an error.
                    // We let the code fall through to the merge logic outside the catch block.
                } else if (e.message == "DOWNLOAD_CANCELED") {
                    AppLogger.d("HLS (Live): Task $taskId was canceled via flag.")
                    finishWork(task.apply { taskState = VideoTaskState.CANCELED })
                } else {
                    AppLogger.e("HLS (Live): Download failed for task $taskId: ${e.message} ${e.printStackTrace()}")
                    finishWork(task.also {
                        it.mId = taskId
                        it.taskState = VideoTaskState.ERROR
                        it.errorMessage = "HLS live download failed: ${e.message}"
                    })
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun getMediaPlaylists(
        playlistUrl: String, headers: Map<String, String>
    ): Pair<HlsPlaylistParser.MediaPlaylist?, HlsPlaylistParser.MediaPlaylist?> {
        val client = proxyOkHttpClient.getProxyOkHttpClient()
        fun fetchAndParse(url: String): HlsPlaylistParser.HlsPlaylist {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (key, value) -> addHeader(key, value) }
            }.build()
            val response = client.newCall(request).execute()
            val content = response.body.string()
            if (!response.isSuccessful || content.isEmpty()) {
                throw IOException("Failed to download playlist at $url. HTTP ${response.code}")
            }
            return HlsPlaylistParser.parse(content, url)
        }

        val selectedFormatId = inputData.getString(GenericDownloader.Constants.SELECTED_FORMAT_ID)
            ?: throw IOException("No selected format ID was provided to the worker.")

        val initialPlaylist = fetchAndParse(playlistUrl)
        if (initialPlaylist !is HlsPlaylistParser.MasterPlaylist) {
            val mediaPlaylist = initialPlaylist as HlsPlaylistParser.MediaPlaylist
            return Pair(mediaPlaylist, null)
        }

        val selectedVideoVariant =
            initialPlaylist.variants.find { "hls-${it.height}p-${it.bandwidth}" == selectedFormatId }
        val selectedAudioRendition =
            initialPlaylist.alternateRenditions.find { it.type == HlsPlaylistParser.RenditionType.AUDIO && "hls-audio-${it.groupId}-${it.name}" == selectedFormatId }

        var videoPlaylist: HlsPlaylistParser.MediaPlaylist? = null
        var audioPlaylist: HlsPlaylistParser.MediaPlaylist? = null

        when {
            selectedVideoVariant != null -> {
                val videoMediaPlaylist = fetchAndParse(selectedVideoVariant.url)
                if (videoMediaPlaylist is HlsPlaylistParser.MediaPlaylist) {
                    videoPlaylist = videoMediaPlaylist
                } else {
                    throw IOException("Expected a media playlist from variant, but got another master playlist.")
                }

                val audioGroupId = selectedVideoVariant.audioGroupId
                if (audioGroupId != null) {
                    val audioRendition =
                        initialPlaylist.alternateRenditions.find { it.groupId == audioGroupId && it.type == HlsPlaylistParser.RenditionType.AUDIO && it.url != null }
                    if (audioRendition?.url != null) {
                        val audioMediaPlaylist = fetchAndParse(audioRendition.url)
                        if (audioMediaPlaylist is HlsPlaylistParser.MediaPlaylist) {
                            audioPlaylist = audioMediaPlaylist
                        }
                    }
                }
            }

            selectedAudioRendition?.url != null -> {
                val audioMediaPlaylist = fetchAndParse(selectedAudioRendition.url!!)
                if (audioMediaPlaylist is HlsPlaylistParser.MediaPlaylist) {
                    audioPlaylist = audioMediaPlaylist
                }
            }

            else -> throw IOException("The selected format ID '$selectedFormatId' could not be found in the HLS manifest.")
        }

        return Pair(videoPlaylist, audioPlaylist)
    }

    private fun downloadLiveSegments(
        videoSegments: List<HlsPlaylistParser.MediaSegment>,
        audioSegments: List<HlsPlaylistParser.MediaSegment>,
        headers: Map<String, String>,
        hlsTmpDir: File,
        task: VideoTaskItem,
        allVideoSegments: List<HlsPlaylistParser.MediaSegment>,
        allAudioSegments: List<HlsPlaylistParser.MediaSegment>
    ) {
        val threadCount = 1 // for live recording always use 1 thread

        val executor = Executors.newFixedThreadPool(threadCount)
        val allFutures = mutableListOf<Future<*>>()

        // Use the existing SegmentDownloader. The total segment count is now unknown, so we pass 0.
        val segmentDownloader = SegmentDownloader(headers, hlsTmpDir, 0)

        val isVideoFmp4 = allVideoSegments.firstOrNull()
            ?.let { (it as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null } == true
        val isAudioFmp4 = allAudioSegments.firstOrNull()
            ?.let { (it as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null } == true

        val videoExt = if (isVideoFmp4) "m4s" else "ts"
        val audioExt = if (isAudioFmp4) "m4s" else "ts"

        try {
            // Submit download tasks for new video segments
            videoSegments.forEach { segment ->
                // The index for the filename must be based on the *overall* list of segments
                val index = allVideoSegments.indexOf(segment)
                if (index == -1) {
                    AppLogger.w("HLS (Live): Could not find index for new video segment. Skipping.")
                    return@forEach
                }
                val future = executor.submit {
                    segmentDownloader.downloadHlsSegment(segment, index, task, "segment_", videoExt)
                }
                allFutures.add(future)
            }

            // Submit download tasks for new audio segments
            audioSegments.forEach { segment ->
                val index = allAudioSegments.indexOf(segment)
                if (index == -1) {
                    AppLogger.w("HLS (Live): Could not find index for new audio segment. Skipping.")
                    return@forEach
                }
                val future = executor.submit {
                    segmentDownloader.downloadHlsSegment(
                        segment,
                        index,
                        task,
                        "audio_segment_",
                        audioExt
                    )
                }
                allFutures.add(future)
            }

            executor.shutdown()
            while (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                if (checkPauseState(hlsTmpDir)) {
                    executor.shutdownNow()
                    throw IOException(PAUSED_MESSAGE)
                }
            }

            // Check if any segment failed to download
            allFutures.forEach { it.get() }

        } catch (e: Exception) {
            // Shut down immediately on failure or pause
            if (!executor.isShutdown) {
                executor.shutdownNow()
            }
            // Propagate the exception to be handled by the main loop's try-catch block
            throw e
        }
    }


    private fun startMpdDownload(task: VideoTaskItem, headers: Map<String, String>) {
        AppLogger.d("MPD: Starting download for task $taskId")
        val mpdTmpDir = fileUtil.tmpDir.resolve(taskId).apply { mkdirs() }
        unPause(mpdTmpDir)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Step 1: Parse the manifest to get the user-selected representations
                val (videoRep, audioRep) = getMpdRepresentations(task.url, headers)

                // Step 2: Determine the primary representation to check for download strategy
                val primaryRep = videoRep ?: audioRep

                if (primaryRep == null) {
                    throw IOException("No downloadable video or audio representation found for the selected format.")
                }

                // Step 3: Decide which download strategy to use based on the manifest type
                if (primaryRep.segments.isNotEmpty()) {
                    // --- STRATEGY A: Segment-based Download ---
                    // This works for both video and audio-only segment lists.
                    AppLogger.d("MPD: Detected manifest with segments. Starting multi-threaded segment download.")
                    downloadMpdBySegments(
                        task,
                        headers,
                        mpdTmpDir,
                        videoRep?.segments, // Pass video segments, will be null for audio-only
                        audioRep?.segments  // Pass audio segments
                    )
                } else if (primaryRep.baseUrls.isNotEmpty()) {
                    // --- STRATEGY B: BaseURL-based Download ---
                    // This works for both video and audio-only Base URLs.
                    AppLogger.d("MPD: Detected manifest with Base URLs. Starting file download.")
                    downloadMpdByBaseUrl(task, headers, mpdTmpDir)
                } else {
                    // This case should be rare, but it's good to handle it.
                    throw IOException("MPD manifest contains neither segments nor base URLs. Cannot download.")
                }
            } catch (e: Exception) {
                // The existing exception handling is fine and will catch failures from any strategy.
                if (e is InterruptedException || e.message == PAUSED_MESSAGE || e.cause?.message == PAUSED_MESSAGE) {
                    AppLogger.d("MPD: Task $taskId is pausing gracefully.")
                    val pausedTask = task.also {
                        it.mId = taskId
                        it.taskState = VideoTaskState.PAUSE
                        it.errorMessage = "Paused"
                    }
                    finishWork(pausedTask)
                } else {
                    AppLogger.e("MPD: Download failed for task $taskId: ${e.message} ${e.printStackTrace()}")
                    val errorTask = task.also {
                        it.mId = taskId
                        it.taskState = VideoTaskState.ERROR
                        it.errorMessage = "MPD download failed: ${e.message}"
                    }
                    finishWork(errorTask)
                }
            }
        }
    }

    /**
     * Downloads DASH content by downloading two separate files (video and audio) using CustomFileDownloader
     * and then merging them. This method is for manifests using SegmentBase.
     */
    private suspend fun downloadMpdByBaseUrl(
        task: VideoTaskItem, headers: Map<String, String>, mpdTmpDir: File
    ) {
        // Reset progress trackers at the start of a new download
        videoProgress.set(Progress(0, 0))
        audioProgress.set(Progress(0, 0))

        // 1. Get representations
        val (videoRep, audioRep) = getMpdRepresentations(task.url, headers)

        // 2. Define temporary file paths
        val videoTempFile = mpdTmpDir.resolve("video_stream")
        val audioTempFile = mpdTmpDir.resolve("audio_stream")
        val finalFile = File(task.filePath)

        AppLogger.d("MPD (BaseURL): Starting concurrent download for Video and Audio streams.")

        val videoUrls = videoRep?.baseUrls
        if (videoUrls == null || videoUrls.isEmpty()) {
            throw IOException("No BaseURLs found for video representation.")
        }

        coroutineScope<Unit> {
            // Launch video download asynchronously
            val videoJob = async {
                downloadFileWithCustomDownloader(
                    urls = videoUrls,
                    outputFile = videoTempFile,
                    headers = headers,
                    task = task,
                    streamType = "Video"
                )
            }

            // Launch audio download asynchronously (if it exists)
            val audioJob = audioRep?.let {
                if (it.baseUrls.isNotEmpty()) {
                    async {
                        downloadFileWithCustomDownloader(
                            urls = it.baseUrls,
                            outputFile = audioTempFile,
                            headers = headers,
                            task = task,
                            streamType = "Audio"
                        )
                    }
                } else null
            }

            // Await the completion of both jobs
            val jobs = listOfNotNull(videoJob, audioJob)

            while (jobs.any { it.isActive }) {
                if (checkPauseState(mpdTmpDir)) {
                    AppLogger.d("MPD (BaseURL): Pause detected. Cancelling download jobs.")
                    // Cancel all running jobs. This will trigger invokeOnCancellation.
                    jobs.forEach {
                        it.cancel(
                            kotlin.coroutines.cancellation.CancellationException(
                                PAUSED_MESSAGE
                            )
                        )
                    }
                    throw IOException(PAUSED_MESSAGE)
                }
                // Wait for a short period before checking again
                delay(500L)
            }

            jobs.awaitAll()
        }

        // Check for pause state after all downloads are attempted
        if (checkPauseState(mpdTmpDir)) {
            // Since CustomFileDownloader responds to stop(), this is a final check.
            throw IOException(PAUSED_MESSAGE)
        }

        // 4.5. Validate downloaded files before merging
        AppLogger.d("MPD (BaseURL): Validating downloaded stream integrity...")

        val videoProbeSession = FFprobeKit.getMediaInformation(videoTempFile.absolutePath)
        if (!ReturnCode.isSuccess(videoProbeSession.returnCode)) {
            throw IOException("Validation failed for video stream. It may be corrupt. FFprobe log: ${videoProbeSession.allLogsAsString}")
        }

        if (audioRep != null && audioTempFile.exists() && audioTempFile.length() > 0) {
            val audioProbeSession = FFprobeKit.getMediaInformation(audioTempFile.absolutePath)
            if (!ReturnCode.isSuccess(audioProbeSession.returnCode)) {
                throw IOException("Validation failed for audio stream. It may be corrupt. FFprobe log: ${audioProbeSession.allLogsAsString}")
            }
        }

        // 5. Merge the downloaded files using a fast FFmpeg command
        AppLogger.d("MPD (BaseURL): All stream downloads complete. Merging files...")
        val ffmpegCommand = mutableListOf("-y").apply {
            add("-i"); add(videoTempFile.absolutePath)
            if (audioRep != null && audioTempFile.exists() && audioTempFile.length() > 0) {
                add("-i"); add(audioTempFile.absolutePath)
            }
            add("-c"); add("copy") // Re-mux, don't re-encode (very fast)
            add(finalFile.absolutePath)
        }

        val commandString = ffmpegCommand.joinToString(" ")
        AppLogger.d("Executing FFmpeg command: $commandString")
        val session = FFmpegKit.execute(commandString)

        // 6. Cleanup and finalize
        videoTempFile.delete()
        audioTempFile.delete()

        if (ReturnCode.isSuccess(session.returnCode)) {
            AppLogger.d("MPD (BaseURL): Merge successful. Final file at ${finalFile.absolutePath}")
            val finalTask = task.also {
                it.taskState = VideoTaskState.SUCCESS
                it.totalSize = File(finalFile.absolutePath).length()
                it.downloadSize = it.totalSize
            }
            finishWork(finalTask)
        } else {
            throw IOException("FFmpeg failed to merge BaseURL streams. Log: ${session.allLogsAsString}")
        }
    }

    /**
     * Wraps the callback-based CustomFileDownloader in a pausable, cancellable coroutine.
     * It also includes the failover logic for multiple BaseURLs.
     */
    private suspend fun downloadFileWithCustomDownloader(
        urls: List<String>,
        outputFile: File,
        headers: Map<String, String>,
        task: VideoTaskItem,
        streamType: String // "Video" or "Audio" for logging
    ) = suspendCancellableCoroutine { continuation ->
        var lastException: Throwable? = null
        var attempt = 0

        fun tryDownload(url: String) {
            AppLogger.d("MPD ($streamType): Attempting download from $url")

            // Prepare the listener for this attempt
            val listener = object : DownloadListener {
                override fun onSuccess() {
                    AppLogger.d("MPD ($streamType): Download successful from $url")
                    if (continuation.isActive) {
                        continuation.resume(Unit) // Resume the coroutine successfully
                    }
                }

                override fun onFailure(e: Throwable) {
                    AppLogger.w("MPD ($streamType): Download failed from $url. Error: ${e.message}")
                    lastException = e

                    // Check for pause/cancel state first
                    if (e.message == CustomFileDownloader.STOPPED || e.message == CustomFileDownloader.CANCELED) {
                        if (continuation.isActive) {
                            continuation.cancel(
                                kotlin.coroutines.cancellation.CancellationException(
                                    e.message
                                )
                            )
                        }
                        return // Don't retry if it was a user action
                    }

                    // If there are more URLs to try, attempt the next one
                    if (++attempt < urls.size) {
                        tryDownload(urls[attempt])
                    } else {
                        // All URLs have failed, fail the coroutine
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IOException(
                                    "All download attempts failed for $streamType stream.",
                                    lastException
                                )
                            )
                        }
                    }
                }

                override fun onProgressUpdate(downloadedBytes: Long, totalBytes: Long) {
                    // 1. Update the specific progress tracker for the current stream
                    val currentProgress = Progress(downloadedBytes, totalBytes)
                    if (streamType == "Video") {
                        videoProgress.set(currentProgress)
                    } else {
                        audioProgress.set(currentProgress)
                    }

                    // 2. Combine video and audio progress
                    val combinedDownloaded =
                        videoProgress.get().currentBytes + audioProgress.get().currentBytes
                    val combinedTotal =
                        videoProgress.get().totalBytes + audioProgress.get().totalBytes

                    // Ensure total is not zero to avoid division errors
                    if (combinedTotal == 0L) return

                    // 3. Throttle updates to avoid spamming the system
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastProgressUpdateTime > PROGRESS_UPDATE_INTERVAL_MS) {
                        lastProgressUpdateTime = currentTime

                        // Create a task item for the notification
                        val progressTask = task.apply {
                            this.downloadSize = combinedDownloaded
                            this.totalSize = combinedTotal
                            this.percent = getPercentFromBytes(combinedDownloaded, combinedTotal)
                            this.taskState = VideoTaskState.DOWNLOADING
                            this.lineInfo = "Downloading streams..."
                        }

                        // 4. Show notification and save progress to DB
                        val notificationData =
                            notificationsHelper.createNotificationBuilder(progressTask)
                        showLongRunningNotificationAsync(
                            notificationData.first, notificationData.second
                        )
                        saveProgress(
                            taskId = task.mId,
                            progress = Progress(combinedDownloaded, combinedTotal),
                            downloadStatus = VideoTaskState.DOWNLOADING
                        )
                    }
                }

                override fun onChunkProgressUpdate(
                    downloadedBytes: Long, allBytesChunk: Long, chunkIndex: Int
                ) {
                }

                override fun onChunkFailure(e: Throwable, index: CustomFileDownloader.Chunk) {}
            }

            val threadCount = sharedPrefHelper.getRegularDownloaderThreadCount()
            val okHttpClient = proxyOkHttpClient.getProxyOkHttpClient()
            val downloader = CustomFileDownloader(
                URL(url),
                outputFile,
                threadCount,
                headers,
                okHttpClient,
                listener,
                isForceStreamDownloadMode = false
            )
            downloader.download()
        }

        // Start the download process with the first URL
        tryDownload(urls[0])
    }

    /**
     * Downloads DASH content by fetching all individual segments in parallel.
     * This is the original logic, now refactored into its own method.
     */
    private fun downloadMpdBySegments(
        task: VideoTaskItem,
        headers: Map<String, String>,
        mpdTmpDir: File,
        videoSegments: List<MpdPlaylistParser.Segment>?,
        audioSegments: List<MpdPlaylistParser.Segment>?
    ) {
        val threadCount = sharedPrefHelper.getM3u8DownloaderThreadCount()
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            hlsSegmentsCompleted.set(0)
            hlsTotalBytesDownloaded.set(0)

            AppLogger.d("MPD (Segments): Found ${videoSegments?.size ?: 0} video and ${audioSegments?.size ?: 0} audio segments.")

            val totalSegmentsToDownload = (videoSegments?.size ?: 0) + (audioSegments?.size ?: 0)
            if (totalSegmentsToDownload == 0) {
                throw IOException("No segments found to download for either video or audio.")
            }
            val segmentDownloader = SegmentDownloader(headers, mpdTmpDir, totalSegmentsToDownload)

            // 2. Correctly identify all previously downloaded segments for BOTH streams
            val alreadyDownloadedVideo = videoSegments?.filter { segment ->
                val segmentFile =
                    mpdTmpDir.resolve("segment_${"%05d".format(videoSegments.indexOf(segment))}.m4s")
                segmentFile.exists() && segmentFile.length() > 0
            } ?: emptyList()

            val alreadyDownloadedAudio = audioSegments?.filter { segment ->
                val segmentFile =
                    mpdTmpDir.resolve("audio_segment_${"%05d".format(audioSegments.indexOf(segment))}.m4s")
                segmentFile.exists() && segmentFile.length() > 0
            } ?: emptyList()

            val initialVideoSize = alreadyDownloadedVideo.sumOf {
                mpdTmpDir.resolve(
                    "segment_${
                        "%05d".format(videoSegments!!.indexOf(it))
                    }.m4s"
                ).length()
            }
            val initialAudioSize = alreadyDownloadedAudio.sumOf {
                mpdTmpDir.resolve(
                    "audio_segment_${
                        "%05d".format(audioSegments!!.indexOf(it))
                    }.m4s"
                ).length()
            }
            val initialTotalDownloaded = initialVideoSize + initialAudioSize
            val initialSegmentsCompleted = alreadyDownloadedVideo.size + alreadyDownloadedAudio.size

            if (initialSegmentsCompleted > 0) {
                hlsSegmentsCompleted.set(initialSegmentsCompleted)
                hlsTotalBytesDownloaded.set(initialTotalDownloaded)

                val avgSegmentSize = initialTotalDownloaded / initialSegmentsCompleted
                val estimatedOverallTotal = avgSegmentSize * totalSegmentsToDownload

                val initialProgress = Progress(initialTotalDownloaded, estimatedOverallTotal)
                onProgress(initialProgress, task, isSizeEstimated = true)
                AppLogger.d("MPD Resumed: ${alreadyDownloadedVideo.size}/${videoSegments?.size ?: 0} video and ${alreadyDownloadedAudio.size}/${audioSegments?.size ?: 0} audio segments already present.")
            }

            // 3. Submit download tasks for segments that are NOT already downloaded
            val allFutures = mutableListOf<Future<*>>()

            val videoSegmentsToDownload =
                videoSegments?.filterNot { alreadyDownloadedVideo.contains(it) }
            videoSegmentsToDownload?.forEach { segment ->
                val index = videoSegments.indexOf(segment) // Get the original index
                allFutures.add(executor.submit {
                    segmentDownloader.downloadMpdSegment(
                        segment, index, task, "segment_"
                    )
                })
            }

            val audioSegmentsToDownload =
                audioSegments?.filterNot { alreadyDownloadedAudio.contains(it) }
            audioSegmentsToDownload?.forEach { segment ->
                val index = audioSegments.indexOf(segment) // Get the original index
                allFutures.add(executor.submit {
                    segmentDownloader.downloadMpdSegment(
                        segment, index, task, "audio_segment_"
                    )
                })
            }

            // Wait for completion
            executor.shutdown()
            while (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                if (checkPauseState(mpdTmpDir)) {
                    executor.shutdownNow()
                    throw IOException(PAUSED_MESSAGE)
                }
            }
            allFutures.forEach { it.get() } // Surface exceptions

            // Merge the segments
            val finalOutputFile = File(task.filePath).absolutePath
            val mergeSession =
                mergeMpdSegments(mpdTmpDir, videoSegments, audioSegments, finalOutputFile)

            if (ReturnCode.isSuccess(mergeSession.returnCode)) {
                AppLogger.d("MPD (Segments): Merge successful.")
                val completedTask = task.also {
                    it.taskState = VideoTaskState.SUCCESS
                    it.totalSize = File(finalOutputFile).length()
                    it.downloadSize = it.totalSize
                }
                finishWork(completedTask)
            } else {
                throw IOException("FFmpeg failed to merge MPD segments. Log: ${mergeSession.allLogsAsString}")
            }
        } finally {
            if (!executor.isShutdown) {
                executor.shutdownNow()
            }
        }
    }

    @Throws(IOException::class)
    private fun getMpdRepresentations(
        manifestUrl: String, headers: Map<String, String>
    ): Pair<MpdPlaylistParser.MpdRepresentation?, MpdPlaylistParser.MpdRepresentation?> {
        val client = proxyOkHttpClient.getProxyOkHttpClient()
        val request = Request.Builder().url(manifestUrl).apply {
            headers.forEach { (key, value) -> addHeader(key, value) }
        }.build()
        val response = client.newCall(request).execute()
        val content = response.body.string()
        if (!response.isSuccessful || content.isEmpty()) {
            throw IOException("Failed to download MPD manifest at $manifestUrl. HTTP ${response.code}")
        }
        val manifest = MpdPlaylistParser.parse(content, manifestUrl)

        val videoRepresentations =
            manifest.periods.first().adaptationSets.filter { it.mimeType?.startsWith("video/") == true }
                .flatMap { it.representations }

        val audioRepresentations =
            manifest.periods.first().adaptationSets.filter { it.mimeType?.startsWith("audio/") == true }
                .flatMap { it.representations }

        // 1. Get the selected format ID from the worker's input data.
        val selectedFormatId = inputData.getString(GenericDownloader.Constants.SELECTED_FORMAT_ID)
        AppLogger.d("MPD: User selected format ID: $selectedFormatId")

        if (selectedFormatId == null) {
            throw IOException("No selected format ID was provided to the worker.")
        }

        // 2. Find the selected video/audio representation based on the ID.
        val selectedVideoRep = videoRepresentations.find {
            // Construct the ID on the fly to match the detector's logic for video
            "mpd-${it.height}p-${it.bandwidth}" == selectedFormatId || "hls-${it.height}p-${it.bandwidth}" == selectedFormatId
        }

        val selectedAudioRep = audioRepresentations.find {
            // Construct the ID on the fly to match the detector's logic for audio
            "mpd-audio-${it.bandwidth}" == selectedFormatId || "hls-audio-${it.bandwidth}" == selectedFormatId
        }

        // 3. Determine the final video and audio representations to use.
        var finalVideoRep: MpdPlaylistParser.MpdRepresentation?
        var finalAudioRep: MpdPlaylistParser.MpdRepresentation?

        when {
            selectedVideoRep != null -> {
                // SCENARIO 1: A video format was selected.
                // Use the selected video and find the best corresponding audio.
                finalVideoRep = selectedVideoRep
                finalAudioRep =
                    audioRepresentations.maxByOrNull { it.bandwidth } // Always take the best audio
                AppLogger.d("MPD: Matched VIDEO format. Will use ${finalVideoRep.height}p and find best audio.")
            }

            selectedAudioRep != null -> {
                // SCENARIO 2: An audio format was selected.
                // We will only download the audio stream. Video will be null.
                finalVideoRep = null // Explicitly set video to null
                finalAudioRep = selectedAudioRep
                AppLogger.d("MPD: Matched AUDIO format. Preparing audio-only download.")
            }

            else -> {
                // SCENARIO 3: No match was found.
                // The provided format ID did not match any available representations.
                throw IOException("The selected format ID '$selectedFormatId' could not be found in the manifest.")
            }
        }

        AppLogger.d("MPD: Final Selected Video: ${finalVideoRep?.let { "${it.width}x${it.height}" } ?: "None"}")
        AppLogger.d("MPD: Final Selected Audio: ${finalAudioRep?.codecs ?: "None"}")

        return Pair(finalVideoRep, finalAudioRep)
    }

    private fun mergeMpdSegments(
        mpdTmpDir: File,
        videoSegments: List<MpdPlaylistParser.Segment>?,
        audioSegments: List<MpdPlaylistParser.Segment>?,
        finalOutputPath: String,
    ): FFmpegSession {
        AppLogger.d("MPD: Starting merge. ${videoSegments?.size ?: 0} video, ${audioSegments?.size ?: 0} audio.")

        val arguments = mutableListOf<String>()

        // The input for MPD is handled differently. We create a text file listing all
        // the segment files for FFmpeg's concat demuxer to read.
        // This is done BEFORE building the final command.
        if (!videoSegments.isNullOrEmpty()) {
            val videoConcatFile = mpdTmpDir.resolve("ffmpeg_video_concat.txt")
            // Create a playlist for the downloaded video segments
            videoConcatFile.writeText(
                videoSegments.indices.joinToString("\n") { index ->
                    "file 'segment_${"%05d".format(index)}.m4s'"
                })
            // Add the video playlist as an input file
            arguments.apply {
                add("-f"); add("concat"); add("-safe"); add("0")
                add("-i"); add(videoConcatFile.absolutePath)
            }
        }

        if (!audioSegments.isNullOrEmpty()) {
            val audioConcatFile = mpdTmpDir.resolve("ffmpeg_audio_concat.txt")
            // Create a playlist for the downloaded audio segments
            audioConcatFile.writeText(
                audioSegments.indices.joinToString("\n") { index ->
                    "file 'audio_segment_${"%05d".format(index)}.m4s'"
                })
            // Add the audio playlist as an input file
            arguments.apply {
                add("-f"); add("concat"); add("-safe"); add("0")
                add("-i"); add(audioConcatFile.absolutePath)
            }
        }

        if (arguments.isEmpty()) {
            throw IOException("Cannot merge segments: No video or audio segments were provided.")
        }

        arguments.apply {
            // Determine input mapping based on what was provided.
            // If we have both video and audio, the video will be input 0 and audio will be input 1.
            val hasVideo = !videoSegments.isNullOrEmpty()
            val hasAudio = !audioSegments.isNullOrEmpty()

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

            val videoCodec = inputData.getString(GenericDownloader.Constants.VIDEO_CODEC) ?: ""

            if (hasVideo && (videoCodec.startsWith("hvc1") || videoCodec.startsWith("dvh1"))) {
                AppLogger.d("MPD: Incompatible video codec '$videoCodec' detected. Re-encoding to H.264.")

                // Re-encode video to H.264 for maximum compatibility
                add("-c:v"); add("libx264")
                add("-preset"); add("veryfast")
                add("-crf"); add("23")
                add("-pix_fmt"); add("yuv420p")

                // Copy audio if it exists
                if (hasAudio) add("-c:a"); add("copy")

            } else {
                AppLogger.d("MPD: Compatible video codec '$videoCodec' detected. Copying stream(s) directly.")
                // If the codec is compatible (e.g., avc1), or if this is an audio-only download,
                // just copy everything.
                add("-c"); add("copy")
            }

            // Bitstream filter is useful for AAC audio, safe to include.
            add("-bsf:a"); add("aac_adtstoasc")
            // Move metadata to the start of the file for fast playback on mobile.
            add("-movflags"); add("+faststart")

            add("-y"); add(finalOutputPath) // Overwrite output file
        }

        AppLogger.d("FFmpeg: Executing MPD merge with arguments: $arguments")

        return FFmpegKit.executeWithArguments(arguments.toTypedArray())
    }

    private fun startHlsDownload(task: VideoTaskItem, headers: Map<String, String>) {
        AppLogger.d("HLS: Starting download for task $taskId")
        val hlsTmpDir = fileUtil.tmpDir.resolve(taskId).apply { mkdirs() }

        unPause(hlsTmpDir)

        val threadCount = sharedPrefHelper.getM3u8DownloaderThreadCount()
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            hlsSegmentsCompleted.set(0)
            hlsTotalBytesDownloaded.set(0)

            // 1. Get the list of media segments
            val (videoSegments, audioSegments) = getMediaSegments(task.url, headers)

            if (videoSegments.isNullOrEmpty() && audioSegments.isNullOrEmpty()) {
                throw IOException("No media segments found in HLS playlist for the selected format.")
            }
            AppLogger.d("HLS: Found ${videoSegments?.size ?: 0} video and ${audioSegments?.size ?: 0} audio segments.")

            val isVideoFmp4 = !videoSegments.isNullOrEmpty() &&
                    (videoSegments.first() as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null
            val isAudioFmp4 = !audioSegments.isNullOrEmpty() &&
                    (audioSegments.first() as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null

            val videoExt = if (isVideoFmp4) "m4s" else "ts"
            val audioExt = if (isAudioFmp4) "m4s" else "ts"

            val totalSegmentsToDownload = (videoSegments?.size ?: 0) + (audioSegments?.size ?: 0)
            val segmentDownloader = SegmentDownloader(headers, hlsTmpDir, totalSegmentsToDownload)

            // 2. Handle initial progress safely, using the correct extension
            val alreadyDownloadedVideo = videoSegments?.filter { segment ->
                val segmentFile =
                    hlsTmpDir.resolve("segment_${"%05d".format(videoSegments.indexOf(segment))}.$videoExt")
                segmentFile.exists() && segmentFile.length() > 0
            } ?: emptyList()

            val alreadyDownloadedAudio = audioSegments?.filter { segment ->
                val segmentFile =
                    hlsTmpDir.resolve("audio_segment_${"%05d".format(audioSegments.indexOf(segment))}.$audioExt")
                segmentFile.exists() && segmentFile.length() > 0
            } ?: emptyList()

            val initialVideoSize = alreadyDownloadedVideo.sumOf {
                hlsTmpDir.resolve(
                    "segment_${"%05d".format(videoSegments!!.indexOf(it))}.$videoExt"
                ).length()
            }
            val initialAudioSize = alreadyDownloadedAudio.sumOf {
                hlsTmpDir.resolve(
                    "audio_segment_${"%05d".format(audioSegments!!.indexOf(it))}.$audioExt"
                ).length()
            }
            val initialTotalDownloaded = initialVideoSize + initialAudioSize
            val initialSegmentsCompleted = alreadyDownloadedVideo.size + alreadyDownloadedAudio.size

            if (initialSegmentsCompleted > 0) {
                hlsSegmentsCompleted.set(initialSegmentsCompleted)
                hlsTotalBytesDownloaded.set(initialTotalDownloaded)

                if (initialSegmentsCompleted < totalSegmentsToDownload) {
                    val avgSegmentSize = initialTotalDownloaded / initialSegmentsCompleted
                    val estimatedOverallTotal = avgSegmentSize * totalSegmentsToDownload
                    val initialProgress = Progress(initialTotalDownloaded, estimatedOverallTotal)
                    onProgress(initialProgress, task, isSizeEstimated = true)
                }
                AppLogger.d("HLS Resumed: ${alreadyDownloadedVideo.size}/${videoSegments?.size ?: 0} video and ${alreadyDownloadedAudio.size}/${audioSegments?.size ?: 0} audio segments already present.")
            }

            // 3. Submit download tasks safely
            val allFutures = mutableListOf<Future<*>>()

            val videoSegmentsToDownload =
                videoSegments?.filterNot { alreadyDownloadedVideo.contains(it) }
            videoSegmentsToDownload?.forEach { segment ->
                val index = videoSegments.indexOf(segment)
                val future = executor.submit {
                    segmentDownloader.downloadHlsSegment(
                        segment, index, task, "segment_", videoExt
                    )
                }
                allFutures.add(future)
            }

            val audioSegmentsToDownload =
                audioSegments?.filterNot { alreadyDownloadedAudio.contains(it) }
            audioSegmentsToDownload?.forEach { segment ->
                val index = audioSegments.indexOf(segment)
                val future = executor.submit {
                    segmentDownloader.downloadHlsSegment(
                        segment, index, task, "audio_segment_", audioExt
                    )
                }
                allFutures.add(future)
            }

            executor.shutdown()
            while (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                if (checkPauseState(hlsTmpDir)) {
                    AppLogger.d("HLS: Pause detected. Shutting down segment downloads.")
                    executor.shutdownNow()
                    throw IOException(PAUSED_MESSAGE)
                }
            }

            allFutures.forEach { it.get() } // Surface exceptions from any failed segment downloads

            AppLogger.d("HLS: All segments downloaded successfully for task $taskId.")
            val finalOutputFile = hlsTmpDir.resolve("merged_output.mp4").absolutePath

            // 4. Call the mergeSegments function (which is now correct)
            val mergeSession =
                mergeSegments(hlsTmpDir, videoSegments, audioSegments, finalOutputFile)
            val returnCode = mergeSession.returnCode

            if (ReturnCode.isSuccess(returnCode)) {
                AppLogger.d("HLS: Merge completed successfully.")
                val completedTask = task.also {
                    it.mId = taskId
                    it.taskState = VideoTaskState.SUCCESS
                    it.filePath = finalOutputFile
                    it.totalSize = File(finalOutputFile).length()
                    it.downloadSize = it.totalSize
                    it.errorMessage = null
                }
                finishWork(completedTask)
            } else {
                AppLogger.e("HLS: Merge failed with return code: $returnCode. Logs: ${mergeSession.allLogsAsString}")
                throw IOException("FFmpeg failed to merge segments. Return code: $returnCode")
            }
        } catch (e: Exception) {
            if (isPaused.get() || e is InterruptedException || e.message == PAUSED_MESSAGE || e.cause?.message == PAUSED_MESSAGE) {
                AppLogger.d("HLS: Task $taskId is pausing gracefully.")
                finishWork(task.also {
                    it.mId = taskId
                    it.taskState = VideoTaskState.PAUSE
                    it.errorMessage = "Paused"
                })
            } else {
                AppLogger.e(
                    "HLS: Download failed for task $taskId: ${e.message} ${e.printStackTrace()}",
                )
                executor.shutdownNow()
                finishWork(task.also {
                    it.mId = taskId
                    it.taskState = VideoTaskState.ERROR
                    it.errorMessage = "HLS download failed: ${e.message}"
                })
            }
        }
    }

    private fun mergeSegments(
        hlsTmpDir: File,
        videoSegments: List<HlsPlaylistParser.MediaSegment>?,
        audioSegments: List<HlsPlaylistParser.MediaSegment>?,
        finalOutputPath: String
    ): FFmpegSession {
        val arguments = mutableListOf<String>()

        val isVideoFmp4 = !videoSegments.isNullOrEmpty() &&
                (videoSegments.first() as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null
        val isAudioFmp4 = !audioSegments.isNullOrEmpty() &&
                (audioSegments.first() as? HlsPlaylistParser.UrlMediaSegment)?.initializationSegment != null

        AppLogger.d("HLS: Starting merge. Is Video fMP4: $isVideoFmp4, Is Audio fMP4: $isAudioFmp4")

        // --- Video Input ---
        if (!videoSegments.isNullOrEmpty()) {
            if (isVideoFmp4) {
                // Manually concatenate fMP4 video segments
                val concatenatedVideoFile = createConcatenatedFmp4File(
                    hlsTmpDir,
                    videoSegments,
                    "video" // file prefix
                )
                arguments.add("-i")
                arguments.add(concatenatedVideoFile.absolutePath)
            } else {
                // Create M3U8/TXT playlist for TS video segments
                val videoPlaylistFile =
                    createTsPlaylistFile(hlsTmpDir, videoSegments, "segment_", "video.m3u8")
                addPlaylistArguments(arguments, videoPlaylistFile)
            }
        }

        // --- Audio Input ---
        if (!audioSegments.isNullOrEmpty()) {
            if (isAudioFmp4) {
                // Manually concatenate fMP4 audio segments
                val concatenatedAudioFile = createConcatenatedFmp4File(
                    hlsTmpDir,
                    audioSegments,
                    "audio" // file prefix
                )
                arguments.add("-i")
                arguments.add(concatenatedAudioFile.absolutePath)
            } else {
                // Create M3U8/TXT playlist for TS audio segments
                val audioPlaylistFile =
                    createTsPlaylistFile(hlsTmpDir, audioSegments, "audio_segment_", "audio.m3u8")
                addPlaylistArguments(arguments, audioPlaylistFile)
            }
        }

        if (videoSegments.isNullOrEmpty() && audioSegments.isNullOrEmpty()) {
            throw IOException("Cannot merge segments: No video or audio segments were provided.")
        }

        // --- Final FFmpeg Arguments ---
        arguments.apply {
            val hasVideo = !videoSegments.isNullOrEmpty()
            val hasAudio = !audioSegments.isNullOrEmpty()

            // Map streams: take video from first input (0), audio from second (1) if it exists
            when {
                // Case 1: Separate video and audio streams exist. Map them individually.
                hasVideo && hasAudio -> {
                    AppLogger.d("Mapping separate video (0:v) and audio (1:a) streams.")
                    add("-map"); add("0:v?")
                    add("-map"); add("1:a?")
                }
                // Case 2: We only have one input, which came from videoSegments.
                // This is the path for MUXED streams. Map ALL streams from this input (0).
                hasVideo -> {
                    AppLogger.d("Mapping all streams from single input (0). Preserving muxed audio.")
                    add("-map"); add("0") // "0" means all streams from input 0.
                }
                // Case 3: We only have an audio-only stream. Map the audio stream.
                hasAudio -> {
                    AppLogger.d("Mapping audio-only stream from input (0).")
                    add("-map"); add("0:a?")
                }
            }

            val videoCodec = inputData.getString(GenericDownloader.Constants.VIDEO_CODEC) ?: ""
            // Check if the codec is HEVC (hvc1) or Dolby Vision (dvh1). Add other incompatible codecs here if needed.
            if (videoCodec.startsWith("hvc1") || videoCodec.startsWith("dvh1")) {
                AppLogger.d("Incompatible video codec '$videoCodec' detected. Re-encoding to H.264 for maximum compatibility.")

                // Video codec: re-encode to H.264 (libx264)
                add("-c:v"); add("libx264")

                // Use a good quality preset. 'veryfast' is a good balance of speed and size.
                add("-preset"); add("veryfast")

                // Set a reasonable quality level (CRF). Lower is better, 23 is a good default.
                add("-crf"); add("23")

                add("-pix_fmt"); add("yuv420p")

                // Audio codec: copy as it's usually compatible (like AAC)
                add("-c:a"); add("copy")
            } else {
                AppLogger.d("Compatible video codec '$videoCodec' detected. Copying stream directly.")
                // If the codec is compatible (e.g., avc1 -> H.264), just copy everything.
                add("-c"); add("copy")
            }

            add("-bsf:a")
            add("aac_adtstoasc")

            add("-movflags")
            add("+faststart")

            add("-y") // Overwrite output file if it exists
            add(finalOutputPath)
        }

        AppLogger.d("SuperX: Executing HLS merge with arguments: $arguments")
        return FFmpegKit.executeWithArguments(arguments.toTypedArray())
    }

    /**
     * Creates a single MP4 file by concatenating an fMP4 init segment and all media segments.
     */
    private fun createConcatenatedFmp4File(
        hlsTmpDir: File,
        segments: List<HlsPlaylistParser.MediaSegment>,
        prefix: String // "video" or "audio"
    ): File {
        val initSegment =
            (segments.first() as HlsPlaylistParser.UrlMediaSegment).initializationSegment!!
        val concatenatedFile = hlsTmpDir.resolve("concatenated_$prefix.mp4")

        try {
            concatenatedFile.outputStream().use { output ->
                // 1. Download and write the initialization segment
                AppLogger.d("HLS (fMP4): Downloading $prefix init segment from ${initSegment.url}")
                val initRequest = Request.Builder().url(initSegment.url).build()
                proxyOkHttpClient.getProxyOkHttpClient().newCall(initRequest).execute()
                    .use { response ->
                        if (!response.isSuccessful) throw IOException("Failed to download fMP4 $prefix init segment. HTTP ${response.code}")
                        response.body.use { output.write(it.bytes()) }
                    }

                // 2. Append all corresponding media segments
                segments.forEachIndexed { index, segment ->

                    // The segment downloader saves files with a predictable pattern like "segment_00000.m4s" or "audio_segment_00000.m4s"
                    // Must use that same pattern here to find the files.
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
        playlistName: String
    ): File {
        val firstSegment = segments.firstOrNull() as? HlsPlaylistParser.UrlMediaSegment
        val key = firstSegment?.encryptionKey
        val isEncrypted = key != null

        val finalPlaylistName = if (isEncrypted) playlistName.replace(
            ".txt",
            ".m3u8"
        ) else playlistName.replace(".m3u8", ".txt")
        val playlistFile = hlsTmpDir.resolve(finalPlaylistName)
        val keyFileName = "${filePrefix}encryption.key"

        if (isEncrypted) {
            AppLogger.d("HLS: Encryption detected for $filePrefix. Method: ${key!!.method}, URI: ${key.uri}")
            val keyFile = hlsTmpDir.resolve(keyFileName)
            try {
                val request = Request.Builder().url(key.uri).build()
                proxyOkHttpClient.getProxyOkHttpClient().newCall(request).execute()
                    .use { response ->
                        if (!response.isSuccessful) throw IOException("Failed to download key file. HTTP ${response.code}")
                        response.body.use { keyFile.writeBytes(it!!.bytes()) }
                        AppLogger.d("HLS: Encryption key for $filePrefix downloaded to ${keyFile.absolutePath}")
                    }
            } catch (e: Exception) {
                throw IOException(
                    "Failed to download HLS encryption key for $filePrefix: ${e.message}",
                    e
                )
            }
        }

        val playlistContent = buildString {
            if (isEncrypted) {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:3")
                appendLine("#EXT-X-TARGETDURATION:10")
                appendLine("#EXT-X-KEY:METHOD=${key!!.method},URI=\"$keyFileName\"")
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

    /**
     * Adds the correct FFmpeg arguments for a given TS playlist file (.m3u8 or .txt).
     */
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


    @Throws(IOException::class)
    private fun getMediaSegments(
        playlistUrl: String, headers: Map<String, String>
    ): Pair<List<HlsPlaylistParser.MediaSegment>?, List<HlsPlaylistParser.MediaSegment>?> {
        val client = proxyOkHttpClient.getProxyOkHttpClient()
        fun fetchAndParse(url: String): HlsPlaylistParser.HlsPlaylist {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (key, value) -> addHeader(key, value) }
            }.build()
            val response = client.newCall(request).execute()
            val content = response.body.string()
            if (!response.isSuccessful || content.isEmpty()) {
                throw IOException("Failed to download playlist at $url. HTTP ${response.code}")
            }
            return HlsPlaylistParser.parse(content, url)
        }

        // 1. Get the selected format ID from the worker's input data.
        val selectedFormatId = inputData.getString(GenericDownloader.Constants.SELECTED_FORMAT_ID)
        AppLogger.d("HLS: User selected format ID: $selectedFormatId")

        if (selectedFormatId == null) {
            throw IOException("No selected format ID was provided to the worker.")
        }

        val initialPlaylist = fetchAndParse(playlistUrl)
        if (initialPlaylist !is HlsPlaylistParser.MasterPlaylist) {
            val mediaPlaylist = initialPlaylist as HlsPlaylistParser.MediaPlaylist
            return Pair(mediaPlaylist.segments, null)
        }

        // 2. Find the selected video or audio variant from the Master Playlist.
        val selectedVideoVariant = initialPlaylist.variants.find { variant ->
            "hls-${variant.height}p-${variant.bandwidth}" == selectedFormatId
        }

        val selectedAudioRendition = initialPlaylist.alternateRenditions.find { rendition ->
            rendition.type == HlsPlaylistParser.RenditionType.AUDIO && "hls-audio-${rendition.groupId}-${rendition.name}" == selectedFormatId
        }
        var videoSegments: List<HlsPlaylistParser.MediaSegment>? = null
        var audioSegments: List<HlsPlaylistParser.MediaSegment>? = null

        // 3. Determine which segments to fetch based on the user's selection.
        when {
            selectedVideoVariant != null -> {
                // SCENARIO 1: User selected a video stream.
                AppLogger.d("HLS: Matched VIDEO format. Selecting variant with URL ${selectedVideoVariant.url}.")
                val videoMediaPlaylist = fetchAndParse(selectedVideoVariant.url)
                if (videoMediaPlaylist is HlsPlaylistParser.MediaPlaylist) {
                    videoSegments = videoMediaPlaylist.segments
                } else {
                    throw IOException("Expected a media playlist from variant, but got another master playlist.")
                }

                val audioGroupId = selectedVideoVariant.audioGroupId
                if (audioGroupId != null) {
                    val audioRendition = initialPlaylist.alternateRenditions.find {
                        it.groupId == audioGroupId && it.type == HlsPlaylistParser.RenditionType.AUDIO && it.url != null
                    }

                    if (audioRendition?.url != null) {
                        AppLogger.d("HLS: Found separate audio rendition at URL: ${audioRendition.url}")
                        val audioMediaPlaylist = fetchAndParse(audioRendition.url)
                        if (audioMediaPlaylist is HlsPlaylistParser.MediaPlaylist) {
                            audioSegments = audioMediaPlaylist.segments
                        }
                    }
                }
            }

            selectedAudioRendition != null -> {
                // SCENARIO 2: User selected an audio-only stream.
                AppLogger.d("HLS: Matched AUDIO format. Preparing audio-only download from URL: ${selectedAudioRendition.url}")
                videoSegments = null // No video to download
                if (selectedAudioRendition.url != null) {
                    val audioMediaPlaylist = fetchAndParse(selectedAudioRendition.url)
                    if (audioMediaPlaylist is HlsPlaylistParser.MediaPlaylist) {
                        audioSegments = audioMediaPlaylist.segments
                    }
                }
            }

            else -> {
                // SCENARIO 3: No match found.
                throw IOException("The selected format ID '$selectedFormatId' could not be found in the HLS manifest.")
            }
        }

        if (videoSegments.isNullOrEmpty() && audioSegments.isNullOrEmpty()) {
            throw IOException("Could not retrieve any media segments for the selected format.")
        }

        return Pair(videoSegments, audioSegments)
    }

    private fun cancelTask(task: VideoTaskItem, isPause: Boolean) {
        val sessionIdString = taskId

        if (sessionIdString.isEmpty()) {
            AppLogger.e("SuperX: Cannot cancel/pause task. Task ID is missing.")
            finishWorkWithFailureTaskId(task)
            return
        }

        // Differentiate between playlist and legacy downloads
        if (isHlsPlaylist(task.url) || isMpdPlaylist(task.url)) {
            // For HLS/MPD, the pause/cancel is handled by creating a flag file
            // and interrupting the segment downloader threads.
            val downloadDir = fileUtil.tmpDir.resolve(sessionIdString)
            if (isPause) {
                CustomFileDownloader.directStop(downloadDir)
                AppLogger.d("SuperX: Creating pause flag for playlist task $sessionIdString")
                try {
                    // Ensure the parent directory exists before creating the flag file.
                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs()
                    }
                    File(downloadDir, PAUSE_FLAG_FILENAME).createNewFile()
                    getContinuation().resume(Result.success())
                } catch (e: IOException) {
                    AppLogger.e("SuperX: Failed to create pause flag file. $e")
                    finishWorkWithFailureTaskId(task)
                }
            } else {
                CustomFileDownloader.directCancel(downloadDir)
                AppLogger.d("SuperX: Setting isCanceled flag for playlist task $sessionIdString")
                try {
                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs()
                    }
                    File(downloadDir, CANCEL_FLAG_FILENAME).createNewFile()
                    getContinuation().resume(Result.success())
                } catch (e: IOException) {
                    finishWorkWithFailureTaskId(task)
                    AppLogger.e("FFmpeg: Failed to create cancel flag file. $e")
                }
            }
        } else {
            // For legacy FFmpeg downloads, use the session ID to cancel
            try {
                val sessionIdLong = sessionIdString.toLong()
                AppLogger.d("FFmpeg: Cancelling legacy task using session ID: $sessionIdLong")
                FFmpegKit.cancel(sessionIdLong)
                getContinuation().resume(Result.success())
            } catch (e: NumberFormatException) {
                finishWorkWithFailureTaskId(task)
                AppLogger.e("FFmpeg: Failed to cancel task. The session ID '$sessionIdString' is not a valid number.")
            }
        }
    }

    private fun checkPauseState(downloadDir: File): Boolean {
        val shouldBePaused = File(downloadDir, PAUSE_FLAG_FILENAME).exists()
        if (shouldBePaused) {
            isPaused.set(true)
        }
        return shouldBePaused
    }

    private inner class SegmentDownloader(
        private val headers: Map<String, String>,
        private val downloadDir: File,
        private val totalSegments: Int
    ) {
        private val client = proxyOkHttpClient.getProxyOkHttpClient()
        fun downloadHlsSegment(
            segment: HlsPlaylistParser.MediaSegment,
            index: Int,
            task: VideoTaskItem,
            filePrefix: String,
            fileExt: String?
        ) {
            val finalExt = if (fileExt.isNullOrBlank()) "ts" else fileExt
            val segmentFileName = "${filePrefix}${"%05d".format(index)}.$finalExt"

            val segmentFile = downloadDir.resolve(segmentFileName)
            downloadWithRetries(segment.url, segmentFile, "HLS", index, task, RETRY_COUNT)
        }

        fun downloadMpdSegment(
            segment: MpdPlaylistParser.Segment, index: Int, task: VideoTaskItem, filePrefix: String
        ) {
            val segmentFileName = "${filePrefix}${"%05d".format(index)}.m4s"
            val segmentFile = downloadDir.resolve(segmentFileName)
            downloadWithRetries(segment.url, segmentFile, "MPD", index, task, RETRY_COUNT)
        }

        private fun downloadWithRetries(
            segmentUrl: String,
            segmentFile: File,
            logPrefix: String,
            index: Int,
            task: VideoTaskItem,
            maxRetries: Int
        ) {
            if (segmentFile.exists() && segmentFile.length() > 0) {
                AppLogger.d("$logPrefix: Segment $index already exists. Skipping.")
                val completedCount = hlsSegmentsCompleted.incrementAndGet()
                val totalDownloaded = hlsTotalBytesDownloaded.addAndGet(segmentFile.length())
                val estimatedTotalSize =
                    if (completedCount > 0) (totalDownloaded / completedCount) * totalSegments else 0
                val progress = Progress(totalDownloaded, estimatedTotalSize)
                onProgress(progress, task, isSizeEstimated = true)
                return
            }
            var lastException: Exception? = null
            for (attempt in 1..maxRetries) {
                if (File(
                        fileUtil.tmpDir.resolve(taskId),
                        CANCEL_FLAG_FILENAME
                    ).exists() || isPaused.get()
                ) throw IOException("Download canceled or paused by user.")
                try {
                    AppLogger.d("$logPrefix: Downloading segment $index of $totalSegments from $segmentUrl (Attempt $attempt/$maxRetries)")
                    val request = Request.Builder().url(segmentUrl).apply {
                        headers.forEach { (key, value) -> addHeader(key, value) }
                    }.build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) throw IOException("Failed to download segment $index. HTTP ${response.code}")
                    var bytesCopied = 0L
                    response.body.byteStream().use { input ->
                        segmentFile.outputStream().use { output ->
                            bytesCopied = input.copyTo(output)
                        }
                    }

                    val isLive = inputData.getBoolean(GenericDownloader.Constants.IS_LIVE, false)
                    val totalDownloaded = hlsTotalBytesDownloaded.addAndGet(bytesCopied)
                    task.apply { this.setIsLive(isLive) }

                    if (isLive) {
                        onProgress(
                            Progress(totalDownloaded, 0), task, false, true
                        )
                    } else {
                        val completedCount = hlsSegmentsCompleted.incrementAndGet()
                        val estimatedTotalSize =
                            if (completedCount > 0) (totalDownloaded / completedCount) * totalSegments else 0
                        val progress = Progress(totalDownloaded, estimatedTotalSize)
                        onProgress(progress, task, isSizeEstimated = true)
                        AppLogger.d("$logPrefix Progress: $completedCount/$totalSegments segments, $totalDownloaded/$estimatedTotalSize bytes")
                    }

                    AppLogger.d("$logPrefix: Segment $index downloaded successfully.")
                    return
                } catch (e: Exception) {
                    lastException = e
                    AppLogger.w("$logPrefix: Failed to download segment $index on attempt $attempt: ${e.message}")
                    if (segmentFile.exists()) segmentFile.delete()
                    if (attempt < maxRetries) Thread.sleep(1000L * attempt)
                }
            }
            throw IOException(
                "Failed to download segment $index after $maxRetries attempts.", lastException
            )
        }
    }

    private fun unPause(downloadDir: File) {
        val stopFile = File(downloadDir, PAUSE_FLAG_FILENAME)
        if (stopFile.exists()) {
            stopFile.delete()
        }
        isPaused.set(false)
    }

    override fun finishWork(item: VideoTaskItem?) {
        if (getDone()) {
            getContinuation().resume(Result.success())
            return
        }
        setDone()

        val taskId = item?.mId ?: run {
            AppLogger.e("SuperX: Cannot finish work, taskId is NULL")
            getContinuation().resume(Result.failure())
            return
        }
        AppLogger.d("FFmpeg: Finishing work for task $taskId with state ${item.taskState}")
        ffmpegSession.set(null)

        handleTaskCompletion(item)

        val notificationData = notificationsHelper.createNotificationBuilder(item)
        showNotificationFinal(notificationData.first, notificationData.second)

        val result =
            if (item.taskState == VideoTaskState.ERROR) Result.failure() else Result.success()
        try {
            getContinuation().resume(result)
        } catch (e: IllegalStateException) {
            AppLogger.e("SuperX: Could not resume continuation: ${e.message}")
        }
    }

    private fun handleTaskCompletion(item: VideoTaskItem) {
        val sourcePath = File(item.filePath ?: return)
        val finalProgress = Progress(item.downloadSize, item.totalSize)

        when (item.taskState) {
            VideoTaskState.CANCELED -> {
                sourcePath.parentFile?.deleteRecursively()
                saveProgress(item.mId, finalProgress, item.taskState, "Canceled")
            }

            VideoTaskState.SUCCESS -> {
                val targetPath = fixFileName(File(fileUtil.folderDir, sourcePath.name).path)
                val from = sourcePath.toUri()
                val to = File(targetPath).toUri()
                AppLogger.d("MOVING FILE $from to -> $to")
                saveProgress(item.mId, finalProgress, VideoTaskState.PREPARE, "Downloaded, moving...")
                val fileMoved = fileUtil.moveMedia(applicationContext, from, to)
                saveProgress(
                    item.mId,
                    finalProgress,
                    item.taskState,
                    "Downloaded, moving ${if (fileMoved) "success" else "failed"}"
                )
                if (fileMoved) {
                    AppLogger.d("SuperX: File moved successfully to $targetPath")
                    sourcePath.parentFile?.deleteRecursively()
                    val successProgress = Progress(item.totalSize, item.totalSize)
                    saveProgress(item.mId, successProgress, VideoTaskState.SUCCESS, "Success")
                } else {
                    AppLogger.e("FFmpeg: Failed to move file to $targetPath")
                    item.taskState = VideoTaskState.ERROR
                    item.errorMessage = "Error moving file"
                    sourcePath.parentFile?.deleteRecursively()
                    saveProgress(item.mId, finalProgress, item.taskState, "Error moving file")
                }
            }

            else -> { // ERROR state
                AppLogger.d("SuperX: Task failed with error: ${item.errorMessage}  ${item.taskState}")
                saveProgress(
                    item.mId,
                    finalProgress,
                    item.taskState,
                    item.errorMessage ?: "Unknown Superx Error"
                )
            }
        }
    }

    private fun showProgress(taskItem: VideoTaskItem, progress: Progress) {
        val isLive = inputData.getBoolean(GenericDownloader.Constants.IS_LIVE, false)

        if (isLive) {
            val downloadedDuration = taskItem.accumulatedDuration
            val hours = TimeUnit.SECONDS.toHours(downloadedDuration)
            val minutes = TimeUnit.SECONDS.toMinutes(downloadedDuration) % 60
            val seconds = downloadedDuration % 60

            val durationString = if (hours > 0) {
                String.format(Locale.ENGLISH, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.ENGLISH, "%02d:%02d", minutes, seconds)
            }

            taskItem.apply {
                // Format the notification line to show "Recording..." with size and duration.
                lineInfo = "Recording: ${
                    Formatter.formatShortFileSize(
                        applicationContext, progress.currentBytes
                    )
                } ($durationString)"
                taskState = VideoTaskState.DOWNLOADING
                totalSize = 0 // Total size is unknown
                downloadSize = progress.currentBytes
                percent = 99F // No percentage for live streams
            }

        } else {
            // --- Regular VOD Progress ---
            taskItem.apply {
                lineInfo = "Downloading: ${taskItem.fileName}"
                taskState = VideoTaskState.DOWNLOADING
                totalSize = progress.totalBytes
                downloadSize = progress.currentBytes
                percent = getPercentFromBytes(downloadSize, totalSize)
            }
        }
        val notificationData = notificationsHelper.createNotificationBuilder(taskItem)
        showLongRunningNotificationAsync(notificationData.first, notificationData.second)
    }

    private fun saveProgress(
        taskId: String,
        progress: Progress,
        downloadStatus: Int,
        infoLine: String = "",
        isLive: Boolean = false
    ) {
        if (getDone() && downloadStatus == VideoTaskState.DOWNLOADING) return
        val dbTask = progressRepository.getProgressInfos().blockingFirst()
            .find { it.id == taskId || it.downloadId == taskId.toLongOrNull() } ?: return
        if (dbTask.downloadStatus == VideoTaskState.SUCCESS) return
        dbTask.downloadStatus = downloadStatus
        dbTask.infoLine = infoLine
        dbTask.isLive = isLive
        dbTask.progressTotal = progress.totalBytes
        dbTask.progressDownloaded = progress.currentBytes

        progressRepository.saveProgressInfo(dbTask)
    }

    private fun decodeCookieHeader(headers: Map<String, String>): Map<String, String> {
        return headers.toMutableMap().also { fixedHeaders ->
            fixedHeaders["Cookie"]?.let {
                try {
                    fixedHeaders["Cookie"] = String(Base64.decode(it, Base64.DEFAULT))
                } catch (e: IllegalArgumentException) {
                    AppLogger.e("SuperX: Failed to decode Base64 Cookie: $it")
                }
            }
        }
    }

    private fun isHlsPlaylist(url: String): Boolean {
        return inputData.getBoolean(GenericDownloader.Constants.IS_M3U8, false)
    }

    private fun isMpdPlaylist(url: String): Boolean {
        return inputData.getBoolean(GenericDownloader.Constants.IS_MPD, false)
    }

    private fun onProgress(
        progress: Progress, task: VideoTaskItem, isSizeEstimated: Boolean, isLIve: Boolean = false
    ) {
        if (getDone()) return
        showProgress(task, progress)
        if (isSizeEstimated || isLIve) {
            saveProgress(
                task.mId,
                progress,
                VideoTaskState.DOWNLOADING,
                isLive = isLIve,
                infoLine = if (isLIve) "Live Recording" else "Downloading..."
            )
        }
    }

    private fun finishWorkWithFailureTaskId(task: VideoTaskItem) {
        AppLogger.d("SMTH WRONG, taskId is NULL  $task")
        try {
            getContinuation().resume(Result.failure())
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
