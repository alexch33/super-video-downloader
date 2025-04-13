package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import android.os.Handler
import android.os.Looper
import androidx.databinding.Observable
import androidx.databinding.ObservableBoolean
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.VideoRepository
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanNotDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateLoading
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import okhttp3.Request
import javax.inject.Inject

class GlobalVideoDetectionModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val baseSchedulers: BaseSchedulers,
    okHttpProxyClient: OkHttpProxyClient,
) : VideoDetectionTabViewModel(videoRepository, baseSchedulers, okHttpProxyClient), IVideoDetector {
    private var lastVerifiedLink: String = ""
    private var lastVerifiedM3u8PointUrl = Pair("", "")

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

    override fun cancelAllCheckJobs() {
        super.cancelAllCheckJobs()

        lastVerifiedLink = ""
    }

    override fun hasCheckLoadingsRegular(): ObservableBoolean {
        return ObservableBoolean(false)
    }

    override fun hasCheckLoadingsM3u8(): ObservableBoolean {
        return ObservableBoolean(false)
    }

    override fun verifyLinkStatus(
        resourceRequest: Request, hlsTitle: String?, isM3u8: Boolean
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

    override fun startVerifyProcess(
        resourceRequest: Request, isM3u8: Boolean, hlsTitle: String?
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
            }.observeOn(baseSchedulers.io).subscribeOn(baseSchedulers.io).subscribe { info ->
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
                        pushNewVideoInfoToAll(info)
                    }
                } else {
                    downloadButtonState.set(DownloadButtonStateCanNotDownload())
                }
            }
    }

    override fun checkRegularMp4(request: Request?): Disposable? {
        if (request == null) {
            return null
        }

        val uriString = request.url.toString()

        if (!uriString.startsWith("http")) {
            return null
        }

        val clearedUrl = uriString.split("?").first().trim()

        if (clearedUrl.contains(filterRegex)) {
            return null
        }

        val headers = try {
            request.headers.toMap().toMutableMap()
        } catch (e: Throwable) {
            mutableMapOf()
        }

        val disposable = io.reactivex.rxjava3.core.Observable.create<Unit> {
            propagateCheckJob(uriString, headers)
            it.onComplete()
        }.subscribeOn(baseSchedulers.io).doOnComplete {
            AppLogger.d("CHECK REGULAR MP4 IN BACKGROUND DONE")
        }.onErrorComplete().doOnError {
            AppLogger.d("Checking IN BACKGROUND ERROR... $clearedUrl")
        }.subscribe()

        return disposable
    }

    override fun pushNewVideoInfoToAll(newInfo: VideoInfo) {
        if (newInfo.formats.formats.isEmpty()) {
            return
        }

        downloadButtonState.set(DownloadButtonStateLoading())
        Handler(Looper.getMainLooper()).postDelayed({
            downloadButtonState.set(DownloadButtonStateCanDownload(newInfo))
        }, 400)
    }

    override fun onStartPage(url: String, userAgentString: String) {

    }

    override fun showVideoInfo() {

    }
}
