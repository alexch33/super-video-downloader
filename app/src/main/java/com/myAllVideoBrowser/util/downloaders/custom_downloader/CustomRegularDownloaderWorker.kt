package com.myAllVideoBrowser.util.downloaders.custom_downloader

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.room.concurrent.AtomicBoolean
import androidx.work.WorkerParameters
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.FfmpegProcessor
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.GenericDownloadWorkerWrapper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import java.io.File
import java.net.URL
import java.util.Date
import kotlin.coroutines.resume

class CustomRegularDownloaderWorker(appContext: Context, workerParams: WorkerParameters) :
    GenericDownloadWorkerWrapper(appContext, workerParams) {
    private var fileMovedSuccess = false
    private var outputFileName: String? = null
    private var progressCached: Progress = Progress(0, 0)

    @Volatile
    private var lastSavedTime = 0L

    var isCanceled = AtomicBoolean(false)

    var isStoppedAndSaved = AtomicBoolean(false)

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL = 1000
    }

    override fun handleAction(
        action: String, task: VideoTaskItem, headers: Map<String, String>, isFileRemove: Boolean
    ) {
        when (action) {
            GenericDownloader.DownloaderActions.DOWNLOAD -> {
                isCanceled.set(false)
                startDownload(task, headers)
            }

            GenericDownloader.DownloaderActions.CANCEL -> {
                isCanceled.set(true)
                cancelTask(task)
            }

            GenericDownloader.DownloaderActions.PAUSE -> {
                isCanceled.set(false)
                pauseTask(task)
            }

            GenericDownloader.DownloaderActions.RESUME -> {
                isCanceled.set(false)
                startDownload(task, headers)
            }

            GenericDownloader.DownloaderActions.STOP_SAVE_ACTION -> {
                isCanceled.set(false)
                isStoppedAndSaved.set(true)
                stopAndSave(task)
            }
        }
    }

    private fun stopAndSave(task: VideoTaskItem) {
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)
            ?: return finishWorkWithFailureTaskId(task)
        val tmpFile = fileUtil.tmpDir.resolve(taskId).resolve(File(task.fileName).name)
        outputFileName = tmpFile.path
        CustomFileDownloader.stop(tmpFile)
        handleSuccessfulDownload(task.also {
            it.mId = taskId
            it.filePath = outputFileName
            it.setIsLive(true)
        }, tmpFile)
    }

    override fun finishWork(item: VideoTaskItem?) {
        AppLogger.d("FINISHING... ${item?.filePath} $item")

        if (getDone()) {
            // Already resumed or finishing
            AppLogger.w("finishWork called but worker is already done. Ignoring.")
            return
        }

        val taskId = item?.mId ?: run {
            AppLogger.d("SMTH WRONG, taskId is NULL in finishWork $item")
            if (!getDone()) {
                setDone()
                getContinuation().resume(Result.failure())
            }
            return
        }

        CustomRegularDownloader.deleteHeadersStringFromSharedPreferences(applicationContext, taskId)

        try {
            val notificationData = notificationsHelper.createNotificationBuilder(item)
            showNotificationFinal(notificationData.first, notificationData.second)

            val result =
                if (item.taskState == VideoTaskState.ERROR) Result.failure() else Result.success()

            val progressInfo = if (item.taskState == VideoTaskState.SUCCESS && !fileMovedSuccess) {
                item.taskState = VideoTaskState.ERROR
                "Error Moving File"
            } else {
                item.errorMessage ?: "Error"
            }

            saveProgress(item.mId, progressCached, item.taskState, progressInfo)

            setDone()
            getContinuation().resume(result)
        } catch (e: Throwable) {
            AppLogger.e("FINISHING UNEXPECTED ERROR $item ${e.message} ${e.printStackTrace()}")
            if (!getDone()) {
                setDone()
                try {
                    getContinuation().resume(Result.failure())
                } catch (ignored: IllegalStateException) {
                    // Ignored because another thread might have failed it first
                }
            }
        }
    }

    private fun handleSuccessfulDownload(item: VideoTaskItem, sourcePath: File) {
        if (outputFileName == null) {
            AppLogger.d("Output file name is NULL")
            return
        }

        AppLogger.d("handleSuccessfulDownload started for item: ${item.mId}")
        val target = fixFileName(File(fileUtil.folderDir, File(outputFileName!!).name).path)

        if (!sourcePath.exists()) {
            AppLogger.w("Source file not exists in handleSuccessfulDownload: $sourcePath")
            return finishWork(item.also {
                it.taskState = VideoTaskState.ERROR
                it.errorMessage = "Downloaded file disappeared"
            })
        }

        try {
            var isProcessFfmpeg: Boolean = sharedPrefHelper.getIsProcessDownloadFfmpeg()
            if (!isProcessFfmpeg) {
                val isOnlyLive = sharedPrefHelper.getIsProcessOnlyLiveDownloadFfmpeg()
                isProcessFfmpeg = isOnlyLive && item.isLive
            }

            var processedUri: Uri? = null

            val isFlv = item.isLive && item.url.contains(".flv", ignoreCase = true)
            if (isProcessFfmpeg || isFlv) {
                val startProgress = Progress(0, sourcePath.length())
                showProgressProcessing(item, startProgress)
                saveProgress(
                    item.mId,
                    startProgress,
                    VideoTaskState.PENDING,
                    "Ffmpeg processing..."
                )
                AppLogger.d("START FFMPEG PROCESSING... $sourcePath")

                // Determine if it's an FLV stream
                AppLogger.d("IS FLV: $isFlv")
                // The FfmpegProcessor call is now correctly blocking this thread
                processedUri = FfmpegProcessor.getInstance()
                    .processDownload(sourcePath.toUri(), isFlv) { percents ->
                        val percentInt = percents
                        if (percentInt in 1..99) {
                            val currentProgress = Progress(
                                (sourcePath.length() * percents / 100f).toLong(),
                                sourcePath.length()
                            )
                            showProgressProcessing(item, currentProgress)
                        }
                    }
                AppLogger.d("END FFMPEG PROCESSING... $processedUri")
                if (processedUri == null) {
                    throw Error("FFmpeg processing failed")
                }
                val endProgress = Progress(sourcePath.length(), sourcePath.length())
                showProgressProcessing(item, endProgress)
                saveProgress(
                    item.mId,
                    endProgress,
                    VideoTaskState.PREPARE,
                    "Ffmpeg processing success!"
                )
            }

            val finalSource = processedUri ?: sourcePath.toUri()
            AppLogger.d("START MOVING... $finalSource -> $target")
            fileMovedSuccess =
                fileUtil.moveMedia(applicationContext, finalSource, File(target).toUri())
            AppLogger.d("END MOVING... fileMovedSuccess: $fileMovedSuccess")

            if (!fileMovedSuccess) {
                throw Error("File Move error")
            } else {
                val finalSize = File(target).length()
                item.apply {
                    taskState = VideoTaskState.SUCCESS
                    filePath = target
                    lineInfo = "Download Success"
                    totalSize = finalSize
                    downloadSize = finalSize
                }
                finalSource.toFile().parentFile?.deleteRecursively()
            }
            finishWork(item)

        } catch (e: Throwable) {
            AppLogger.e("Error during post-processing ${e.printStackTrace()}")
            finishWork(item.also {
                it.taskState = VideoTaskState.ERROR
                it.errorMessage = e.message
            })
        }
    }

    private fun getProgressInfo(taskState: Int): String {
        return when (taskState) {
            VideoTaskState.PAUSE -> "PAUSED"
            VideoTaskState.CANCELED -> "CANCELED"
            else -> "ERROR"
        }
    }

    private fun startDownload(taskItem: VideoTaskItem, headers: Map<String, String>) {
        AppLogger.d("Start download regular: $taskItem headers: $headers")
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)!!
        val url = taskItem.url
        showProgress(taskItem.also { it.mId = taskId }, progressCached)
        val tmpDir = fileUtil.tmpDir.resolve(taskId).apply { mkdirs() }
        outputFileName = tmpDir.resolve(taskItem.fileName).toString()
        val fixedHeaders = decodeCookieHeader(headers)
        updateProgressInfoAndStartDownload(taskItem, taskId, url, fixedHeaders)
    }

    private fun updateProgressInfoAndStartDownload(
        taskItem: VideoTaskItem, taskId: String, url: String, headers: Map<String, String>
    ) {
        saveProgress(taskId, Progress(0, 0), VideoTaskState.PENDING)
        val threadCount = sharedPrefHelper.getRegularDownloaderThreadCount()
        val okHttpClient = proxyOkHttpClient.getProxyOkHttpClient()
        val isForceStreamDownload = sharedPrefHelper.getIsForceStreamDownload()
        CustomFileDownloader(
            URL(url),
            File(outputFileName!!),
            threadCount,
            headers,
            okHttpClient,
            createDownloadListener(taskItem, taskId),
            isForceStreamDownload
        ).download()
    }

    private fun createDownloadListener(taskItem: VideoTaskItem, taskId: String): DownloadListener {
        return object : DownloadListener {
            override fun onSuccess() {
                AppLogger.d("Download Success, proceeding to post-processing for $outputFileName")
                // DO NOT call finishWork here. Instead, trigger the next step.
                handleSuccessfulDownload(taskItem.also {
                    it.taskState = VideoTaskState.SUCCESS
                    it.mId = taskId
                    it.filePath = outputFileName
                }, File(outputFileName!!))
            }

            override fun onFailure(e: Throwable) {
                AppLogger.e("${isStoppedAndSaved.get()}  ${e.message} Download Failed for $outputFileName")
                e.printStackTrace()
                val taskState = when {
                    e.message == CustomFileDownloader.STOPPED && isStoppedAndSaved.get() -> VideoTaskState.SUCCESS // Special case
                    e.message == CustomFileDownloader.STOPPED -> VideoTaskState.PAUSE
                    e.message == CustomFileDownloader.CANCELED -> VideoTaskState.CANCELED
                    else -> if (taskItem.isLive) VideoTaskState.SUCCESS else VideoTaskState.ERROR
                }

                if (taskState == VideoTaskState.SUCCESS) {
                    handleSuccessfulDownload(taskItem.also {
                        it.taskState = VideoTaskState.SUCCESS
                        it.mId = taskId
                        it.filePath = outputFileName
                    }, File(outputFileName!!))
                } else {
                    finishWork(taskItem.also {
                        it.taskState = taskState
                        it.errorMessage = e.message
                        it.mId = taskId
                    })
                }
            }

            override fun onProgressUpdate(downloadedBytes: Long, totalBytes: Long) {
                progressCached = Progress(downloadedBytes, totalBytes)
                val totalBytesFixed =
                    if (downloadedBytes > totalBytes) downloadedBytes else totalBytes
                onProgress(Progress(downloadedBytes, totalBytesFixed), taskItem.also {
                    it.mId = taskId
                    it.filePath = outputFileName
                })
            }

            override fun onChunkProgressUpdate(
                downloadedBytes: Long,
                allBytesChunk: Long,
                chunkIndex: Int
            ) {
            }

            override fun onChunkFailure(e: Throwable, index: CustomFileDownloader.Chunk) {}
        }
    }

    private fun onProgress(progress: Progress, downloadTask: VideoTaskItem) {
        if (getDone()) return

        progressCached = progress

        val currentTime = Date().time
        if (currentTime - lastSavedTime > PROGRESS_UPDATE_INTERVAL) {
            lastSavedTime = currentTime

            val taskItem = VideoTaskItem(downloadTask.url).also {
                it.mId = downloadTask.mId.toString()
                it.downloadSize = downloadTask.downloadSize
                it.fileName = outputFileName?.let { it1 -> File(it1).name } ?: downloadTask.fileName
                it.taskState = VideoTaskState.DOWNLOADING
                it.percent = (progress.currentBytes / progress.totalBytes * 100).toFloat()
            }


            showProgress(taskItem, progress)
            saveProgress(downloadTask.mId, progress, VideoTaskState.DOWNLOADING)
        }
    }

    private fun cancelTask(task: VideoTaskItem) {
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)
        if (taskId == null) {
            finishWorkWithFailureTaskId(task)

            return
        }

        val tmpFile = fileUtil.tmpDir.resolve(taskId).resolve(File(task.fileName).name)
        CustomFileDownloader.cancel(tmpFile)

        finishWork(task.also {
            it.mId = taskId
            it.taskState = VideoTaskState.CANCELED
        })
    }

    private fun pauseTask(task: VideoTaskItem) {
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)

        if (taskId == null) {
            finishWorkWithFailureTaskId(task)

            return
        }

        val tmpFile = fileUtil.tmpDir.resolve(taskId).resolve(File(task.fileName).name)
        CustomFileDownloader.stop(tmpFile)

        finishWork(task.also {
            it.mId = taskId
            it.taskState = VideoTaskState.PAUSE
        })
    }

    private fun showProgressProcessing(taskItem: VideoTaskItem, progress: Progress?) {
        val text = "Processing: ${taskItem.fileName}"
        val totalSize = progress?.totalBytes ?: 0
        val downloadSize = progress?.currentBytes ?: 0

        taskItem.apply {
            lineInfo = text
            taskState = VideoTaskState.PREPARE
            this.totalSize = totalSize
            this.downloadSize = downloadSize
            percent = getPercentFromBytes(downloadSize, totalSize)
        }

        val notificationData = notificationsHelper.createNotificationBuilder(taskItem)
        showLongRunningNotificationAsync(notificationData.first, notificationData.second)
    }


    private fun showProgress(taskItem: VideoTaskItem, progress: Progress?) {
        val text = "Downloading: ${taskItem.fileName}"
        val totalSize = progress?.totalBytes ?: 0
        val downloadSize = progress?.currentBytes ?: 0

        taskItem.apply {
            lineInfo = text
            taskState = VideoTaskState.DOWNLOADING
            this.totalSize = totalSize
            this.downloadSize = downloadSize
            percent = getPercentFromBytes(downloadSize, totalSize)
        }

        val notificationData = notificationsHelper.createNotificationBuilder(taskItem)
        showLongRunningNotificationAsync(notificationData.first, notificationData.second)
    }

    private fun saveProgress(
        taskId: String, progress: Progress, downloadStatus: Int, infoLine: String = ""
    ) {
        if (getDone() && downloadStatus == VideoTaskState.DOWNLOADING) {
            AppLogger.d(
                "saveProgress task returned cause DONE!!! $progress"
            )
            return
        }

        val progressList = progressRepository.getProgressInfos().blockingFirst()
        val dbTask = progressList.find { it.id == taskId }
        if (dbTask?.downloadStatus == VideoTaskState.SUCCESS) {
            return
        }

        val isBytesNoTouch = progress.totalBytes == 0L
        val isNoTouchCurrent = progress.currentBytes == 0L

        if (!isBytesNoTouch && (downloadStatus != VideoTaskState.ERROR)) {
            dbTask?.infoLine = infoLine
            dbTask?.progressTotal = progress.totalBytes
        }

        if (downloadStatus != VideoTaskState.SUCCESS) {
            if (!isNoTouchCurrent) {
                dbTask?.progressDownloaded = progress.currentBytes
            }
        } else {
            if (!isBytesNoTouch) {
                dbTask?.progressDownloaded = dbTask?.progressTotal ?: -1
            }
        }

        dbTask?.downloadStatus = downloadStatus

        val isLive =
            dbTask?.progressTotal == dbTask?.progressDownloaded && downloadStatus == VideoTaskState.DOWNLOADING
        if (dbTask?.isLive != true && isLive) {
            dbTask?.isLive = true
        }

        if (dbTask != null) {
            if (getDone() && downloadStatus == VideoTaskState.DOWNLOADING) {
                AppLogger.d(
                    "saveProgress task returned cause DONE!!! $progress"
                )
            } else {
                progressRepository.saveProgressInfo(dbTask)
            }
        }
    }

    private fun decodeCookieHeader(headers: Map<String, String>): Map<String, String> {
        val fixedHeaders = headers.toMutableMap()
        headers.forEach { (key, value) ->
            if (key == "Cookie") {
                fixedHeaders[key] = String(Base64.decode(value, Base64.DEFAULT))
            }
        }
        return fixedHeaders
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
