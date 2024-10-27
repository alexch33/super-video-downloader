package com.myAllVideoBrowser.util.downloaders.custom_downloader

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.net.toUri
import androidx.work.WorkerParameters
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.GenericDownloadWorkerWrapper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import io.reactivex.rxjava3.core.Flowable
import java.io.File
import java.net.URL
import java.util.Base64
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
        private const val INTERVAL = 1000
    }

    override fun handleAction(
        action: String, task: VideoTaskItem, headers: Map<String, String>, isFileRemove: Boolean
    ) {
        when (action) {
            GenericDownloader.DownloaderActions.DOWNLOAD -> {
                isCanceled = false

                startDownload(task, headers)
            }

            GenericDownloader.DownloaderActions.CANCEL -> { //
                isCanceled = true

                val taskId = inputData.getString(GenericDownloader.TASK_ID_KEY)
                val tmpFile = File("${fileUtil.tmpDir}/$taskId/${File(task.fileName).name}")
                CustomFileDownloader.cancel(tmpFile)

                finishWork(task.also {
                    it.mId = taskId
                    it.taskState = VideoTaskState.CANCELED
                })
            }

            GenericDownloader.DownloaderActions.PAUSE -> {
                isCanceled = false

                val taskId = inputData.getString(GenericDownloader.TASK_ID_KEY)
                val tmpFile = File("${fileUtil.tmpDir}/$taskId/${File(task.fileName).name}")
                CustomFileDownloader.stop(tmpFile)

                finishWork(task.also {
                    it.mId = taskId
                    it.taskState = VideoTaskState.PAUSE
                    it.filePath = task.filePath
                })
            }

            GenericDownloader.DownloaderActions.RESUME -> {
                isCanceled = false
                startDownload(task, headers)
            }
        }
    }

    override fun finishWork(item: VideoTaskItem?) {
        AppLogger.d("FINISHING... ${item?.filePath} $item")
        if (getDone()) {
            getContinuation().resume(Result.success())
        }

        setDone()

        if (item == null) {
            getContinuation().resume(Result.failure())
        }

        val taskId = item?.mId

        if (taskId != null) {
            GenericDownloader.deleteHeadersStringFromSharedPreferences(applicationContext, taskId)
            try {
                if (item.taskState != VideoTaskState.ERROR && item.taskState != VideoTaskState.PAUSE) {
                    val sourcePath = try {
                        File(item.filePath)
                    } catch (e: Throwable) {
                        null
                    }

                    when (item.taskState) {
                        VideoTaskState.CANCELED -> {
                            if (isCanceled) {
                                sourcePath?.delete()
                            }
                        }

                        VideoTaskState.SUCCESS -> {
                            if (outputFileName != null) {
                                val target = fixFileName(
                                    File(
                                        fileUtil.folderDir, File(outputFileName!!).name
                                    ).path
                                )
                                if (sourcePath?.exists() == true) {
                                    try {
                                        AppLogger.d(
                                            "START MOOVING...  $sourcePath  $target"
                                        )
                                        fileMovedSuccess = fileUtil.moveMedia(
                                            applicationContext,
                                            sourcePath.toUri(),
                                            File(target).toUri()
                                        )
                                        AppLogger.d(
                                            "END MOOVING...  $sourcePath  $target"
                                        )
                                        if (!fileMovedSuccess) {
                                            throw Error("File Move error")
                                        } else {
                                            sourcePath.parent?.let { File(it).deleteRecursively() }
                                        }
                                    } catch (e: Throwable) {
                                        finishWork(item.also {
                                            it.mId = taskId
                                            it.taskState = VideoTaskState.ERROR
                                            it.errorMessage = e.message
                                        })
                                        return
                                    }
                                }
                            }
                        }

                        else -> {

                        }
                    }
                    if (item.taskState == VideoTaskState.SUCCESS && !fileMovedSuccess) {
                        item.taskState = VideoTaskState.ERROR

                        saveProgress(
                            taskId,
                            progress = progressCached,
                            downloadStatus = item.taskState,
                            "Error Transfer file"
                        )
                    } else {
                        saveProgress(
                            taskId,
                            progress = progressCached,
                            downloadStatus = item.taskState,
                            item.errorMessage ?: "Error"
                        )
                    }

                } else {
                    val info = when (item.taskState) {
                        VideoTaskState.PAUSE -> {
                            "PAUSED"
                        }

                        VideoTaskState.CANCELED -> {
                            "CANCELED"
                        }

                        else -> {
                            "ERROR"
                        }
                    }
                    saveProgress(
                        taskId, progress = progressCached, downloadStatus = item.taskState, info
                    )
                }

                try {
                    val notificationIdPair =
                        notificationsHelper.createNotificationBuilder(item.also {
                            it.mId = taskId
                        })
                    showNotification(
                        notificationIdPair.first, notificationIdPair.second
                    )
                    if (item.taskState == VideoTaskState.ERROR) {
                        AppLogger.d("FINISHING ERROR  $item")
                        getContinuation().resume(Result.failure())
                    } else {
                        AppLogger.d("FINISHING SUCCESS  $item")

                        getContinuation().resume(Result.success())
                    }
                } catch (_: Exception) {
                    AppLogger.d("FINISHING UNEXPECTED ERROR  $item")
                    getContinuation().resume(Result.failure())
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        } else {
            try {
                AppLogger.d("SMTH WRONG  $item")
                getContinuation().resume(Result.failure())
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun startDownload(taskItem: VideoTaskItem, headers: Map<String, String>) {
        AppLogger.d("Start download regular: $taskItem")


        val taskId = inputData.getString(GenericDownloader.TASK_ID_KEY)!!

        val url = taskItem.url
        showProgress(taskItem.also { it.mId = taskId }, progressCached)

        val tmpDir = File("${fileUtil.tmpDir}/$taskId")
        if (!tmpDir.exists()) {
            tmpDir.mkdir()
        }
        outputFileName = fixFileName("${tmpDir.path}/${taskItem.fileName}")


        val fixedHeaders = headers.toMutableMap()
        for (header in headers) {
            if (header.key == "Cookie") {
                val cookieVal = String(Base64.getDecoder().decode(header.value))
                fixedHeaders[header.key] = cookieVal
            } else {
                continue
            }
        }

        val inf = changeProgressInfoDownloadId(taskId, taskId.hashCode()).blockingFirst()
        saveProgress(
            inf.id, Progress(0, 0), VideoTaskState.PENDING
        )
        val threadCount = sharedPrefHelper.getRegularDownloaderThreadCount()
        proxyController.getClient()?.let { okHttpClient ->
            CustomFileDownloader(URL(url),
                File(outputFileName!!),
                threadCount,
                fixedHeaders,
                okHttpClient,
                object : DownloadListener {
                    override fun onSuccess() {
                        AppLogger.d(
                            "Download Success $outputFileName"
                        )
                        finishWork(taskItem.also {
                            it.taskState = VideoTaskState.SUCCESS
                            it.mId = taskId
                            it.filePath = outputFileName
                        })
                    }

                    override fun onFailure(e: Throwable) {
                        AppLogger.d(
                            "Download Failed  ${e.message} $outputFileName"
                        )
                        val taskState =
                            if (e.message == CustomFileDownloader.STOPPED && !isCanceled) {
                                VideoTaskState.PAUSE
                            } else if (e.message == CustomFileDownloader.CANCELED && isCanceled) {
                                VideoTaskState.CANCELED
                            } else {
                                VideoTaskState.ERROR
                            }

                        finishWork(taskItem.also {
                            it.taskState = taskState
                            it.errorMessage = e.message
                            it.mId = taskId
                        })
                    }

                    override fun onProgressUpdate(downloadedBytes: Long, totalBytes: Long) {
                        progressCached = Progress(downloadedBytes, totalBytes)

                        val ttlBytes =
                            if (downloadedBytes > totalBytes) downloadedBytes else totalBytes
                        onProgress(Progress(downloadedBytes, ttlBytes), taskItem.also {
                            it.mId = taskId
                            it.filePath = outputFileName
                        })
                    }

                    override fun onChunkProgressUpdate(
                        downloadedBytes: Long, allBytesChunk: Long, chunkIndex: Int
                    ) {
                    }

                    override fun onChunkFailure(
                        e: Throwable, index: CustomFileDownloader.Chunk
                    ) {
                        AppLogger.d(
                            "Chunck failure $index ${e.printStackTrace()} "
                        )
                    }
                }).download()
        }
    }

    override fun fixFileName(fileName: String): String {
        val currentFile = File(fileName)
        val hasChunks = currentFile.parentFile?.listFiles()?.firstOrNull {
            it.name.contains("chunk")
        } != null


        return if (!currentFile.exists()) {
            var fixedFileName =
                File("${currentFile.parent}/${currentFile.nameWithoutExtension}.mp4")

            while (fixedFileName.exists()) {
                fixedFileName =
                    File("${fixedFileName.parent}/${fixedFileName.nameWithoutExtension}_copy.mp4")
            }
            fixedFileName.absolutePath
        } else {

            var fixedFileName = if (hasChunks) {
                File("${currentFile.parent}/${currentFile.nameWithoutExtension}.mp4")
            } else {
                File("${currentFile.parent}/${currentFile.nameWithoutExtension}_copy.mp4")
            }


            while (fixedFileName.exists() && !hasChunks) {
                fixedFileName =
                    File("${fixedFileName.parent}/${fixedFileName.nameWithoutExtension}_copy.mp4")
            }
            fixedFileName.absolutePath
        }
    }

    private fun onProgress(
        progress: Progress, downloadTask: VideoTaskItem
    ) {
        if (!getDone()) {
            progressCached = progress

            val currentTime = Date().time
            if ((currentTime - lastSavedTime) > INTERVAL && !getDone()) {
                lastSavedTime = Date().time

                val taskItem = VideoTaskItem(downloadTask.url).also {
                    it.mId = downloadTask.mId.toString()
                    it.downloadSize = downloadTask.downloadSize
                    it.fileName =
                        outputFileName?.let { it1 -> File(it1).name } ?: downloadTask.fileName
                    it.taskState = VideoTaskState.DOWNLOADING
                    it.percent = (progress.currentBytes / progress.totalBytes * 100).toFloat()
                }

                showProgress(taskItem, progress)

                saveProgress(
                    downloadTask.mId, progress, VideoTaskState.DOWNLOADING
                )
            }
        }
    }

    private fun showProgress(
        taskItem: VideoTaskItem, progress: Progress?
    ) {
        val text = "Downloading: ${taskItem.fileName}"

        val totalSize = progress?.totalBytes ?: 0
        val downloadSize = progress?.currentBytes ?: 0
        val percent = taskItem.getPercentFromBytes(downloadSize, totalSize)

        val data = notificationsHelper.createNotificationBuilder(taskItem.also {
            it.mId = taskItem.mId
            it.lineInfo = text
            it.taskState = VideoTaskState.DOWNLOADING
            it.totalSize = totalSize
            it.downloadSize = downloadSize
            it.percent = percent
        })
        showNotification(data.first, data.second)
        showNotificationAsync(data.first, data.second)
    }

    private fun changeProgressInfoDownloadId(oldId: String, newId: Int): Flowable<ProgressInfo> {
        return progressRepository.getProgressInfos().flatMap { list ->
            val result = list.find { it.id == oldId }

            if (result != null) {
                Flowable.just(result)
            } else {
                Flowable.empty()
            }
        }.map {
            it.downloadId = newId.toLong()
            progressRepository.saveProgressInfo(it)
            it
        }
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
}
