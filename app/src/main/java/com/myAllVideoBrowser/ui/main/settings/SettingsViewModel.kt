package com.myAllVideoBrowser.ui.main.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import androidx.lifecycle.viewModelScope
//import com.allVideoDownloaderXmaster.OpenForTesting
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.SingleLiveEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.max

enum class StorageType {
    SD, HIDDEN, HIDDEN_SD
}

//@OpenForTesting
class SettingsViewModel @Inject constructor(
    private val sharedPrefHelper: SharedPrefHelper,
) :
    BaseViewModel() {
    val isAskRedirection = ObservableBoolean(false)
    val regularThreadsCount = ObservableInt(1)
    val m3u8ThreadsCount = ObservableInt(4)
    val videoDetectionTreshold = ObservableInt(4 * 1024 * 1024)
    val storageType = ObservableField(StorageType.SD)

    val clearCookiesEvent = SingleLiveEvent<Void?>()
    val openVideoFolderEvent = SingleLiveEvent<Void?>()
    val isDesktopMode = ObservableBoolean(false)
    val isDarkMode = ObservableBoolean(false)
    val isAutoDarkMode = ObservableBoolean(true)
    val isLockPortrait = ObservableBoolean(false)
    val isCheckIfEveryRequestOnM3u8 = ObservableBoolean(true)
    val isCheckOnAudio = ObservableBoolean(true)
    val isForceStreamDownloading = ObservableBoolean(false)
    val isForceStreamDetection = ObservableBoolean(false)
    val isAlwaysRemuxRegularDownloads = ObservableBoolean(false)
    val isRemuxOnlyLiveRegularDownloads = ObservableBoolean(false)
    val isInterruptIntreceptedResources = ObservableBoolean(false)
    val isUseLegacyM3u8Detection = ObservableBoolean(false)
    val queueSize = ObservableInt(1)
    private val isShowVideoActionButton = ObservableBoolean(true)
    private val isShowVideoAlert = ObservableBoolean(true)
    private val isCheckEveryRequestOnVideo = ObservableBoolean(true)
    private val isFindVideoByUrl = ObservableBoolean(true)

    override fun start() {
        viewModelScope.launch(Dispatchers.IO) {
            val simDownloadsCount = sharedPrefHelper.getMaxSimultaneousDownloads()
            val useLegacy = sharedPrefHelper.getIsUseLegacyM3u8Detection()
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
                queueSize.set(simDownloadsCount)
                isUseLegacyM3u8Detection.set(useLegacy)
                isAlwaysRemuxRegularDownloads.set(alwaysRemux)
                isRemuxOnlyLiveRegularDownloads.set(remuxOnlyLive)
                isForceStreamDownloading.set(forceStreamDownload)
                isForceStreamDetection.set(forceStreamDetect)
                isInterruptIntreceptedResources.set(interruptIntercepted)
                isCheckIfEveryRequestOnM3u8.set(checkEveryM3u8)
                isDesktopMode.set(isDesktop)
                isShowVideoAlert.set(showVideoAlert)
                isShowVideoActionButton.set(showVideoAction)
                isCheckEveryRequestOnVideo.set(checkEveryVideo)
                isFindVideoByUrl.set(findVideoByUrl)
                isAutoDarkMode.set(autoDarkMode)
                isDarkMode.set(isDark)
                setDarkMode(isDark)
                regularThreadsCount.set(regularThreads)
                m3u8ThreadsCount.set(m3u8Threads)
                isCheckOnAudio.set(checkOnAudio)
                videoDetectionTreshold.set(videoTreshold)
                isLockPortrait.set(lockPortrait)
                isAskRedirection.set(askRedirection)
                storageType.set(sType)
            }
        }
    }

    override fun stop() {
    }

    fun setIsAskRedirection(isAsk: Boolean) {
        if (isAskRedirection.get() != isAsk) {
            isAskRedirection.set(isAsk)
            viewModelScope.launch(Dispatchers.IO) {
                sharedPrefHelper.setIsAskRedirection(isAsk)
            }
        }
    }

    fun setUseLegacyM3u8Detection(isTurnedOn: Boolean) {
        isUseLegacyM3u8Detection.set(isTurnedOn)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsUseLegacyM3u8Detection(isTurnedOn)
        }
    }

    fun setIsRemuxOnlyLiveRegularDownloads(isTurnedOn: Boolean) {
        isRemuxOnlyLiveRegularDownloads.set(isTurnedOn)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsProcessOnlyLiveDownloadFfmpeg(isTurnedOn)
        }
    }

    fun setIsRemuxOnlyRegularDownloads(isTurnedOn: Boolean) {
        isAlwaysRemuxRegularDownloads.set(isTurnedOn)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsProcessDownloadFfmpeg(isTurnedOn)
        }
    }


    fun setForceStreamDownloading(isTurnedOn: Boolean) {
        isForceStreamDownloading.set(isTurnedOn)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsForceStreamDownload(isTurnedOn)
        }
    }

    fun setForceStreamDetection(isTurnedOn: Boolean) {
        isForceStreamDetection.set(isTurnedOn)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsForceStreamDetection(isTurnedOn)
        }
    }

    fun setIsLockPortrait(isLock: Boolean) {
        isLockPortrait.set(isLock)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsLockPortrait(isLock)
        }
    }

    fun clearCookies() {
        clearCookiesEvent.call()
    }

    fun setIsInterruptInterceptedResources(isTurnedOn: Boolean) {
        isInterruptIntreceptedResources.set(isTurnedOn)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsInterruptInterceptedResources(isTurnedOn)
        }
    }


    fun openVideoFolder() {
        openVideoFolderEvent.call()
    }

    fun getIsFindVideoByUrl(): ObservableBoolean {
        return isFindVideoByUrl
    }

    fun setIsFindVideoByUrl(isFind: Boolean) {
        isFindVideoByUrl.set(isFind)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.saveIsFindByUrl(isFind)
        }
    }

    fun setIsCheckIfEveryUrlOnM3u8(isCheck: Boolean) {
        isCheckIfEveryRequestOnM3u8.set(isCheck)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.saveIsCheckEveryOnM3u8(isCheck)
        }
    }

    fun setIsCheckOnAudio(isCheck: Boolean) {
        isCheckOnAudio.set(isCheck)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.saveIsCheckOnAudio(isCheck)
        }
    }

    fun setIsAutoTheme(isChecked: Boolean) {
        isAutoDarkMode.set(isChecked)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsAutoTheme(isChecked)

            val isDark = sharedPrefHelper.isDarkMode()
            withContext(Dispatchers.Main) {
                setIsDarkMode(isDark)
            }
        }
    }

    fun setIsDarkMode(isDark: Boolean) {
        isDarkMode.set(isDark)
        viewModelScope.launch(Dispatchers.IO) {
            delay(10)
            sharedPrefHelper.setIsDarkMode(isDark)
            withContext(Dispatchers.Main) {
                setDarkMode(isDark)
            }
        }
    }

    fun getIsCheckEveryRequestOnMp4Video(): ObservableBoolean {
        return isCheckEveryRequestOnVideo
    }

    fun setIsCheckEveryRequestOnVideo(isCheck: Boolean) {
        isCheckEveryRequestOnVideo.set(isCheck)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.saveIsCheck(isCheck)
        }
    }

    fun setIsDesktopMode(isDesktop: Boolean) {
        isDesktopMode.set(isDesktop)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.saveIsDesktop(isDesktop)
        }
    }

    fun getVideoAlertState(): ObservableBoolean {
        return isShowVideoAlert
    }

    fun getVideoButtonState(): ObservableBoolean {
        return isShowVideoActionButton
    }

    fun setShowVideoAlertOn() {
        isShowVideoAlert.set(true)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsShowVideoAlert(true)
        }
    }

    fun setShowVideoAlertOff() {
        isShowVideoAlert.set(false)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsShowVideoAlert(false)
        }
    }

    fun setShowVideoActionButtonOn() {
        isShowVideoActionButton.set(true)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsShowActionButton(true)
        }
    }

    fun setShowVideoActionButtonOff() {
        isShowVideoActionButton.set(false)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsShowActionButton(false)
        }
    }

    fun setIsFirstStart(isFirstStart: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsFirstStart(isFirstStart)
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

        queueSize.set(simDownloadsCount)

        simThreadsJob?.cancel()
        simThreadsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(10)
            sharedPrefHelper.setMaxSimultaneousDownloads(simDownloadsCount)
        }
    }

    private var m3u8ThreadsJob: Job? = null
    fun setM3u8ThreadsCount(progress: Int) {
        val finalCount = max(1, progress)
        m3u8ThreadsCount.set(finalCount)

        m3u8ThreadsJob?.cancel()
        m3u8ThreadsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(10)
            sharedPrefHelper.setM3u8DownloaderThreadCount(finalCount)
        }
    }

    private var regularThreadsJob: Job? = null
    fun setRegularThreadsCount(progress: Int) {
        val finalCount = max(1, progress)
        regularThreadsCount.set(finalCount)

        regularThreadsJob?.cancel()
        regularThreadsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(10)
            sharedPrefHelper.setRegularDownloaderThreadCount(finalCount)
        }
    }

    fun setDownloadsFolderSdCard() {
        FileUtil.IS_APP_DATA_DIR_USE = false
        FileUtil.IS_EXTERNAL_STORAGE_USE = true

        storageType.set(StorageType.SD)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsExternalUse(true)
            sharedPrefHelper.setIsAppDirUse(false)
        }
    }

    fun setDownloadsFolderHidden() {
        FileUtil.IS_APP_DATA_DIR_USE = true
        FileUtil.IS_EXTERNAL_STORAGE_USE = false

        storageType.set(StorageType.HIDDEN)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsExternalUse(false)
            sharedPrefHelper.setIsAppDirUse(true)
        }
    }

    fun setDownloadsFolderHiddenSdCard() {
        FileUtil.IS_APP_DATA_DIR_USE = true
        FileUtil.IS_EXTERNAL_STORAGE_USE = true

        storageType.set(StorageType.HIDDEN_SD)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsExternalUse(true)
            sharedPrefHelper.setIsAppDirUse(true)
        }
    }

    private var videoDetectionJob: Job? = null
    fun setVideoDetectionTreshold(progress: Int) {
        val finalResult = max(0, progress)
        videoDetectionTreshold.set(finalResult)

        videoDetectionJob?.cancel()
        videoDetectionJob = viewModelScope.launch(Dispatchers.IO) {
            delay(10)
            sharedPrefHelper.setVideoDetectionTreshold(finalResult)
        }
    }
}
