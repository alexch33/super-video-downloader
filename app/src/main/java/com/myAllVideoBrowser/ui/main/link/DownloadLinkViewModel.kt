package com.myAllVideoBrowser.ui.main.link

import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.VideoRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Request
import javax.inject.Inject

class DownloadLinkViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val baseSchedulers: BaseSchedulers
) : BaseViewModel() {
    private val disposableContainer = CompositeDisposable()

    val isLoading = ObservableField(false)

    val showDownloadDialogEvent = SingleLiveEvent<VideoInfo>()

    override fun start() {
    }

    override fun stop() {
        disposableContainer.clear()
    }

    fun fetchDownloadInfo(videoUrl: String) {
        isLoading.set(true)

        try {
            viewModelScope.launch(Dispatchers.Default) {
                val req =  Request.Builder().url(videoUrl.trim()).build()
                val info = videoRepository.getVideoInfo(req)
                isLoading.set(false)
                if (info != null) {
                    showDownloadDialogEvent.value = info!!
                }
            }
        } catch (e: Throwable) {
            isLoading.set(false)
        }
    }
}