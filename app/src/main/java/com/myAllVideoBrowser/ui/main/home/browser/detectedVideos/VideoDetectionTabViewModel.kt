package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import android.webkit.CookieManager
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
import com.myAllVideoBrowser.util.DetectionLogger
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.get
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import java.net.HttpCookie
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

open class VideoDetectionTabViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val baseSchedulers: BaseSchedulers,
    private val okHttpProxyClient: OkHttpProxyClient,
) : BaseViewModel(), IVideoDetector {

    companion object {
        const val MAX_DETECTED_COUNT = 10
    }

    // key: videoInfo.id, value: format - string
    private val _selectedFormats = MutableLiveData<Map<String, String>>(emptyMap())
    val selectedFormats: LiveData<Map<String, String>> = _selectedFormats

    // key: videoInfo.id, value: title - string
    private val _formatsTitles = MutableLiveData<Map<String, String>>(emptyMap())
    val formatsTitles: LiveData<Map<String, String>> = _formatsTitles

    private val _selectedFormatUrl = MutableLiveData("")
    val selectedFormatUrl: LiveData<String> = _selectedFormatUrl

    var initialUrl: String = ""

    private val _m3u8LoadingList = MutableLiveData<Set<String>>(emptySet())

    private val _regularLoadingList = MutableLiveData<Set<String>>(emptySet())

    val showDetectedVideosEvent = SingleLiveEvent<Void?>()

    val videoPushedEvent = SingleLiveEvent<Void?>()

    private val _downloadButtonState =
        MutableLiveData<DownloadButtonState>(DownloadButtonStateCanNotDownload())
    val downloadButtonState: LiveData<DownloadButtonState> = _downloadButtonState

    val executorReload = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val executorPusher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    var webTabModel: WebTabViewModel? = null
    lateinit var settingsModel: SettingsViewModel

    private val _detectedVideosList = MutableLiveData(emptySet<VideoInfo>())
    val detectedVideosList: LiveData<Set<VideoInfo>> = _detectedVideosList

    val filterRegex =
        Regex("^(.*\\.(apk|html|xml|ico|css|js|png|gif|json|jpg|jpeg|svg|woff|woff2|m3u8|mpd|ts|php|ttf|otf|eot|cur|webp|bmp|tif|tiff|psd|ai|eps|pdf|doc|docx|xls|xlsx|ppt|pptx|csv|md|rtf|vtt|srt|swf|jar|log|txt|m4s))?$")

    private val _downloadButtonIcon = MutableLiveData(R.drawable.invisible_24px)
    val downloadButtonIcon: LiveData<Int> = _downloadButtonIcon

    @Volatile
    var verifyVideoLinkJobStorage = ConcurrentHashMap<String, Disposable>()

    private val _hasCheckLoadingsM3u8 = MutableLiveData(false)
    val hasCheckLoadingsM3u8: LiveData<Boolean> = _hasCheckLoadingsM3u8

    private val _hasCheckLoadingsRegular = MutableLiveData(false)
    val hasCheckLoadingsRegular: LiveData<Boolean> = _hasCheckLoadingsRegular

    private val executorRegular = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Volatile
    private var lastUrl = ""

    fun setDetectedVideoList(list: Set<VideoInfo>) {
        _detectedVideosList.postValue(list)
    }

    override fun start() {
        AppLogger.d("START")
        updateButtonIcon(_downloadButtonState.value)
    }

    override fun stop() {
        AppLogger.d("STOP")
        cancelAllCheckJobs()
    }

    override fun onStartPage(url: String, userAgentString: String) {
        if (url == lastUrl) {
            AppLogger.d("onStartPage: URL is the same. Not clearing list.")
            return
        }
        lastUrl = url
        setButtonState(DownloadButtonStateCanNotDownload())

        if (url != initialUrl) {
            AppLogger.d("onStartPage: URL is not initial url. Clearing list.")
            setDetectedVideoList(emptySet())
            cancelAllCheckJobs()
        } else {
            AppLogger.d("onStartPage: URL is initial url. Skipped clearing list.")
        }

        val req = getRequestWithHeadersForUrl(
            url, url, userAgentString
        )?.build()

        if (req != null) {
            verifyLinkStatus(req)
        }
    }

    suspend fun onReloadPage(url: String, userAgentString: String) {
        lastUrl = url

        setDetectedVideoList(emptySet())
        cancelAllCheckJobs()

        delay(10.milliseconds)
        setButtonState(DownloadButtonStateCanNotDownload())

        val req = getRequestWithHeadersForUrl(
            url, url, userAgentString
        )?.build()

        if (req != null) {
            verifyLinkStatus(req)
        }
    }

    override fun hasCheckLoadingsRegular(): LiveData<Boolean> {
        return hasCheckLoadingsRegular
    }

    override fun hasCheckLoadingsM3u8(): LiveData<Boolean> {
        return hasCheckLoadingsM3u8
    }

    override fun showVideoInfo() {
        AppLogger.d("SHOW")
        val state = _downloadButtonState.value

        if (state is DownloadButtonStateCanNotDownload) {
            webTabModel?.tabUrlInput.get()?.let {
                if (it.startsWith("http")) {
                    viewModelScope.launch(executorRegular) {
                        onReloadPage(
                            it.trim(),
                            webTabModel?.userAgent?.get() ?: BrowserFragment.MOBILE_USER_AGENT
                        )
                    }
                }
            }
        }

        if (_detectedVideosList.value?.isNotEmpty() == true) {
            showDetectedVideosEvent.call()
        }
    }

    override fun verifyLinkStatus(
        resourceRequest: Request, hlsTitle: String?, isM3u8: Boolean, isMpd: Boolean
    ) {
        val urlToVerify = resourceRequest.url.toString()
        if (isM3u8 || isMpd) {
            startVerifyProcess(resourceRequest, isM3u8, isMpd, hlsTitle)
        } else {
            if (urlToVerify.contains(".txt")) {
                return
            }
            if (settingsModel.getIsFindVideoByUrl().get()) {
                startVerifyProcess(resourceRequest, isM3u8 = false, isMpd = false)
            }
        }
    }

    open fun startVerifyProcess(
        resourceRequest: Request, isM3u8: Boolean, isMpd: Boolean, hlsTitle: String? = null
    ) {
        val taskUrl = resourceRequest.url.toString().trim()

        val job = verifyVideoLinkJobStorage[taskUrl]
        if (job != null && !job.isDisposed || taskUrl.isEmpty()) {
            return
        }

        addM3u8Loading(resourceRequest.url.toString())

        if (_detectedVideosList.value?.isEmpty() == true) {
            setButtonState(DownloadButtonStateLoading())
        }

        verifyVideoLinkJobStorage[taskUrl] =
            io.reactivex.rxjava3.core.Observable.create { emitter ->
                val info = try {
                    val isUseLegacyDetection = settingsModel.isUseLegacyM3u8Detection.get()
                    if (!isUseLegacyDetection && (isM3u8 || isMpd)) {
                        videoRepository.getVideoInfoBySuperXDetector(
                            resourceRequest, isM3u8, isMpd, settingsModel.isCheckOnAudio.get()
                        )
                    } else {
                        videoRepository.getVideoInfo(
                            resourceRequest, isM3u8 || isMpd, settingsModel.isCheckOnAudio.get()
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
                removeM3u8Loading(resourceRequest.url.toString())
                verifyVideoLinkJobStorage.remove(taskUrl)
            }.observeOn(baseSchedulers.computation).subscribeOn(baseSchedulers.videoService)
                .subscribe { info ->
                    if (info.id.isNotEmpty()) {
                        if (info.isM3u8 && !hlsTitle.isNullOrEmpty()) {
                            info.title = hlsTitle
                        }
                        viewModelScope.launch(executorPusher) {
                            pushNewVideoInfoToAll(info)
                        }
                    } else {
                        setButtonState(DownloadButtonStateCanNotDownload())
                    }
                }
    }

    open fun getOfflineVideoInfo(
        baseUrl: String,
        manifestBody: String,
        isM3u8: Boolean = false,
        isMpd: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        hlsTitle: String? = null
    ) {
        val taskUrl = baseUrl.trim()
        val job = verifyVideoLinkJobStorage[taskUrl]
        if (job != null && !job.isDisposed || taskUrl.isEmpty()) {
            return
        }

        addM3u8Loading(baseUrl)

        if (_detectedVideosList.value?.isEmpty() == true) {
            setButtonState(DownloadButtonStateLoading())
        }

        verifyVideoLinkJobStorage[taskUrl] =
            io.reactivex.rxjava3.core.Observable.create { emitter ->
                val info = try {
                    videoRepository.getVideoInfoOffline(
                        baseUrl, manifestBody, isM3u8, isMpd, headers
                    )
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
                removeM3u8Loading(baseUrl)
                verifyVideoLinkJobStorage.remove(taskUrl)
            }.observeOn(baseSchedulers.computation).subscribeOn(baseSchedulers.videoService)
                .subscribe { info ->
                    if (info.id.isNotEmpty()) {
                        if (info.isM3u8 && !hlsTitle.isNullOrEmpty()) {
                            info.title = hlsTitle
                        }
                        viewModelScope.launch(executorPusher) {
                            pushNewVideoInfoToAll(info)
                        }
                    } else {
                        setButtonState(DownloadButtonStateCanNotDownload())
                    }
                }
    }

    open fun getVideoAudioInfoLocal(
        url: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "",
        contentLength: Long = 0L,
        hlsTitle: String? = null
    ) {
        val taskUrl = url.trim()
        val job = verifyVideoLinkJobStorage[taskUrl]
        if (job != null && !job.isDisposed || taskUrl.isEmpty()) {
            return
        }

        addRegularLoading(url)

        if (_detectedVideosList.value?.isEmpty() == true) {
            setButtonState(DownloadButtonStateLoading())
        }

        verifyVideoLinkJobStorage[taskUrl] =
            io.reactivex.rxjava3.core.Observable.create { emitter ->
                val title = hlsTitle ?: webTabModel?.currentTitle?.get() ?: "no_title"
                val origUrl = webTabModel?.tabUrlInput?.get() ?: ""
                val info = videoRepository.getVideoAudioInfoLocal(
                    url, headers, contentType, contentLength, title, origUrl
                )
                if (info != null) {
                    emitter.onNext(info)
                } else {
                    emitter.onNext(VideoInfo(id = ""))
                }
                emitter.onComplete()
            }.doOnTerminate {
                removeRegularLoading(url)
                verifyVideoLinkJobStorage.remove(taskUrl)
            }.observeOn(baseSchedulers.computation).subscribeOn(baseSchedulers.videoService)
                .subscribe { info ->
                    if (info.id.isNotEmpty()) {
                        viewModelScope.launch(executorPusher) {
                            pushNewVideoInfoToAll(info)
                        }
                    } else {
                        setButtonState(DownloadButtonStateCanNotDownload())
                    }
                }
    }

    open suspend fun pushNewVideoInfoToAll(newInfo: VideoInfo) {
        if (newInfo.formats.allFormats().isEmpty() || newInfo.id.isEmpty()) {
            return
        }

        val detectedVideos = _detectedVideosList.value ?: emptySet()
        val nextSet = detectedVideos.toMutableSet()

        var isDuplicateFound = false

        for (existing in detectedVideos) {
            val type = if (newInfo.isDetectedBySuperX) {
                "SUPERX"
            } else if (!newInfo.isRegularDownload) {
                "YOUTUBE-DLP"
            } else {
                "REGULAR"
            }

            if (isVideoInfoDuplicate(existing, newInfo)) {
                isDuplicateFound = true
                if (newInfo.isMaster && !existing.isMaster) {
                    nextSet.remove(existing)
                    isDuplicateFound = false
                    DetectionLogger.log(
                        type, "REPLACE", existing.originalUrl, "WITH MASTER LIST"
                    )
                    AppLogger.d("REPLACING single rendition with Master playlist for video: $newInfo")
                } else if (!newInfo.isMaster && !existing.isMaster) {
                    val replaceUrl = newInfo.firstDownloadUrl ?: newInfo.originalUrl
                    val existingTimeStamp = existing.timestamp
                    // keep old timestamp for order
                    newInfo.timestamp = existingTimeStamp
                    // keep title
                    newInfo.title = existing.title
                    nextSet.remove(existing)
                    isDuplicateFound = false
                    DetectionLogger.log(type, "REFRESH", replaceUrl)
                    AppLogger.d("REFRESH DUPLICATED VIDEO INFO: $newInfo")
                } else if (newInfo.isMaster && existing.isMaster) {
                    val replaceUrl = newInfo.firstDownloadUrl ?: newInfo.originalUrl
                    val existingTimeStamp = existing.timestamp
                    // keep old timestamp for order
                    newInfo.timestamp = existingTimeStamp
                    // keep title
                    newInfo.title = existing.title
                    nextSet.remove(existing)
                    isDuplicateFound = false
                    DetectionLogger.log(type, "REFRESH MASTER", replaceUrl)
                    AppLogger.d("REFRESH DUPLICATED VIDEO INFO: $newInfo")
                }
            }
        }

        if (!isDuplicateFound) {
            AppLogger.d("PUSHING $newInfo to list")
            nextSet.add(newInfo)

            val sortedList = nextSet.sortedBy { it.timestamp }.takeLast(MAX_DETECTED_COUNT)
            setDetectedVideoList(sortedList.toSet())
            setButtonState(DownloadButtonStateCanDownload(newInfo))

            viewModelScope.launch(Dispatchers.Main) {
                videoPushedEvent.call()
            }
        }
    }

    fun isVideoInfoDuplicate(existing: VideoInfo, newInfo: VideoInfo): Boolean {
        return if (newInfo.isRegularDownload) {
            existing.firstUrlToString == newInfo.firstUrlToString
        } else {
            existing.formats.allFormats().any { existingFormat ->
                newInfo.formats.allFormats().any { newFormat ->
                    existingFormat.url == newFormat.url
                }
            }
        }
    }

    override fun getDownloadBtnIcon(): LiveData<Int> {
        return downloadButtonIcon
    }

    override fun checkRegularVideoOrAudio(
        request: Request?, isCheckOnAudio: Boolean, isCheckOnVideo: Boolean
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
            if (request.url.toString().contains(".mp4")) {
                setButtonState(DownloadButtonStateLoading())
            }
            addRegularLoading(request.url.toString())
            propagateCheckJob(uriString, headers, isCheckOnAudio, isCheckOnVideo)
            it.onComplete()
        }.subscribeOn(baseSchedulers.io).doOnComplete {
            removeRegularLoading(request.url.toString())
        }.onErrorComplete().doOnError {
            DetectionLogger.log("REGULAR", "ERROR", uriString, it.message ?: "unknown")
            AppLogger.d("Checking ERROR... $clearedUrl")
        }.subscribe()

        return disposable
    }

    override fun cancelAllCheckJobs() {
        try {
            clearRegularLoading()
            clearM3u8Loading()
            executorRegular.cancel()

            // Iterate over a snapshot of values to safely dispose
            val jobs = verifyVideoLinkJobStorage.values.toMutableList()
            verifyVideoLinkJobStorage.clear()
            jobs.forEach { it.dispose() }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun setSelectedFormats(formats: Map<String, String>) {
        _selectedFormats.value = formats
    }

    fun setFormatsTitles(titles: Map<String, String>) {
        _formatsTitles.value = titles
    }

    fun setSelectedFormatUrl(url: String) {
        _selectedFormatUrl.value = url
    }

    fun addM3u8Loading(url: String) {
        val current = (_m3u8LoadingList.value ?: emptySet()) + url
        _m3u8LoadingList.postValue(current)
        _hasCheckLoadingsM3u8.postValue(current.isNotEmpty())
        if (current.isNotEmpty()) {
            setButtonState(DownloadButtonStateCanNotDownload())
        }
    }

    fun removeM3u8Loading(url: String) {
        val current = (_m3u8LoadingList.value ?: emptySet()) - url
        _m3u8LoadingList.postValue(current)
        _hasCheckLoadingsM3u8.postValue(current.isNotEmpty())
    }

    fun clearM3u8Loading() {
        _m3u8LoadingList.postValue(emptySet())
        _hasCheckLoadingsM3u8.postValue(false)
    }

    fun addRegularLoading(url: String) {
        val current = (_regularLoadingList.value ?: emptySet()) + url
        _regularLoadingList.postValue(current)
        _hasCheckLoadingsRegular.postValue(current.isNotEmpty())
        if (current.isNotEmpty()) {
            setButtonState(DownloadButtonStateCanNotDownload())
        }
    }

    fun removeRegularLoading(url: String) {
        val current = (_regularLoadingList.value ?: emptySet()) - url
        _regularLoadingList.postValue(current)
        _hasCheckLoadingsRegular.postValue(current.isNotEmpty())
    }

    fun clearRegularLoading() {
        _regularLoadingList.postValue(emptySet())
        _hasCheckLoadingsRegular.postValue(false)
    }

    @Synchronized
    fun setButtonState(state: DownloadButtonState) {
        val targetState = when (state) {
            is DownloadButtonStateCanDownload -> state
            is DownloadButtonStateCanNotDownload -> {
                val list = _detectedVideosList.value?.sortedBy { it.timestamp }
                if (list.isNullOrEmpty()) {
                    DownloadButtonStateCanNotDownload()
                } else {
                    DownloadButtonStateCanDownload(list.firstOrNull())
                }
            }

            is DownloadButtonStateLoading -> {
                val list = _detectedVideosList.value?.sortedBy { it.timestamp } ?: emptyList()
                if (list.isEmpty()) {
                    DownloadButtonStateLoading()
                } else {
                    DownloadButtonStateCanDownload(list.firstOrNull())
                }
            }

            else -> state
        }
        _downloadButtonState.postValue(targetState)
        updateButtonIcon(targetState)
    }

    private fun updateButtonIcon(state: DownloadButtonState?) {
        val icon = when (state) {
            is DownloadButtonStateCanNotDownload -> R.drawable.refresh_24px
            is DownloadButtonStateCanDownload -> R.drawable.ic_download_24dp
            is DownloadButtonStateLoading -> R.drawable.invisible_24px
            else -> R.drawable.refresh_24px
        }
        _downloadButtonIcon.postValue(icon)
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
            } catch (_: Throwable) {
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
                } catch (_: Throwable) {
                    null
                }
                builder?.addHeader("Referer", "https://${originalUrl.toUri().host}/")

                builder?.addHeader("User-Agent", userAgent)

                try {
                    if (cookies.isNotEmpty()) {
                        builder?.addHeader("Cookie", stringBuilder.toString())
                    }
                } catch (e: Throwable) {
                    AppLogger.d("Url parse error ${e.message}")
                }
                return builder

            } else {
                val builder = try {
                    Request.Builder().url(url.trim())
                } catch (_: Throwable) {
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

    fun propagateCheckJob(
        url: String,
        headersMap: Map<String, String>,
        isCheckOnAudio: Boolean,
        isCheckOnVideo: Boolean
    ) {
        DetectionLogger.log("REGULAR", "START", url)
        val threshold = settingsModel.videoDetectionTreshold.get()

        val finalUrlPair = runCatching {
            CookieUtils.getFinalRedirectURL(
                URL(
                    CookieUtils.sanitizeUrlOrText(url).toUri().toString()
                ), headersMap
            )
        }.getOrNull() ?: return

        val cookies = runCatching {
            CookieManager.getInstance().getCookie(finalUrlPair.first.toString())
                ?: CookieManager.getInstance().getCookie(url) ?: ""
        }.getOrNull() ?: ""

        val headers = headersMap.toMutableMap().apply {
            if (cookies.isNotEmpty()) {
                put("Cookie", cookies)
            }
        }

        runCatching {
            val request =
                Request.Builder().url(finalUrlPair.first).headers(headers.toHeaders()).build()

            okHttpProxyClient.getProxyOkHttpClient().newCall(request).execute().use { response ->
                val contentType = response.body.contentType().toString()
                val contentLength = response.body.contentLength()

                if (response.code == 403 || response.code == 401) {
                    DetectionLogger.log("REGULAR", "ERROR", url, "HTTP ${response.code}")
                    handleUnauthorizedResponse(url, threshold, isCheckOnAudio, isCheckOnVideo)
                    return
                }

                val isTikTok = url.contains(".tiktok.com/")
                val isRegularStreamDetectionOn = settingsModel.isForceStreamDetection.get()

                val isVideo = contentType.contains("video", true)
                val isAudio = contentType.contains("audio", true)

                val tikTokThreshold = 1024 * 1024 / 3 // ~333KB
                val isLargeEnoughForTikTok = isTikTok && contentLength > tikTokThreshold
                val isAboveUserThreshold = contentLength > threshold
                val isStreamDetectionOn = isRegularStreamDetectionOn

                val isVideoContent = isVideo && isCheckOnVideo

                val isAudioContent = isAudio && isCheckOnAudio

                if (isVideoContent && (isAboveUserThreshold || isLargeEnoughForTikTok || isStreamDetectionOn)) {
                    DetectionLogger.log("REGULAR", "SUCCESS", url)
                    setMediaInfoWrapperFromUrl(
                        finalUrlPair.first,
                        webTabModel?.tabUrlInput?.get(),
                        finalUrlPair.second.toMap(),
                        contentLength
                    )
                } else if (isAudioContent && (isAboveUserThreshold || isLargeEnoughForTikTok || isStreamDetectionOn)) {
                    DetectionLogger.log("REGULAR AUDIO", "SUCCESS", url)
                    setMediaInfoWrapperFromUrl(
                        finalUrlPair.first,
                        webTabModel?.tabUrlInput?.get(),
                        finalUrlPair.second.toMap(),
                        contentLength,
                        isAudio = true
                    )
                } else {
                    if (isAudioContent || isVideoContent) {
                        val reason = "Below User's Threshold"
                        DetectionLogger.log("REGULAR", "FAILED", url, reason)
                    }
                }
            }
        }.onFailure { e ->
            DetectionLogger.log("REGULAR", "ERROR", url, e.message ?: "unknown")
            e.printStackTrace()
        }
    }

    // THIS BULLSHIT NEEDED FOR SOME INDIAN WEB-SITES
    private fun handleUnauthorizedResponse(
        url: String, threshold: Int, isCheckOnAudio: Boolean, isCheckOnVideo: Boolean
    ) {
        val finalUrlPairEmpty = runCatching {
            CookieUtils.getFinalRedirectURL(
                URL(
                    CookieUtils.sanitizeUrlOrText(url).toUri().toString()
                ), emptyMap()
            )
        }.getOrNull() ?: return

        runCatching {
            DetectionLogger.log("REGULAR", "START", url)
            val request = Request.Builder().url(finalUrlPairEmpty.first).build()
            okHttpProxyClient.getProxyOkHttpClient().newCall(request).execute().use { response ->
                val contentType = response.body.contentType().toString()
                val contentLength = response.body.contentLength()

                when {
                    contentType.contains(
                        "video", true
                    ) && isCheckOnVideo && contentLength > threshold.toLong() -> {
                        DetectionLogger.log("REGULAR", "SUCCESS", url)
                        setMediaInfoWrapperFromUrl(
                            finalUrlPairEmpty.first,
                            webTabModel?.tabUrlInput?.get(),
                            finalUrlPairEmpty.second.toMap(),
                            contentLength
                        )
                    }

                    contentType.contains("audio", true) && isCheckOnAudio -> {
                        DetectionLogger.log("REGULAR", "SUCCESS", url)
                        setMediaInfoWrapperFromUrl(
                            finalUrlPairEmpty.first,
                            webTabModel?.tabUrlInput?.get(),
                            finalUrlPairEmpty.second.toMap(),
                            contentLength,
                            true
                        )
                    }

                    else -> {
                        DetectionLogger.log("REGULAR", "FAILED", url, "unknown unauthorized error")
                    }
                }
            }
        }
    }

    private fun setMediaInfoWrapperFromUrl(
        url: URL,
        originalUrl: String?,
        alternativeHeaders: Map<String, String> = emptyMap(),
        contentLength: Long,
        isAudio: Boolean = false
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
                    ext = if (isAudio) "mp3" else "mp4",
                    originalUrl = webTabModel?.tabUrlInput?.get() ?: "",
                    // TODO format regular file link
                    formats = VideFormatEntityList(
                        mutableListOf(
                            VideoFormatEntity(
                                formatId = "0",
                                format = if (isAudio) "audio" else ContextUtils.getApplicationContext()
                                    .getString(R.string.player_resolution),
                                ext = if (isAudio) "mp3" else "mp4",
                                url = downloadUrls.firstOrNull()?.url?.toString() ?: "",
                                httpHeaders = downloadUrls.firstOrNull()?.headers?.toMap()
                                    ?: emptyMap(),
                                fileSize = contentLength
                            )
                        )
                    ),
                    isRegularDownload = true
                )
            )
            video.videoInfo?.let {
                viewModelScope.launch(executorPusher) {
                    pushNewVideoInfoToAll(it)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
