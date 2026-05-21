package com.myAllVideoBrowser.util.downloaders

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.myAllVideoBrowser.util.AppLogger
import javax.inject.Inject

class QueueWorker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {

    @Inject
    lateinit var systemDownloadManager: SystemDownloadManager


    override fun doWork(): Result {
        AppLogger.d("QueueWorker: Triggering checkQueue from background")
        systemDownloadManager.checkQueue(applicationContext)
        return Result.success()
    }
}