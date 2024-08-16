package com.myAllVideoBrowser.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlDownloaderWorker
import java.io.File
import javax.inject.Singleton

@Singleton
class NotificationsHelper(private val context: Context) {
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "NOTIFICATION_CHANNEL_ID_ALL_DOWNLOADER"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel(context)
    }

    fun createNotificationBuilder(task: VideoTaskItem): Pair<Int, NotificationCompat.Builder> {
        val taskPercent = if (task.percentFromBytes == 0F) task.percent else task.percentFromBytes

        val builder = NotificationCompat.Builder(
            context, NOTIFICATION_CHANNEL_ID
        ).setOnlyAlertOnce(true)

        builder.setContentTitle(File(task.fileName).name).setContentText(task.lineInfo)
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(false)
            .setProgress(100, taskPercent.toInt(), false).addAction(notificationActionOpen(false))

        when (task.taskState) {
            VideoTaskState.PREPARE -> {
                builder.setSubText("prepare").setProgress(0, 0, true)
                builder.setOngoing(false).setSmallIcon(android.R.drawable.stat_sys_download_done)
            }

            VideoTaskState.PENDING -> {
                builder.setSubText("pending").setProgress(0, 0, true)
                builder.setOngoing(false).setSmallIcon(android.R.drawable.stat_sys_download_done)
            }

            VideoTaskState.DOWNLOADING -> {
                builder.setSubText("downloading...").setProgress(100, taskPercent.toInt(), false)
                builder.setOngoing(false).setSmallIcon(android.R.drawable.stat_sys_download)
            }

            VideoTaskState.PAUSE -> {
                builder.setSubText("pause")
                builder.setProgress(100, taskPercent.toInt(), false)
                builder.setOngoing(false).setSmallIcon(android.R.drawable.stat_sys_download)
            }

            VideoTaskState.SUCCESS -> {
                builder.clearActions()
                val actionOpenInApp = notificationActionOpen(true)
                val actionWatch = notificationActionWatch(task.fileName)
                val actionWatchIntent = notificationIntentWatch(task.fileName)

                builder.setContentIntent(actionWatchIntent)
                builder.setSubText("success!!!").setProgress(0, 0, false)
                builder.setOngoing(false).setSmallIcon(android.R.drawable.stat_sys_download_done)
                builder.addAction(actionOpenInApp).addAction(actionWatch)
            }

            VideoTaskState.ERROR, VideoTaskState.ENOSPC -> {
                builder.clearActions()
                val action = notificationActionOpen(true)

                builder.setSubText("Error")
                builder.setContentText("Failed " + task.errorMessage)
                    .setProgress(100, taskPercent.toInt(), false)
                builder.setOngoing(false).setSmallIcon(android.R.drawable.stat_sys_download_done)
                builder.addAction(action)
            }

            VideoTaskState.CANCELED -> {
                builder.setSubText("Canceled")
                builder.setProgress(0, 0, false)
                builder.setOngoing(false).setSmallIcon(android.R.drawable.stat_sys_download)
            }

            else -> {}
        }
        hideNotification(task.mId.hashCode() + 1)

        if (task.taskState == VideoTaskState.SUCCESS || task.taskState == VideoTaskState.ERROR || task.taskState == VideoTaskState.CANCELED) {
            hideNotification(task.mId.hashCode())

            return Pair(task.mId.hashCode() + 1, builder)
        }

        return Pair(task.mId.hashCode(), builder)
    }

    fun showNotification(builderPair: Pair<Int, NotificationCompat.Builder>) {
        notificationManager.notify(builderPair.first, builderPair.second.build())
    }

    fun hideNotification(id: Int) {
        notificationManager.cancel(id)
    }


    private fun notificationActionOpen(
        isFinished: Boolean, isError: Boolean = false
    ): NotificationCompat.Action {
        val intent = Intent(
            context, MainActivity::class.java
        )

        intent.putExtra(YoutubeDlDownloaderWorker.IS_FINISHED_DOWNLOAD_ACTION_KEY, isFinished)
        intent.putExtra(YoutubeDlDownloaderWorker.IS_FINISHED_DOWNLOAD_ACTION_ERROR_KEY, isError)


        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getActivity(
                context, if (isFinished) 0 else 2, intent, PendingIntent.FLAG_MUTABLE
            )
        } else {
            PendingIntent.getActivity(
                context, if (isFinished) 0 else 2, intent, PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        return NotificationCompat.Action(
            android.R.drawable.stat_sys_download_done,
            context.resources.getString(R.string.download_open_in_app),
            pendingIntent
        )
    }

    private fun notificationActionWatch(filename: String): NotificationCompat.Action {
        return NotificationCompat.Action(
            android.R.drawable.stat_sys_download_done,
            context.resources.getString(R.string.download_watch_in_app),
            notificationIntentWatch(filename)
        )
    }

    private fun notificationIntentWatch(filename: String): PendingIntent {
        val filenameFixed = File(filename).name
        val intent = Intent(
            context, MainActivity::class.java
        )
        intent.putExtra(YoutubeDlDownloaderWorker.IS_FINISHED_DOWNLOAD_ACTION_KEY, true)
            .putExtra(YoutubeDlDownloaderWorker.DOWNLOAD_FILENAME_KEY, filenameFixed)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getActivity(
                context, 777, intent, PendingIntent.FLAG_MUTABLE
            )
        } else {
            PendingIntent.getActivity(
                context, 777, intent, PendingIntent.FLAG_UPDATE_CURRENT
            )
        }


    }

    private fun createChannel(appContext: Context) {
        // Make a channel if necessary
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create the NotificationChannel, but only on API 26+ because
            // the NotificationChannel class is new and not in the support library
            val name = appContext.applicationInfo.loadLabel(appContext.packageManager)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance)
            channel.setSound(null, null)

            val channelName =
                context.getString(com.myAllVideoBrowser.R.string.app_download_channel_id)
            channel.description = channelName
            // Add the channel
            notificationManager.createNotificationChannel(channel)
        }
    }
}