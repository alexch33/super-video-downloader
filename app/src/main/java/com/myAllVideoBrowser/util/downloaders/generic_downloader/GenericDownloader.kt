package com.myAllVideoBrowser.util.downloaders.generic_downloader

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.work.*
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater


abstract class GenericDownloader {
    companion object {
        private var instance: GenericDownloader? = null

        fun getInstance(): GenericDownloader {
            if (instance == null) {
                instance = object : GenericDownloader() {
                    override fun getDownloadDataFromVideoInfo(videoInfo: VideoInfo): Data.Builder {
                        throw UnsupportedOperationException("getDownloadDataFromVideoInfo not implemented")
                    }

                    override fun getWorkRequest(id: String): OneTimeWorkRequest.Builder {
                        throw UnsupportedOperationException("getWorkRequest not implemented")
                    }
                }
            }

            return instance!!
        }
    }


    class Constants {
        companion object {
            const val ACTION_KEY = "ACTION_KEY"
            const val URL_KEY = "URL_KEY"
            const val FILENAME_KEY = "FILENAME_KEY"
            const val IS_FILE_REMOVE_KEY = "IS_FILE_REMOVE"
            const val TITLE_KEY = "TITLE_KEY"
            const val ORIGIN_KEY = "ORIGIN_KEY"
            const val TASK_ID_KEY = "TASK_ID"
            const val DURATION = "DURATION"
            const val IS_M3U8 = "IS_M3U8"
            const val IS_MPD = "IS_MPD"
            const val SELECTED_FORMAT_ID = "SELECTED_FORMAT_ID"
            const val IS_LIVE = "IS_LIVE"
            const val VIDEO_CODEC = "VIDEO_CODEC"
        }
    }

    class DownloaderActions {
        companion object {
            const val DOWNLOAD = "DOWNLOAD"
            const val PAUSE = "PAUSE"
            const val CANCEL = "CANCEL"
            const val RESUME = "RESUME"
            const val STOP_SAVE_ACTION = "STOP_SAVE_ACTION"
        }
    }

    abstract fun getDownloadDataFromVideoInfo(videoInfo: VideoInfo): Data.Builder


    abstract fun getWorkRequest(id: String): OneTimeWorkRequest.Builder

    open fun startDownload(context: Context, videoInfo: VideoInfo) {
        val downloadWork = getWorkRequest(videoInfo.id)

        val downloaderData = getDownloadDataFromVideoInfo(videoInfo)
        downloaderData.putString(Constants.ACTION_KEY, DownloaderActions.DOWNLOAD)
        downloadWork.setInputData(downloaderData.build())

        runWorkerTask(
            context,
            videoInfo,
            downloadWork.build()
        )

    }

    open fun cancelDownload(context: Context, progressInfo: ProgressInfo, removeFile: Boolean) {
        val downloadWork = getWorkRequest(progressInfo.videoInfo.id)
        DownloaderActions
        val downloaderData = getDownloadDataFromVideoInfo(progressInfo.videoInfo)
        downloaderData.putString(Constants.ACTION_KEY, DownloaderActions.CANCEL)
        downloaderData.putString(Constants.IS_FILE_REMOVE_KEY, removeFile.toString())
        downloadWork.setInputData(downloaderData.build())

        runWorkerTask(
            context,
            progressInfo.videoInfo,
            downloadWork.build()
        )
    }

    open fun pauseDownload(context: Context, progressInfo: ProgressInfo) {
        val downloadWork = getWorkRequest(progressInfo.videoInfo.id)

        val downloaderData = getDownloadDataFromVideoInfo(progressInfo.videoInfo)
        downloaderData.putString(Constants.ACTION_KEY, DownloaderActions.PAUSE)
        downloadWork.setInputData(downloaderData.build())

        runWorkerTask(
            context,
            progressInfo.videoInfo,
            downloadWork.build()
        )
    }

    open fun resumeDownload(context: Context, progressInfo: ProgressInfo) {
        val downloadWork = getWorkRequest(progressInfo.videoInfo.id)

        val downloaderData = getDownloadDataFromVideoInfo(progressInfo.videoInfo)
        downloaderData.putString(Constants.ACTION_KEY, DownloaderActions.RESUME)
        downloadWork.setInputData(downloaderData.build())

        runWorkerTask(
            context,
            progressInfo.videoInfo,
            downloadWork.build()
        )
    }

    fun saveStringToSharedPreferences(
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

    fun runWorkerTask(context: Context, info: VideoInfo, taskData: OneTimeWorkRequest) {
        val op = WorkManager.getInstance(ContextUtils.getApplicationContext())
            .cancelAllWorkByTag(info.id)
        try {
            op.result.get()
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            WorkManager.getInstance(context).enqueueUniqueWork(
                info.id, ExistingWorkPolicy.REPLACE, taskData
            )
        }
    }

    private fun getDownloaderPreferences(context: Context): SharedPreferences {
        return context
            .getSharedPreferences("custom_downloader", Context.MODE_PRIVATE)
    }

}

