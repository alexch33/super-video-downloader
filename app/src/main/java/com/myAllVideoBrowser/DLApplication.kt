package com.myAllVideoBrowser

import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.myAllVideoBrowser.di.component.DaggerAppComponent
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.DaggerWorkerFactory
import com.myAllVideoBrowser.util.proxy_utils.ProxyWorker
import com.tencent.mmkv.MMKV
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import dagger.android.AndroidInjector
import dagger.android.DaggerApplication
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttp
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security
import javax.inject.Inject


open class DLApplication : DaggerApplication() {
    companion object {
        const val DEBUG_TAG: String = "YOUTUBE_DL_DEBUG_TAG"
    }

    @Inject
    lateinit var workerFactory: DaggerWorkerFactory

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    @Inject
    lateinit var fileUtil: FileUtil

    private lateinit var appComponent: AndroidInjector<DLApplication>

    override fun applicationInjector(): AndroidInjector<out DaggerApplication> {
        if (!::appComponent.isInitialized) {
            ContextUtils.initApplicationContext(this)
            appComponent = DaggerAppComponent.builder()
                .application(this)
                .build()
        }

        return appComponent
    }

    override fun onCreate() {
        super.onCreate()

        try {
            OkHttp.initialize(this)
        } catch (e: Throwable) {
            AppLogger.e("Failed to initialize OkHttp: ${e.message}")
        }

        initializeFileUtils()

        val file: File = fileUtil.folderDir
        val ctx = applicationContext

        MMKV.initialize(this)

        // this should fix native ssl crash on old devices
        try {
            val provider = Conscrypt.newProvider()
            Security.insertProviderAt(provider, 1)
            AppLogger.i("Conscrypt provider initialized successfully")
        } catch (e: Throwable) {
            AppLogger.e("Failed to insert Conscrypt provider: ${e.message}")
        }

        WorkManager.initialize(
            ctx, Configuration.Builder().setWorkerFactory(workerFactory).build()
        )

        RxJavaPlugins.setErrorHandler { error: Throwable? ->
            AppLogger.e("RxJavaError unhandled $error")
        }

        CoroutineScope(Dispatchers.Default).launch {
            if (!file.exists()) {
                file.mkdirs()
            }

            initializeYoutubeDl()
            updateYoutubeDL()

            startProxyWorker()
        }
    }

    private fun initializeFileUtils() {
        val isExternal = sharedPrefHelper.getIsExternalUse()
        val isAppDir = sharedPrefHelper.getIsAppDirUse()

        FileUtil.IS_EXTERNAL_STORAGE_USE = isExternal
        FileUtil.IS_APP_DATA_DIR_USE = isAppDir
        FileUtil.INITIIALIZED = true
    }

    private fun initializeYoutubeDl() {
        try {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
        } catch (e: YoutubeDLException) {
            AppLogger.e("failed to initialize youtubedl-android $e")
        }
    }

    private fun updateYoutubeDL() {
        try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel._MASTER)
            AppLogger.d("UPDATE_STATUS MASTER: $status")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun startProxyWorker() {
        val isProxyOn = sharedPrefHelper.getIsProxyOn()
        val isDohOn = sharedPrefHelper.getIsDohOn()
        if (!(isProxyOn || isDohOn)) {
            AppLogger.i("Proxy not enabled in settings, skipping WorkManager enqueue")
            WorkManager.getInstance(this).cancelUniqueWork(ProxyWorker.WORK_NAME)
            return
        }

        AppLogger.i("Enqueuing ProxyWorker...")

        val workRequest = OneTimeWorkRequestBuilder<ProxyWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            ProxyWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }
}
