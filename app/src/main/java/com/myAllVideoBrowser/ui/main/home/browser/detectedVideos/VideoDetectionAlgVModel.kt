package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.databinding.Observable
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.VideoInfoWrapper
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.VideoRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonState
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanNotDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateLoading
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.CookieUtils
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.Response
import java.net.URL
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

// TODO: @i3po refactoring: remove duplicated code
class VideoDetectionAlgVModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val baseSchedulers: BaseSchedulers,
    private val okHttpProxyClient: OkHttpProxyClient,
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
        if (request == null) {
            return null
        }

        val uriString = request.url.toString()

        if (!uriString.startsWith("http")) {
            return null
        }

        val clearedUrl = uriString.split("?").first().trim()

        if (clearedUrl.contains(Regex("^(.*\\.(apk|html|xml|ico|css|js|png|gif|json|jpg|jpeg|svg|woff|woff2|m3u8|mpd|ts|php|ttf|otf|eot|cur|webp|bmp|tif|tiff|psd|ai|eps|pdf|doc|docx|xls|xlsx|ppt|pptx|csv|md|rtf|vtt|srt|swf|jar|log|txt))?$"))) {
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
            AppLogger.d("CHECK REGULAR MP4 DONE")
        }.onErrorComplete().doOnError {
            AppLogger.d("Checking ERROR... $clearedUrl")
        }.subscribe()

        return disposable
    }

    private fun propagateCheckJob(url: String, headersMap: Map<String, String>) {
        val treshold = settingsModel.videoDetectionTreshold.get()
        var headers = headersMap.toMutableMap()
        val finlUrlPair = try {
            CookieUtils.getFinalRedirectURL(URL(Uri.parse(url).toString()), headers)
        } catch (e: Throwable) {
            null
        } ?: return

        try {
            val cookies = CookieManager.getInstance().getCookie(finlUrlPair.first.toString())
                ?: CookieManager.getInstance().getCookie(url) ?: ""
            if (cookies.isNotEmpty()) {
                headers["Cookie"] = cookies
            }
        } catch (_: Throwable) {

        }

        var respons: Response? = null
        try {
            headers = finlUrlPair.second.toMap().toMutableMap()
            val requestOk: Request =
                Request.Builder().url(finlUrlPair.first).headers(headers.toHeaders()).build()
            respons = okHttpProxyClient.getProxyOkHttpClient().newCall(requestOk).execute()

            val length = respons.body.contentLength()
            val type = respons.body.contentType()
            respons.body.close()

            if (respons.code == 403 || respons.code == 401) {
                val finlUrlPairEmpty = try {
                    CookieUtils.getFinalRedirectURL(URL(Uri.parse(url).toString()), emptyMap())
                } catch (e: Throwable) {
                    null
                }

                if (finlUrlPairEmpty != null) {
                    val emptyHeadersReq = Request.Builder().url(finlUrlPairEmpty.first).build()
                    val emptyRes =
                        okHttpProxyClient.getProxyOkHttpClient().newCall(emptyHeadersReq).execute()
                    if (emptyRes.body.contentType().toString()
                            .contains("video") && length > treshold
                    ) {
                        setVideoInfoWrapperFromUrl(
                            finlUrlPairEmpty.first,
                            url.hashCode().toString(),
                            finlUrlPairEmpty.second.toMap(),
                            length
                        )
                        emptyRes.close()

                        return
                    }
                }
            }

            val isTikTok = url.contains(".tiktok.com/")
            if (type.toString()
                    .contains("video") && (length > treshold || (isTikTok && length > 1024 * 1024 / 3))
            ) {
                setVideoInfoWrapperFromUrl(
                    finlUrlPair.first,
                    url.hashCode().toString(),
                    finlUrlPair.second.toMap(),
                    length
                )
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            respons?.close()
        }
    }

    private fun setVideoInfoWrapperFromUrl(
        url: URL,
        originalUrl: String?,
        alternativeHeaders: Map<String, String> = emptyMap(),
        contentLength: Long
    ) {
        try {
            if (!url.toString().startsWith("http")) {
                return
            }

            val builder = if (originalUrl != null) {
                Request.Builder().url(url.toString()).headers(alternativeHeaders.toHeaders())
            } else {
                null
            }

            val downloadUrls = listOfNotNull(
                builder?.build()
            )

            val video = VideoInfoWrapper(
                VideoInfo(
                    downloadUrls = downloadUrls,
                    title = "${originalUrl.hashCode()}",
                    ext = "mp4",
                    originalUrl = originalUrl ?: "",
                    // TODO format regular file link
                    formats = VideFormatEntityList(
                        mutableListOf(
                            VideoFormatEntity(
                                formatId = "0",
                                format = ContextUtils.getApplicationContext()
                                    .getString(R.string.player_resolution),
                                ext = "mp4",
                                url = downloadUrls.first().url.toString(),
                                httpHeaders = downloadUrls.first().headers.toMap(),
                                fileSize = contentLength
                            )
                        )
                    ),
                    isRegularDownload = true
                )
            )
            video.videoInfo?.let {
                setCanDownloadState(it)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
