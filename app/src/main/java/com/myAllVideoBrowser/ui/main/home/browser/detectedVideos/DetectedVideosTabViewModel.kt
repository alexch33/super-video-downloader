package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import android.net.Uri
import android.webkit.CookieManager
import androidx.databinding.Observable
import androidx.databinding.Observable.OnPropertyChangedCallback
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.VideoInfoWrapper
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.VideoRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonState
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanNotDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateLoading
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabViewModel
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.CookieUtils
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.Response
import java.net.HttpCookie
import java.net.URL
import java.util.concurrent.Executors
import javax.inject.Inject

class DetectedVideosTabViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val baseSchedulers: BaseSchedulers,
    private val okHttpProxyClient: OkHttpProxyClient,
) : BaseViewModel(), IVideoDetector {
    // key: videoInfo.id, value: format - string
    val selectedFormats = ObservableField<Map<String, String>>()

    // key: videoInfo.id, value: title - string
    val formatsTitles = ObservableField<Map<String, String>>()

    val selectedFormatUrl = ObservableField<String>()

    @Volatile
    var m3u8LoadingList = ObservableField<MutableSet<String>>()

    @Volatile
    var regularLoadingList = ObservableField<MutableSet<String>>()

    val showDetectedVideosEvent = SingleLiveEvent<Void?>()

    val videoPushedEvent = SingleLiveEvent<Void?>()

    @Volatile
    var downloadButtonState =
        ObservableField<DownloadButtonState>(DownloadButtonStateCanNotDownload())

    val executorReload = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    var webTabModel: WebTabViewModel? = null
    lateinit var settingsModel: SettingsViewModel
    val detectedVideosList = ObservableField(mutableSetOf<VideoInfo>())

    private val downloadButtonIcon = ObservableInt(R.drawable.invisible_24px)
    private val executorRegular = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Volatile
    private var verifyVideoLinkJobStorage = mutableMapOf<String, Disposable>()

    private val hasCheckLoadingsM3u8 = ObservableBoolean(false)
    private val hasCheckLoadingsRegular = ObservableBoolean(false)

    override fun start() {
        AppLogger.d("START")
        regularLoadingList.addOnPropertyChangedCallback(object :
            OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                val notEmpty = regularLoadingList.get()?.isNotEmpty() == true
                hasCheckLoadingsRegular.set(notEmpty)
                if (notEmpty) {
                    setButtonState(DownloadButtonStateCanNotDownload())
                }
            }
        })
        m3u8LoadingList.addOnPropertyChangedCallback(object :
            OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                val notEmpty = m3u8LoadingList.get()?.isNotEmpty() == true
                hasCheckLoadingsM3u8.set(notEmpty)
                if (notEmpty) {
                    setButtonState(DownloadButtonStateCanNotDownload())
                }
            }
        })
        downloadButtonState.addOnPropertyChangedCallback(object :
            OnPropertyChangedCallback() {
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
        AppLogger.d("STOP")
        cancelAllCheckJobs()
    }

    override fun onStartPage(url: String, userAgentString: String) {
        downloadButtonState.set(DownloadButtonStateCanNotDownload())

        detectedVideosList.set(mutableSetOf())
        cancelAllCheckJobs()

        val req = getRequestWithHeadersForUrl(
            url,
            url,
            userAgentString
        )?.build()

        if (req != null) {
            verifyLinkStatus(req)
        }
    }

    override fun hasCheckLoadingsRegular(): ObservableBoolean {
        return hasCheckLoadingsRegular
    }

    override fun hasCheckLoadingsM3u8(): ObservableBoolean {
        return hasCheckLoadingsM3u8
    }

    override fun showVideoInfo() {
        AppLogger.d("SHOW")
        val state = downloadButtonState.get()

        if (state is DownloadButtonStateCanNotDownload) {
            webTabModel?.getTabTextInput()?.get()?.let {
                if (it.startsWith("http")) {
                    viewModelScope.launch(executorRegular) {
                        onStartPage(
                            it.trim(),
                            webTabModel?.userAgent?.get() ?: BrowserFragment.MOBILE_USER_AGENT
                        )
                    }
                }
            }
        }

        if (detectedVideosList.get()?.isNotEmpty() == true) {
            showDetectedVideosEvent.call()
        }
    }

    override fun verifyLinkStatus(resourceRequest: Request, hlsTitle: String?, isM3u8: Boolean) {
        // TODO list of sites, where youtube dl should be disabled
        if (resourceRequest.url.toString().contains("tiktok.")) {
            return
        }

        val urlToVerify = resourceRequest.url.toString()
        if (isM3u8) {
            startVerifyProcess(resourceRequest, true, hlsTitle)
        } else {
            if (urlToVerify.contains(
                    ".txt"
                )
            ) {
                return
            }
            if (settingsModel.getIsFindVideoByUrl().get()) {
                startVerifyProcess(resourceRequest, false)
            }
        }
    }

    private fun startVerifyProcess(
        resourceRequest: Request, isM3u8: Boolean, hlsTitle: String? = null
    ) {
        val taskUrlCleaned = resourceRequest.url.toString().split("?").firstOrNull()?.trim() ?: ""

        val job = verifyVideoLinkJobStorage[taskUrlCleaned]
        if (job != null && !job.isDisposed || taskUrlCleaned.isEmpty()) {
            return
        }

        val loadings = m3u8LoadingList.get()?.toMutableSet()
        loadings?.add(resourceRequest.url.toString())
        m3u8LoadingList.set(loadings?.toMutableSet())
        setButtonState(DownloadButtonStateLoading())

        verifyVideoLinkJobStorage[taskUrlCleaned] =
            io.reactivex.rxjava3.core.Observable.create { emitter ->
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
                emitter.onComplete()
            }.doOnComplete {
                val url = resourceRequest.url.toString().split("?").firstOrNull()?.trim() ?: ""
                val loadings2 = m3u8LoadingList.get()?.toMutableSet()
                loadings2?.remove(url)
                m3u8LoadingList.set(loadings2?.toMutableSet())
                verifyVideoLinkJobStorage.remove(url)
            }.observeOn(baseSchedulers.computation).subscribeOn(baseSchedulers.videoService)
                .subscribe { info ->
                    if (info.id.isNotEmpty()) {
                        if (info.isM3u8 && !hlsTitle.isNullOrEmpty()) {
                            info.title = hlsTitle
                        }
                        pushNewVideoInfoToAll(info)
                    } else if (info.id.isEmpty()) {
                        setButtonState(DownloadButtonStateCanNotDownload())
                    }
                }
    }

    fun pushNewVideoInfoToAll(newInfo: VideoInfo) {
        if (newInfo.id.isEmpty()) {
            return
        }

        val currentTabUrl = webTabModel?.getTabTextInput()?.get()
        val isTwitch = currentTabUrl?.contains(".twitch.") == true

        if ((isTwitch) && !newInfo.isMaster) {
            return
        }

        val detected = detectedVideosList.get()?.toList() ?: emptyList()
        var contains = false
        if (newInfo.isRegularDownload) {
            for (vid in detected) {
                val one = vid.firstUrlToString
                val searching = newInfo.firstUrlToString
                contains = one == searching
                if (contains) {
                    break
                }
            }
        } else {
            for (vid in detected) {
                for (vF in vid.formats.formats) {
                    for (k in newInfo.formats.formats) {
                        if (vF.url == k.url) {
                            contains = true
                            break
                        }
                    }
                    if (contains) {
                        break
                    }
                }
                if (vid.originalUrl == newInfo.originalUrl) {
                    contains = true
                    break
                }
            }
        }
        if (contains) {
            return
        }

        AppLogger.d("PUSHING $newInfo  to list: \n  ${detectedVideosList.get()}")
        val list = detectedVideosList.get()?.toMutableSet() ?: mutableSetOf()
        list.add(newInfo)
        detectedVideosList.set(list)
        viewModelScope.launch(Dispatchers.Main) {
            videoPushedEvent.call()
        }
        setButtonState(DownloadButtonStateCanDownload(newInfo))
    }

    override fun getDownloadBtnIcon(): ObservableInt {
        return downloadButtonIcon
    }

    override fun checkRegularMp4(request: Request?): Disposable? {
        if (request == null) {
            return null
        }

        val uriString = request.url.toString()

        val isAd = webTabModel?.isAd(uriString) ?: false
        if (!uriString.startsWith("http") || isAd) {
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
            if (request.url.toString().contains(".mp4")) {
                setButtonState(DownloadButtonStateLoading())
            }
            val loadings = regularLoadingList.get()
            loadings?.add(request.url.toString())
            regularLoadingList.set(loadings?.toMutableSet())
            propagateCheckJob(uriString, headers)
            it.onComplete()
        }.subscribeOn(baseSchedulers.io).doOnComplete {
            val loadings = regularLoadingList.get()
            loadings?.remove(request.url.toString())
            regularLoadingList.set(loadings?.toMutableSet())
        }.onErrorComplete().doOnError {
            AppLogger.d("Checking ERROR... $clearedUrl")
        }.subscribe()

        return disposable
    }

    override fun cancelAllCheckJobs() {
        regularLoadingList.set(mutableSetOf())
        m3u8LoadingList.set(mutableSetOf())
        executorReload.cancel()
        executorRegular.cancel()
        verifyVideoLinkJobStorage.forEach { (_, process) ->
            process.dispose()
        }
        verifyVideoLinkJobStorage.clear()
    }

    fun setButtonState(state: DownloadButtonState) {
        when (state) {
            is DownloadButtonStateCanDownload -> {
                downloadButtonState.set(state)
            }

            is DownloadButtonStateCanNotDownload -> {
                val detectedSize = detectedVideosList.get()?.size
                if (detectedSize == null || detectedSize == 0) {
                    val impEl = regularLoadingList.get()?.find { it.contains(".mp4") }
                    if (m3u8LoadingList.get()?.isEmpty() != true || (m3u8LoadingList.get()
                            ?.isEmpty() == true && impEl != null)
                    ) {
                        downloadButtonState.set(DownloadButtonStateLoading())
                    } else {
                        downloadButtonState.set(DownloadButtonStateCanNotDownload())
                    }
                } else {
                    downloadButtonState.set(
                        DownloadButtonStateCanDownload(
                            detectedVideosList.get()?.first()
                        )
                    )
                }
            }

            is DownloadButtonStateLoading -> {
                val list = detectedVideosList.get() ?: emptySet()
                if (list.isEmpty()) {
                    downloadButtonState.set(DownloadButtonStateLoading())
                } else {
                    downloadButtonState.set(DownloadButtonStateCanDownload(list.first()))
                }
            }
        }
    }

    private fun getRequestWithHeadersForUrl(
        url: String,
        originalUrl: String,
        userAgent: String,
        alternativeHeaders: Map<String, String> = emptyMap()
    ): Request.Builder? {
        try {
            val cookies = try {
                CookieManager.getInstance().getCookie(url) ?: CookieManager.getInstance()
                    .getCookie(originalUrl) ?: ""
            } catch (e: Throwable) {
                ""
            }
            val stringBuilder = StringBuilder()
            if (cookies.isNotEmpty()) {
                for (cookie in cookies.split(";")) {
                    val parsedCookies = HttpCookie.parse(cookie)

                    for (httpCookie in parsedCookies) {
                        stringBuilder.append("${httpCookie.name}=${httpCookie.value};")
                    }
                }
            }

            if (alternativeHeaders.isEmpty()) {
                val builder = try {
                    Request.Builder().url(url.trim())
                } catch (e: Exception) {
                    null
                }
                builder?.addHeader("Referer", "https://${Uri.parse(originalUrl).host}/")

                builder?.addHeader("User-Agent", userAgent)

                try {
                    if (cookies.isNotEmpty()) {
                        builder?.addHeader("Cookie", stringBuilder.toString())
                    }
                } catch (e: Exception) {
                    AppLogger.d("Url parse error ${e.message}")
                }
                return builder

            } else {
                val builder = try {
                    Request.Builder().url(url.trim())
                } catch (e: Exception) {
                    null
                }
                builder?.headers(alternativeHeaders.toHeaders())
                if (cookies.isNotEmpty() && alternativeHeaders["Cookie"] == null) {
                    builder?.addHeader("Cookie", stringBuilder.toString())
                }

                return builder
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        return null
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
                            webTabModel?.getTabTextInput()?.get(),
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
                    webTabModel?.getTabTextInput()?.get(),
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
                    title = webTabModel?.currentTitle?.get() ?: "no_title",
                    ext = "mp4",
                    originalUrl = webTabModel?.getTabTextInput()?.get() ?: "",
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
            video.videoInfo?.let { pushNewVideoInfoToAll(it) }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
