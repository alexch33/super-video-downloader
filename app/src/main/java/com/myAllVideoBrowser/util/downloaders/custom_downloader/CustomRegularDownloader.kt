package com.myAllVideoBrowser.util.downloaders.custom_downloader

import android.content.Context
import android.util.Base64
import androidx.work.*
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object CustomRegularDownloader : GenericDownloader() {
    fun runWorkerTask(
        context: Context,
        info: VideoInfo,
        taskData: OneTimeWorkRequest,
        action: String
    ) {
        if (action == DownloaderActions.PAUSE) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                info.id + DownloaderActions.PAUSE, ExistingWorkPolicy.APPEND_OR_REPLACE, taskData
            )
            return
        }

        if (action == DownloaderActions.CANCEL) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                info.id + DownloaderActions.CANCEL, ExistingWorkPolicy.APPEND_OR_REPLACE, taskData
            )
            return
        }

        if (action == DownloaderActions.STOP_SAVE_ACTION) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                info.id + DownloaderActions.STOP_SAVE_ACTION,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                taskData
            )
            return
        }
    }

    override fun pauseDownload(context: Context, progressInfo: ProgressInfo) {
        val downloadWork = getWorkRequest(progressInfo.videoInfo.id)

        val downloaderData = getDownloadDataFromVideoInfo(progressInfo.videoInfo)
        downloaderData.putString(Constants.ACTION_KEY, DownloaderActions.PAUSE)
        downloadWork.setInputData(downloaderData.build())

        runWorkerTask(
            context,
            progressInfo.videoInfo,
            downloadWork.build(), DownloaderActions.PAUSE
        )
    }

    override fun cancelDownload(context: Context, progressInfo: ProgressInfo, removeFile: Boolean) {
        val downloadWork = getWorkRequest(progressInfo.videoInfo.id)
        DownloaderActions
        val downloaderData = getDownloadDataFromVideoInfo(progressInfo.videoInfo)
        downloaderData.putString(Constants.ACTION_KEY, DownloaderActions.CANCEL)
        downloaderData.putString(Constants.IS_FILE_REMOVE_KEY, removeFile.toString())
        downloadWork.setInputData(downloaderData.build())

        runWorkerTask(
            context,
            progressInfo.videoInfo,
            downloadWork.build(), DownloaderActions.CANCEL
        )
    }

    fun stopAndSaveDownload(context: Context, progressInfo: ProgressInfo) {
        val downloadWork = getWorkRequest(progressInfo.videoInfo.id)
        val downloaderData =
            getDownloadDataFromVideoInfo(progressInfo.videoInfo)
        downloaderData.putString(Constants.ACTION_KEY, DownloaderActions.STOP_SAVE_ACTION)
        downloadWork.setInputData(downloaderData.build())

        runWorkerTask(
            context,
            progressInfo.videoInfo,
            downloadWork.build(),
            DownloaderActions.STOP_SAVE_ACTION
        )
    }

    override fun getDownloadDataFromVideoInfo(videoInfo: VideoInfo): Data.Builder {
        val videoUrl = videoInfo.firstUrlToString
        val headers = videoInfo.downloadUrls.firstOrNull()?.headers
        val headersMap = mutableMapOf<String, String>()

        for (name in headers?.names() ?: emptySet()) {
            headersMap[name] = headers?.get(name) ?: ""
        }

        var fileName = videoInfo.name

        val cookie = headersMap["Cookie"]
        if (cookie != null) {
            headersMap["Cookie"] =
                Base64.encodeToString(cookie.toString().toByteArray(), Base64.DEFAULT)
        }

        val headersForClean = (headersMap as Map<*, *>?)?.let { JSONObject(it).toString() }
        val headersVal = try {
            Base64.encodeToString(headersForClean?.toByteArray(), Base64.DEFAULT)
        } catch (_: Exception) {
            "{}"
        }

        val data = Data.Builder()
        data.putString(Constants.URL_KEY, videoUrl)
        data.putString(Constants.TASK_ID_KEY, videoInfo.id)

        val zipHeaders = compressString(headersVal)
        AppLogger.d("superZip ${zipHeaders.toByteArray().size}  ---- ${headersVal.toByteArray().size}")

        saveStringToSharedPreferences(
            ContextUtils.getApplicationContext(), videoInfo.id, zipHeaders
        )

        data.putString(Constants.TITLE_KEY, videoInfo.title)
        data.putString(Constants.FILENAME_KEY, fileName)

        return data
    }

    override fun getWorkRequest(id: String): OneTimeWorkRequest.Builder {
        return OneTimeWorkRequest.Builder(CustomRegularDownloaderWorker::class.java)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).addTag(id)
    }
}

