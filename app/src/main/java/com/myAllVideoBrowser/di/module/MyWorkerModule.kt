package com.myAllVideoBrowser.di.module

import androidx.work.WorkerFactory
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.NotificationsHelper
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.DaggerWorkerFactory
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
class MyWorkerModule {
    @Provides
    @Singleton
    fun workerFactory(
        progressRepository: ProgressRepository,
        fileUtil: FileUtil,
        notificationsHelper: NotificationsHelper,
        proxyController: CustomProxyController,
        okHttpProxyClient: OkHttpProxyClient,
        sharedPrefHelper: SharedPrefHelper
    ): WorkerFactory {
        return DaggerWorkerFactory(
            progressRepository,
            fileUtil,
            notificationsHelper,
            proxyController,
            okHttpProxyClient,
            sharedPrefHelper
        )
    }
}

