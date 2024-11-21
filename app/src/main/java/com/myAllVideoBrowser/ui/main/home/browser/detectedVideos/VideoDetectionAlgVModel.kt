package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import android.os.Handler
import android.os.Looper
import androidx.databinding.Observable
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.VideoRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonState
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanNotDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateLoading
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import okhttp3.Request
import java.util.concurrent.Executors
import javax.inject.Inject

interface IVideoDetector {
    fun onStartPage(url: String, userAgentString: String)

    fun showVideoInfo()

    fun verifyLinkStatus(
        resourceRequest: Request,
        hlsTitle: String? = null,
        isM3u8: Boolean = false
    )

    fun getDownloadBtnIcon(): ObservableInt

    fun checkRegularMp4(request: Request?): Disposable?

    fun cancelAllCheckJobs()

    fun hasCheckLoadingsRegular(): ObservableBoolean

    fun hasCheckLoadingsM3u8(): ObservableBoolean
}

class VideoDetectionAlgVModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val baseSchedulers: BaseSchedulers,
) : BaseViewModel(), IVideoDetector {
    var downloadButtonState =
        ObservableField<DownloadButtonState>(DownloadButtonStateCanNotDownload())
    val downloadButtonIcon = ObservableInt(R.drawable.refresh_24px)

    lateinit var settingsModel: SettingsViewModel

    private var verifyVideoLinkJobStorage = mutableMapOf<String, Disposable>()
    private var lastVerifiedLink: String = ""
    private var lastVerifiedM3u8PointUrl = Pair("", "")
    private val executorRegular = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val jobPool = mutableListOf<Job>()

    override fun start() {
        downloadButtonState.addOnPropertyChangedCallback(object :
            Observable.OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                when (downloadButtonState.get()) {
                    is DownloadButtonStateCanNotDownload -> downloadButtonIcon.set(R.drawable.refresh_24px)
                    is DownloadButtonStateCanDownload -> downloadButtonIcon.set(R.drawable.ic_download_24dp)
                    is DownloadButtonStateLoading -> {
                        downloadButtonIcon.set(R.drawable.invisible_24px)
                    }
                }
            }
        })
    }

    override fun stop() {
        cancelAllCheckJobs()
    }

    override fun cancelAllCheckJobs() {
        jobPool.forEach { it.cancel() }
        executorRegular.cancel()
        cancelAllVerifyJobs()
    }

    override fun hasCheckLoadingsRegular(): ObservableBoolean {
        return ObservableBoolean(false)
    }

    override fun hasCheckLoadingsM3u8(): ObservableBoolean {
        return ObservableBoolean(false)
    }

    override fun onStartPage(url: String, userAgentString: String) {

    }

    override fun showVideoInfo() {

    }

    override fun verifyLinkStatus(
        resourceRequest: Request,
        hlsTitle: String?,
        isM3u8: Boolean
    ) {
        if (resourceRequest.url.toString().contains("tiktok.")) {
            return
        }

        val urlToVerify = resourceRequest.url.toString()

        if (lastVerifiedLink != urlToVerify) {
            val currentPageUrl = "${resourceRequest.url}"

            if (isM3u8) {
                if ((currentPageUrl == lastVerifiedM3u8PointUrl.first && lastVerifiedM3u8PointUrl.second != urlToVerify) || currentPageUrl != lastVerifiedM3u8PointUrl.first) {
                    lastVerifiedM3u8PointUrl = Pair(currentPageUrl, urlToVerify)

                    startVerifyProcess(resourceRequest, true, hlsTitle)
                }
            } else {
                if (urlToVerify.contains(
                        ".txt"
                    )
                ) {
                    return
                }
                lastVerifiedLink = urlToVerify

                if (settingsModel.getIsFindVideoByUrl().get()) {
                    startVerifyProcess(resourceRequest, false)
                }
            }
        }
    }

    override fun getDownloadBtnIcon(): ObservableInt {
        return downloadButtonIcon
    }

    private fun startVerifyProcess(
        resourceRequest: Request, isM3u8: Boolean, hlsTitle: String? = null
    ) {
        val job = verifyVideoLinkJobStorage[resourceRequest.url.toString()]
        if (job != null && !job.isDisposed) {
            return
        }

        verifyVideoLinkJobStorage[resourceRequest.url.toString()] =
            io.reactivex.rxjava3.core.Observable.create { emitter ->
                downloadButtonState.set(DownloadButtonStateLoading())
                val info = try {
                    videoRepository.getVideoInfo(resourceRequest, isM3u8)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    null
                }
                if (info != null) {
                    emitter.onNext(info)
                } else {
                    emitter.onNext(VideoInfo(id = ""))
                }
            }.observeOn(baseSchedulers.io).subscribeOn(baseSchedulers.io)
                .subscribe { info ->
                    val isLastNotEmpty = lastVerifiedLink.isNotEmpty()

                    if (info.id.isNotEmpty()) {
                        if (info.isM3u8 && !hlsTitle.isNullOrEmpty()) {
                            info.title = hlsTitle
                        }
                        val state = downloadButtonState.get()
                        if (state is DownloadButtonStateCanDownload) {
                            if (state.info?.isRegularDownload == true) {
                                AppLogger.d(
                                    "Watching set new info state with Regular Download... currentState: $state skippingInfo: $info"
                                )
                            }
                        }
                        if (state is DownloadButtonStateCanDownload && state.info?.isM3u8 == true && state.info.isMaster && isLastNotEmpty || (state is DownloadButtonStateCanDownload && state.info?.isRegularDownload != true && info.isRegularDownload) || state is DownloadButtonStateLoading && info.isRegularDownload) {
                            AppLogger.d(
                                "Skipping set new info state... currentState: $state skippingInfo: $info"
                            )
                        } else {
                            AppLogger.d(
                                "Setting set new info state... state: $state info: $info"
                            )
                            setCanDownloadState(info)
                        }
                    } else {
                        downloadButtonState.set(DownloadButtonStateCanNotDownload())
                    }
                }
    }

    private fun cancelAllVerifyJobs() {
        verifyVideoLinkJobStorage.map { it1 -> it1.value.dispose() }
        verifyVideoLinkJobStorage.clear()

        lastVerifiedLink = ""
    }

    private fun setCanDownloadState(info: VideoInfo) {
        downloadButtonState.set(DownloadButtonStateLoading())
        Handler(Looper.getMainLooper()).postDelayed({
            downloadButtonState.set(DownloadButtonStateCanDownload(info))
        }, 400)
    }

    override fun checkRegularMp4(request: Request?): Disposable? {
        return null
    }
}