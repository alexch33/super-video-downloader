package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import androidx.lifecycle.LiveData
import io.reactivex.rxjava3.disposables.Disposable
import okhttp3.Request

interface IVideoDetector {
    fun onStartPage(url: String, userAgentString: String)

    fun showVideoInfo()

    fun verifyLinkStatus(
        resourceRequest: Request,
        hlsTitle: String? = null,
        isM3u8: Boolean = false,
        isMpd: Boolean = false
    )

    fun getDownloadBtnIcon(): LiveData<Int>

    fun checkRegularVideoOrAudio(
        request: Request?,
        isCheckOnAudio: Boolean,
        isCheckOnVideo: Boolean
    ): Disposable?

    fun cancelAllCheckJobs()

    fun hasCheckLoadingsRegular(): LiveData<Boolean>

    fun hasCheckLoadingsM3u8(): LiveData<Boolean>
}
