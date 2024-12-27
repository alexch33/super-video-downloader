package com.myAllVideoBrowser.util.downloaders.generic_downloader.workers

import android.content.Context
import android.util.Base64
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.google.gson.Gson
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.io.Serializable
import kotlin.coroutines.Continuation
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

abstract class GenericDownloadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    @Volatile
    private lateinit var continuation: Continuation<Result>
    private val fileDir: String = File(
        applicationContext.filesDir.absolutePath, FileUtil.FOLDER_NAME
    ).absolutePath

    @Volatile
    private var isDone: Boolean = false

    abstract fun finishWork(item: VideoTaskItem?)

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    abstract fun createForegroundInfo(task: VideoTaskItem): ForegroundInfo

    abstract fun handleAction(
        action: String, task: VideoTaskItem, headers: Map<String, String>, isFileRemove: Boolean
    )

    open fun fixFileName(fileName: String): String {
        val currentFile = File(fileName)
        if (!currentFile.exists()) return fileName

        var counter = 1
        var fixedFileName: File
        do {
            fixedFileName =
                File(currentFile.parent, "${currentFile.nameWithoutExtension}_cp$counter.mp4")
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
        return suspendCoroutine { continuation ->
            setWorkContinuation(continuation)
            try {
                val task = getTaskFromInput()
                val action = inputData.getString(GenericDownloader.Constants.ACTION_KEY)
                val isFileRemove =
                    inputData.getString(GenericDownloader.Constants.IS_FILE_REMOVE_KEY)
                        .toString() == "true"
                val headers = loadHeaders(task.mId)

                if (action.isNullOrBlank() || task.url == null) {
                    continuation.resumeWithException(IllegalArgumentException("ACTION or TASK is null"))
                    return@suspendCoroutine
                }

                handleAction(action, task, headers, isFileRemove)
            } catch (e: IllegalArgumentException) {
                AppLogger.e("Invalid input: $e")
                Result.failure()
            } catch (e: IOException) {
                AppLogger.e("Download error: $e")
                Result.retry()
            } catch (e: Exception) {
                AppLogger.e("Unexpected error: $e")
                Result.failure()
            }
        }.also {
            afterDone()
        }
    }

    private fun loadHeaders(taskId: String): Map<String, String> {
        val inpHeaders =
            GenericDownloader.getInstance().loadHeadersStringFromSharedPreferences(applicationContext, taskId)
        return inpHeaders?.let {
            try {
                val decodedHeaders =
                    String(Base64.decode(GenericDownloader.getInstance().decompressString(it), Base64.DEFAULT))

                Gson().fromJson(decodedHeaders, Map::class.java) as Map<String, String>
            } catch (e: Exception) {
                emptyMap()
            }
        } ?: emptyMap()
    }

    open fun afterDone() {

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
}


class Progress(var currentBytes: Long, var totalBytes: Long) : Serializable {
    override fun toString(): String {
        return "Progress{currentBytes=$currentBytes, totalBytes=$totalBytes}"
    }
}