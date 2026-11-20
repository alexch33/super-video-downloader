package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.VideoRepository
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanNotDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateLoading
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.get
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

class GlobalVideoDetectionModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val baseSchedulers: BaseSchedulers,
    okHttpProxyClient: OkHttpProxyClient,
) : VideoDetectionTabViewModel(videoRepository, baseSchedulers, okHttpProxyClient), IVideoDetector {

    @Volatile
    private var lastVerifiedLink: String = ""

    @Volatile
    private var lastVerifiedM3u8PointUrl = Pair("", "")

    override fun start() {
        super.start()
    }

    override fun stop() {
        super.stop()
    }

    override fun cancelAllCheckJobs() {
        super.cancelAllCheckJobs()
        synchronized(this) {
            lastVerifiedLink = ""
            lastVerifiedM3u8PointUrl = Pair("", "")
        }
    }

    override fun hasCheckLoadingsRegular(): LiveData<Boolean> {
        return MutableLiveData(false)
    }

    override fun hasCheckLoadingsM3u8(): LiveData<Boolean> {
        return MutableLiveData(false)
    }

    @Synchronized
    override fun verifyLinkStatus(
        resourceRequest: Request, hlsTitle: String?, isM3u8: Boolean, isMpd: Boolean
    ) {
        val urlToVerify = resourceRequest.url.toString()

        if (lastVerifiedLink != urlToVerify) {
            val currentPageUrl = "${resourceRequest.url}"

            if (isM3u8 || isMpd) {
                if ((currentPageUrl == lastVerifiedM3u8PointUrl.first && lastVerifiedM3u8PointUrl.second != urlToVerify) || currentPageUrl != lastVerifiedM3u8PointUrl.first) {
                    lastVerifiedM3u8PointUrl = Pair(currentPageUrl, urlToVerify)

                    startVerifyProcess(resourceRequest, isM3u8, isMpd, hlsTitle)
                }
            } else {
                if (urlToVerify.contains(".txt")) {
                    return
                }
                lastVerifiedLink = urlToVerify

                if (settingsModel.getIsFindVideoByUrl().get()) {
                    startVerifyProcess(resourceRequest, isM3u8 = false, isMpd = false)
                }
            }
        }
    }

    override fun startVerifyProcess(
        resourceRequest: Request, isM3u8: Boolean, isMpd: Boolean, hlsTitle: String?
    ) {
        val taskUrl = resourceRequest.url.toString()
        if (taskUrl.isEmpty()) return

        val existingJob = verifyVideoLinkJobStorage[taskUrl]
        if (existingJob != null && !existingJob.isDisposed) {
            return
        }

        verifyVideoLinkJobStorage[taskUrl] =
            io.reactivex.rxjava3.core.Observable.create { emitter ->
                setButtonState(DownloadButtonStateLoading())
                val info = try {
                    if (isM3u8 || isMpd) {
                        videoRepository.getVideoInfoBySuperXDetector(
                            resourceRequest,
                            isM3u8,
                            isMpd,
                            settingsModel.isCheckOnAudio.get()
                        )
                    } else {
                        videoRepository.getVideoInfo(
                            resourceRequest,
                            false,
                            settingsModel.isCheckOnAudio.get()
                        )
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    null
                }
                if (info != null) {
                    emitter.onNext(info)
                } else {
                    emitter.onNext(VideoInfo(id = ""))
                }
                emitter.onComplete()
            }.doOnTerminate {
                verifyVideoLinkJobStorage.remove(taskUrl)
            }.observeOn(baseSchedulers.io).subscribeOn(baseSchedulers.io).subscribe { info ->
                val isLastNotEmpty = lastVerifiedLink.isNotEmpty()

                if (info.id.isNotEmpty()) {
                    if (info.isM3u8 && !hlsTitle.isNullOrEmpty()) {
                        info.title = hlsTitle
                    }
                    val state = downloadButtonState.value

                    val shouldSkip =
                        (state is DownloadButtonStateCanDownload && state.info?.isM3u8 == true && state.info.isMaster && isLastNotEmpty) ||
                                (state is DownloadButtonStateCanDownload && state.info?.isRegularDownload != true && info.isRegularDownload) ||
                                (state is DownloadButtonStateLoading && info.isRegularDownload)

                    if (!shouldSkip) {
                        viewModelScope.launch(executorPusher) {
                            pushNewVideoInfoToAll(info)
                        }
                    }
                } else {
                    setButtonState(DownloadButtonStateCanNotDownload())
                }
            }
    }

    override fun checkRegularVideoOrAudio(
        request: Request?,
        isCheckOnAudio: Boolean,
        isCheckOnVideo: Boolean
    ): Disposable? {
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
        } catch (_: Throwable) {
            mutableMapOf()
        }

        val disposable = io.reactivex.rxjava3.core.Observable.create<Unit> {
            propagateCheckJob(uriString, headers, isCheckOnAudio, isCheckOnVideo)
            it.onComplete()
        }.subscribeOn(baseSchedulers.io).doOnComplete {
            AppLogger.d("CHECK REGULAR MP4 IN BACKGROUND DONE")
        }.onErrorComplete().doOnError {
            AppLogger.d("Checking IN BACKGROUND ERROR... $clearedUrl")
        }.subscribe()

        return disposable
    }

    override suspend fun onStartPage(url: String, userAgentString: String) {

    }

    override fun showVideoInfo() {

    }
}
