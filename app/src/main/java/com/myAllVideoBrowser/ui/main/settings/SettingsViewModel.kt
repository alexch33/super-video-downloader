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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class StorageType {
    SD, HIDDEN, HIDDEN_SD
}

//@OpenForTesting
class SettingsViewModel @Inject constructor(
    private val sharedPrefHelper: SharedPrefHelper,
) :
    BaseViewModel() {
    val isDrmEnabled = ObservableBoolean(false)
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
    private val isShowVideoActionButton = ObservableBoolean(true)
    private val isShowVideoAlert = ObservableBoolean(true)
    private val isCheckEveryRequestOnVideo = ObservableBoolean(true)
    private val isFindVideoByUrl = ObservableBoolean(true)

    override fun start() {
        viewModelScope.launch(Dispatchers.IO) {
            // 2. INITIALIZE ITS VALUE FROM SHARED PREFERENCES
            isUseLegacyM3u8Detection.set(sharedPrefHelper.getIsUseLegacyM3u8Detection())
            isAlwaysRemuxRegularDownloads.set(sharedPrefHelper.getIsProcessDownloadFfmpeg())
            isRemuxOnlyLiveRegularDownloads.set(sharedPrefHelper.getIsProcessOnlyLiveDownloadFfmpeg())
            isForceStreamDownloading.set(sharedPrefHelper.getIsForceStreamDownload())
            isForceStreamDetection.set(sharedPrefHelper.getIsForceStreamDetection())
            isInterruptIntreceptedResources.set(sharedPrefHelper.getIsInterruptInterceptedResources())
            isCheckIfEveryRequestOnM3u8.set(sharedPrefHelper.getIsCheckEveryOnM3u8())
            isDesktopMode.set(sharedPrefHelper.getIsDesktop())
            isShowVideoAlert.set(sharedPrefHelper.isShowVideoAlert())
            isShowVideoActionButton.set(sharedPrefHelper.isShowActionButton())
            isCheckEveryRequestOnVideo.set(sharedPrefHelper.isCheckEveryRequestOnVideo())
            isFindVideoByUrl.set(sharedPrefHelper.isFindVideoByUrl())
            isAutoDarkMode.set(sharedPrefHelper.isAutoTheme())
            val isDark = sharedPrefHelper.isDarkMode()
            setDarkMode(isDark)
            isDarkMode.set(isDark)
            regularThreadsCount.set(sharedPrefHelper.getRegularDownloaderThreadCount())
            m3u8ThreadsCount.set(sharedPrefHelper.getM3u8DownloaderThreadCount())
            isCheckOnAudio.set(sharedPrefHelper.getIsCheckOnAudio())
            videoDetectionTreshold.set(sharedPrefHelper.getVideoDetectionTreshold())
            isLockPortrait.set(sharedPrefHelper.getIsLockPortrait())
            isDrmEnabled.set(sharedPrefHelper.getIsDrmEnabled())
            if (sharedPrefHelper.getIsExternalUse() && !sharedPrefHelper.getIsAppDirUse()) {
                storageType.set(StorageType.SD)
            } else if (sharedPrefHelper.getIsAppDirUse() && sharedPrefHelper.getIsExternalUse()) {
                storageType.set(StorageType.HIDDEN_SD)
            } else {
                storageType.set(StorageType.HIDDEN)
            }
        }
    }

    override fun stop() {
    }

    fun setIsDrmEnabled(isEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isDrmEnabled.get() != isEnabled) {
                isDrmEnabled.set(isEnabled)
                sharedPrefHelper.setIsDrmEnabled(isEnabled)
            }
        }
    }

    fun setUseLegacyM3u8Detection(isTurnedOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isUseLegacyM3u8Detection.set(isTurnedOn)
            sharedPrefHelper.setIsUseLegacyM3u8Detection(isTurnedOn)
        }
    }

    fun setIsRemuxOnlyLiveRegularDownloads(isTurnedOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isRemuxOnlyLiveRegularDownloads.set(isTurnedOn)
            sharedPrefHelper.setIsProcessOnlyLiveDownloadFfmpeg(isTurnedOn)
        }
    }

    fun setIsRemuxOnlyRegularDownloads(isTurnedOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isAlwaysRemuxRegularDownloads.set(isTurnedOn)
            sharedPrefHelper.setIsProcessDownloadFfmpeg(isTurnedOn)
        }
    }


    fun setForceStreamDownloading(isTurnedOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isForceStreamDownloading.set(isTurnedOn)
            sharedPrefHelper.setIsForceStreamDownload(isTurnedOn)
        }
    }

    fun setForceStreamDetection(isTurnedOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isForceStreamDetection.set(isTurnedOn)
            sharedPrefHelper.setIsForceStreamDetection(isTurnedOn)
        }
    }

    fun setIsLockPortrait(isLock: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isLockPortrait.set(isLock)
            sharedPrefHelper.setIsLockPortrait(isLock)
        }
    }

    fun clearCookies() {
        clearCookiesEvent.call()
    }

    fun setIsInterruptInterceptedResources(isTurnedOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isInterruptIntreceptedResources.set(isTurnedOn)
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
        viewModelScope.launch(Dispatchers.IO) {
            isFindVideoByUrl.set(isFind)
            sharedPrefHelper.saveIsFindByUrl(isFind)
        }
    }

    fun setIsCheckIfEveryUrlOnM3u8(isCheck: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isCheckIfEveryRequestOnM3u8.set(isCheck)
            sharedPrefHelper.saveIsCheckEveryOnM3u8(isCheck)
        }
    }

    fun setIsCheckOnAudio(isCheck: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isCheckOnAudio.set(isCheck)
            sharedPrefHelper.saveIsCheckOnAudio(isCheck)
        }
    }

    fun setIsAutoTheme(isChecked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isAutoDarkMode.set(isChecked)
            sharedPrefHelper.setIsAutoTheme(isChecked)

            val isDark = sharedPrefHelper.isDarkMode()
            setIsDarkMode(isDark)
        }
    }

    fun setIsDarkMode(isDark: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isDarkMode.set(isDark)
            delay(300)
            sharedPrefHelper.setIsDarkMode(isDark)
            setDarkMode(isDark)
        }
    }

    fun getIsCheckEveryRequestOnMp4Video(): ObservableBoolean {
        return isCheckEveryRequestOnVideo
    }

    fun setIsCheckEveryRequestOnVideo(isCheck: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isCheckEveryRequestOnVideo.set(isCheck)
            sharedPrefHelper.saveIsCheck(isCheck)
        }
    }

    fun setIsDesktopMode(isDesktop: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isDesktopMode.set(isDesktop)

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
        viewModelScope.launch(Dispatchers.IO) {
            isShowVideoAlert.set(true)

            sharedPrefHelper.setIsShowVideoAlert(true)
        }
    }

    fun setShowVideoAlertOff() {
        viewModelScope.launch(Dispatchers.IO) {
            isShowVideoAlert.set(false)

            sharedPrefHelper.setIsShowVideoAlert(false)
        }
    }

    fun setShowVideoActionButtonOn() {
        viewModelScope.launch(Dispatchers.IO) {
            isShowVideoActionButton.set(true)

            sharedPrefHelper.setIsShowActionButton(true)
        }
    }

    fun setShowVideoActionButtonOff() {
        viewModelScope.launch(Dispatchers.IO) {
            isShowVideoActionButton.set(false)

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

    fun setM3u8ThreadsCount(progress: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            m3u8ThreadsCount.set(progress)
            sharedPrefHelper.setM3u8DownloaderThreadCount(progress)
        }
    }

    fun setRegularThreadsCount(progress: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            regularThreadsCount.set(progress)
            sharedPrefHelper.setRegularDownloaderThreadCount(progress)
        }
    }

    fun setDownloadsFolderSdCard() {
        FileUtil.IS_APP_DATA_DIR_USE = false
        FileUtil.IS_EXTERNAL_STORAGE_USE = true

        viewModelScope.launch(Dispatchers.IO) {
            storageType.set(StorageType.SD)
            sharedPrefHelper.setIsExternalUse(true)
            sharedPrefHelper.setIsAppDirUse(false)
        }
    }

    fun setDownloadsFolderHidden() {
        FileUtil.IS_APP_DATA_DIR_USE = true
        FileUtil.IS_EXTERNAL_STORAGE_USE = false

        viewModelScope.launch(Dispatchers.IO) {
            storageType.set(StorageType.HIDDEN)
            sharedPrefHelper.setIsExternalUse(false)
            sharedPrefHelper.setIsAppDirUse(true)
        }
    }

    fun setDownloadsFolderHiddenSdCard() {
        FileUtil.IS_APP_DATA_DIR_USE = true
        FileUtil.IS_EXTERNAL_STORAGE_USE = true

        viewModelScope.launch(Dispatchers.IO) {
            storageType.set(StorageType.HIDDEN_SD)
            sharedPrefHelper.setIsExternalUse(true)
            sharedPrefHelper.setIsAppDirUse(true)
        }
    }

    fun setVideoDetectionTreshold(progress: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            videoDetectionTreshold.set(progress)
            sharedPrefHelper.setVideoDetectionTreshold(progress)
        }
    }
}
