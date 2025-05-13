package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableInt
import io.reactivex.rxjava3.disposables.Disposable
import okhttp3.Request

interface IVideoDetector {
    fun onStartPage(url: String, userAgentString: String)

    fun showVideoInfo()

    fun verifyLinkStatus(
        resourceRequest: Request, hlsTitle: String? = null, isM3u8: Boolean = false
    )

    fun getDownloadBtnIcon(): ObservableInt

    fun checkRegularVideoOrAudio(request: Request?, isCheckOnAudio: Boolean): Disposable?

    fun cancelAllCheckJobs()

    fun hasCheckLoadingsRegular(): ObservableBoolean

    fun hasCheckLoadingsM3u8(): ObservableBoolean
}
