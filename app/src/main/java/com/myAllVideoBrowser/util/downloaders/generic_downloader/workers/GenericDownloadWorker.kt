package com.myAllVideoBrowser.util.downloaders.generic_downloader.workers

import android.content.Context
import android.util.Base64
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.google.gson.Gson
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader.DownloaderActions
import com.myAllVideoBrowser.util.proxy_utils.proxy_manager.ProxyManager
import java.io.File
import java.io.IOException
import java.io.Serializable
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

interface DownloadWorkerListener {
    suspend fun onTaskFinished(taskId: String)
}

abstract class GenericDownloadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams), DownloadWorkerListener {
    @Volatile
    private lateinit var continuation: Continuation<Result>
    private val fileDir: String = File(
        applicationContext.filesDir.absolutePath, FileUtil.FOLDER_NAME
    ).absolutePath

    @Volatile
    private var isDone: Boolean = false

    abstract fun finishWork(item: VideoTaskItem?)

    abstract fun handleAction(
        action: String, task: VideoTaskItem, headers: Map<String, String>, isFileRemove: Boolean
    )

    /**
     * Hook for subclasses to perform cleanup when the worker is stopped/cancelled.
     * This is needed because onStopped() is final in CoroutineWorker.
     */
    open fun onDownloadStopped() {
        // To be overridden by subclasses
    }

    open fun fixFileName(fileName: String, isAudioOnlyExtract: Boolean): String {
        var currentFile = File(fileName)
        if (!currentFile.exists()) {
            if (isAudioOnlyExtract && currentFile.extension != "mp3") {
                currentFile = File(currentFile.parent, "${currentFile.nameWithoutExtension}.mp3")
                if (!currentFile.exists()) {
                    return currentFile.absolutePath
                }
            } else {
                return currentFile.absolutePath
            }
        }

        var counter = 1
        var fixedFileName: File
        var ext = File(fileName).extension
        if (isAudioOnlyExtract) {
            ext = "mp3"
        }
        do {
            fixedFileName =
                File(currentFile.parent, "${currentFile.nameWithoutExtension}_cp$counter.$ext")
            counter++
        } while (fixedFileName.exists())

        return fixedFileName.absolutePath
    }

    open fun getTaskFromInput(): VideoTaskItem {
        val url = inputData.getString(GenericDownloader.Constants.URL_KEY)
        val fileName =
            inputData.getString(GenericDownloader.Constants.FILENAME_KEY) ?: url.hashCode()
                .toString()
        val title = inputData.getString(GenericDownloader.Constants.TITLE_KEY)
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)

        return VideoTaskItem(url).apply {
            mId = taskId
            this.title = title
            this.fileName = fileName
            saveDir = fileDir
            filePath = File(saveDir).resolve(fileName).toString()
        }
    }

    override suspend fun doWork(): Result {
        val result = suspendCancellableCoroutine<Result> { continuation ->
            setWorkContinuation(continuation)
            
            continuation.invokeOnCancellation {
                onDownloadStopped()
            }

            try {
                if (!ProxyManager.isProxyRunning()) {
                    AppLogger.d("Proxy process is not running.")
                } else {
                    AppLogger.d("Proxy process is running.")
                }

                val task = getTaskFromInput()
                val action = inputData.getString(GenericDownloader.Constants.ACTION_KEY)
                val isFileRemove =
                    inputData.getString(GenericDownloader.Constants.IS_FILE_REMOVE_KEY)
                        .toString() == "true"
                val headers = loadHeaders(task.mId)

                if (action.isNullOrBlank() || task.url == null) {
                    continuation.resumeWithException(IllegalArgumentException("ACTION or TASK is null"))
                    return@suspendCancellableCoroutine
                }

                handleAction(action, task, headers, isFileRemove)
            } catch (e: IllegalArgumentException) {
                AppLogger.e("Invalid input: $e")
                continuation.resume(Result.failure())
            } catch (e: IOException) {
                AppLogger.e("Download error:- $e")
                continuation.resume(Result.failure())
            } catch (e: Exception) {
                AppLogger.e("Unexpected error: $e")
                continuation.resume(Result.failure())
            }
        }

        afterDone()

        return result
    }

    private fun loadHeaders(taskId: String): Map<String, String> {
        val inpHeaders =
            GenericDownloader.getInstance()
                .loadHeadersStringFromSharedPreferences(applicationContext, taskId)
        return inpHeaders?.let {
            try {
                val decodedHeaders =
                    String(
                        Base64.decode(
                            GenericDownloader.getInstance().decompressString(it),
                            Base64.DEFAULT
                        )
                    )

                Gson().fromJson(decodedHeaders, Map::class.java) as Map<String, String>
            } catch (e: Exception) {
                emptyMap()
            }
        } ?: emptyMap()
    }

    open suspend fun afterDone() {
        AppLogger.d("AFTER DONE ${inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)}")
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)
        val isActionTask =
            taskId?.endsWith(DownloaderActions.PAUSE) == true || taskId?.endsWith(DownloaderActions.CANCEL) == true || taskId?.endsWith(
                DownloaderActions.RESUME
            ) == true || taskId?.endsWith(DownloaderActions.STOP_SAVE_ACTION) == true
        if (taskId != null && !isActionTask) {
            onTaskFinished(taskId)
        }
    }

    @Synchronized
    fun setDone() {
        isDone = true
    }

    @Synchronized
    fun getDone(): Boolean {
        return isDone
    }

    @Synchronized
    fun getContinuation(): Continuation<Result> {
        return this.continuation
    }

    @Synchronized
    private fun setWorkContinuation(continuation: Continuation<Result>) {
        this.continuation = continuation
    }

    protected fun failWork(message: String) {
        AppLogger.e(message)
        try {
            if (!getDone()) {
                setDone()
                getContinuation().resume(Result.failure())
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}

// info field overrides infoLine for VideoTaskItem
class Progress(var currentBytes: Long, var totalBytes: Long, var info: String = "") :
    Serializable {
    override fun toString(): String {
        return "Progress{currentBytes=$currentBytes, totalBytes=$totalBytes, info=$info}"
    }
}