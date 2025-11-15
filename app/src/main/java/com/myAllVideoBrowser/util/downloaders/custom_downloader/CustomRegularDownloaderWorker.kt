package com.myAllVideoBrowser.util.downloaders.custom_downloader

import android.content.Context
import android.util.Base64
import androidx.core.net.toUri
import androidx.work.WorkerParameters
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.GenericDownloadWorkerWrapper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlDownloaderWorker.Companion.STOP_SAVE_ACTION
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

    companion object {
        var isCanceled: Boolean = false
        private const val PROGRESS_UPDATE_INTERVAL = 1000
    }

    override fun handleAction(
        action: String, task: VideoTaskItem, headers: Map<String, String>, isFileRemove: Boolean
    ) {
        when (action) {
            GenericDownloader.DownloaderActions.DOWNLOAD -> {
                isCanceled = false

                startDownload(task, headers)
            }

            GenericDownloader.DownloaderActions.CANCEL -> {
                isCanceled = true

                cancelTask(task)
            }

            GenericDownloader.DownloaderActions.PAUSE -> {
                isCanceled = false

                pauseTask(task)
            }

            GenericDownloader.DownloaderActions.RESUME -> {
                isCanceled = false

                startDownload(task, headers)
            }

            STOP_SAVE_ACTION -> {
                isCanceled = false

                stopAndSave(task)
            }
        }
    }

    private fun stopAndSave(task: VideoTaskItem) {
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)

        if (taskId == null) {
            finishWorkWithFailureTaskId(task)

            return
        }

        val tmpFile = fileUtil.tmpDir.resolve(taskId).resolve(File(task.fileName).name)
        CustomFileDownloader.stop(tmpFile)

        finishWork(task.also {
            it.mId = taskId
            it.taskState = VideoTaskState.SUCCESS
        })
    }

    override fun finishWork(item: VideoTaskItem?) {
        AppLogger.d("FINISHING... ${item?.filePath} $item")

        if (getDone()) {
            getContinuation().resume(Result.success())
            return
        }

        setDone()

        val taskId = item?.mId ?: run {
            AppLogger.d("SMTH WRONG, taskId is NULL  $item")
            getContinuation().resume(Result.failure())
            return
        }

        CustomRegularDownloader.deleteHeadersStringFromSharedPreferences(applicationContext, taskId)

        try {
            handleTaskCompletion(item)

            val notificationData = notificationsHelper.createNotificationBuilder(item)
            showNotificationFinal(notificationData.first, notificationData.second)

            val result = if (item.taskState == VideoTaskState.ERROR) {
                Result.failure()
            } else {
                Result.success()
            }
            getContinuation().resume(result)
        } catch (e: Throwable) {
            AppLogger.d("FINISHING UNEXPECTED ERROR  $item  $e")
            try {
                getContinuation().resume(Result.failure())
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun handleTaskCompletion(item: VideoTaskItem) {
        if (item.taskState in listOf(VideoTaskState.ERROR, VideoTaskState.PAUSE)) {
            saveProgress(item.mId, progressCached, item.taskState, getProgressInfo(item.taskState))
            return
        }

        val sourcePath = File(item.filePath)

        when (item.taskState) {
            VideoTaskState.CANCELED -> if (isCanceled) sourcePath.delete()
            VideoTaskState.SUCCESS -> handleSuccessfulDownload(item, sourcePath)
            else -> {} // No action needed for other states
        }

        val progressInfo = if (item.taskState == VideoTaskState.SUCCESS && !fileMovedSuccess) {
            item.taskState = VideoTaskState.ERROR
            "Error Transfer file"
        } else {
            item.errorMessage ?: "Error"
        }

        saveProgress(item.mId, progressCached, item.taskState, progressInfo)
    }

    private fun handleSuccessfulDownload(item: VideoTaskItem, sourcePath: File) {
        if (outputFileName == null) {
            AppLogger.d("Output file name is NULL")
            return
        }

        val target = fixFileName(File(fileUtil.folderDir, File(outputFileName!!).name).path)

        if (sourcePath.exists()) {
            try {
                AppLogger.d("START MOOVING...  $sourcePath  $target")
                fileMovedSuccess =
                    fileUtil.moveMedia(applicationContext, sourcePath.toUri(), File(target).toUri())
                AppLogger.d("END MOOVING...  $sourcePath  $target  fileMovedSuccess: $fileMovedSuccess")

                if (!fileMovedSuccess) {
                    throw Error("File Move error")
                } else {
                    sourcePath.parentFile?.deleteRecursively()
                }
            } catch (e: Throwable) {
                finishWork(item.also {
                    it.taskState = VideoTaskState.ERROR
                    it.errorMessage = e.message
                })
            }
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
        AppLogger.d("Start download regular: $taskItem   headers: $headers")

        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)!!
        val url = taskItem.url

        showProgress(taskItem.also { it.mId = taskId }, progressCached)

        val tmpDir = fileUtil.tmpDir.resolve(taskId).apply { mkdirs() }
        outputFileName = tmpDir.resolve(taskItem.fileName).toString()
        val fixedHeaders = decodeCookieHeader(headers)

        updateProgressInfoAndStartDownload(
            taskItem, taskId, url, fixedHeaders
        )
    }

    private fun updateProgressInfoAndStartDownload(
        taskItem: VideoTaskItem, taskId: String, url: String, headers: Map<String, String>
    ) {
        saveProgress(taskId, Progress(0, 0), VideoTaskState.PENDING)

        val threadCount = sharedPrefHelper.getRegularDownloaderThreadCount()
        val okHttpClient = proxyOkHttpClient.getProxyOkHttpClient()

        // for videos not supporting range headers
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
                AppLogger.d("Download Success $outputFileName")
                finishWork(taskItem.also {
                    it.taskState = VideoTaskState.SUCCESS
                    it.mId = taskId
                    it.filePath = outputFileName
                })
            }

            override fun onFailure(e: Throwable) {
                AppLogger.d("Download Failed  ${e.message} $outputFileName")
                val taskState = when {
                    e.message == CustomFileDownloader.STOPPED && !isCanceled -> VideoTaskState.PAUSE
                    e.message == CustomFileDownloader.CANCELED && isCanceled -> VideoTaskState.CANCELED
                    else -> VideoTaskState.ERROR
                }

                finishWork(taskItem.also {
                    it.taskState = taskState
                    it.errorMessage = e.message
                    it.mId = taskId
                })
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
                downloadedBytes: Long, allBytesChunk: Long, chunkIndex: Int
            ) {
            }

            override fun onChunkFailure(e: Throwable, index: CustomFileDownloader.Chunk) {
            }
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

        dbTask?.isLive =
            dbTask.progressTotal == dbTask.progressDownloaded && downloadStatus == VideoTaskState.DOWNLOADING

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
