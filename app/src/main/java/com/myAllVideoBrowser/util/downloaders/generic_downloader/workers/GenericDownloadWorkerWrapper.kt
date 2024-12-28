package com.myAllVideoBrowser.util.downloaders.generic_downloader.workers

import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.NotificationsHelper
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import io.reactivex.rxjava3.disposables.Disposable
import javax.inject.Inject

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

    @Inject
    lateinit var proxyOkHttpClient: OkHttpProxyClient

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    private var disposable: Disposable? = null

    // Delay of showing final notification after setForegroundAsync(),
    // without it final notification not shown
    private val finalNotificationDelay = 2000L

    fun showNotificationFinal(id: Int, notification: NotificationCompat.Builder) {
        Handler(Looper.getMainLooper()).postDelayed({
            notificationsHelper.showNotification(Pair(id, notification))
        }, finalNotificationDelay)
    }

    fun showLongRunningNotificationAsync(id: Int, notification: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForegroundAsync(
                ForegroundInfo(
                    id, // taskId.hashcode()
                    notification.build(), FOREGROUND_SERVICE_TYPE_DATA_SYNC
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
        action: String, task: VideoTaskItem, headers: Map<String, String>, isFileRemove: Boolean
    ) {
        when (action) {
            GenericDownloader.DownloaderActions.DOWNLOAD -> {
                throw UnsupportedOperationException("Download action is not implemented")
            }

            GenericDownloader.DownloaderActions.CANCEL -> {
                throw UnsupportedOperationException("Download action is not implemented")
            }

            GenericDownloader.DownloaderActions.PAUSE -> {
                throw UnsupportedOperationException("Download action is not implemented")
            }

            GenericDownloader.DownloaderActions.RESUME -> {
                throw UnsupportedOperationException("Resume action is not implemented")
            }
        }
    }

    override fun finishWork(item: VideoTaskItem?) {
        setDone()
        disposable?.dispose()
    }
}
