package com.myAllVideoBrowser.util.downloaders.generic_downloader.workers

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.NotificationsHelper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.NotificationReceiver
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import io.reactivex.rxjava3.disposables.Disposable
import javax.inject.Inject
import kotlin.coroutines.resume

open class GenericDownloadWorkerWrapper(
    appContext: Context, workerParams: WorkerParameters
) : GenericDownloadWorker(appContext, workerParams) {
    @Inject
    lateinit var progressRepository: ProgressRepository

    @Inject
    lateinit var fileUtil: FileUtil

    @Inject
    lateinit var notificationsHelper: NotificationsHelper

    @Inject
    lateinit var proxyController: CustomProxyController

    private var disposable: Disposable? = null

    override fun onDownloadPrepare(item: VideoTaskItem?) {
        super.onDownloadPrepare(item)

        GenericDownloader.downloadListener?.onDownloadPrepare(item)
    }

    override fun onDownloadProgress(item: VideoTaskItem?) {
        if (item != null) {
            if (item.taskState == VideoTaskState.SUCCESS) {
                return
            }
        }
        super.onDownloadProgress(item)

        GenericDownloader.downloadListener?.onDownloadProgress(item)
    }

    override fun onDownloadPause(item: VideoTaskItem?) {
        AppLogger.d("Download Task PAUSE!!! $item")

        if (item?.taskState == VideoTaskState.SUCCESS) {
            return
        }

        super.onDownloadPause(item)

        GenericDownloader.downloadListener?.onDownloadPause(item)
    }

    override fun onDownloadError(item: VideoTaskItem?) {
        AppLogger.d("Download Task ERROR!!! $item")
        super.onDownloadError(item)

        GenericDownloader.downloadListener?.onDownloadError(item)
    }

    override fun onDownloadSuccess(item: VideoTaskItem?) {
        super.onDownloadSuccess(item)

        disposable?.dispose()
        disposable = progressRepository.getProgressInfos().subscribe { infos ->
            if (item != null) {
                infos.find { it.id == item.url }
                    ?.let { it1 -> progressRepository.deleteProgressInfo(it1) }
            }
        }

        val builderPair = item?.let { notificationsHelper.createNotificationBuilder(it) }

        if (builderPair != null) {
            applicationContext.sendBroadcast(
                Intent(applicationContext, NotificationReceiver::class.java).setAction(
                    NotificationReceiver.ACTION_SEND_NOTIFICATION
                ).putExtra(NotificationReceiver.EXTRA_ID, item.url.hashCode() + 1)
                    .putExtra(NotificationReceiver.EXTRA_NOTIFICATION, builderPair.second.build())
            )
        }

        GenericDownloader.downloadListener?.onDownloadSuccess(item)

        finishWork(item)
    }

    override fun createForegroundInfo(task: VideoTaskItem): ForegroundInfo {
        val pairData = notificationsHelper.createNotificationBuilder(task)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                pairData.first,
                pairData.second.build(),
                FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(pairData.first, pairData.second.build())
        }
    }

    fun showNotification(id: Int, notification: NotificationCompat.Builder) {
        notificationsHelper.showNotification(Pair(id, notification))
    }

    fun showNotificationAsync(id: Int, notification: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForegroundAsync(
                ForegroundInfo(
                    id, // taskId.hashcode()
                    notification.build(),
                    FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            )
        } else {
            setForegroundAsync(
                ForegroundInfo(
                    id, // taskId.hashcode()
                    notification.build()
                )
            )
        }
    }

    override fun handleAction(
        action: String,
        task: VideoTaskItem,
        headers: Map<String, String>,
        isFileRemove: Boolean
    ) {
        when (action) {
            GenericDownloader.DownloaderActions.DOWNLOAD -> {
                throw Error("Unimplemented")
            }

            GenericDownloader.DownloaderActions.CANCEL -> {
                notificationsHelper.hideNotification(task.url.hashCode())
                getContinuation().resume(Result.success())
            }

            GenericDownloader.DownloaderActions.PAUSE -> {
                getContinuation().resume(Result.success())
            }

            GenericDownloader.DownloaderActions.RESUME -> {
                throw Error("Unimplemented")
            }
        }
    }

    override fun finishWork(item: VideoTaskItem?) {
        setDone()

        disposable?.dispose()
    }
}
