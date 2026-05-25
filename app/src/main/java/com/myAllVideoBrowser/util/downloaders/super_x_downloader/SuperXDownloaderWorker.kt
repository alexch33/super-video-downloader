package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import android.content.Context
import android.text.format.Formatter
import android.util.Base64
import androidx.core.net.toUri
import androidx.work.WorkerParameters
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.GenericDownloadWorkerWrapper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy.HlsDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy.HlsLiveDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy.MpdDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy.MpdLiveDownloader
import com.myAllVideoBrowser.util.hls_parser.HlsPlaylistParser
import com.myAllVideoBrowser.util.hls_parser.MpdPlaylistParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import java.util.concurrent.TimeUnit
import kotlin.collections.filter
import kotlin.collections.first
import kotlin.collections.flatMap
import kotlin.coroutines.cancellation.CancellationException

class SuperXDownloaderWorker(appContext: Context, workerParams: WorkerParameters) :
    GenericDownloadWorkerWrapper(appContext, workerParams) {

    @Volatile
    private lateinit var progressCached: Progress

    @Volatile
    private lateinit var taskId: String

    private val workerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastProgressUpdateTime = 0L

    override fun onDownloadStopped() {
        workerScope.cancel()
        AppLogger.d("SuperX: Worker stopped, scope cancelled for task ${if (::taskId.isInitialized) taskId else "unknown"}")
    }

    override fun handleAction(
        action: String, task: VideoTaskItem, headers: Map<String, String>, isFileRemove: Boolean
    ) {
        taskId = task.mId ?: inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)!!
        val downloadDir = fileUtil.tmpDir.resolve(taskId)

        val controller = FileBasedDownloadController(downloadDir)

        try {
            when (action) {
                GenericDownloader.DownloaderActions.DOWNLOAD, GenericDownloader.DownloaderActions.RESUME -> {
                    controller.start()
                    startDownload(task, headers)
                }

                GenericDownloader.DownloaderActions.CANCEL -> {
                    AppLogger.d("HLS: Cancel action received for task $taskId. Creating flag file.")
                    val isWorkerRunning =
                        GenericDownloader.isWorkScheduled(applicationContext, taskId)
                    if (isWorkerRunning) {
                        controller.requestCancel()
                        getContinuation().resume(Result.success())
                    } else {
                        controller.requestCancel()
                        finishWork(task.also { it.taskState = VideoTaskState.CANCELED })
                    }
                }

                GenericDownloader.DownloaderActions.PAUSE -> {
                    AppLogger.d("HLS: Pause action received for task $taskId. Creating flag file.")
                    controller.requestPause()
                    getContinuation().resume(Result.success())
                }

                GenericDownloader.DownloaderActions.STOP_SAVE_ACTION -> {
                    AppLogger.d("HLS (Live): Stop and Save action received for task $taskId.")
                    val isWorkerRunning =
                        GenericDownloader.isWorkScheduled(applicationContext, taskId)

                    if (isWorkerRunning) {
                        AppLogger.d("HLS (Live): Worker is active. Signaling it to stop and save.")
                        controller.requestStopAndSave()
                        getContinuation().resume(Result.success()) // Acknowledge the request
                    } else {
                        AppLogger.d("HLS (Live): No active worker found. Starting a new merge-only job.")
                        startLiveHlsDownloadLoop(
                            task,
                            headers,
                            mergeOnly = true
                        )
                    }
                }
            }
        } catch (e: IOException) {
            finishWorkWithFailureTaskId(task)
            AppLogger.e("SuperX: Failed to create state flag file. $e")
        }
    }


    private fun startDownload(task: VideoTaskItem, headersRaw: Map<String, String>) {
        val taskDuration = inputData.getLong(GenericDownloader.Constants.DURATION, 0)
        AppLogger.d("Custom M3U8/MPD: Starting download for initial taskId: $taskId with duration: $taskDuration\ntask: $task")

        val headers = decodeCookieHeader(headersRaw)

        val isLive = inputData.getBoolean(GenericDownloader.Constants.IS_LIVE, false)

        onProgress(
            Progress(0L, 0L, "Starting..."),
            task.also { it.taskState = VideoTaskState.PREPARE },
            isSizeEstimated = true
        )
        // Check if the URL is a playlist
        if (isHlsPlaylist()) {
            AppLogger.d("HLS Playlist detected. Starting advanced parsing flow.")
            if (isLive) {
                startLiveHlsDownloadLoop(task, headers)
            } else {
                startHlsDownload(task, headers)
            }
        } else if (isMpdPlaylist()) {
            AppLogger.d("MPD Manifest detected. Starting advanced parsing flow.")
            if (isLive) {
                startMpdLiveDownload(task, headers)
            } else {
                startMpdDownload(task, headers)
            }
        } else {
            val tsk = task.apply {
                this.taskState = VideoTaskState.ERROR
                this.errorMessage = "Unsupported manifest type."
            }
            finishWork(tsk)
        }
    }

    private fun startLiveHlsDownloadLoop(
        task: VideoTaskItem,
        headers: Map<String, String>,
        mergeOnly: Boolean = false
    ) {
        AppLogger.d("HLS (Live): Delegating download for task $taskId to HlsLiveDownloader strategy.")
        val hlsTmpDir = fileUtil.tmpDir.resolve(taskId)
        val controller = FileBasedDownloadController(hlsTmpDir)
        controller.start()

        workerScope.launch {
            try {
                val isAudioOnlyExtract =
                    inputData.getBoolean(GenericDownloader.Constants.IS_AUDIO_ONLY_EXTRACT, false)

                // 1. Instantiate the HlsLiveDownloader strategy
                val liveDownloader = HlsLiveDownloader(
                    httpClient = proxyOkHttpClient.getProxyOkHttpClient(),
                    getMediaPlaylists = ::getMediaPlaylists,
                    onMergeProgress = { progress, progressTask ->
                        onProgress(
                            progress,
                            progressTask.also {
                                it.taskState = VideoTaskState.PREPARE
                            },
                            isSizeEstimated = true,
                            isLIve = true,
                            isOnMerge = true
                        )
                    },
                    videoCodec = inputData.getString(GenericDownloader.Constants.VIDEO_CODEC),
                    mergeOnly = mergeOnly,
                    isAudioOnlyExtract = isAudioOnlyExtract
                )

                // 2. Execute the download. This handles the entire live recording loop.
                val finalOutputFile = liveDownloader.download(
                    task = task,
                    headers = headers,
                    downloadDir = hlsTmpDir,
                    controller = controller,
                    onProgress = { progress ->
                        onProgress(progress, task, isSizeEstimated = false, isLIve = true)
                    }
                )

                // 3. Handle success
                AppLogger.d("HLS (Live): Strategy download completed successfully.")
                val completedTask = task.also {
                    it.taskState = VideoTaskState.SUCCESS
                    it.filePath = finalOutputFile.absolutePath
                    it.totalSize = finalOutputFile.length()
                    it.downloadSize = it.totalSize
                    it.errorMessage = null
                }
                finishWork(completedTask)

            } catch (e: Exception) {
                // 4. Handle failures (Pause, Cancel, Error)
                when {
                    controller.isCancelRequested() -> {
                        AppLogger.d("HLS (Live): Task $taskId was canceled.")
                        finishWork(task.apply { taskState = VideoTaskState.CANCELED })
                        return@launch
                    }

                    controller.isPauseRequested() || e is CancellationException -> {
                        AppLogger.d("HLS (Live): Task $taskId is pausing gracefully.")
                        finishWork(task.also {
                            it.taskState = VideoTaskState.PAUSE
                            it.errorMessage = "Paused"
                        })
                    }

                    else -> {
                        AppLogger.e("HLS (Live): Download failed for task $taskId: ${e.message}")
                        e.printStackTrace()
                        finishWork(task.also {
                            it.taskState = VideoTaskState.ERROR
                            it.errorMessage = "HLS Live download failed: ${e.message}"
                        })
                    }
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
            val request = Request.Builder().url(url).headers(headers.toHeaders()).build()
            client.newCall(request).execute().use { response ->
                val content = response.body.string()
                if (!response.isSuccessful || content.isEmpty()) {
                    throw IOException("Failed to download playlist at $url. HTTP ${response.code}")
                }
                return HlsPlaylistParser.parse(content, url)
            }
        }

        val selectedFormatId = inputData.getString(GenericDownloader.Constants.SELECTED_FORMAT_ID)
            ?: throw IOException("No selected format ID was provided to the worker.")

        val isAudioOnlyExtract =
            inputData.getBoolean(GenericDownloader.Constants.IS_AUDIO_ONLY_EXTRACT, false)

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

                if (!isAudioOnlyExtract || audioPlaylist == null) {
                    val videoMediaPlaylist = fetchAndParse(selectedVideoVariant.url)
                    if (videoMediaPlaylist is HlsPlaylistParser.MediaPlaylist) {
                        videoPlaylist = videoMediaPlaylist
                    } else {
                        throw IOException("Expected a media playlist from variant, but got another master playlist.")
                    }
                }
            }

            selectedAudioRendition?.url != null -> {
                val audioMediaPlaylist = fetchAndParse(selectedAudioRendition.url)
                if (audioMediaPlaylist is HlsPlaylistParser.MediaPlaylist) {
                    audioPlaylist = audioMediaPlaylist
                }
            }

            else -> throw IOException("The selected format ID '$selectedFormatId' could not be found in the HLS manifest.")
        }

        return Pair(videoPlaylist, audioPlaylist)
    }

    private fun startMpdDownload(task: VideoTaskItem, headers: Map<String, String>) {
        AppLogger.d("MPD: Delegating download for task $taskId to MpdDownloader strategy.")
        val mpdTmpDir = fileUtil.tmpDir.resolve(taskId)
        val controller = FileBasedDownloadController(mpdTmpDir)
        controller.start()

        workerScope.launch {
            try {
                // 1. Instantiate the MpdDownloader strategy
                val mpdDownloader = MpdDownloader(
                    httpClient = proxyOkHttpClient.getProxyOkHttpClient(),
                    getMpdRepresentations = ::getMpdRepresentations,
                    onMergeProgress = { progress, progressTask ->
                        onProgress(
                            progress,
                            progressTask,
                            isSizeEstimated = true,
                            isOnMerge = true
                        )
                    },
                    threadCount = sharedPrefHelper.getM3u8DownloaderThreadCount(),
                    videoCodec = inputData.getString(GenericDownloader.Constants.VIDEO_CODEC),
                    isAudioOnlyExtract = inputData.getBoolean(
                        GenericDownloader.Constants.IS_AUDIO_ONLY_EXTRACT,
                        false
                    )
                )

                // 2. Execute the download. This one method handles both segment and BaseURL logic.
                val finalOutputFile = mpdDownloader.download(
                    task = task,
                    headers = headers,
                    downloadDir = mpdTmpDir,
                    controller = controller,
                    onProgress = { progress ->
                        onProgress(progress, task, isSizeEstimated = true)
                    }
                )

                // 3. Handle success
                AppLogger.d("MPD: Strategy download completed successfully.")
                val completedTask = task.also {
                    it.taskState = VideoTaskState.SUCCESS
                    it.filePath = finalOutputFile.absolutePath
                    it.totalSize = finalOutputFile.length()
                    it.downloadSize = it.totalSize
                    it.errorMessage = null
                }
                finishWork(completedTask)

            } catch (e: Exception) {
                // 4. Handle failures
                when {
                    controller.isCancelRequested() -> {
                        AppLogger.d("MPD: Task $taskId was canceled by user.")
                        finishWork(task.also { it.taskState = VideoTaskState.CANCELED })
                        return@launch
                    }

                    controller.isPauseRequested() || e is CancellationException -> {
                        AppLogger.d("MPD: Task $taskId is pausing gracefully.")
                        finishWork(task.also {
                            it.taskState = VideoTaskState.PAUSE
                            it.errorMessage = "Paused"
                        })
                    }

                    else -> {
                        AppLogger.e("MPD: Download failed for task $taskId: ${e.message}")
                        e.printStackTrace()
                        finishWork(task.also {
                            it.taskState = VideoTaskState.ERROR
                            it.errorMessage = "MPD download failed: ${e.message}"
                        })
                    }
                }
            }
        }
    }

    private fun startMpdLiveDownload(task: VideoTaskItem, headers: Map<String, String>) {
        AppLogger.d("MPD (Live): Delegating download for task $taskId to MpdLiveDownloader strategy.")
        val mpdTmpDir = fileUtil.tmpDir.resolve(taskId)
        val controller = FileBasedDownloadController(mpdTmpDir)
        controller.start()

        workerScope.launch {
            try {
                // 1. Instantiate the MpdLiveDownloader strategy
                val liveDownloader = MpdLiveDownloader(
                    httpClient = proxyOkHttpClient.getProxyOkHttpClient(),
                    getMpdRepresentations = ::getMpdRepresentations,
                    onMergeProgress = { progress, progressTask ->
                        onProgress(
                            progress,
                            progressTask,
                            isSizeEstimated = true,
                            isLIve = true,
                            isOnMerge = true
                        )
                    },
                    videoCodec = inputData.getString(GenericDownloader.Constants.VIDEO_CODEC),
                    isAudioOnlyExtract = inputData.getBoolean(
                        GenericDownloader.Constants.IS_AUDIO_ONLY_EXTRACT,
                        false
                    )
                )

                // 2. Execute the download. This handles the entire live recording loop.
                val finalOutputFile = liveDownloader.download(
                    task = task,
                    headers = headers,
                    downloadDir = mpdTmpDir,
                    controller = controller,
                    onProgress = { progress ->
                        onProgress(progress, task, isSizeEstimated = false, isLIve = true)
                    }
                )

                // 3. Handle success
                AppLogger.d("MPD (Live): Strategy download completed successfully.")
                val completedTask = task.also {
                    it.taskState = VideoTaskState.SUCCESS
                    it.filePath = finalOutputFile.absolutePath
                    it.totalSize = finalOutputFile.length()
                    it.downloadSize = it.totalSize
                    it.errorMessage = null
                }
                finishWork(completedTask)

            } catch (e: Exception) {
                // 4. Handle failures (Cancel, Pause/Error)
                when {
                    e is CancellationException -> {
                        AppLogger.d("MPD (Live): Task $taskId was stopped by user. Checking for merged file.")
                        // The downloader is designed to proceed to merge on cancellation.
                        // We check if the merged file was successfully created.
                        val finalFile = mpdTmpDir.resolve("merged_output.mp4")
                        if (finalFile.exists() && finalFile.length() > 500) { // Check for a reasonable file size
                            AppLogger.d("MPD (Live): Merge after stop was successful.")
                            // This is now a SUCCESS state.
                            val completedTask = task.also {
                                it.taskState = VideoTaskState.SUCCESS
                                it.filePath =
                                    finalFile.absolutePath
                                it.totalSize = finalFile.length()
                                it.downloadSize = it.totalSize
                                it.errorMessage = "Recording stopped by user."
                            }

                            finishWork(completedTask)
                        } else {
                            AppLogger.d("MPD (Live): Task $taskId was canceled before any segments could be merged.")
                            finishWork(task.apply { taskState = VideoTaskState.CANCELED })
                        }
                        return@launch
                    }

                    else -> {
                        AppLogger.e("MPD (Live): Download failed for task $taskId: ${e.message}")
                        e.printStackTrace()
                        finishWork(task.also {
                            it.taskState = VideoTaskState.ERROR
                            it.errorMessage = "MPD Live download failed: ${e.message}"
                        })
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun getMpdRepresentations(
        manifestUrl: String, headers: Map<String, String>
    ): Pair<MpdPlaylistParser.MpdRepresentation?, MpdPlaylistParser.MpdRepresentation?> {
        val client = proxyOkHttpClient.getProxyOkHttpClient()
        val request = Request.Builder().url(manifestUrl).headers(headers.toHeaders()).build()
        val response = client.newCall(request).execute()
        val content = response.body.string()
        if (!response.isSuccessful || content.isEmpty()) {
            throw IOException("Failed to download MPD manifest at $manifestUrl. HTTP ${response.code}")
        }
        response.close()

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

        val isAudioOnlyExtract =
            inputData.getBoolean(GenericDownloader.Constants.IS_AUDIO_ONLY_EXTRACT, false)

        // 2. Find the selected video/audio representation based on the ID.
        val selectedVideoRep = videoRepresentations.find {
            // Construct the ID on to match the detector's logic for video
            "mpd-${it.height}p-${it.bandwidth}" == selectedFormatId || "hls-${it.height}p-${it.bandwidth}" == selectedFormatId
        }

        val selectedAudioRep = audioRepresentations.find {
            // Construct the ID to match the detector's logic for audio
            "mpd-audio-${it.bandwidth}" == selectedFormatId || "hls-audio-${it.bandwidth}" == selectedFormatId
        }

        // 3. Determine the final video and audio representations to use.
        var finalVideoRep: MpdPlaylistParser.MpdRepresentation?
        var finalAudioRep: MpdPlaylistParser.MpdRepresentation?

        when {
            selectedVideoRep != null -> {
                // SCENARIO 1: A video format was selected.
                // Use the selected video and find the best corresponding audio.
                finalAudioRep =
                    audioRepresentations.maxByOrNull { it.bandwidth } // Always take the best audio

                if (isAudioOnlyExtract && finalAudioRep != null) {
                    finalVideoRep = null
                    AppLogger.d("MPD: Audio-only extract requested. Skipping video representation.")
                } else {
                    finalVideoRep = selectedVideoRep
                    AppLogger.d("MPD: Matched VIDEO format. Will use ${finalVideoRep.height}p and find best audio.")
                }
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

    private fun startHlsDownload(task: VideoTaskItem, headers: Map<String, String>) {
        AppLogger.d("HLS: Delegating download for task $taskId to HlsDownloader strategy.")
        val hlsTmpDir = fileUtil.tmpDir.resolve(taskId)
        val controller = FileBasedDownloadController(hlsTmpDir)
        controller.start()

        // The worker's main coroutine scope will manage the lifecycle of the download.
        workerScope.launch {
            try {
                // 1. Instantiate the HlsDownloader strategy with its dependencies.
                val hlsDownloader = HlsDownloader(
                    httpClient = proxyOkHttpClient.getProxyOkHttpClient(),
                    getMediaSegments = ::getMediaSegments,
                    onMergeProgress = { progress, progressTask ->
                        onProgress(
                            progress,
                            progressTask,
                            isSizeEstimated = true,
                            isOnMerge = true
                        )
                    },
                    threadCount = sharedPrefHelper.getM3u8DownloaderThreadCount(),
                    videoCodec = inputData.getString(GenericDownloader.Constants.VIDEO_CODEC),
                    isAudioOnlyExtract = inputData.getBoolean(
                        GenericDownloader.Constants.IS_AUDIO_ONLY_EXTRACT,
                        false
                    )
                )

                // 2. Execute the download. This suspend fun handles everything.
                val finalOutputFile = hlsDownloader.download(
                    task = task,
                    headers = headers,
                    downloadDir = hlsTmpDir,
                    controller = controller,
                    onProgress = { progress ->
                        onProgress(progress, task, isSizeEstimated = true)
                    }
                )

                // 3. Handle success.
                AppLogger.d("HLS: Strategy download completed successfully.")
                val completedTask = task.also {
                    it.taskState = VideoTaskState.SUCCESS
                    it.filePath = finalOutputFile.absolutePath
                    it.totalSize = finalOutputFile.length()
                    it.downloadSize = it.totalSize
                    it.errorMessage = null
                }
                finishWork(completedTask)

            } catch (e: Exception) {
                // 4. Handle failures, including pause and cancel.
                when {
                    controller.isCancelRequested() -> {
                        AppLogger.d("HLS: Task $taskId was canceled by user.")
                        finishWork(task.apply { taskState = VideoTaskState.CANCELED })
                        return@launch
                    }

                    controller.isPauseRequested() || e is CancellationException -> {
                        AppLogger.d("HLS: Task $taskId is pausing gracefully.")
                        finishWork(task.also {
                            it.taskState = VideoTaskState.PAUSE
                        })
                    }
                    // Handle other errors.
                    else -> {
                        AppLogger.e("HLS: Download failed for task $taskId: ${e.message}")
                        e.printStackTrace()
                        finishWork(task.also {
                            it.taskState = VideoTaskState.ERROR
                            it.errorMessage = "HLS download failed: ${e.message}"
                        })
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun getMediaSegments(
        playlistUrl: String, headers: Map<String, String>
    ): Pair<List<HlsPlaylistParser.MediaSegment>?, List<HlsPlaylistParser.MediaSegment>?> {
        val client = proxyOkHttpClient.getProxyOkHttpClient()
        fun fetchAndParse(url: String): HlsPlaylistParser.HlsPlaylist {
            val request = Request.Builder().url(url).headers(headers.toHeaders()).build()
            client.newCall(request).execute().use { response ->
                val content = response.body.string()
                if (!response.isSuccessful || content.isEmpty()) {
                    throw IOException("Failed to download playlist at $url. HTTP ${response.code}")
                }
                return HlsPlaylistParser.parse(content, url)
            }
        }

        // 1. Get the selected format ID from the worker's input data.
        val selectedFormatId = inputData.getString(GenericDownloader.Constants.SELECTED_FORMAT_ID)
        AppLogger.d("HLS: User selected format ID: $selectedFormatId")

        if (selectedFormatId == null) {
            throw IOException("No selected format ID was provided to the worker.")
        }

        val isAudioOnlyExtract =
            inputData.getBoolean(GenericDownloader.Constants.IS_AUDIO_ONLY_EXTRACT, false)

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

                if (!isAudioOnlyExtract || audioSegments == null) {
                    AppLogger.d("HLS: Matched VIDEO format. Selecting variant with URL ${selectedVideoVariant.url}.")
                    val videoMediaPlaylist = fetchAndParse(selectedVideoVariant.url)
                    if (videoMediaPlaylist is HlsPlaylistParser.MediaPlaylist) {
                        videoSegments = videoMediaPlaylist.segments
                    } else {
                        throw IOException("Expected a media playlist from variant, but got another master playlist.")
                    }
                } else {
                    AppLogger.d("HLS: Audio-only requested and separate audio track found. Skipping video track download.")
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

        handleTaskCompletion(item.also {
            it.downloadSize = progressCached.currentBytes
            it.totalSize = progressCached.totalBytes
        })

        val notificationData = notificationsHelper.createNotificationBuilder(item.also {
            if (item.taskState == VideoTaskState.SUCCESS) {
                it.lineInfo = "Success"
            }
        })
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
                // for cancellation path should be specified separately
                val taskDir = fileUtil.tmpDir.resolve(taskId)
                AppLogger.d("Task cancelled by user, removing task's dir: $taskDir")
                taskDir.deleteRecursively()
                saveProgress(item.mId, finalProgress, item.taskState, "Canceled")
            }

            VideoTaskState.SUCCESS -> {
                val isAudioOnlyExtract =
                    inputData.getBoolean(GenericDownloader.Constants.IS_AUDIO_ONLY_EXTRACT, false)
                var fileName = item.fileName
                if (isAudioOnlyExtract && !fileName.endsWith(".mp3", true)) {
                    fileName = fileName.substringBeforeLast(".") + ".mp3"
                }

                val targetPath = fixFileName(
                    File(fileUtil.folderDir, fileName).path,
                    isAudioOnlyExtract
                )
                val from = sourcePath.toUri()
                val to = File(targetPath).toUri()
                AppLogger.d("MOVING FILE $from to -> $to")
                saveProgress(
                    item.mId,
                    finalProgress,
                    VideoTaskState.PREPARE,
                    "Downloaded, moving..."
                )
                val fileMoved = fileUtil.moveMedia(applicationContext, from, to)
                saveProgress(
                    item.mId,
                    finalProgress,
                    if (fileMoved) VideoTaskState.SUCCESS else VideoTaskState.ERROR,
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
                    if (item.taskState == VideoTaskState.ERROR) {
                        item.errorMessage ?: "Unknown Superx Error"
                    } else {
                        "Paused"
                    }
                )
            }
        }
    }

    private fun showProgress(taskItem: VideoTaskItem, progress: Progress) {
        if (getDone()) {
            AppLogger.d("SuperX: Ignoring progress update for ${taskItem.mId} because worker is already done.")
            return
        }

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
                val calculatedPercent =
                    getPercentFromBytes(progress.currentBytes, progress.totalBytes)
                val taskPercent =
                    if (taskItem.percentFromBytes == 0F) calculatedPercent else taskItem.percentFromBytes
                lineInfo = taskItem.lineInfo
                    ?: "Downloading: ${String.format("%.2f", taskPercent)}% ${taskItem.fileName}"
                taskState = VideoTaskState.DOWNLOADING
                totalSize = progress.totalBytes
                downloadSize = progress.currentBytes
                percent = calculatedPercent
            }
        }
        val notificationData = notificationsHelper.createNotificationBuilder(taskItem)

        if (!getDone()) {
            showLongRunningNotificationAsync(notificationData.first, notificationData.second)
        }
    }

    private fun saveProgress(
        taskId: String,
        progress: Progress,
        downloadStatus: Int,
        infoLine: String = "",
        isLive: Boolean = false,
    ) {
        if (getDone() && downloadStatus == VideoTaskState.DOWNLOADING) {
            return
        }
        val dbTask = progressRepository.getProgressInfos().blockingFirst(emptyList())
            .find { it.id == taskId || it.downloadId == taskId.toLongOrNull() } ?: return
        if (dbTask.downloadStatus == VideoTaskState.SUCCESS) {
            return
        }
        if (downloadStatus == VideoTaskState.CANCELED || dbTask.downloadStatus == VideoTaskState.CANCELED) {
            progressRepository.deleteProgressInfo(dbTask)
            return
        }
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
                } catch (_: IllegalArgumentException) {
                    AppLogger.e("SuperX: Failed to decode Base64 Cookie: $it")
                }
            }
        }
    }

    private fun isHlsPlaylist(): Boolean {
        return inputData.getBoolean(GenericDownloader.Constants.IS_M3U8, false)
    }

    private fun isMpdPlaylist(): Boolean {
        return inputData.getBoolean(GenericDownloader.Constants.IS_MPD, false)
    }

    private fun onProgress(
        progress: Progress,
        task: VideoTaskItem,
        isSizeEstimated: Boolean,
        isLIve: Boolean = false,
        isOnMerge: Boolean = false
    ) {
        if (getDone()) return

        progressCached = progress
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProgressUpdateTime < 2000) {
            return
        }
        lastProgressUpdateTime = currentTime

        if (progress.info.isNotEmpty()) {
            task.also { it.lineInfo = progress.info }
        }

        val isLiveLocal = isLIve || task.isLive
        if (isOnMerge) {
            showProgress(task.clone(), progress)
            saveProgress(
                task.mId,
                progress,
                VideoTaskState.PREPARE,
                isLive = isLiveLocal,
                infoLine = task.lineInfo
            )
            return
        }
        showProgress(task.clone(), progress)

        if (isSizeEstimated || isLiveLocal) {
            val infoString = if (!isLiveLocal && progress.info.isNotEmpty()) {
                progress.info
            } else {
                "Downloading..."
            }
            saveProgress(
                task.mId,
                progress,
                VideoTaskState.DOWNLOADING,
                isLive = isLiveLocal,
                infoLine = if (isLiveLocal) "Live Recording" else infoString
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
