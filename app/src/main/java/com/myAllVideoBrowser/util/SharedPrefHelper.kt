package com.myAllVideoBrowser.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.google.gson.Gson
import com.myAllVideoBrowser.data.local.GeneratedProxyCreds
import com.myAllVideoBrowser.data.local.model.Proxy
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

@Singleton
class SharedPrefHelper @Inject constructor(
    private val context: Context,
    private val appUtil: AppUtil
) {
    companion object {
        const val PREF_KEY = "settings_prefs"
        private const val IS_DESKTOP = "IS_DESKTOP"
        private const val IS_FIND_BY_URL = "IS_FIND_BY_URL"
        private const val IS_CHECK_EVERY_REQUEST = "IS_CHECK_EVERY_REQUEST"
        private const val IS_PROXY_TURN_ON = "IS_PROXY_TURN_ON"
        private const val IS_FIRST_START = "IS_FIRST_START"
        private const val IS_SHOW_VIDEO_ALERT = "IS_SHOW_VIDEO_ALERT"
        private const val IS_SHOW_VIDEO_ACTION_BUTTON = "IS_SHOW_VIDEO_ACTION_BUTTON"
        private const val IS_EXTERNAL_USE = "IS_EXTERNAL_USE"
        private const val IS_APP_DIR_USE = "IS_APP_DIR_USE"
        private const val IS_DARK_MODE = "IS_DARK_MODE"
        const val REGULAR_THREAD_COUNT = "REGULAR_THREAD_COUNT"
        private const val M3U8_THREAD_COUNT = "M3U8_THREAD_COUNT"
        private const val VIDEO_DETECTION_TRESHOLD = "VIDEO_DETECTION_TRESHOLD"
        private const val IS_LOCK_PORTRAIT = "IS_LOCK_PORTRAIT"
        private const val USER_PROXY_CHAIN = "USER_PROXY_CHAIN"
        private const val IS_CHECK_EVERY_ON_M3U8 = "IS_CHECK_EVERY_ON_M3U8"
        private const val IS_AUTO_THEME = "IS_AUTO_THEME"
        private const val IS_CHECK_ON_AUDIO = "IS_CHECK_ON_AUDIO"
        private const val IS_FORCE_STREAM_DOWNLOAD = "IS_FORCE_STREAM_DOWNLOAD"

        private const val IS_FORCE_STREAM_DETECTION = "IS_FORCE_STREAM_DETECTION"

        private const val IS_PROCESS_DOWNLOAD_FFMPEG = "IS_PROCESS_DOWNLOAD_FFMPEG"
        private const val IS_PROCESS_ONLY_LIVE_DOWNLOAD_FFMPEG =
            "IS_PROCESS_ONLY_LIVE_DOWNLOAD_FFMPEG"
        private const val IS_INTERRUPT_INTERCEPTED_RESOURCES =
            "IS_INTERRUPT_INTERCEPTED_RESOURCES"
        private const val GENERATED_CREDENTIALS = "GENERATED_CREDENTIALS"
        private const val IS_DOH_ON = "IS_DOH_ON"
        private const val SELECTED_DNS_PROVIDER = "SELECTED_DNS_PROVIDER"
        private const val CUSTOM_DNS_URL = "CUSTOM_DNS_URL"
        private const val IS_USE_LEGACY_M3U8_DETECTION = "IS_USE_LEGACY_M3U8_DETECTION"

        private const val IS_DRM_ENABLED = "IS_DRM_ENABLED"
    }

    private val gson = Gson()

    private var sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREF_KEY, Context.MODE_PRIVATE)

    fun saveIsDesktop(isDesktop: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_DESKTOP, isDesktop)
        }
    }

    fun getIsDesktop(): Boolean {
        return sharedPreferences.getBoolean(IS_DESKTOP, false)
    }

    fun saveIsFindByUrl(isFind: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_FIND_BY_URL, isFind)
        }
    }

    fun isFindVideoByUrl(): Boolean {
        return sharedPreferences.getBoolean(IS_FIND_BY_URL, true)
    }

    fun saveIsCheck(isCheck: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_CHECK_EVERY_REQUEST, isCheck)
        }
    }

    fun isCheckEveryRequestOnVideo(): Boolean {
        return sharedPreferences.getBoolean(IS_CHECK_EVERY_REQUEST, true)
    }

    fun getIsProxyOn(): Boolean {
        return sharedPreferences.getBoolean(IS_PROXY_TURN_ON, false)
    }

    fun setIsProxyOn(isTurnedOn: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_PROXY_TURN_ON, isTurnedOn)
        }
    }

    fun getIsFirstStart(): Boolean {
        return sharedPreferences.getBoolean(IS_FIRST_START, true)
    }

    fun setIsFirstStart(isFirstStart: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_FIRST_START, isFirstStart)
        }
    }

    fun isShowVideoAlert(): Boolean {
        return sharedPreferences.getBoolean(IS_SHOW_VIDEO_ALERT, true)
    }

    fun setIsShowVideoAlert(isShow: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_SHOW_VIDEO_ALERT, isShow)
        }
    }

    fun isShowActionButton(): Boolean {
        return sharedPreferences.getBoolean(IS_SHOW_VIDEO_ACTION_BUTTON, true)
    }

    fun setIsShowActionButton(isShow: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_SHOW_VIDEO_ACTION_BUTTON, isShow)
        }
    }

    fun getIsExternalUse(): Boolean {
        val defIsExternal = FileUtil.isExternalStorageWritable()

        return sharedPreferences.getBoolean(IS_EXTERNAL_USE, defIsExternal)
    }

    fun setIsExternalUse(isExternalUse: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_EXTERNAL_USE, isExternalUse)
        }
    }

    fun getIsAppDirUse(): Boolean {
        return sharedPreferences.getBoolean(IS_APP_DIR_USE, false)
    }

    fun setIsAppDirUse(isAppDirUse: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_APP_DIR_USE, isAppDirUse)
        }
    }

    fun isDarkMode(): Boolean {
        val isNightMode = appUtil.getSystemDefaultThemeIsDark(context)

        if (isAutoTheme()) {
            return isNightMode
        }

        return sharedPreferences.getBoolean(
            IS_DARK_MODE,
            true
        )
    }

    fun isAutoTheme(): Boolean {
        return sharedPreferences.getBoolean(IS_AUTO_THEME, false)
    }

    fun setIsAutoTheme(isAuto: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_AUTO_THEME, isAuto)
        }
    }

    fun setIsDarkMode(isDarkMode: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_DARK_MODE, isDarkMode)
        }
    }

    fun getRegularDownloaderThreadCount(): Int {
        return maxOf(1, sharedPreferences.getInt(REGULAR_THREAD_COUNT, 1))
    }

    fun setRegularDownloaderThreadCount(count: Int) {
        sharedPreferences.edit {
            putInt(REGULAR_THREAD_COUNT, count)
        }
    }

    fun getM3u8DownloaderThreadCount(): Int {
        return maxOf(1, sharedPreferences.getInt(M3U8_THREAD_COUNT, 3)) // means 4
    }

    fun setM3u8DownloaderThreadCount(count: Int) {
        sharedPreferences.edit {
            putInt(M3U8_THREAD_COUNT, count)
        }
    }

    fun getVideoDetectionTreshold(): Int {
        return sharedPreferences.getInt(VIDEO_DETECTION_TRESHOLD, 5 * 1024 * 1024)
    }

    fun setVideoDetectionTreshold(count: Int) {
        sharedPreferences.edit {
            putInt(VIDEO_DETECTION_TRESHOLD, count)
        }
    }

    fun getIsLockPortrait(): Boolean {
        return sharedPreferences.getBoolean(IS_LOCK_PORTRAIT, true)
    }

    fun setIsLockPortrait(isLock: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_LOCK_PORTRAIT, isLock)
        }
    }

    fun getUserProxyChain(): Array<Proxy> {
        val proxyString = sharedPreferences.getString(USER_PROXY_CHAIN, null)
        if (proxyString != null) {
            try {
                val proxies = gson.fromJson(proxyString, Array<Proxy>::class.java)
                return proxies
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        return arrayOf(Proxy.noProxy())
    }

    fun saveUserProxyChain(proxies: Array<Proxy>) {
        val proxyString = gson.toJson(proxies)
        sharedPreferences.edit {
            putString(USER_PROXY_CHAIN, proxyString)
        }
    }


    fun getIsCheckEveryOnM3u8(): Boolean {
        return sharedPreferences.getBoolean(IS_CHECK_EVERY_ON_M3U8, true)
    }

    fun saveIsCheckEveryOnM3u8(isCheck: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_CHECK_EVERY_ON_M3U8, isCheck)
        }
    }

    fun getIsCheckOnAudio(): Boolean {
        return sharedPreferences.getBoolean(IS_CHECK_ON_AUDIO, false)
    }

    fun saveIsCheckOnAudio(isCheck: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_CHECK_ON_AUDIO, isCheck)
        }
    }

    fun getIsForceStreamDownload(): Boolean {
        return sharedPreferences.getBoolean(IS_FORCE_STREAM_DOWNLOAD, false)
    }

    fun setIsForceStreamDownload(isForce: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_FORCE_STREAM_DOWNLOAD, isForce)
        }
    }

    fun getIsForceStreamDetection(): Boolean {
        return sharedPreferences.getBoolean(IS_FORCE_STREAM_DETECTION, false)
    }

    fun setIsForceStreamDetection(isForce: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_FORCE_STREAM_DETECTION, isForce)
        }
    }

    fun getIsProcessDownloadFfmpeg(): Boolean {
        return sharedPreferences.getBoolean(IS_PROCESS_DOWNLOAD_FFMPEG, false)
    }

    fun setIsProcessDownloadFfmpeg(isProcessFfmpeg: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_PROCESS_DOWNLOAD_FFMPEG, isProcessFfmpeg)
        }
    }

    fun getIsProcessOnlyLiveDownloadFfmpeg(): Boolean {
        return sharedPreferences.getBoolean(IS_PROCESS_ONLY_LIVE_DOWNLOAD_FFMPEG, true)
    }

    fun setIsProcessOnlyLiveDownloadFfmpeg(isProcessFfmpeg: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_PROCESS_ONLY_LIVE_DOWNLOAD_FFMPEG, isProcessFfmpeg)
        }
    }

    fun setIsInterruptInterceptedResources(isTurnedOn: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_INTERRUPT_INTERCEPTED_RESOURCES, isTurnedOn)
        }
    }

    fun getIsInterruptInterceptedResources(): Boolean {
        return sharedPreferences.getBoolean(IS_INTERRUPT_INTERCEPTED_RESOURCES, false)
    }

    fun setGeneratedCreds(creds: GeneratedProxyCreds) {
        sharedPreferences.edit {
            putString(GENERATED_CREDENTIALS, creds.toJson())
        }
    }

    fun getGeneratedCreds(): GeneratedProxyCreds {
        val creds = sharedPreferences.getString(GENERATED_CREDENTIALS, null)
        return if (creds != null) {
            val saved = GeneratedProxyCreds.fromJson(creds)

            val hasSpecialChar = saved.localPassword.any { !it.isLetterOrDigit() }
            if (hasSpecialChar) {
                val newCreds = GeneratedProxyCreds.generateProxyCredentials()
                setGeneratedCreds(newCreds)
                return newCreds
            }
            saved
        } else {
            val initialCreds = GeneratedProxyCreds.generateProxyCredentials()
            setGeneratedCreds(initialCreds)
            initialCreds
        }
    }

    fun getIsDohOn(): Boolean {
        return sharedPreferences.getBoolean(IS_DOH_ON, true)
    }

    fun setIsDohOn(isOn: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_DOH_ON, isOn)
        }
    }

    fun saveSelectedDnsProvider(providerName: String) {
        sharedPreferences.edit {
            putString(SELECTED_DNS_PROVIDER, providerName)
        }
    }

    fun getSelectedDnsProvider(): String? {
        return sharedPreferences.getString(SELECTED_DNS_PROVIDER, null)
    }

    fun saveCustomDnsUrl(url: String) {
        sharedPreferences.edit {
            putString(CUSTOM_DNS_URL, url)
        }
    }

    fun getCustomDnsUrl(): String {
        return sharedPreferences.getString(CUSTOM_DNS_URL, "") ?: ""
    }

    fun getIsUseLegacyM3u8Detection(): Boolean {
        return sharedPreferences.getBoolean(IS_USE_LEGACY_M3U8_DETECTION, false)
    }

    fun setIsUseLegacyM3u8Detection(isUse: Boolean) {
        sharedPreferences.edit {
            putBoolean(IS_USE_LEGACY_M3U8_DETECTION, isUse)
        }
    }

    // In SharedPrefHelper.kt
    fun getIsDrmEnabled(): Boolean {
        return sharedPreferences.getBoolean(IS_DRM_ENABLED, false)
    }

    fun setIsDrmEnabled(isEnabled: Boolean) {
        sharedPreferences.edit { putBoolean(IS_DRM_ENABLED, isEnabled) }
    }

}
