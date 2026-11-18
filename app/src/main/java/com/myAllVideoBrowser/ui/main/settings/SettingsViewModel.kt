package com.myAllVideoBrowser.ui.main.settings

import android.os.Environment
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.repository.HistoryRepository
import com.myAllVideoBrowser.data.repository.TopPagesRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.SingleLiveEvent
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

enum class StorageType {
    SD, HIDDEN, HIDDEN_SD
}

class SettingsViewModel @Inject constructor(
    private val sharedPrefHelper: SharedPrefHelper,
    private val historyRepository: HistoryRepository,
    private val topPagesRepository: TopPagesRepository,
) : BaseViewModel() {

    private val _isAskRedirection = MutableLiveData(false)
    val isAskRedirection: LiveData<Boolean> = _isAskRedirection

    private val _regularThreadsCount = MutableLiveData(1)
    val regularThreadsCount: LiveData<Int> = _regularThreadsCount

    private val _m3u8ThreadsCount = MutableLiveData(4)
    val m3u8ThreadsCount: LiveData<Int> = _m3u8ThreadsCount

    private val _videoDetectionTreshold = MutableLiveData(4 * 1024 * 1024)
    val videoDetectionTreshold: LiveData<Int> = _videoDetectionTreshold

    private val _storageType = MutableLiveData(StorageType.SD)
    val storageType: LiveData<StorageType> = _storageType

    val clearCookiesEvent = SingleLiveEvent<Void?>()
    val exportHistoryEvent = SingleLiveEvent<Boolean?>()
    val openImportHistoryEvent = SingleLiveEvent<Void?>()
    val importHistoryEvent = SingleLiveEvent<Boolean?>()
    val exportBookmarksEvent = SingleLiveEvent<Boolean?>()
    val openImportBookmarksEvent = SingleLiveEvent<Void?>()
    val importBookmarksEvent = SingleLiveEvent<Boolean?>()
    val openVideoFolderEvent = SingleLiveEvent<Void?>()
    val openAdBlockSettingsEvent = SingleLiveEvent<Unit?>()

    private val _isDesktopMode = MutableLiveData(false)
    val isDesktopMode: LiveData<Boolean> = _isDesktopMode

    private val _isDarkMode = MutableLiveData(false)
    val isDarkMode: LiveData<Boolean> = _isDarkMode

    private val _isAutoDarkMode = MutableLiveData(true)
    val isAutoDarkMode: LiveData<Boolean> = _isAutoDarkMode

    private val _isLockPortrait = MutableLiveData(false)
    val isLockPortrait: LiveData<Boolean> = _isLockPortrait

    private val _isCheckIfEveryRequestOnM3u8 = MutableLiveData(true)
    val isCheckIfEveryRequestOnM3u8: LiveData<Boolean> = _isCheckIfEveryRequestOnM3u8

    private val _isCheckOnAudio = MutableLiveData(true)
    val isCheckOnAudio: LiveData<Boolean> = _isCheckOnAudio

    private val _isForceStreamDownloading = MutableLiveData(false)
    val isForceStreamDownloading: LiveData<Boolean> = _isForceStreamDownloading

    private val _isForceStreamDetection = MutableLiveData(false)
    val isForceStreamDetection: LiveData<Boolean> = _isForceStreamDetection

    private val _isAlwaysRemuxRegularDownloads = MutableLiveData(false)
    val isAlwaysRemuxRegularDownloads: LiveData<Boolean> = _isAlwaysRemuxRegularDownloads

    private val _isRemuxOnlyLiveRegularDownloads = MutableLiveData(false)
    val isRemuxOnlyLiveRegularDownloads: LiveData<Boolean> = _isRemuxOnlyLiveRegularDownloads

    private val _isInterruptIntreceptedResources = MutableLiveData(false)
    val isInterruptIntreceptedResources: LiveData<Boolean> = _isInterruptIntreceptedResources

    private val _isUseLegacyM3u8Detection = MutableLiveData(false)
    val isUseLegacyM3u8Detection: LiveData<Boolean> = _isUseLegacyM3u8Detection

    private val _isUseExperimentalDetection = MutableLiveData(false)
    val isUseExperimentalDetection: LiveData<Boolean> = _isUseExperimentalDetection

    private val _isAdBlockOn = MutableLiveData(true)
    val isAdBlockOn: LiveData<Boolean> = _isAdBlockOn

    private val _isDownloadSubtitles = MutableLiveData(false)
    val isDownloadSubtitles: LiveData<Boolean> = _isDownloadSubtitles

    private val _queueSize = MutableLiveData(1)
    val queueSize: LiveData<Int> = _queueSize

    private val _isShowVideoActionButton = MutableLiveData(true)
    val isShowVideoActionButton: LiveData<Boolean> = _isShowVideoActionButton

    private val _isShowVideoAlert = MutableLiveData(true)
    val isShowVideoAlert: LiveData<Boolean> = _isShowVideoAlert

    private val _isCheckEveryRequestOnVideo = MutableLiveData(true)
    val isCheckEveryRequestOnVideo: LiveData<Boolean> = _isCheckEveryRequestOnVideo

    private val _isFindVideoByUrl = MutableLiveData(true)
    val isFindVideoByUrl: LiveData<Boolean> = _isFindVideoByUrl

    override fun start() {
        viewModelScope.launch(Dispatchers.IO) {
            val simDownloadsCount = sharedPrefHelper.getMaxSimultaneousDownloads()
            val useExperimental = sharedPrefHelper.getIsUseExperimentalDetection()
            val useLegacy = !useExperimental && sharedPrefHelper.getIsUseYoutubedlpM3u8Detection()
            if (useExperimental && sharedPrefHelper.getIsUseYoutubedlpM3u8Detection()) {
                sharedPrefHelper.setIsUseLegacyM3u8Detection(false)
            }
            val alwaysRemux = sharedPrefHelper.getIsProcessDownloadFfmpeg()
            val remuxOnlyLive = sharedPrefHelper.getIsProcessOnlyLiveDownloadFfmpeg()
            val forceStreamDownload = sharedPrefHelper.getIsForceStreamDownload()
            val forceStreamDetect = sharedPrefHelper.getIsForceStreamDetection()
            val interruptIntercepted = sharedPrefHelper.getIsInterruptInterceptedResources()
            val checkEveryM3u8 = sharedPrefHelper.getIsCheckEveryOnM3u8()
            val isDesktop = sharedPrefHelper.getIsDesktop()
            val showVideoAlert = sharedPrefHelper.isShowVideoAlert()
            val showVideoAction = sharedPrefHelper.isShowActionButton()
            val checkEveryVideo = sharedPrefHelper.isCheckEveryRequestOnVideo()
            val findVideoByUrl = sharedPrefHelper.isFindVideoByUrl()
            val autoDarkMode = sharedPrefHelper.isAutoTheme()
            val isDark = sharedPrefHelper.isDarkMode()
            val regularThreads = sharedPrefHelper.getRegularDownloaderThreadCount()
            val m3u8Threads = sharedPrefHelper.getM3u8DownloaderThreadCount()
            val checkOnAudio = sharedPrefHelper.getIsCheckOnAudio()
            val videoTreshold = sharedPrefHelper.getVideoDetectionTreshold()
            val lockPortrait = sharedPrefHelper.getIsLockPortrait()
            val askRedirection = sharedPrefHelper.getIsAskRedirection()
            val adBlockOn = sharedPrefHelper.getIsAdBlockOn()
            val downloadSubtitles = sharedPrefHelper.getIsDownloadSubtitles()

            val isExternal = sharedPrefHelper.getIsExternalUse()
            val isAppDir = sharedPrefHelper.getIsAppDirUse()
            val sType = if (isExternal && !isAppDir) {
                StorageType.SD
            } else if (isAppDir && isExternal) {
                StorageType.HIDDEN_SD
            } else {
                StorageType.HIDDEN
            }

            withContext(Dispatchers.Main) {
                _queueSize.value = simDownloadsCount
                _isUseLegacyM3u8Detection.value = useLegacy
                _isUseExperimentalDetection.value = useExperimental
                _isAlwaysRemuxRegularDownloads.value = alwaysRemux
                _isRemuxOnlyLiveRegularDownloads.value = remuxOnlyLive
                _isForceStreamDownloading.value = forceStreamDownload
                _isForceStreamDetection.value = forceStreamDetect
                _isInterruptIntreceptedResources.value = interruptIntercepted
                _isCheckIfEveryRequestOnM3u8.value = checkEveryM3u8
                _isDesktopMode.value = isDesktop
                _isShowVideoAlert.value = showVideoAlert
                _isShowVideoActionButton.value = showVideoAction
                _isCheckEveryRequestOnVideo.value = checkEveryVideo
                _isFindVideoByUrl.value = findVideoByUrl
                _isAutoDarkMode.value = autoDarkMode
                _isDarkMode.value = isDark
                setDarkMode(isDark)
                _regularThreadsCount.value = regularThreads
                _m3u8ThreadsCount.value = m3u8Threads
                _isCheckOnAudio.value = checkOnAudio
                _videoDetectionTreshold.value = videoTreshold
                _isLockPortrait.value = lockPortrait
                _isAskRedirection.value = askRedirection
                _storageType.value = sType
                _isAdBlockOn.value = adBlockOn
                _isDownloadSubtitles.value = downloadSubtitles
            }
        }
    }

    override fun stop() {
    }

    fun setIsAskRedirection(isAsk: Boolean) {
        if (_isAskRedirection.value != isAsk) {
            _isAskRedirection.postValue(isAsk)
            viewModelScope.launch(Dispatchers.IO) {
                sharedPrefHelper.setIsAskRedirection(isAsk)
            }
        }
    }

    fun setUseLegacyM3u8Detection(isTurnedOn: Boolean) {
        if (_isUseExperimentalDetection.value == true && isTurnedOn) {
            return
        }
        _isUseLegacyM3u8Detection.postValue(isTurnedOn)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsUseLegacyM3u8Detection(isTurnedOn)
        }
    }

    fun setIsUseExperimentalDetection(isTurnedOn: Boolean) {
        _isUseExperimentalDetection.postValue(isTurnedOn)
        if (isTurnedOn) {
            _isUseLegacyM3u8Detection.postValue(false)
            viewModelScope.launch(Dispatchers.IO) {
                sharedPrefHelper.setIsUseLegacyM3u8Detection(false)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsUseExperimentalDetection(isTurnedOn)
        }
    }

    fun setIsRemuxOnlyLiveRegularDownloads(isTurnedOn: Boolean) {
        _isRemuxOnlyLiveRegularDownloads.postValue(isTurnedOn)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsProcessOnlyLiveDownloadFfmpeg(isTurnedOn)
        }
    }

    fun setIsRemuxOnlyRegularDownloads(isTurnedOn: Boolean) {
        _isAlwaysRemuxRegularDownloads.postValue(isTurnedOn)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsProcessDownloadFfmpeg(isTurnedOn)
        }
    }

    fun setForceStreamDownloading(isTurnedOn: Boolean) {
        _isForceStreamDownloading.postValue(isTurnedOn)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsForceStreamDownload(isTurnedOn)
        }
    }

    fun setForceStreamDetection(isTurnedOn: Boolean) {
        _isForceStreamDetection.postValue(isTurnedOn)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsForceStreamDetection(isTurnedOn)
        }
    }

    fun setIsLockPortrait(isLock: Boolean) {
        _isLockPortrait.postValue(isLock)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsLockPortrait(isLock)
        }
    }

    fun clearCookies() {
        clearCookiesEvent.call()
    }

    fun setIsInterruptInterceptedResources(isTurnedOn: Boolean) {
        _isInterruptIntreceptedResources.postValue(isTurnedOn)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsInterruptInterceptedResources(isTurnedOn)
        }
    }

    fun openVideoFolder() {
        openVideoFolderEvent.call()
    }

    fun openAdBlockSettings() {
        openAdBlockSettingsEvent.call()
    }

    fun getIsFindVideoByUrl(): LiveData<Boolean> {
        return isFindVideoByUrl
    }

    fun setIsFindVideoByUrl(isFind: Boolean) {
        _isFindVideoByUrl.postValue(isFind)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.saveIsFindByUrl(isFind)
        }
    }

    fun setIsCheckIfEveryUrlOnM3u8(isCheck: Boolean) {
        _isCheckIfEveryRequestOnM3u8.postValue(isCheck)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.saveIsCheckEveryOnM3u8(isCheck)
        }
    }

    fun setIsCheckOnAudio(isCheck: Boolean) {
        _isCheckOnAudio.postValue(isCheck)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.saveIsCheckOnAudio(isCheck)
        }
    }

    fun setIsAutoTheme(isChecked: Boolean) {
        _isAutoDarkMode.postValue(isChecked)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsAutoTheme(isChecked)

            val isDark = sharedPrefHelper.isDarkMode()
            withContext(Dispatchers.Main) {
                setIsDarkMode(isDark)
            }
        }
    }

    fun setIsDarkMode(isDark: Boolean) {
        _isDarkMode.postValue(isDark)
        viewModelScope.launch(Dispatchers.IO) {
            delay(10.milliseconds)
            sharedPrefHelper.setIsDarkMode(isDark)
            withContext(Dispatchers.Main) {
                setDarkMode(isDark)
            }
        }
    }

    fun getIsCheckEveryRequestOnMp4Video(): LiveData<Boolean> {
        return isCheckEveryRequestOnVideo
    }

    fun setIsCheckEveryRequestOnVideo(isCheck: Boolean) {
        _isCheckEveryRequestOnVideo.postValue(isCheck)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.saveIsCheck(isCheck)
        }
    }

    fun setIsDesktopMode(isDesktop: Boolean) {
        _isDesktopMode.postValue(isDesktop)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.saveIsDesktop(isDesktop)
        }
    }

    fun getVideoAlertState(): LiveData<Boolean> {
        return isShowVideoAlert
    }

    fun getVideoButtonState(): LiveData<Boolean> {
        return isShowVideoActionButton
    }

    fun setShowVideoAlertOn() {
        _isShowVideoAlert.postValue(true)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsShowVideoAlert(true)
        }
    }

    fun setShowVideoAlertOff() {
        _isShowVideoAlert.postValue(false)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsShowVideoAlert(false)
        }
    }

    fun setShowVideoActionButtonOn() {
        _isShowVideoActionButton.postValue(true)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsShowActionButton(true)
        }
    }

    fun setShowVideoActionButtonOff() {
        _isShowVideoActionButton.postValue(false)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsShowActionButton(false)
        }
    }

    fun setIsFirstStart(isFirstStart: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsFirstStart(isFirstStart)
        }
    }

    fun setIsAdBlockOn(isOn: Boolean) {
        _isAdBlockOn.postValue(isOn)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsAdBlockOn(isOn)
        }
    }

    fun setIsDownloadSubtitles(isDownload: Boolean) {
        _isDownloadSubtitles.postValue(isDownload)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsDownloadSubtitles(isDownload)
        }
    }

    private fun setDarkMode(isDark: Boolean) {
        viewModelScope.launch {
            if (isDark) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }

    private var simThreadsJob: Job? = null
    fun setSimulationsCount(count: Int) {
        val coreCount = Runtime.getRuntime().availableProcessors()
        val simDownloadsCount = count.coerceIn(1, coreCount)

        _queueSize.postValue(simDownloadsCount)

        simThreadsJob?.cancel()
        simThreadsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(10.milliseconds)
            sharedPrefHelper.setMaxSimultaneousDownloads(simDownloadsCount)
        }
    }

    private var m3u8ThreadsJob: Job? = null
    fun setM3u8ThreadsCount(progress: Int) {
        val finalCount = max(1, progress)
        _m3u8ThreadsCount.postValue(finalCount)

        m3u8ThreadsJob?.cancel()
        m3u8ThreadsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(10.milliseconds)
            sharedPrefHelper.setM3u8DownloaderThreadCount(finalCount)
        }
    }

    private var regularThreadsJob: Job? = null
    fun setRegularThreadsCount(progress: Int) {
        val finalCount = max(1, progress)
        _regularThreadsCount.postValue(finalCount)

        regularThreadsJob?.cancel()
        regularThreadsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(10.milliseconds)
            sharedPrefHelper.setRegularDownloaderThreadCount(finalCount)
        }
    }

    fun setDownloadsFolderSdCard() {
        FileUtil.IS_APP_DATA_DIR_USE = false
        FileUtil.IS_EXTERNAL_STORAGE_USE = true

        _storageType.postValue(StorageType.SD)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsExternalUse(true)
            sharedPrefHelper.setIsAppDirUse(false)
        }
    }

    fun setDownloadsFolderHidden() {
        FileUtil.IS_APP_DATA_DIR_USE = true
        FileUtil.IS_EXTERNAL_STORAGE_USE = false

        _storageType.postValue(StorageType.HIDDEN)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsExternalUse(false)
            sharedPrefHelper.setIsAppDirUse(true)
        }
    }

    fun setDownloadsFolderHiddenSdCard() {
        FileUtil.IS_APP_DATA_DIR_USE = true
        FileUtil.IS_EXTERNAL_STORAGE_USE = true

        _storageType.postValue(StorageType.HIDDEN_SD)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsExternalUse(true)
            sharedPrefHelper.setIsAppDirUse(true)
        }
    }

    private var videoDetectionJob: Job? = null
    fun setVideoDetectionTreshold(progress: Int) {
        val finalResult = max(0, progress)
        _videoDetectionTreshold.postValue(finalResult)

        videoDetectionJob?.cancel()
        videoDetectionJob = viewModelScope.launch(Dispatchers.IO) {
            delay(10.milliseconds)
            sharedPrefHelper.setVideoDetectionTreshold(finalResult)
        }
    }

    private fun getUniqueExportFile(dir: File, baseName: String, extension: String = "json"): File {
        var file = File(dir, "$baseName.$extension")
        var counter = 1
        while (file.exists()) {
            file = File(dir, "${baseName}_$counter.$extension")
            counter++
        }
        return file
    }

    fun exportHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val historyList = historyRepository.getAllHistory().blockingFirst()
                val json = Gson().toJson(historyList)
                val targetDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val file = getUniqueExportFile(targetDir, "browser_history")
                file.writeText(json)
                exportHistoryEvent.postValue(true)
            } catch (e: Throwable) {
                e.printStackTrace()
                exportHistoryEvent.postValue(false)
            }
        }
    }

    fun openImportHistory() {
        openImportHistoryEvent.call()
    }

    fun importHistory(items: List<HistoryItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyRepository.bulkSaveHistory(items)
                importHistoryEvent.postValue(true)
            } catch (e: Throwable) {
                e.printStackTrace()
                importHistoryEvent.postValue(false)
            }
        }
    }

    fun exportBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bookmarksList = topPagesRepository.getTopPages()
                val json = Gson().toJson(bookmarksList)
                val targetDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val file = getUniqueExportFile(targetDir, "bookmarks")
                file.writeText(json)
                exportBookmarksEvent.postValue(true)
            } catch (e: Throwable) {
                e.printStackTrace()
                exportBookmarksEvent.postValue(false)
            }
        }
    }

    fun openImportBookmarks() {
        openImportBookmarksEvent.call()
    }

    fun importBookmarks(items: List<PageInfo>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                for (item in items) {
                    topPagesRepository.saveTopPage(item)
                }
                importBookmarksEvent.postValue(true)
            } catch (e: Throwable) {
                e.printStackTrace()
                importBookmarksEvent.postValue(false)
            }
        }
    }
}
