package com.myAllVideoBrowser.util.downloaders.generic_downloader

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.work.*
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.GenericDownloadWorkerWrapper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.Inflater


open class GenericDownloader {
    companion object {
        const val IS_TRY_WITHOUT_HEADERS: String = "IS_TRY_WITHOUT_HEADERS"
        const val ACTION_KEY = "ACTION_KEY"
        const val URL_KEY = "URL_KEY"
        const val FILENAME_KEY = "FILENAME_KEY"
        const val IS_FILE_REMOVE_KEY = "IS_FILE_REMOVE"
        const val TITLE_KEY = "TITLE_KEY"
        const val VIDEO_FORMAT_KEY = "VIDEO_FORMAT_KEY"
        const val ORIGIN_KEY = "ORIGIN_KEY"
        const val TASK_ID_KEY = "TASK_ID"
        const val NOTIFICATION_ID_KEY = "NOTIFICATION_ID"

        fun getDownloaderPreferences(context: Context): SharedPreferences {
            return context
                .getSharedPreferences("custom_downloader", Context.MODE_PRIVATE)
        }

        fun saveHeadersStringToSharedPreferences(
            context: Context,
            workId: String?,
            headersString: String
        ) {
            if (workId == null) {
                return
            }
            val editor = getDownloaderPreferences(context).edit()
            editor.putString(workId, headersString)
            editor.apply()
            AppLogger.d("saveHeadersStringToSharedPreferences  $workId")
        }

        fun loadHeadersStringFromSharedPreferences(context: Context, workId: String?): String? {
            AppLogger.d("loadHeadersStringFromSharedPreferences  $workId")

            if (workId == null) {
                return null
            }

            return getDownloaderPreferences(context).getString(
                workId,
                null
            )
        }

        fun deleteHeadersStringFromSharedPreferences(context: Context, workId: String?) {
            if (workId == null) {
                return
            }

            val editor = getDownloaderPreferences(context).edit()
            editor.remove(workId)
            editor.apply()
            AppLogger.d("deleteHeadersStringFromSharedPreferences  $workId")
        }

        fun compressString(text: String): String {
            val deflater = Deflater()
            deflater.setInput(text.toByteArray())
            deflater.finish()
            val buffer = ByteArray(text.length)
            val byteArrayOutputStream = ByteArrayOutputStream(text.length)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                byteArrayOutputStream.write(buffer, 0, count)
            }
            byteArrayOutputStream.close()

            return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.DEFAULT)
        }

        fun decompressString(compressedText: String): String {
            val compressedData = Base64.decode(compressedText, Base64.DEFAULT)
            val inflater = Inflater()
            inflater.setInput(compressedData)
            val buffer = ByteArray(1024)
            val byteArrayOutputStream = ByteArrayOutputStream(compressedData.size)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                byteArrayOutputStream.write(buffer, 0, count)
            }
            byteArrayOutputStream.close()

            return String(byteArrayOutputStream.toByteArray())
        }

        fun addDownload(context: Context, videoInfo: VideoInfo) {
            val downloadWork = getWorkRequest()

            val downloaderData = getDownloadDataFromVideoInfo(videoInfo)
            downloaderData.putString(ACTION_KEY, DownloaderActions.DOWNLOAD)
            downloadWork.setInputData(downloaderData.build())

            runWorkerTask(
                context,
                videoInfo,
                downloadWork.build()
            )

        }

        fun cancelDownload(context: Context, progressInfo: ProgressInfo, removeFile: Boolean) {
            val downloadWork = getWorkRequest()
            DownloaderActions
            val downloaderData = getDownloadDataFromVideoInfo(progressInfo.videoInfo)
            downloaderData.putString(ACTION_KEY, DownloaderActions.CANCEL)
            downloaderData.putString(IS_FILE_REMOVE_KEY, removeFile.toString())
            downloadWork.setInputData(downloaderData.build())

            runWorkerTask(
                context,
                progressInfo.videoInfo,
                downloadWork.build()
            )
        }

        fun pauseDownload(context: Context, progressInfo: ProgressInfo) {
            val downloadWork = getWorkRequest()

            val downloaderData = getDownloadDataFromVideoInfo(progressInfo.videoInfo)
            downloaderData.putString(ACTION_KEY, DownloaderActions.PAUSE)
            downloadWork.setInputData(downloaderData.build())

            runWorkerTask(
                context,
                progressInfo.videoInfo,
                downloadWork.build()
            )
        }

        fun resumeDownload(context: Context, progressInfo: ProgressInfo) {
            val downloadWork = getWorkRequest()

            val downloaderData = getDownloadDataFromVideoInfo(progressInfo.videoInfo)
            downloaderData.putString(ACTION_KEY, DownloaderActions.RESUME)
            downloadWork.setInputData(downloaderData.build())

            runWorkerTask(
                context,
                progressInfo.videoInfo,
                downloadWork.build()
            )
        }

        private fun runWorkerTask(context: Context, info: VideoInfo, taskData: OneTimeWorkRequest) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(info.firstUrlToString.hashCode().toString())
            WorkManager.getInstance(context).enqueueUniqueWork(
                info.firstUrlToString.hashCode().toString(),
                ExistingWorkPolicy.REPLACE,
                taskData
            )
        }

        private fun getDownloadDataFromVideoInfo(videoInfo: VideoInfo): Data.Builder {
           throw UnsupportedOperationException("getDownloadDataFromVideoInfo not implemented")
        }


        private fun getWorkRequest(): OneTimeWorkRequest.Builder {
            return OneTimeWorkRequest.Builder(GenericDownloadWorkerWrapper::class.java)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
        }
    }

    class DownloaderActions {
        companion object {
            const val DOWNLOAD = "DOWNLOAD"
            const val PAUSE = "PAUSE"
            const val CANCEL = "CANCEL"
            const val RESUME = "RESUME"
        }
    }
}

